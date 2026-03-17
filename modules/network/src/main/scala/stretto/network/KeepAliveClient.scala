package stretto.network

import cats.effect.{Clock, IO}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector

import scala.concurrent.duration.*
import scala.util.Random

/**
 * KeepAlive N2N mini-protocol client (initiator).
 *
 * In Ouroboros N2N, the TCP connection initiator runs the KeepAlive client.
 * The client periodically sends MsgKeepAlive(cookie) and expects
 * MsgKeepAliveResponse(cookie) back from the peer.
 *
 * Wire format (CBOR):
 *   MsgKeepAlive(cookie)         = [0, cookie]  (client → server, initiator)
 *   MsgKeepAliveResponse(cookie) = [1, cookie]  (server → client, responder)
 *   MsgDone                      = [2]          (client → server, terminal)
 *
 * This runs as a background fiber for the lifetime of the connection.
 *
 * Reference: ouroboros-network keep-alive.cddl
 */
final class KeepAliveClient(mux: MuxDemuxer, interval: FiniteDuration = 10.seconds):

  private val logger  = Slf4jLogger.getLoggerFromName[IO]("stretto.network.KeepAlive")
  private val protoId = MiniProtocolId.KeepAlive.id

  /** Send a KeepAlive ping to the peer (initiator direction). */
  private def send(payload: ByteVector): IO[Unit] =
    mux.send(protoId, payload)

  /** Receive a KeepAlive response from the peer. */
  private def recv: IO[ByteVector] =
    mux.recvProtocol(protoId)

  /**
   * Run the KeepAlive client loop.
   *
   * Sends MsgKeepAlive(cookie) every `interval`, waits for
   * MsgKeepAliveResponse(cookie), validates the cookie matches.
   * Exits when the connection closes.
   */
  def keepAliveLoop: IO[Unit] =
    val cookie  = Random.nextInt(65536) // uint16
    val encoded = encodeMsgKeepAlive(cookie)
    val ping = for
      sendTime <- Clock[IO].monotonic
      _        <- logger.debug(s"Sending MsgKeepAlive cookie=$cookie")
      _        <- send(encoded)
      response <- recv
      recvTime <- Clock[IO].monotonic
      rtt = (recvTime - sendTime).toMillis
      _ <- parseMsgKeepAliveResponse(response) match
        case Some(responseCookie) if responseCookie == cookie =>
          logger.debug(s"KeepAlive response OK cookie=$cookie rtt=${rtt}ms")
        case Some(responseCookie) =>
          logger.warn(s"KeepAlive cookie mismatch: sent=$cookie got=$responseCookie rtt=${rtt}ms")
        case None =>
          logger.warn(s"KeepAlive unexpected response: ${response.take(16).toHex}")
      _ <- IO.sleep(interval)
    yield ()

    ping
      .flatMap(_ => keepAliveLoop)
      .handleErrorWith { err =>
        logger.debug(s"KeepAlive loop ended: ${err.getMessage}")
      }

  /**
   * Run the KeepAlive responder loop (for when we are the server/acceptor).
   *
   * Used by the N2C relay node when accepting incoming connections.
   * Receives MsgKeepAlive(cookie) from client, responds with
   * MsgKeepAliveResponse(cookie).
   */
  def respondLoop: IO[Unit] =
    recv
      .flatMap { payload =>
        val tag = parseCborArrayTag(payload)
        tag match
          case Some(0) =>
            val cookie   = extractCookie(payload)
            val response = encodeMsgKeepAliveResponse(cookie)
            for
              recvTime <- Clock[IO].monotonic
              _        <- logger.debug(s"Received MsgKeepAlive cookie=$cookie")
              _        <- mux.sendResponse(protoId, response)
              sentTime <- Clock[IO].monotonic
              delay = (sentTime - recvTime).toMillis
              _ <-
                if delay > 100 then logger.warn(s"KeepAlive response delayed ${delay}ms (cookie=$cookie)")
                else logger.debug(s"KeepAlive response sent in ${delay}ms (cookie=$cookie)")
              _ <- respondLoop
            yield ()
          case Some(other) =>
            logger.warn(s"KeepAlive unexpected tag=$other, payload=${payload.take(16).toHex}")
          case None =>
            logger.warn(s"KeepAlive failed to parse tag, payload=${payload.take(16).toHex}")
      }
      .handleErrorWith { err =>
        logger.debug(s"KeepAlive responder loop ended: ${err.getMessage}")
      }

  /** Encode MsgKeepAlive = [0, cookie]. Client → Server. */
  private def encodeMsgKeepAlive(cookie: Int): ByteVector =
    if cookie < 24 then ByteVector(0x82.toByte, 0x00.toByte, cookie.toByte)
    else if cookie < 256 then ByteVector(0x82.toByte, 0x00.toByte, 0x18.toByte, cookie.toByte)
    else ByteVector(0x82.toByte, 0x00.toByte, 0x19.toByte, (cookie >> 8).toByte, cookie.toByte)

  /** Encode MsgKeepAliveResponse = [1, cookie]. Server → Client. */
  private def encodeMsgKeepAliveResponse(cookie: Int): ByteVector =
    if cookie < 24 then ByteVector(0x82.toByte, 0x01.toByte, cookie.toByte)
    else if cookie < 256 then ByteVector(0x82.toByte, 0x01.toByte, 0x18.toByte, cookie.toByte)
    else ByteVector(0x82.toByte, 0x01.toByte, 0x19.toByte, (cookie >> 8).toByte, cookie.toByte)

  /** Parse MsgKeepAliveResponse = [1, cookie]. Returns the cookie if valid. */
  private def parseMsgKeepAliveResponse(bytes: ByteVector): Option[Int] =
    val tag = parseCborArrayTag(bytes)
    tag match
      case Some(1) => Some(extractCookie(bytes))
      case _       => None

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

  /** Extract the cookie uint16 from [tag, cookie]. Cookie is at position 1 in the array. */
  private def extractCookie(bytes: ByteVector): Int =
    if bytes.size < 3 then 0
    else
      val b  = bytes(2) & 0xff
      val ai = b & 0x1f
      if ai < 24 then ai
      else if ai == 24 && bytes.size >= 4 then bytes(3) & 0xff
      else if ai == 25 && bytes.size >= 5 then ((bytes(3) & 0xff) << 8) | (bytes(4) & 0xff)
      else 0
