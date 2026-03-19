package stretto.node

import cats.effect.IO
import cats.syntax.all.*
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

  def handle(
      socket: Socket[IO],
      store: RocksDbStore,
      tipTopic: Topic[IO, ChainEvent],
      networkMagic: Long
  ): IO[Unit] =
    (for
      mux <- MuxDemuxer(socket)
      _   <- logger.info("N2N: mux created, awaiting handshake")
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
      _ <- (
        chainSync.serve.handleErrorWith(e => logger.warn(s"N2N ChainSync server error: ${e.getMessage}")),
        blockFetch.serve.handleErrorWith(e => logger.warn(s"N2N BlockFetch server error: ${e.getMessage}")),
        keepAlive.respondLoop
      ).parTupled.void
    yield ()).handleErrorWith { e =>
      logger.warn(s"N2N connection handler error: ${e.getClass.getSimpleName}: ${e.getMessage}")
    }
