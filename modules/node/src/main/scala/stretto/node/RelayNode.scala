package stretto.node

import cats.effect.{IO, Ref}
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
      n2nListenPort: Int = 3001, // N2N peer-to-peer, 0 = disabled
      n2cListenPort: Int = 0,    // N2C local clients, 0 = disabled
      metricsPort: Int = 0,      // Prometheus metrics, 0 = disabled
      dbPath: Path,
      maxN2NPeers: Int = 16,
      maxN2CClients: Int = 32,
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
        tipTopic   <- Topic[IO, ChainEvent]
        metricsRef <- Ref.of[IO, MetricsServer.Metrics](MetricsServer.Metrics())
        _          <- logger.info(s"Stretto Relay Node starting")
        _          <- logger.info(s"Network: ${config.networkName} (magic ${config.networkMagic})")
        _          <- logger.info(s"Upstream: ${config.upstreamHost}:${config.upstreamPort}")
        _          <- logger.info(s"Database: ${config.dbPath}")
        _ <-
          if config.n2nListenPort > 0
          then
            logger.info(s"N2N listen: ${config.listenHost}:${config.n2nListenPort} (max ${config.maxN2NPeers} peers)")
          else logger.info("N2N server: disabled")
        _ <-
          if config.n2cListenPort > 0
          then
            logger.info(
              s"N2C listen: ${config.listenHost}:${config.n2cListenPort} (max ${config.maxN2CClients} clients)"
            )
          else logger.info("N2C server: disabled")
        _ <-
          if config.metricsPort > 0
          then logger.info(s"Metrics: http://${config.listenHost}:${config.metricsPort}/metrics")
          else logger.info("Metrics server: disabled")
        // Start upstream sync + optional listeners concurrently
        genesis = GenesisConfig.forNetwork(config.networkName)
        n2nListener =
          if config.n2nListenPort > 0 then
            N2NListener.listen(
              config.listenHost,
              config.n2nListenPort,
              store,
              tipTopic,
              config.networkMagic,
              config.maxN2NPeers
            )
          else IO.never[Nothing]
        n2cListener =
          if config.n2cListenPort > 0 then
            N2CListener.listen(
              config.listenHost,
              config.n2cListenPort,
              store,
              tipTopic,
              config.networkMagic,
              config.maxN2CClients,
              genesis
            )
          else IO.never[Nothing]
        metricsServer =
          if config.metricsPort > 0 then
            MetricsServer.serve(config.listenHost, config.metricsPort, metricsRef, config.networkName)
          else IO.never[Nothing]
        // All fibers run forever (IO[Nothing]).
        upstreamFiber <- upstreamSyncLoop(config, store, tipTopic, metricsRef).start
        _             <- n2nListener.start
        _             <- n2cListener.start
        _             <- metricsServer.start
        result        <- upstreamFiber.joinWithNever
      yield result
    }

  /**
   * Upstream N2N sync with auto-reconnect.
   * Publishes ChainEvents to the topic after each batch.
   */
  private def upstreamSyncLoop(
      config: Config,
      store: RocksDbStore,
      tipTopic: Topic[IO, ChainEvent],
      metricsRef: Ref[IO, MetricsServer.Metrics]
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
          metricsRef.update(m =>
            m.copy(
              chainTipSlot = progress.currentSlot.value,
              chainTipBlock = progress.blocksStored,
              peerTipSlot = progress.peerTipSlot.value,
              peerTipBlock = progress.peerTipBlock.blockNoValue,
              syncedBlocks = progress.blocksStored,
              syncedBytes = progress.blockBytes,
              rollbacks = progress.rollbacks
            )
          ) *>
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
