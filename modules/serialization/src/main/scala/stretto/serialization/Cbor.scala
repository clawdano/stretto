package stretto.serialization

import scodec.Attempt
import scodec.Codec
import scodec.DecodeResult
import scodec.Err
import scodec.SizeBound
import scodec.bits.BitVector
import scodec.bits.ByteVector
import scodec.codecs

/** Low-level CBOR codec primitives built on scodec. */
object Cbor:

  // --- CBOR major types (3-bit values) ---
  val MajorUnsignedInt: Int = 0
  val MajorNegativeInt: Int = 1
  val MajorByteString: Int  = 2
  val MajorTextString: Int  = 3
  val MajorArray: Int       = 4
  val MajorMap: Int         = 5
  val MajorTag: Int         = 6
  val MajorSimple: Int      = 7

  // ------------------------------------------------------------------
  // Helper to build codecs from encode/decode functions
  // ------------------------------------------------------------------

  private def codec[A](
      enc: A => Attempt[BitVector],
      dec: BitVector => Attempt[DecodeResult[A]]
  ): Codec[A] =
    new Codec[A]:
      def sizeBound: SizeBound                              = SizeBound.unknown
      def encode(value: A): Attempt[BitVector]              = enc(value)
      def decode(bits: BitVector): Attempt[DecodeResult[A]] = dec(bits)

  // ------------------------------------------------------------------
  // Initial-byte helpers
  // ------------------------------------------------------------------

  /**
   * Decode the initial byte into (majorType, additionalInfo).
   * Additional info meaning:
   *   - 0..23  -> value is inline
   *   - 24     -> next 1 byte is the value
   *   - 25     -> next 2 bytes
   *   - 26     -> next 4 bytes
   *   - 27     -> next 8 bytes
   */
  val initialByte: Codec[(Int, Int)] =
    codecs.uint8.xmap(
      b => (b >> 5, b & 0x1f),
      { case (major, info) => (major << 5) | (info & 0x1f) }
    )

  /**
   * Read the "argument" (unsigned Long) that follows the initial byte
   * given the 5-bit additional info field.
   */
  def argumentCodec(additionalInfo: Int): Codec[Long] =
    if additionalInfo <= 23 then codecs.provide(additionalInfo.toLong)
    else if additionalInfo == 24 then codecs.uint8.xmap(_.toLong, _.toInt)
    else if additionalInfo == 25 then codecs.uint16.xmap(_.toLong, _.toInt)
    else if additionalInfo == 26 then codecs.uint32
    else if additionalInfo == 27 then codecs.int64.xmap(identity, identity)
    else codecs.fail(Err(s"Unsupported CBOR additional info: $additionalInfo"))

  /** Encode a Long into the shortest CBOR additional-info + argument. */
  private def additionalInfoFor(value: Long): Int =
    if value <= 23 then value.toInt
    else if value <= 0xffL then 24
    else if value <= 0xffffL then 25
    else if value <= 0xffffffffL then 26
    else 27

  // ------------------------------------------------------------------
  // Codec that reads a CBOR head: expects a specific major type and
  // returns the argument as Long.
  // ------------------------------------------------------------------

  def headCodec(expectedMajor: Int): Codec[Long] =
    codec[Long](
      value =>
        val ai = additionalInfoFor(value)
        val ib = (expectedMajor << 5) | ai
        for
          ibBits  <- codecs.uint8.encode(ib)
          argBits <- argumentCodec(ai).encode(value)
        yield ibBits ++ argBits
      ,
      bits =>
        for
          ibResult <- initialByte.decode(bits)
          (major, ai) = ibResult.value
          _ <-
            if major == expectedMajor then Attempt.successful(())
            else Attempt.failure(Err(s"Expected CBOR major type $expectedMajor but got $major"))
          argResult <- argumentCodec(ai).decode(ibResult.remainder)
        yield argResult
    )

  // ------------------------------------------------------------------
  // Major type 0: Unsigned integer
  // ------------------------------------------------------------------

  val uint: Codec[Long] = headCodec(MajorUnsignedInt)

  // ------------------------------------------------------------------
  // Major type 1: Negative integer  (value = -1 - n)
  // ------------------------------------------------------------------

  val negInt: Codec[Long] =
    headCodec(MajorNegativeInt).xmap(
      n => -1L - n,
      v => -1L - v
    )

  // ------------------------------------------------------------------
  // Major type 2: Byte string
  // ------------------------------------------------------------------

  val byteString: Codec[ByteVector] =
    codec[ByteVector](
      bv =>
        for
          hd   <- headCodec(MajorByteString).encode(bv.size)
          body <- codecs.bits.encode(bv.bits)
        yield hd ++ body,
      bits =>
        for
          len  <- headCodec(MajorByteString).decode(bits)
          body <- codecs.fixedSizeBytes(len.value, codecs.bytes).decode(len.remainder)
        yield body
    )

  /** Byte string that must be exactly `n` bytes. */
  def byteStringN(n: Int): Codec[ByteVector] =
    byteString.exmap(
      bv =>
        if bv.size == n.toLong then Attempt.successful(bv)
        else Attempt.failure(Err(s"Expected $n bytes, got ${bv.size}")),
      bv =>
        if bv.size == n.toLong then Attempt.successful(bv)
        else Attempt.failure(Err(s"Expected $n bytes, got ${bv.size}"))
    )

  // ------------------------------------------------------------------
  // Major type 3: Text string (UTF-8)
  // ------------------------------------------------------------------

  val textString: Codec[String] =
    codec[String](
      s =>
        val bv = ByteVector(s.getBytes("UTF-8"))
        for
          hd   <- headCodec(MajorTextString).encode(bv.size)
          body <- codecs.bits.encode(bv.bits)
        yield hd ++ body
      ,
      bits =>
        for
          len  <- headCodec(MajorTextString).decode(bits)
          body <- codecs.fixedSizeBytes(len.value, codecs.bytes).decode(len.remainder)
        yield body.map(bv => new String(bv.toArray, "UTF-8"))
    )

  // ------------------------------------------------------------------
  // Major type 4: Array (definite length)
  // ------------------------------------------------------------------

  /** Codec for the array header -- returns the number of items. */
  val arrayHeader: Codec[Long] = headCodec(MajorArray)

  /** Codec for a CBOR array of known length with homogeneous element codec. */
  def array[A](elementCodec: Codec[A]): Codec[Vector[A]] =
    codec[Vector[A]](
      vec =>
        for
          hd    <- arrayHeader.encode(vec.size.toLong)
          elems <- codecs.vector(elementCodec).encode(vec)
        yield hd ++ elems,
      bits =>
        for
          len <- arrayHeader.decode(bits)
          elems <- codecs
            .vectorOfN(codecs.provide(len.value.toInt), elementCodec)
            .decode(len.remainder)
        yield elems
    )

  // ------------------------------------------------------------------
  // Major type 5: Map (definite length)
  // ------------------------------------------------------------------

  /** Codec for the map header -- returns the number of key-value pairs. */
  val mapHeader: Codec[Long] = headCodec(MajorMap)

  /** Codec for a CBOR map of known length with homogeneous key/value codecs. */
  def cborMap[K, V](keyCodec: Codec[K], valueCodec: Codec[V]): Codec[Vector[(K, V)]] =
    val pairCodec: Codec[(K, V)] = keyCodec ~ valueCodec
    codec[Vector[(K, V)]](
      vec =>
        for
          hd    <- mapHeader.encode(vec.size.toLong)
          pairs <- codecs.vector(pairCodec).encode(vec)
        yield hd ++ pairs,
      bits =>
        for
          len <- mapHeader.decode(bits)
          pairs <- codecs
            .vectorOfN(codecs.provide(len.value.toInt), pairCodec)
            .decode(len.remainder)
        yield pairs
    )

  // ------------------------------------------------------------------
  // Major type 6: Tag
  // ------------------------------------------------------------------

  /** Codec for the tag header -- returns the tag number. */
  val tagHeader: Codec[Long] = headCodec(MajorTag)

  /** Codec that reads a CBOR tag wrapping a value. Returns (tag, value). */
  def tagged[A](valueCodec: Codec[A]): Codec[(Long, A)] =
    codec[(Long, A)](
      { case (tag, a) =>
        for
          hd   <- tagHeader.encode(tag)
          body <- valueCodec.encode(a)
        yield hd ++ body
      },
      bits =>
        for
          tag <- tagHeader.decode(bits)
          v   <- valueCodec.decode(tag.remainder)
        yield v.map(a => (tag.value, a))
    )

  // ------------------------------------------------------------------
  // Major type 7: Simple values
  // ------------------------------------------------------------------

  /** CBOR false (0xf4). */
  val cborFalse: Codec[Boolean] =
    codecs.constant(ByteVector(0xf4)).xmap(_ => false, _ => ())

  /** CBOR true (0xf5). */
  val cborTrue: Codec[Boolean] =
    codecs.constant(ByteVector(0xf5)).xmap(_ => true, _ => ())

  /** CBOR boolean (true or false). */
  val cborBool: Codec[Boolean] =
    codec[Boolean](
      b =>
        if b then cborTrue.encode(true)
        else cborFalse.encode(false),
      bits => cborTrue.decode(bits).orElse(cborFalse.decode(bits))
    )

  /** CBOR null (0xf6). */
  val cborNull: Codec[Unit] =
    codecs.constant(ByteVector(0xf6))

  /** CBOR undefined (0xf7). */
  val cborUndefined: Codec[Unit] =
    codecs.constant(ByteVector(0xf7))
