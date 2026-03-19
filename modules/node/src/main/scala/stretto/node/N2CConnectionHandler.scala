package stretto.node

import cats.effect.IO
import cats.syntax.all.*
import fs2.concurrent.Topic
import fs2.io.net.Socket
import org.typelevel.log4cats.slf4j.Slf4jLogger
import stretto.core.Types.*
import stretto.mempool.Mempool
import stretto.network.{HandshakeMessage, MuxDemuxer}
import stretto.storage.RocksDbStore

import scala.concurrent.duration.*

/**
 * Handles a single N2C client connection.
 *
 * Performs the N2C handshake as responder, then runs ChainSync, LSQ,
 * and LocalTxSubmission servers concurrently.
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
   * @param genesis      genesis config for LSQ
   * @param mempool      transaction mempool (None = tx submission disabled)
   * @param ledgerState  ledger state (None = tx submission disabled)
   * @param currentSlotRef function to get current slot
   */
  def handle(
      socket: Socket[IO],
      store: RocksDbStore,
      tipTopic: Topic[IO, ChainEvent],
      networkMagic: Long,
      genesis: GenesisConfig,
      mempool: Option[Mempool] = None,
      ledgerState: Option[LedgerState] = None,
      currentSlotRef: () => IO[SlotNo] = () => IO.pure(SlotNo(0L))
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
      chainSync = new ChainSyncServer(mux, store, tipTopic)
      lsqServer = new LocalStateQueryServer(mux, store, genesis)
      // Run ChainSync + LSQ + optional LocalTxSubmission concurrently
      _ <- (mempool, ledgerState) match
        case (Some(mp), Some(ls)) =>
          val txSubmit = new LocalTxSubmissionServer(mux, mp, ls, currentSlotRef)
          (chainSync.serve, lsqServer.serve, txSubmit.serve).parTupled.void
        case _ =>
          IO.both(chainSync.serve, lsqServer.serve).void
    yield ()
