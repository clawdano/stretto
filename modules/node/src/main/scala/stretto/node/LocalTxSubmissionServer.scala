package stretto.node

import cats.effect.IO
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*
import stretto.ledger.ProtocolParameters
import stretto.mempool.Mempool
import stretto.network.{MiniProtocolId, MuxDemuxer}
import stretto.serialization.BlockDecoder

/**
 * LocalTxSubmission N2C server — accepts transactions from local clients.
 *
 * Protocol state machine: Idle → receive MsgSubmitTx → validate → respond → Idle
 *
 * Wire format:
 *   MsgSubmitTx = [0, era_wrapped_tx]
 *   MsgAcceptTx = [1]
 *   MsgRejectTx = [2, reason_cbor]
 *   MsgDone     = [3]
 *
 * The era-wrapped tx format: [era_tag, tx_bytes] where tx_bytes is the raw
 * Conway-era transaction CBOR: [body, witnesses, isValid, auxiliaryData].
 *
 * Reference: Ouroboros LocalTxSubmission mini-protocol
 */
final class LocalTxSubmissionServer(
    mux: MuxDemuxer,
    mempool: Mempool,
    @annotation.unused ledgerState: LedgerState,
    currentSlotRef: () => IO[SlotNo]
):

  private val logger  = Slf4jLogger.getLoggerFromName[IO]("stretto.node.LocalTxSubmissionServer")
  private val protoId = MiniProtocolId.LocalTxSubmission.id

  // -------------------------------------------------------------------------
  // CBOR helpers
  // -------------------------------------------------------------------------

  private def cborUInt(n: Long): ByteVector =
    if n < 24 then ByteVector(n.toByte)
    else if n < 256 then ByteVector(0x18.toByte, n.toByte)
    else if n < 65536 then ByteVector(0x19.toByte) ++ ByteVector.fromShort(n.toShort, size = 2)
    else if n < 0x100000000L then ByteVector(0x1a.toByte) ++ ByteVector.fromInt(n.toInt, size = 4)
    else ByteVector(0x1b.toByte) ++ ByteVector.fromLong(n, size = 8)

  private def cborArrayHeader(len: Int): ByteVector =
    if len < 24 then ByteVector((0x80 | len).toByte)
    else ByteVector(0x98.toByte, len.toByte)

  @annotation.unused
  private def cborByteString(bv: ByteVector): ByteVector =
    val len = bv.size
    val hdr =
      if len < 24 then ByteVector((0x40 | len.toInt).toByte)
      else if len < 256 then ByteVector(0x58.toByte, len.toByte)
      else if len < 65536 then ByteVector(0x59.toByte) ++ ByteVector.fromShort(len.toShort, size = 2)
      else ByteVector(0x5a.toByte) ++ ByteVector.fromInt(len.toInt, size = 4)
    hdr ++ bv

  private def cborTextString(s: String): ByteVector =
    val bytes = ByteVector.view(s.getBytes("UTF-8"))
    val len   = bytes.size
    val hdr =
      if len < 24 then ByteVector((0x60 | len.toInt).toByte)
      else if len < 256 then ByteVector(0x78.toByte, len.toByte)
      else ByteVector(0x79.toByte) ++ ByteVector.fromShort(len.toShort, size = 2)
    hdr ++ bytes

  // -------------------------------------------------------------------------
  // Message encoding
  // -------------------------------------------------------------------------

  /** MsgAcceptTx = [1] */
  private val msgAcceptTx: ByteVector =
    cborArrayHeader(1) ++ cborUInt(1)

  /** MsgRejectTx = [2, reason] where reason is a CBOR-encoded error. */
  private def msgRejectTx(reason: String): ByteVector =
    // Encode reason as a simple CBOR array: [0, text_string]
    // This matches a simplified ApplyTxErr encoding
    val reasonCbor = cborArrayHeader(1) ++ cborTextString(reason)
    cborArrayHeader(2) ++ cborUInt(2) ++ reasonCbor

  // -------------------------------------------------------------------------
  // Message decoding
  // -------------------------------------------------------------------------

  private enum ClientMsg:
    case SubmitTx(eraTag: Int, txBytes: ByteVector)
    case Done

  private def decodeClientMsg(payload: ByteVector): Either[String, ClientMsg] =
    if payload.isEmpty then Left("empty payload")
    else
      // Read outer array header + tag
      val b0    = payload(0) & 0xff
      val major = b0 >> 5
      if major != 4 then Left(s"expected array, got major $major")
      else
        val (_, tagOffset) = if (b0 & 0x1f) < 24 then ((b0 & 0x1f).toLong, 1) else (payload(1).toLong & 0xff, 2)
        if tagOffset >= payload.size then Left("payload too short for tag")
        else
          val tag = payload(tagOffset.toLong) & 0xff
          tag match
            case 0 => // MsgSubmitTx = [0, era_wrapped_tx]
              // The era_wrapped_tx starts after the tag byte
              // It's [era_tag, tx_cbor] — we need the era_tag and raw tx bytes
              val eraWrapStart = tagOffset + 1
              if eraWrapStart >= payload.size then Left("payload too short for era-wrapped tx")
              else
                // Read inner array header
                val ib0    = payload(eraWrapStart.toLong) & 0xff
                val iMajor = ib0 >> 5
                if iMajor != 4 then Left(s"expected inner array, got major $iMajor")
                else
                  val iAi       = ib0 & 0x1f
                  val eraTagOff = if iAi < 24 then eraWrapStart + 1 else eraWrapStart + 2
                  if eraTagOff >= payload.size then Left("payload too short for era tag")
                  else
                    val eraTag  = (payload(eraTagOff.toLong) & 0xff).toLong
                    val txStart = eraTagOff + 1
                    // Everything from txStart to end is the raw tx CBOR
                    val txBytes = payload.drop(txStart.toLong)
                    Right(ClientMsg.SubmitTx(eraTag.toInt, txBytes))
            case 3 => // MsgDone
              Right(ClientMsg.Done)
            case other =>
              Left(s"unknown LocalTxSubmission message tag: $other")

  // -------------------------------------------------------------------------
  // Server loop
  // -------------------------------------------------------------------------

  /** Run the server loop. Returns when the client sends MsgDone. */
  def serve: IO[Unit] =
    def loop: IO[Unit] =
      mux
        .recvProtocol(protoId)
        .flatMap { payload =>
          decodeClientMsg(payload) match
            case Right(ClientMsg.SubmitTx(eraTag, txBytes)) =>
              handleSubmitTx(eraTag, txBytes) *> loop
            case Right(ClientMsg.Done) =>
              logger.debug("LocalTxSubmission: client sent MsgDone")
            case Left(err) =>
              logger.warn(s"LocalTxSubmission decode error: $err") *> loop
        }
        .handleErrorWith { err =>
          logger.debug(s"LocalTxSubmission connection closed: ${err.getMessage}")
        }

    loop

  /** Handle a tx submission: decode, validate via mempool, respond. */
  private def handleSubmitTx(@annotation.unused eraTag: Int, txBytes: ByteVector): IO[Unit] =
    BlockDecoder.decodeTx(txBytes) match
      case Left(err) =>
        logger.warn(s"LocalTxSubmission: failed to decode tx: $err") *>
          mux.sendResponse(protoId, msgRejectTx(s"tx decode failed: $err"))
      case Right(tx) =>
        val txHash = TxHash(Hash32.unsafeFrom(Crypto.blake2b256(tx.body.rawCbor)))
        for
          currentSlot <- currentSlotRef()
          // Use Conway params (current era) — in the future, use dynamic params
          params = ProtocolParameters.Conway
          result <- mempool.addTx(
            tx.body,
            tx.witnesses.vkeyWitnesses,
            tx.rawTx,
            txHash,
            params,
            currentSlot
          )
          _ <- result match
            case Right(_) =>
              logger.info(s"LocalTxSubmission: accepted tx ${txHash.txHashToHash32.hash32Hex.take(16)}") *>
                mux.sendResponse(protoId, msgAcceptTx)
            case Left(errors) =>
              val reason = errors.mkString("; ")
              logger.info(s"LocalTxSubmission: rejected tx ${txHash.txHashToHash32.hash32Hex.take(16)}: $reason") *>
                mux.sendResponse(protoId, msgRejectTx(reason))
        yield ()
