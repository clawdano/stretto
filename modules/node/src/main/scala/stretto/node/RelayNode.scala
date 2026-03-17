package stretto.node

import cats.effect.IO
import cats.syntax.all.*
import fs2.concurrent.Topic
import org.typelevel.log4cats.slf4j.Slf4jLogger
import stretto.storage.RocksDbStore

import java.nio.file.Path
import scala.concurrent.duration.*

/**
 * Lightweight relay node: syncs blocks from an upstream N2N peer and
 * serves them to local N2C clients.
 *
 * Architecture:
 *   Upstream (N2N) → BlockSyncPipeline → RocksDB ← ChainSyncServer (N2C)
 *                                      ↘ Topic  → N2C clients
 */
object RelayNode:

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.node.RelayNode")

  final case class Config(
      upstreamHost: String,
      upstreamPort: Int,
      networkMagic: Long,
      networkName: String,
      listenHost: String,
      listenPort: Int,
      dbPath: Path,
      maxClients: Int = 32,
      keepAliveInterval: FiniteDuration = 10.seconds
  )

  private val RetryDelaySec = 5

  /**
   * Run the relay node. This never terminates normally.
   *
   * Opens RocksDB, creates a Topic, starts upstream N2N sync and
   * N2C listener concurrently.
   */
  def run(config: Config): IO[Nothing] =
    RocksDbStore.open(config.dbPath).use { store =>
      for
        tipTopic <- Topic[IO, ChainEvent]
        _        <- logger.info(s"Stretto Relay Node starting")
        _        <- logger.info(s"Network: ${config.networkName} (magic ${config.networkMagic})")
        _        <- logger.info(s"Upstream: ${config.upstreamHost}:${config.upstreamPort}")
        _        <- logger.info(s"N2C listen: ${config.listenHost}:${config.listenPort}")
        _        <- logger.info(s"Database: ${config.dbPath}")
        _        <- logger.info(s"Max N2C clients: ${config.maxClients}")
        // Start upstream sync and N2C listener concurrently
        genesis = GenesisConfig.forNetwork(config.networkName)
        result <- IO
          .both(
            upstreamSyncLoop(config, store, tipTopic),
            N2CListener.listen(
              config.listenHost,
              config.listenPort,
              store,
              tipTopic,
              config.networkMagic,
              config.maxClients,
              genesis
            )
          )
          .map(_._2) // Both return Nothing
      yield result
    }

  /**
   * Upstream N2N sync with auto-reconnect.
   * Publishes ChainEvents to the topic after each batch.
   */
  private def upstreamSyncLoop(
      config: Config,
      store: RocksDbStore,
      tipTopic: Topic[IO, ChainEvent]
  ): IO[Nothing] =
    def syncOnce: IO[BlockSyncPipeline.SyncProgress] =
      BlockSyncPipeline.syncWithTopic(
        host = config.upstreamHost,
        port = config.upstreamPort,
        networkMagic = config.networkMagic,
        store = store,
        maxBlocks = 0L,
        tipTopic = tipTopic,
        keepAliveInterval = config.keepAliveInterval,
        onProgress = progress =>
          logger.info(
            s"Sync: ${progress.blocksStored} blocks " +
              s"(slot ${progress.currentSlot.value}) " +
              s"tip: slot ${progress.peerTipSlot.value} block ${progress.peerTipBlock.blockNoValue}" +
              (if progress.rollbacks > 0 then s" | rollbacks: ${progress.rollbacks}" else "")
          )
      )

    def loop(attempt: Int): IO[Nothing] =
      syncOnce
        .handleErrorWith { err =>
          logger.warn(
            s"Upstream connection lost: ${err.getMessage}. " +
              s"Reconnecting in ${RetryDelaySec}s (attempt $attempt)..."
          ) *>
            IO.sleep(RetryDelaySec.seconds)
        }
        .flatMap(_ => IO.defer(loop(attempt + 1)))

    IO.defer(loop(1))
