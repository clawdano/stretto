package stretto.serialization

import munit.FunSuite
import scodec.bits.ByteVector
import scodec.bits.hex
import stretto.core.Types.*

class CardanoCborSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // hash32Codec
  // ---------------------------------------------------------------------------

  test("hash32Codec: round-trip with 32-byte value") {
    val bv      = ByteVector.fill(32)(0xab)
    val h32     = Hash32.unsafeFrom(bv)
    val bits    = CardanoCbor.hash32Codec.encode(h32).require
    val decoded = CardanoCbor.hash32Codec.decode(bits).require.value
    assertEquals(decoded.hash32Bytes, bv)
  }

  test("hash32Codec: encoded starts with bstr(32) header 0x5820") {
    val h32  = Hash32.unsafeFrom(ByteVector.fill(32)(0x00))
    val bits = CardanoCbor.hash32Codec.encode(h32).require
    assertEquals(bits.bytes.take(2), hex"5820")
  }

  test("hash32Codec: decode rejects 31-byte value") {
    // Encode a 31-byte bstr manually
    val bits   = Cbor.byteString.encode(ByteVector.fill(31)(0x00)).require
    val result = CardanoCbor.hash32Codec.decode(bits)
    assert(result.isFailure)
  }

  test("hash32Codec: decode rejects 33-byte value") {
    val bits   = Cbor.byteString.encode(ByteVector.fill(33)(0x00)).require
    val result = CardanoCbor.hash32Codec.decode(bits)
    assert(result.isFailure)
  }

  // ---------------------------------------------------------------------------
  // hash28Codec
  // ---------------------------------------------------------------------------

  test("hash28Codec: round-trip with 28-byte value") {
    val bv      = ByteVector.fill(28)(0xcd)
    val h28     = Hash28.unsafeFrom(bv)
    val bits    = CardanoCbor.hash28Codec.encode(h28).require
    val decoded = CardanoCbor.hash28Codec.decode(bits).require.value
    assertEquals(decoded.hash28Bytes, bv)
  }

  test("hash28Codec: encoded starts with bstr(28) header 0x581c") {
    val h28  = Hash28.unsafeFrom(ByteVector.fill(28)(0x00))
    val bits = CardanoCbor.hash28Codec.encode(h28).require
    assertEquals(bits.bytes.take(2), hex"581c")
  }

  test("hash28Codec: decode rejects 27-byte value") {
    val bits   = Cbor.byteString.encode(ByteVector.fill(27)(0x00)).require
    val result = CardanoCbor.hash28Codec.decode(bits)
    assert(result.isFailure)
  }

  // ---------------------------------------------------------------------------
  // slotNoCodec
  // ---------------------------------------------------------------------------

  test("slotNoCodec: round-trip slot 0") {
    val slot    = SlotNo(0L)
    val bits    = CardanoCbor.slotNoCodec.encode(slot).require
    val decoded = CardanoCbor.slotNoCodec.decode(bits).require.value
    assertEquals(decoded.value, 0L)
  }

  test("slotNoCodec: round-trip large slot number") {
    val slot    = SlotNo(116000000L)
    val bits    = CardanoCbor.slotNoCodec.encode(slot).require
    val decoded = CardanoCbor.slotNoCodec.decode(bits).require.value
    assertEquals(decoded.value, 116000000L)
  }

  test("slotNoCodec: slot 23 encodes to single byte") {
    val bits = CardanoCbor.slotNoCodec.encode(SlotNo(23L)).require
    assertEquals(bits.bytes, hex"17")
  }

  // ---------------------------------------------------------------------------
  // blockNoCodec
  // ---------------------------------------------------------------------------

  test("blockNoCodec: round-trip block 0") {
    val blk     = BlockNo(0L)
    val bits    = CardanoCbor.blockNoCodec.encode(blk).require
    val decoded = CardanoCbor.blockNoCodec.decode(bits).require.value
    assertEquals(decoded.blockNoValue, 0L)
  }

  test("blockNoCodec: round-trip large block number") {
    val blk     = BlockNo(3000000L)
    val bits    = CardanoCbor.blockNoCodec.encode(blk).require
    val decoded = CardanoCbor.blockNoCodec.decode(bits).require.value
    assertEquals(decoded.blockNoValue, 3000000L)
  }

  // ---------------------------------------------------------------------------
  // coinCodec (Lovelace)
  // ---------------------------------------------------------------------------

  test("coinCodec: round-trip zero") {
    val coin    = Lovelace(0L)
    val bits    = CardanoCbor.coinCodec.encode(coin).require
    val decoded = CardanoCbor.coinCodec.decode(bits).require.value
    assertEquals(decoded.lovelaceValue, 0L)
  }

  test("coinCodec: round-trip 2 ADA (2_000_000 lovelace)") {
    val coin    = Lovelace(2_000_000L)
    val bits    = CardanoCbor.coinCodec.encode(coin).require
    val decoded = CardanoCbor.coinCodec.decode(bits).require.value
    assertEquals(decoded.lovelaceValue, 2_000_000L)
  }

  // ---------------------------------------------------------------------------
  // epochNoCodec
  // ---------------------------------------------------------------------------

  test("epochNoCodec: round-trip epoch 0") {
    val epoch   = EpochNo(0L)
    val bits    = CardanoCbor.epochNoCodec.encode(epoch).require
    val decoded = CardanoCbor.epochNoCodec.decode(bits).require.value
    assertEquals(decoded.epochNoValue, 0L)
  }

  test("epochNoCodec: round-trip epoch 500") {
    val epoch   = EpochNo(500L)
    val bits    = CardanoCbor.epochNoCodec.encode(epoch).require
    val decoded = CardanoCbor.epochNoCodec.decode(bits).require.value
    assertEquals(decoded.epochNoValue, 500L)
  }

  // ---------------------------------------------------------------------------
  // multiEraBlockCodec
  // ---------------------------------------------------------------------------

  test("multiEraBlockCodec: round-trip Byron era") {
    val blockBytes = hex"deadbeefcafebabe"
    val bits       = CardanoCbor.multiEraBlockCodec.encode((CardanoCbor.Era.Byron, blockBytes)).require
    val decoded    = CardanoCbor.multiEraBlockCodec.decode(bits).require.value
    assertEquals(decoded._1, CardanoCbor.Era.Byron)
    assertEquals(decoded._2, blockBytes)
  }

  test("multiEraBlockCodec: round-trip Conway era") {
    val blockBytes = ByteVector.fill(100)(0xff)
    val bits       = CardanoCbor.multiEraBlockCodec.encode((CardanoCbor.Era.Conway, blockBytes)).require
    val decoded    = CardanoCbor.multiEraBlockCodec.decode(bits).require.value
    assertEquals(decoded._1, CardanoCbor.Era.Conway)
    assertEquals(decoded._2, blockBytes)
  }

  test("multiEraBlockCodec: encoded starts with array(2)") {
    val bits = CardanoCbor.multiEraBlockCodec.encode((CardanoCbor.Era.Shelley, hex"00")).require
    // array(2) = 0x82
    assertEquals(bits.bytes(0) & 0xff, 0x82)
  }

  test("multiEraBlockCodec: all era tags are distinct") {
    val tags = CardanoCbor.Era.values.map(_.tag)
    assertEquals(tags.distinct.length, tags.length)
  }

  test("Era.fromTag: valid tags round-trip") {
    for era <- CardanoCbor.Era.values do
      val result = CardanoCbor.Era.fromTag(era.tag)
      assert(result.isSuccessful, s"fromTag failed for ${era.tag}")
      assertEquals(result.require, era)
  }

  test("Era.fromTag: invalid tag fails") {
    val result = CardanoCbor.Era.fromTag(99)
    assert(result.isFailure)
  }
