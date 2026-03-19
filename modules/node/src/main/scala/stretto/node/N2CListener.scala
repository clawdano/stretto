package stretto.node

import cats.effect.IO
import cats.effect.std.Semaphore
import com.comcast.ip4s.{Host, Port}
import fs2.concurrent.Topic
import fs2.io.net.Network
import org.typelevel.log4cats.slf4j.Slf4jLogger
// N2CConnectionHandler is in this same package
import stretto.storage.RocksDbStore

/**
 * TCP listener that accepts N2C connections and spawns a handler per client.
 *
 * Connection count is gated by a Semaphore to enforce a max-clients limit.
 * Binds to 127.0.0.1 by default for safety.
 */
object N2CListener:

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.node.N2CListener")

  /**
   * Start the N2C listener. This never terminates normally.
   *
   * @param host         bind address (default 127.0.0.1)
   * @param port         bind port
   * @param store        RocksDB store for serving blocks
   * @param tipTopic     topic for chain tip events
   * @param networkMagic network magic for handshake
   * @param maxClients   maximum concurrent N2C clients
   */
  def listen(
      host: String,
      port: Int,
      store: RocksDbStore,
      tipTopic: Topic[IO, ChainEvent],
      networkMagic: Long,
      maxClients: Int = 32,
      genesis: GenesisConfig = GenesisConfig.Preprod
  ): IO[Nothing] =
    val hostParsed = Host.fromString(host)
    val portParsed = Port.fromInt(port)
    (hostParsed, portParsed) match
      case (Some(h), Some(p)) =>
        for
          gate <- Semaphore[IO](maxClients.toLong)
          _    <- logger.info(s"N2C listener starting on $host:$port (max $maxClients clients)")
          _ <-
            if host == "0.0.0.0" then logger.warn("Listening on 0.0.0.0 — N2C is accessible from external networks")
            else IO.unit
          result <- Network[IO]
            .server(address = Some(h), port = Some(p))
            .map { socket =>
              // Each socket is resource-scoped by fs2; using map+parJoin keeps
              // the scope alive for the entire handler lifetime.
              fs2.Stream.eval(
                gate.tryAcquire.flatMap {
                  case true =>
                    val clientAddr = socket.remoteAddress
                    (for
                      addr <- clientAddr
                      _    <- logger.info(s"N2C client connected: $addr")
                      _ <- N2CConnectionHandler
                        .handle(socket, store, tipTopic, networkMagic, genesis)
                        .guarantee(
                          clientAddr
                            .flatMap(a => logger.info(s"N2C client disconnected: $a"))
                            .handleError(_ => ()) *>
                            gate.release
                        )
                    yield ())
                      .handleErrorWith { err =>
                        logger.warn(s"N2C client error: ${err.getMessage}") *>
                          gate.release
                      }
                  case false =>
                    logger.warn(s"N2C connection rejected: max clients ($maxClients) reached") *>
                      socket.endOfOutput.attempt.void
                }
              )
            }
            .parJoin(maxClients)
            .compile
            .drain
          never <- IO.never[Nothing]
        yield never
      case _ =>
        IO.raiseError(new IllegalArgumentException(s"Invalid listen address: $host:$port"))
