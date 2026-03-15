package stretto.serialization

import munit.FunSuite
import scodec.bits.BitVector
import scodec.bits.ByteVector
import scodec.bits.hex

class CborSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // Unsigned integer (major type 0)
  // ---------------------------------------------------------------------------

  test("uint: encode 0 produces single byte 0x00") {
    val result = Cbor.uint.encode(0L).require
    assertEquals(result.bytes, hex"00")
  }

  test("uint: encode 23 produces single byte 0x17") {
    val result = Cbor.uint.encode(23L).require
    assertEquals(result.bytes, hex"17")
  }

  test("uint: encode 24 uses 2 bytes (ai=24)") {
    val result = Cbor.uint.encode(24L).require
    assertEquals(result.bytes, hex"1818")
  }

  test("uint: encode 255 uses 2 bytes") {
    val result = Cbor.uint.encode(255L).require
    assertEquals(result.bytes, hex"18ff")
  }

  test("uint: encode 256 uses 3 bytes (ai=25)") {
    val result = Cbor.uint.encode(256L).require
    assertEquals(result.bytes, hex"190100")
  }

  test("uint: encode 65535 uses 3 bytes") {
    val result = Cbor.uint.encode(65535L).require
    assertEquals(result.bytes, hex"19ffff")
  }

  test("uint: encode 65536 uses 5 bytes (ai=26)") {
    val result = Cbor.uint.encode(65536L).require
    assertEquals(result.bytes, hex"1a00010000")
  }

  test("uint: round-trip small values") {
    for v <- List(0L, 1L, 10L, 23L) do
      val bits    = Cbor.uint.encode(v).require
      val decoded = Cbor.uint.decode(bits).require.value
      assertEquals(decoded, v, s"uint round-trip failed for $v")
  }

  test("uint: round-trip medium values") {
    for v <- List(24L, 100L, 255L, 256L, 1000L, 65535L) do
      val bits    = Cbor.uint.encode(v).require
      val decoded = Cbor.uint.decode(bits).require.value
      assertEquals(decoded, v, s"uint round-trip failed for $v")
  }

  test("uint: round-trip large values") {
    for v <- List(65536L, 100000L, 0xffffffffL) do
      val bits    = Cbor.uint.encode(v).require
      val decoded = Cbor.uint.decode(bits).require.value
      assertEquals(decoded, v, s"uint round-trip failed for $v")
  }

  // ---------------------------------------------------------------------------
  // Negative integer (major type 1)
  // ---------------------------------------------------------------------------

  test("negInt: encode -1 produces 0x20") {
    val result = Cbor.negInt.encode(-1L).require
    assertEquals(result.bytes, hex"20")
  }

  test("negInt: round-trip -1") {
    val bits    = Cbor.negInt.encode(-1L).require
    val decoded = Cbor.negInt.decode(bits).require.value
    assertEquals(decoded, -1L)
  }

  test("negInt: round-trip -100") {
    val bits    = Cbor.negInt.encode(-100L).require
    val decoded = Cbor.negInt.decode(bits).require.value
    assertEquals(decoded, -100L)
  }

  // ---------------------------------------------------------------------------
  // Byte string (major type 2)
  // ---------------------------------------------------------------------------

  test("byteString: empty round-trips") {
    val bits    = Cbor.byteString.encode(ByteVector.empty).require
    val decoded = Cbor.byteString.decode(bits).require.value
    assertEquals(decoded, ByteVector.empty)
  }

  test("byteString: empty encodes to 0x40") {
    val bits = Cbor.byteString.encode(ByteVector.empty).require
    assertEquals(bits.bytes, hex"40")
  }

  test("byteString: 4 bytes round-trips") {
    val bv      = hex"deadbeef"
    val bits    = Cbor.byteString.encode(bv).require
    val decoded = Cbor.byteString.decode(bits).require.value
    assertEquals(decoded, bv)
  }

  test("byteString: 32 bytes round-trips") {
    val bv      = ByteVector.fill(32)(0xab)
    val bits    = Cbor.byteString.encode(bv).require
    val decoded = Cbor.byteString.decode(bits).require.value
    assertEquals(decoded, bv)
  }

  test("byteStringN: rejects wrong length on encode") {
    val result = Cbor.byteStringN(32).encode(ByteVector.fill(16)(0x00))
    assert(result.isFailure)
  }

  test("byteStringN: rejects wrong length on decode") {
    // Encode a 16-byte bstr, try to decode as byteStringN(32)
    val bits   = Cbor.byteString.encode(ByteVector.fill(16)(0x00)).require
    val result = Cbor.byteStringN(32).decode(bits)
    assert(result.isFailure)
  }

  // ---------------------------------------------------------------------------
  // Text string (major type 3)
  // ---------------------------------------------------------------------------

  test("textString: empty string round-trips") {
    val bits    = Cbor.textString.encode("").require
    val decoded = Cbor.textString.decode(bits).require.value
    assertEquals(decoded, "")
  }

  test("textString: hello round-trips") {
    val bits    = Cbor.textString.encode("hello").require
    val decoded = Cbor.textString.decode(bits).require.value
    assertEquals(decoded, "hello")
  }

  test("textString: UTF-8 round-trips") {
    val s       = "caf\u00e9"
    val bits    = Cbor.textString.encode(s).require
    val decoded = Cbor.textString.decode(bits).require.value
    assertEquals(decoded, s)
  }

  // ---------------------------------------------------------------------------
  // Array (major type 4)
  // ---------------------------------------------------------------------------

  test("arrayHeader: encode 0 produces 0x80") {
    val bits = Cbor.arrayHeader.encode(0L).require
    assertEquals(bits.bytes, hex"80")
  }

  test("arrayHeader: encode 3 produces 0x83") {
    val bits = Cbor.arrayHeader.encode(3L).require
    assertEquals(bits.bytes, hex"83")
  }

  test("arrayHeader: round-trip") {
    for n <- List(0L, 1L, 5L, 23L, 24L, 100L) do
      val bits    = Cbor.arrayHeader.encode(n).require
      val decoded = Cbor.arrayHeader.decode(bits).require.value
      assertEquals(decoded, n)
  }

  test("array of uints: round-trip") {
    val vec     = Vector(1L, 2L, 3L)
    val bits    = Cbor.array(Cbor.uint).encode(vec).require
    val decoded = Cbor.array(Cbor.uint).decode(bits).require.value
    assertEquals(decoded, vec)
  }

  test("array: empty round-trips") {
    val vec     = Vector.empty[Long]
    val bits    = Cbor.array(Cbor.uint).encode(vec).require
    val decoded = Cbor.array(Cbor.uint).decode(bits).require.value
    assertEquals(decoded, vec)
  }

  // ---------------------------------------------------------------------------
  // Map (major type 5)
  // ---------------------------------------------------------------------------

  test("mapHeader: encode 0 produces 0xa0") {
    val bits = Cbor.mapHeader.encode(0L).require
    assertEquals(bits.bytes, hex"a0")
  }

  test("mapHeader: round-trip") {
    for n <- List(0L, 1L, 5L, 23L) do
      val bits    = Cbor.mapHeader.encode(n).require
      val decoded = Cbor.mapHeader.decode(bits).require.value
      assertEquals(decoded, n)
  }

  test("cborMap: round-trip uint->uint") {
    val entries = Vector((1L, 100L), (2L, 200L))
    val bits    = Cbor.cborMap(Cbor.uint, Cbor.uint).encode(entries).require
    val decoded = Cbor.cborMap(Cbor.uint, Cbor.uint).decode(bits).require.value
    assertEquals(decoded, entries)
  }

  // ---------------------------------------------------------------------------
  // Tag (major type 6)
  // ---------------------------------------------------------------------------

  test("tagHeader: encode tag 24 produces 0xd818") {
    val bits = Cbor.tagHeader.encode(24L).require
    assertEquals(bits.bytes, hex"d818")
  }

  test("tagged: round-trip tag 24 with byte string") {
    val value   = hex"deadbeef"
    val bits    = Cbor.tagged(Cbor.byteString).encode((24L, value)).require
    val decoded = Cbor.tagged(Cbor.byteString).decode(bits).require.value
    assertEquals(decoded, (24L, value))
  }

  // ---------------------------------------------------------------------------
  // Boolean (major type 7)
  // ---------------------------------------------------------------------------

  test("cborBool: true encodes to 0xf5") {
    val bits = Cbor.cborBool.encode(true).require
    assertEquals(bits.bytes, hex"f5")
  }

  test("cborBool: false encodes to 0xf4") {
    val bits = Cbor.cborBool.encode(false).require
    assertEquals(bits.bytes, hex"f4")
  }

  test("cborBool: round-trip true") {
    val bits    = Cbor.cborBool.encode(true).require
    val decoded = Cbor.cborBool.decode(bits).require.value
    assertEquals(decoded, true)
  }

  test("cborBool: round-trip false") {
    val bits    = Cbor.cborBool.encode(false).require
    val decoded = Cbor.cborBool.decode(bits).require.value
    assertEquals(decoded, false)
  }

  // ---------------------------------------------------------------------------
  // Null and Undefined
  // ---------------------------------------------------------------------------

  test("cborNull: encodes to 0xf6") {
    val bits = Cbor.cborNull.encode(()).require
    assertEquals(bits.bytes, hex"f6")
  }

  test("cborUndefined: encodes to 0xf7") {
    val bits = Cbor.cborUndefined.encode(()).require
    assertEquals(bits.bytes, hex"f7")
  }

  // ---------------------------------------------------------------------------
  // headCodec: wrong major type fails decode
  // ---------------------------------------------------------------------------

  test("headCodec: decoding wrong major type fails") {
    // 0x40 = major type 2 (byte string), but headCodec(0) expects major type 0
    val result = Cbor.headCodec(0).decode(BitVector(hex"40"))
    assert(result.isFailure)
  }

  // ---------------------------------------------------------------------------
  // initialByte
  // ---------------------------------------------------------------------------

  test("initialByte: decodes 0x83 as (4, 3) -- array of 3") {
    val result = Cbor.initialByte.decode(BitVector(hex"83")).require
    assertEquals(result.value, (4, 3))
  }

  test("initialByte: round-trip (0, 5) for uint 5") {
    val bits    = Cbor.initialByte.encode((0, 5)).require
    val decoded = Cbor.initialByte.decode(bits).require.value
    assertEquals(decoded, (0, 5))
  }
