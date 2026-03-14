package stretto.serialization

import scodec.Attempt
import scodec.Codec
import scodec.DecodeResult
import scodec.Err
import scodec.SizeBound
import scodec.bits.BitVector
import scodec.bits.ByteVector
import stretto.core.Types.BlockNo
import stretto.core.Types.EpochNo
import stretto.core.Types.Hash28
import stretto.core.Types.Hash32
import stretto.core.Types.Lovelace
import stretto.core.Types.SlotNo

/** Cardano-specific CBOR codecs layered on top of [[Cbor]]. */
object CardanoCbor:

  private def codec[A](
      enc: A => Attempt[BitVector],
      dec: BitVector => Attempt[DecodeResult[A]]
  ): Codec[A] =
    new Codec[A]:
      def sizeBound: SizeBound                              = SizeBound.unknown
      def encode(value: A): Attempt[BitVector]              = enc(value)
      def decode(bits: BitVector): Attempt[DecodeResult[A]] = dec(bits)

  /** Codec for a 28-byte hash encoded as a CBOR byte string. */
  val hash28Codec: Codec[Hash28] =
    Cbor
      .byteStringN(28)
      .exmap(
        bv => Attempt.fromEither(Hash28(bv).left.map(Err.apply)),
        h => Attempt.successful(h.hash28Bytes)
      )

  /** Codec for a 32-byte hash encoded as a CBOR byte string. */
  val hash32Codec: Codec[Hash32] =
    Cbor
      .byteStringN(32)
      .exmap(
        bv => Attempt.fromEither(Hash32(bv).left.map(Err.apply)),
        h => Attempt.successful(h.hash32Bytes)
      )

  /** Lovelace (ADA sub-unit) encoded as a CBOR unsigned integer. */
  val coinCodec: Codec[Lovelace] =
    Cbor.uint.xmap(Lovelace.apply, _.lovelaceValue)

  /** Slot number encoded as a CBOR unsigned integer. */
  val slotNoCodec: Codec[SlotNo] =
    Cbor.uint.xmap(SlotNo.apply, _.value)

  /** Block number encoded as a CBOR unsigned integer. */
  val blockNoCodec: Codec[BlockNo] =
    Cbor.uint.xmap(BlockNo.apply, _.blockNoValue)

  /** Epoch number encoded as a CBOR unsigned integer. */
  val epochNoCodec: Codec[EpochNo] =
    Cbor.uint.xmap(EpochNo.apply, _.epochNoValue)

  /** Cardano era tags. */
  enum Era(val tag: Int):
    case Byron   extends Era(0)
    case Shelley extends Era(1)
    case Allegra extends Era(2)
    case Mary    extends Era(3)
    case Alonzo  extends Era(4)
    case Babbage extends Era(5)
    case Conway  extends Era(6)

  object Era:

    def fromTag(t: Int): Attempt[Era] =
      Era.values.find(_.tag == t) match
        case Some(e) => Attempt.successful(e)
        case None    => Attempt.failure(Err(s"Unknown era tag: $t"))

  /**
   * Multi-era block wrapper: reads a 2-element CBOR array [era_tag, block_bytes]
   * and returns (Era, ByteVector) where block_bytes is the raw CBOR byte string.
   */
  val multiEraBlockCodec: Codec[(Era, ByteVector)] =
    codec[(Era, ByteVector)](
      { case (era, blockBytes) =>
        for
          hdr  <- Cbor.arrayHeader.encode(2L)
          tag  <- Cbor.uint.encode(era.tag.toLong)
          body <- Cbor.byteString.encode(blockBytes)
        yield hdr ++ tag ++ body
      },
      bits =>
        for
          len <- Cbor.arrayHeader.decode(bits)
          _ <-
            if len.value == 2L then Attempt.successful(())
            else Attempt.failure(Err(s"Expected 2-element array, got ${len.value}"))
          tag <- Cbor.uint.decode(len.remainder)
          era <- Era.fromTag(tag.value.toInt)
          blk <- Cbor.byteString.decode(tag.remainder)
        yield blk.map(bv => (era, bv))
    )
