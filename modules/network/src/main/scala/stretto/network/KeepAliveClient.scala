package stretto.network

import cats.effect.{Clock, IO}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector

/**
 * KeepAlive N2N mini-protocol responder.
 *
 * The Cardano node sends periodic MsgKeepAlive pings (cookie).
 * We must respond with MsgKeepAliveResponse echoing the cookie.
 * Failure to respond within the timeout (~90s) causes disconnection.
 *
 * Wire format (CBOR):
 *   MsgKeepAlive(cookie)         = [0, cookie]  (server → client)
 *   MsgKeepAliveResponse(cookie) = [1, cookie]  (client → server)
 *   MsgDone                      = [2]          (client → server)
 *
 * This runs as a background fiber for the lifetime of the connection.
 */
final class KeepAliveClient(mux: MuxDemuxer):

  private val logger  = Slf4jLogger.getLoggerFromName[IO]("stretto.network.KeepAlive")
  private val protoId = MiniProtocolId.KeepAlive.id

  /** Receive a KeepAlive message from the peer. */
  private def recv: IO[ByteVector] =
    mux.recvProtocol(protoId)

  /** Send a KeepAlive response to the peer. */
  private def sendResponse(payload: ByteVector): IO[Unit] =
    mux.sendResponse(protoId, payload)

  /**
   * Run the KeepAlive responder loop.
   * Responds to MsgKeepAlive with MsgKeepAliveResponse.
   * Exits when the connection closes (Protocol stream terminated).
   */
  def respondLoop: IO[Unit] =
    recv
      .flatMap { payload =>
        // Parse: expect [0, cookie] = MsgKeepAlive
        val tag = parseCborArrayTag(payload)
        tag match
          case Some(0) =>
            // Extract cookie and respond with [1, cookie]
            val cookie   = extractCookie(payload)
            val response = encodeMsgKeepAliveResponse(cookie)
            for
              recvTime <- Clock[IO].monotonic
              _        <- logger.debug(s"Received MsgKeepAlive cookie=$cookie at ${recvTime.toMillis}ms")
              _        <- sendResponse(response)
              sentTime <- Clock[IO].monotonic
              delay = (sentTime - recvTime).toMillis
              _ <- if delay > 100 then
                logger.warn(s"KeepAlive response delayed ${delay}ms (cookie=$cookie)")
              else
                logger.debug(s"KeepAlive response sent in ${delay}ms (cookie=$cookie)")
              _ <- respondLoop
            yield ()
          case Some(other) =>
            logger.warn(s"KeepAlive unexpected tag=$other, payload=${payload.take(16).toHex}") *>
              IO.unit
          case None =>
            logger.warn(s"KeepAlive failed to parse tag, payload=${payload.take(16).toHex}") *>
              IO.unit
      }
      .handleErrorWith { err =>
        // Connection closed — normal termination
        logger.debug(s"KeepAlive loop ended: ${err.getMessage}")
      }

  /** Parse the first element of a CBOR array to get the message tag. */
  private def parseCborArrayTag(bytes: ByteVector): Option[Int] =
    if bytes.size < 2 then None
    else
      val b = bytes(0) & 0xff
      if (b >> 5) != 4 then None // not an array
      else
        val ai      = b & 0x1f
        val tagByte = if ai < 24 then 1 else if ai == 24 then 2 else return None
        val tagIdx  = tagByte.toLong
        if tagIdx >= bytes.size then None
        else
          val tb    = bytes(tagIdx) & 0xff
          val major = tb >> 5
          if major != 0 then None // not a uint
          else Some(tb & 0x1f)

  /** Extract the cookie uint16 from [0, cookie]. */
  private def extractCookie(bytes: ByteVector): Int =
    // [0, cookie] where cookie is a CBOR uint
    // After array header (1 byte) and tag 0 (1 byte), the cookie is at offset 2
    if bytes.size < 3 then 0
    else
      val b  = bytes(2) & 0xff
      val ai = b & 0x1f
      if ai < 24 then ai
      else if ai == 24 && bytes.size >= 4 then bytes(3) & 0xff
      else if ai == 25 && bytes.size >= 5 then ((bytes(3) & 0xff) << 8) | (bytes(4) & 0xff)
      else 0

  /** Encode MsgKeepAliveResponse = [1, cookie]. */
  private def encodeMsgKeepAliveResponse(cookie: Int): ByteVector =
    if cookie < 24 then
      // [1, small_uint] = 0x82 0x01 cookie
      ByteVector(0x82.toByte, 0x01.toByte, cookie.toByte)
    else if cookie < 256 then
      // [1, uint8] = 0x82 0x01 0x18 cookie
      ByteVector(0x82.toByte, 0x01.toByte, 0x18.toByte, cookie.toByte)
    else
      // [1, uint16] = 0x82 0x01 0x19 hi lo
      ByteVector(0x82.toByte, 0x01.toByte, 0x19.toByte, (cookie >> 8).toByte, cookie.toByte)
