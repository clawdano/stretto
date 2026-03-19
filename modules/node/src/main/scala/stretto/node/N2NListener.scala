package stretto.node

import cats.effect.IO
import cats.effect.std.Semaphore
import com.comcast.ip4s.{Host, Port}
import fs2.concurrent.Topic
import fs2.io.net.Network
import org.typelevel.log4cats.slf4j.Slf4jLogger
import stretto.storage.RocksDbStore

/**
 * TCP listener that accepts inbound N2N peer connections.
 *
 * Connection count is gated by a Semaphore to enforce a max-peers limit.
 * Each connected peer gets its own ChainSync server, BlockFetch server,
 * and KeepAlive responder running concurrently.
 */
object N2NListener:

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.node.N2NListener")

  /**
   * Start the N2N listener. This never terminates normally.
   *
   * @param host         bind address
   * @param port         bind port
   * @param store        RocksDB store for serving headers and blocks
   * @param tipTopic     topic for chain tip events
   * @param networkMagic network magic for handshake
   * @param maxPeers     maximum concurrent N2N peers
   */
  def listen(
      host: String,
      port: Int,
      store: RocksDbStore,
      tipTopic: Topic[IO, ChainEvent],
      networkMagic: Long,
      maxPeers: Int = 16
  ): IO[Nothing] =
    val hostParsed = Host.fromString(host)
    val portParsed = Port.fromInt(port)
    (hostParsed, portParsed) match
      case (Some(h), Some(p)) =>
        for
          gate <- Semaphore[IO](maxPeers.toLong)
          _    <- logger.info(s"N2N listener starting on $host:$port (max $maxPeers peers)")
          result <- Network[IO]
            .server(address = Some(h), port = Some(p))
            .map { socket =>
              // Each socket is resource-scoped by fs2; using map+parJoin keeps
              // the scope alive for the entire handler lifetime (not evalMap+start
              // which would release the socket immediately).
              fs2.Stream.eval(
                gate.tryAcquire.flatMap {
                  case true =>
                    val clientAddr = socket.remoteAddress
                    (for
                      addr <- clientAddr
                      _    <- logger.info(s"N2N peer connected: $addr")
                      _ <- N2NConnectionHandler
                        .handle(socket, store, tipTopic, networkMagic)
                        .guarantee(
                          clientAddr
                            .flatMap(a => logger.info(s"N2N peer disconnected: $a"))
                            .handleError(_ => ()) *>
                            gate.release
                        )
                    yield ())
                      .handleErrorWith { err =>
                        logger.warn(s"N2N peer error: ${err.getMessage}") *>
                          gate.release
                      }
                  case false =>
                    logger.warn(s"N2N connection rejected: max peers ($maxPeers) reached") *>
                      socket.endOfOutput.attempt.void
                }
              )
            }
            .parJoin(maxPeers)
            .compile
            .drain
          never <- IO.never[Nothing]
        yield never
      case _ =>
        IO.raiseError(new IllegalArgumentException(s"Invalid N2N listen address: $host:$port"))
