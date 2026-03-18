package stretto.node

import cats.effect.IO
import fs2.concurrent.Topic
import fs2.io.net.Socket
import org.typelevel.log4cats.slf4j.Slf4jLogger
import stretto.network.{HandshakeMessage, KeepAliveClient, MuxDemuxer}
import stretto.storage.RocksDbStore

import scala.concurrent.duration.*

/**
 * Handles a single inbound N2N peer connection.
 *
 * Performs the N2N handshake as responder, then runs:
 *   - ChainSync N2N server (serve headers)
 *   - BlockFetch server (serve blocks)
 *   - KeepAlive responder (echo pings)
 *
 * All three run concurrently for the lifetime of the connection.
 */
object N2NConnectionHandler:

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.node.N2NConnectionHandler")

  private val HandshakeTimeout = 30.seconds

  /**
   * Handle a single inbound N2N peer connection from accept to close.
   *
   * @param socket       the accepted TCP socket
   * @param store        the RocksDB store for reading headers and blocks
   * @param tipTopic     topic for chain tip events
   * @param networkMagic the network magic for handshake
   */
  def handle(
      socket: Socket[IO],
      store: RocksDbStore,
      tipTopic: Topic[IO, ChainEvent],
      networkMagic: Long
  ): IO[Unit] =
    for
      mux <- MuxDemuxer(socket)
      version <- HandshakeMessage
        .handshakeN2NServer(mux, networkMagic)
        .timeoutTo(
          HandshakeTimeout,
          IO.raiseError(new RuntimeException("N2N handshake timed out"))
        )
      _ <- logger.info(s"N2N handshake accepted version $version")
      chainSync  = new ChainSyncServerN2N(mux, store, tipTopic)
      blockFetch = new BlockFetchServer(mux, store)
      keepAlive  = new KeepAliveClient(mux)
      // Run ChainSync server, BlockFetch server, and KeepAlive responder concurrently
      _ <- IO
        .race(
          IO.race(chainSync.serve, blockFetch.serve),
          keepAlive.respondLoop
        )
        .void
    yield ()
