package stretto.node

import cats.effect.IO
import fs2.concurrent.Topic
import fs2.io.net.Socket
import org.typelevel.log4cats.slf4j.Slf4jLogger
import stretto.network.{HandshakeMessage, MuxDemuxer}
import stretto.storage.RocksDbStore

import scala.concurrent.duration.*

/**
 * Handles a single N2C client connection.
 *
 * Performs the N2C handshake as responder, then runs the ChainSync N2C server loop.
 */
object N2CConnectionHandler:

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.node.N2CConnectionHandler")

  private val HandshakeTimeout = 30.seconds

  /**
   * Handle a single client connection from accept to close.
   *
   * @param socket       the accepted TCP socket
   * @param store        the RocksDB store for reading blocks
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
        .handshakeN2CServer(mux, networkMagic)
        .timeoutTo(
          HandshakeTimeout,
          IO.raiseError(new RuntimeException("N2C handshake timed out"))
        )
      _ <- logger.info(s"N2C handshake accepted version $version")
      server = new ChainSyncServer(mux, store, tipTopic)
      _ <- server.serve
    yield ()
