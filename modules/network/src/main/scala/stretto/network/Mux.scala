package stretto.network

import cats.effect.IO
import cats.effect.std.Queue
import fs2.{Chunk, Pull, Stream}
import fs2.io.net.Socket
import scodec.bits.ByteVector

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

/** Multiplexer/demultiplexer over a TCP socket. */
final class MuxDemuxer private (
    socket: Socket[IO],
    incoming: Queue[IO, Option[MuxFrame]]
):

  /** Send a payload on the given mini-protocol id (initiator direction). */
  def send(miniProtocolId: Int, payload: ByteVector): IO[Unit] =
    val frame = MuxFrame(
      transmissionTime = 0L,
      miniProtocolId = miniProtocolId,
      isResponse = false,
      payload = payload
    )
    socket.write(Chunk.byteVector(MuxFrame.encode(frame)))

  /** Send a response payload on the given mini-protocol id. */
  def sendResponse(miniProtocolId: Int, payload: ByteVector): IO[Unit] =
    val frame = MuxFrame(
      transmissionTime = 0L,
      miniProtocolId = miniProtocolId,
      isResponse = true,
      payload = payload
    )
    socket.write(Chunk.byteVector(MuxFrame.encode(frame)))

  /** Stream of incoming demultiplexed frames as (miniProtocolId, payload). */
  def receive: Stream[IO, (Int, ByteVector)] =
    Stream
      .fromQueueNoneTerminated(incoming)
      .map(f => (f.miniProtocolId, f.payload))

object MuxDemuxer:

  /** Create a MuxDemuxer that reads from the socket in a background fiber. */
  def apply(socket: Socket[IO]): IO[MuxDemuxer] =
    for
      q <- Queue.unbounded[IO, Option[MuxFrame]]
      demuxer = new MuxDemuxer(socket, q)
      _ <- MuxFrame
        .frameStream(socket.reads)
        .evalMap(frame => q.offer(Some(frame)))
        .onFinalize(q.offer(None))
        .compile
        .drain
        .start
    yield demuxer
