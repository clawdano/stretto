package stretto.network

import munit.FunSuite
import scodec.bits.ByteVector
import scodec.bits.hex
import stretto.core.{Point, Tip}
import stretto.core.Types.*

class ChainSyncCodecSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // Test fixtures
  // ---------------------------------------------------------------------------

  private val sampleHash32 = Hash32.unsafeFrom(ByteVector.fill(32)(0xab))
  private val sampleBhh    = BlockHeaderHash(sampleHash32)
  private val sampleSlot   = SlotNo(42L)
  private val samplePoint  = Point.BlockPoint(sampleSlot, sampleBhh)
  private val sampleTip    = Tip(samplePoint, BlockNo(100L))
  private val originTip    = Tip(Point.Origin, BlockNo(0L))

  // A small header payload for RollForward tests
  private val sampleHeader = ByteVector.fromValidHex("deadbeefcafebabe")

  // ---------------------------------------------------------------------------
  // MsgRequestNext
  // ---------------------------------------------------------------------------

  test("MsgRequestNext: encode produces hex 8100") {
    val encoded = ChainSyncMessage.encode(ChainSyncMessage.MsgRequestNext)
    assertEquals(encoded, hex"8100")
  }

  test("MsgRequestNext: encode then decode round-trips") {
    val msg     = ChainSyncMessage.MsgRequestNext
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // MsgAwaitReply
  // ---------------------------------------------------------------------------

  test("MsgAwaitReply: encode then decode round-trips") {
    val msg     = ChainSyncMessage.MsgAwaitReply
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  test("MsgAwaitReply: encode produces [1]") {
    val encoded = ChainSyncMessage.encode(ChainSyncMessage.MsgAwaitReply)
    // CBOR array(1) + uint(1) = 0x81 0x01
    assertEquals(encoded, hex"8101")
  }

  // ---------------------------------------------------------------------------
  // MsgRollForward
  // ---------------------------------------------------------------------------

  test("MsgRollForward: encode then decode preserves tip") {
    val msg     = ChainSyncMessage.MsgRollForward(sampleHeader, sampleTip)
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assert(decoded.isRight, s"decode failed: $decoded")
    val ChainSyncMessage.MsgRollForward(_, decodedTip) = decoded.toOption.get: @unchecked
    assertEquals(decodedTip, sampleTip)
  }

  test("MsgRollForward: decoded header contains tag24-wrapped original") {
    // encode wraps header in tag24: d8 18 bstr(header)
    // decode via skipCborItem captures the entire tag24 CBOR item as raw bytes
    val msg     = ChainSyncMessage.MsgRollForward(sampleHeader, sampleTip)
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assert(decoded.isRight)
    val ChainSyncMessage.MsgRollForward(decodedHeader, _) = decoded.toOption.get: @unchecked
    // The decoded header should be the tag24-wrapped byte string
    // tag24 = 0xd8 0x18, then bstr(8 bytes) = 0x48 + 8 bytes
    val expectedWrapped =
      ByteVector(0xd8.toByte, 0x18.toByte, 0x48.toByte) ++ sampleHeader
    assertEquals(decodedHeader, expectedWrapped)
  }

  // ---------------------------------------------------------------------------
  // MsgRollBackward
  // ---------------------------------------------------------------------------

  test("MsgRollBackward: encode then decode round-trips with BlockPoint") {
    val msg     = ChainSyncMessage.MsgRollBackward(samplePoint, sampleTip)
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  test("MsgRollBackward: encode then decode round-trips with Origin") {
    val msg     = ChainSyncMessage.MsgRollBackward(Point.Origin, originTip)
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // MsgFindIntersect
  // ---------------------------------------------------------------------------

  test("MsgFindIntersect: empty list round-trips") {
    val msg     = ChainSyncMessage.MsgFindIntersect(Nil)
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  test("MsgFindIntersect: single point round-trips") {
    val msg     = ChainSyncMessage.MsgFindIntersect(List(samplePoint))
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  test("MsgFindIntersect: multiple points round-trips") {
    val hash2   = Hash32.unsafeFrom(ByteVector.fill(32)(0xcd))
    val bhh2    = BlockHeaderHash(hash2)
    val point2  = Point.BlockPoint(SlotNo(999L), bhh2)
    val msg     = ChainSyncMessage.MsgFindIntersect(List(samplePoint, Point.Origin, point2))
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // MsgIntersectFound
  // ---------------------------------------------------------------------------

  test("MsgIntersectFound: encode then decode round-trips") {
    val msg     = ChainSyncMessage.MsgIntersectFound(samplePoint, sampleTip)
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // MsgIntersectNotFound
  // ---------------------------------------------------------------------------

  test("MsgIntersectNotFound: encode then decode round-trips") {
    val msg     = ChainSyncMessage.MsgIntersectNotFound(sampleTip)
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // MsgDone
  // ---------------------------------------------------------------------------

  test("MsgDone: encode produces hex 8107") {
    val encoded = ChainSyncMessage.encode(ChainSyncMessage.MsgDone)
    assertEquals(encoded, hex"8107")
  }

  test("MsgDone: encode then decode round-trips") {
    val msg     = ChainSyncMessage.MsgDone
    val encoded = ChainSyncMessage.encode(msg)
    val decoded = ChainSyncMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // Point encoding specifics
  // ---------------------------------------------------------------------------

  test("Point.Origin encodes to empty CBOR array 0x80") {
    // MsgFindIntersect([Origin]) = [4, [[]]]
    // The inner Origin is encoded as 0x80 (empty array)
    val msg     = ChainSyncMessage.MsgFindIntersect(List(Point.Origin))
    val encoded = ChainSyncMessage.encode(msg)
    // [4, [Origin]] = array(2) ++ uint(4) ++ array(1) ++ array(0)
    // = 0x82 0x04 0x81 0x80
    assertEquals(encoded, hex"82048180")
  }

  test("Point.BlockPoint encodes with slot and 32-byte hash") {
    // MsgFindIntersect with a single BlockPoint
    val hash    = Hash32.unsafeFrom(ByteVector.fill(32)(0x00))
    val bhh     = BlockHeaderHash(hash)
    val point   = Point.BlockPoint(SlotNo(0L), bhh)
    val msg     = ChainSyncMessage.MsgFindIntersect(List(point))
    val encoded = ChainSyncMessage.encode(msg)
    // array(2)=82, uint(4)=04, array(1)=81, array(2)=82, uint(0)=00, bstr(32)=5820 + 32*00
    val expected = ByteVector(0x82, 0x04, 0x81, 0x82, 0x00, 0x58, 0x20) ++ ByteVector.fill(32)(0x00)
    assertEquals(encoded, expected)
  }

  // ---------------------------------------------------------------------------
  // Error cases
  // ---------------------------------------------------------------------------

  test("decode: empty payload returns Left") {
    val decoded = ChainSyncMessage.decode(ByteVector.empty)
    assert(decoded.isLeft)
  }

  test("decode: unknown tag returns Left") {
    // array(1) + uint(99) = 0x81 0x18 0x63
    val decoded = ChainSyncMessage.decode(hex"811863")
    assert(decoded.isLeft)
    assert(decoded.left.toOption.get.contains("unknown ChainSync tag"))
  }
