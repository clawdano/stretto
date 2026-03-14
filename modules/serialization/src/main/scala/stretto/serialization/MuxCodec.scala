package stretto.serialization

import scodec.Attempt
import scodec.Codec
import scodec.DecodeResult
import scodec.SizeBound
import scodec.bits.BitVector
import scodec.bits.ByteVector
import scodec.codecs

/**
 * Ouroboros network multiplexer frame codec.
 *
 * The mux header is 8 bytes (big-endian):
 *   - 4 bytes: transmission time (uint32)
 *   - 2 bytes: mini-protocol ID (uint16, bit 15 = direction: 0 = initiator, 1 = responder)
 *   - 2 bytes: payload length (uint16)
 */
object MuxCodec:

  private def codec[A](
      enc: A => Attempt[BitVector],
      dec: BitVector => Attempt[DecodeResult[A]]
  ): Codec[A] =
    new Codec[A]:
      def sizeBound: SizeBound                              = SizeBound.unknown
      def encode(value: A): Attempt[BitVector]              = enc(value)
      def decode(bits: BitVector): Attempt[DecodeResult[A]] = dec(bits)

  /** Parsed multiplexer header. */
  case class MuxHeader(
      transmissionTime: Long,
      miniProtocolId: Int,
      isResponse: Boolean,
      payloadLength: Int
  )

  /** A complete multiplexer frame: header + payload bytes. */
  case class MuxFrame(
      header: MuxHeader,
      payload: ByteVector
  )

  /** Codec for the 8-byte mux header. */
  val muxHeaderCodec: Codec[MuxHeader] =
    (codecs.uint32 ~ codecs.uint16 ~ codecs.uint16).xmap(
      { case ((time, protoWord), len) =>
        val isResp  = (protoWord & 0x8000) != 0
        val protoId = protoWord & 0x7fff
        MuxHeader(time, protoId, isResp, len)
      },
      { hdr =>
        val protoWord = hdr.miniProtocolId | (if hdr.isResponse then 0x8000 else 0)
        ((hdr.transmissionTime, protoWord), hdr.payloadLength)
      }
    )

  /** Codec for a complete mux frame (header + payload). */
  val muxFrameCodec: Codec[MuxFrame] =
    codec[MuxFrame](
      frame =>
        for
          hdrBits     <- muxHeaderCodec.encode(frame.header)
          payloadBits <- codecs.bytes(frame.header.payloadLength).encode(frame.payload)
        yield hdrBits ++ payloadBits,
      bits =>
        for
          hdr     <- muxHeaderCodec.decode(bits)
          payload <- codecs.bytes(hdr.value.payloadLength).decode(hdr.remainder)
        yield payload.map(bv => MuxFrame(hdr.value, bv))
    )
