package stretto.network

import cats.effect.IO
import cats.effect.std.{Queue, Semaphore}
import cats.effect.Ref
import cats.syntax.all.*
import fs2.{Chunk, Pull, Stream}
import fs2.io.net.Socket
import scodec.bits.ByteVector

import scala.collection.concurrent.TrieMap

/**
 * A single multiplexer frame as transmitted on the wire.
 *
 * Wire format (8 bytes header + payload):
 *   - bytes 0-3: transmission time (big-endian UInt32)
 *   - bytes 4-5: mini-protocol id with high bit = direction flag
 *   - bytes 6-7: payload length (big-endian UInt16)
 *   - bytes 8..: payload
 */
final case class MuxFrame(
    transmissionTime: Long,
    miniProtocolId: Int,
    isResponse: Boolean,
    payload: ByteVector
)

object MuxFrame:

  val HeaderSize: Int     = 8
  val MaxPayloadSize: Int = 65535

  def encode(frame: MuxFrame): ByteVector =
    val timeBv    = ByteVector.fromInt(frame.transmissionTime.toInt, size = 4)
    val idRaw     = frame.miniProtocolId & 0x7fff
    val idWithDir = if frame.isResponse then idRaw | 0x8000 else idRaw
    val idBv      = ByteVector.fromShort(idWithDir.toShort, size = 2)
    val lenBv     = ByteVector.fromShort(frame.payload.size.toShort, size = 2)
    timeBv ++ idBv ++ lenBv ++ frame.payload

  def decodeHeader(header: ByteVector): (Long, Int, Boolean, Int) =
    val time       = header.take(4).toInt(signed = false).toLong & 0xffffffffL
    val idWord     = header.drop(4).take(2).toShort(signed = false) & 0xffff
    val isResponse = (idWord & 0x8000) != 0
    val protoId    = idWord & 0x7fff
    val len        = header.drop(6).take(2).toShort(signed = false) & 0xffff
    (time, protoId, isResponse, len)

  /** Parse a byte stream into MuxFrames using fs2 Pull. */
  def frameStream(bytes: Stream[IO, Byte]): Stream[IO, MuxFrame] =
    def go(buffer: ByteVector, s: Stream[IO, Byte]): Pull[IO, MuxFrame, Unit] =
      if buffer.size >= HeaderSize then
        val (time, protoId, isResp, payloadLen) = decodeHeader(buffer)
        val totalNeeded                         = HeaderSize + payloadLen
        if buffer.size >= totalNeeded then
          val payload = buffer.slice(HeaderSize.toLong, totalNeeded.toLong)
          val frame   = MuxFrame(time, protoId, isResp, payload)
          Pull.output1(frame) >> go(buffer.drop(totalNeeded.toLong), s)
        else pullMore(buffer, s)
      else pullMore(buffer, s)

    def pullMore(
        buffer: ByteVector,
        s: Stream[IO, Byte]
    ): Pull[IO, MuxFrame, Unit] =
      s.pull.uncons.flatMap:
        case None => Pull.done
        case Some((chunk, rest)) =>
          go(buffer ++ ByteVector.view(chunk.toArray), rest)

    go(ByteVector.empty, bytes).stream

/**
 * Per-protocol channel buffer that reassembles segmented mux messages.
 *
 * The Ouroboros mux protocol may split a single protocol message across
 * multiple mux frames. This buffer accumulates raw segment payloads and
 * delivers complete CBOR messages by trying to decode after each chunk.
 *
 * Follows the same pattern as Pallas' ChannelBuffer (Rust Cardano impl).
 */
private final class ChannelBuffer(
    segments: Queue[IO, Option[ByteVector]],
    buffer: Ref[IO, ByteVector]
):

  /** Receive one complete protocol message (reassembled from segments). */
  def recvMessage: IO[ByteVector] =
    buffer.get.flatMap { buf =>
      if buf.nonEmpty then
        tryExtractCborItem(buf) match
          case Some((msg, remaining)) =>
            buffer.set(remaining).as(msg)
          case None =>
            readMoreAndTry
      else readMoreAndTry
    }

  private def readMoreAndTry: IO[ByteVector] =
    segments.take.flatMap {
      case None =>
        IO.raiseError(new RuntimeException("Protocol stream terminated"))
      case Some(chunk) =>
        buffer.modify(buf => (buf ++ chunk, ())).flatMap { _ =>
          buffer.get.flatMap { buf =>
            tryExtractCborItem(buf) match
              case Some((msg, remaining)) =>
                buffer.set(remaining).as(msg)
              case None =>
                readMoreAndTry
          }
        }
    }

  /** Offer a segment into this channel's queue. */
  def offer(segment: Option[ByteVector]): IO[Unit] =
    segments.offer(segment)

  /**
   * Try to extract one complete CBOR item from the buffer.
   * Returns Some((item, remaining)) if successful, None if more data needed.
   */
  private def tryExtractCborItem(bytes: ByteVector): Option[(ByteVector, ByteVector)] =
    if bytes.isEmpty then None
    else
      skipCborItem(bytes, 0) match
        case Right(endOffset) =>
          Some((bytes.take(endOffset.toLong), bytes.drop(endOffset.toLong)))
        case Left(_) =>
          None // incomplete — need more segments

  /** Skip one complete CBOR item, returning the offset after it. */
  private def skipCborItem(bytes: ByteVector, offset: Int): Either[String, Int] =
    if offset >= bytes.size then Left("need more data")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f

      readArgAndOffset(bytes, offset, ai) match
        case Left(err) => Left(err)
        case Right((arg, nextOffset)) =>
          major match
            case 0 | 1 | 7 => Right(nextOffset)
            case 2 | 3 =>
              val end = nextOffset + arg.toInt
              if end > bytes.size then Left("need more data")
              else Right(end)
            case 4 =>
              var cursor = nextOffset
              var i      = 0L
              while i < arg do
                skipCborItem(bytes, cursor) match
                  case Right(next) => cursor = next
                  case Left(err)   => return Left(err)
                i += 1
              Right(cursor)
            case 5 =>
              var cursor = nextOffset
              var i      = 0L
              while i < arg * 2 do
                skipCborItem(bytes, cursor) match
                  case Right(next) => cursor = next
                  case Left(err)   => return Left(err)
                i += 1
              Right(cursor)
            case 6 =>
              skipCborItem(bytes, nextOffset)
            case _ => Left(s"unknown major type $major")

  private def readArgAndOffset(bytes: ByteVector, offset: Int, ai: Int): Either[String, (Long, Int)] =
    if ai < 24 then Right((ai.toLong, offset + 1))
    else if ai == 24 then
      if offset + 2 > bytes.size then Left("need more data")
      else Right(((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2))
    else if ai == 25 then
      if offset + 3 > bytes.size then Left("need more data")
      else
        val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
        Right((v.toLong, offset + 3))
    else if ai == 26 then
      if offset + 5 > bytes.size then Left("need more data")
      else
        val v =
          ((bytes(offset.toLong + 1) & 0xff).toLong << 24) |
            ((bytes(offset.toLong + 2) & 0xff).toLong << 16) |
            ((bytes(offset.toLong + 3) & 0xff).toLong << 8) |
            (bytes(offset.toLong + 4) & 0xff).toLong
        Right((v, offset + 5))
    else if ai == 27 then
      if offset + 9 > bytes.size then Left("need more data")
      else
        val v =
          ((bytes(offset.toLong + 1) & 0xff).toLong << 56) |
            ((bytes(offset.toLong + 2) & 0xff).toLong << 48) |
            ((bytes(offset.toLong + 3) & 0xff).toLong << 40) |
            ((bytes(offset.toLong + 4) & 0xff).toLong << 32) |
            ((bytes(offset.toLong + 5) & 0xff).toLong << 24) |
            ((bytes(offset.toLong + 6) & 0xff).toLong << 16) |
            ((bytes(offset.toLong + 7) & 0xff).toLong << 8) |
            (bytes(offset.toLong + 8) & 0xff).toLong
        Right((v, offset + 9))
    else Left(s"unsupported additional info: $ai")

/** Multiplexer/demultiplexer over a TCP socket. */
final class MuxDemuxer private (
    socket: Socket[IO],
    channels: TrieMap[Int, ChannelBuffer],
    fallbackQueue: Queue[IO, Option[MuxFrame]],
    writeLock: Semaphore[IO]
):

  /** Send a payload on the given mini-protocol id (initiator direction). */
  def send(miniProtocolId: Int, payload: ByteVector): IO[Unit] =
    val frame = MuxFrame(
      transmissionTime = 0L,
      miniProtocolId = miniProtocolId,
      isResponse = false,
      payload = payload
    )
    writeLock.permit.use(_ => socket.write(Chunk.byteVector(MuxFrame.encode(frame))))

  /** Send a response payload on the given mini-protocol id. */
  def sendResponse(miniProtocolId: Int, payload: ByteVector): IO[Unit] =
    val frame = MuxFrame(
      transmissionTime = 0L,
      miniProtocolId = miniProtocolId,
      isResponse = true,
      payload = payload
    )
    writeLock.permit.use(_ => socket.write(Chunk.byteVector(MuxFrame.encode(frame))))

  /**
   * Receive one complete reassembled message for a specific mini-protocol.
   * Accumulates mux segments until a complete CBOR item is available.
   */
  def recvProtocol(miniProtocolId: Int): IO[ByteVector] =
    getOrCreateChannel(miniProtocolId).flatMap(_.recvMessage)

  /** Stream of incoming demultiplexed frames as (miniProtocolId, payload). Kept for backward compat. */
  def receive: Stream[IO, (Int, ByteVector)] =
    Stream
      .fromQueueNoneTerminated(fallbackQueue)
      .map(f => (f.miniProtocolId, f.payload))

  private def getOrCreateChannel(protoId: Int): IO[ChannelBuffer] =
    channels.get(protoId) match
      case Some(ch) => IO.pure(ch)
      case None =>
        for
          q   <- Queue.unbounded[IO, Option[ByteVector]]
          buf <- Ref.of[IO, ByteVector](ByteVector.empty)
          ch = new ChannelBuffer(q, buf)
          result <- IO {
            channels.putIfAbsent(protoId, ch) match
              case Some(existing) => existing
              case None           => ch
          }
        yield result

  private[network] def routeFrame(frame: MuxFrame): IO[Unit] =
    getOrCreateChannel(frame.miniProtocolId).flatMap(_.offer(Some(frame.payload)))

  private[network] def terminate: IO[Unit] =
    val terminateChannels = IO {
      channels.values.toList
    }.flatMap(_.traverse_(_.offer(None)))
    terminateChannels *> fallbackQueue.offer(None)

object MuxDemuxer:

  /** Create a MuxDemuxer that reads from the socket in a background fiber. */
  def apply(socket: Socket[IO]): IO[MuxDemuxer] =
    for
      fallback  <- Queue.unbounded[IO, Option[MuxFrame]]
      writeLock <- Semaphore[IO](1)
      chans   = new TrieMap[Int, ChannelBuffer]()
      demuxer = new MuxDemuxer(socket, chans, fallback, writeLock)
      _ <- MuxFrame
        .frameStream(socket.reads)
        .evalMap(frame => demuxer.routeFrame(frame))
        .onFinalize(demuxer.terminate)
        .compile
        .drain
        .start
    yield demuxer
