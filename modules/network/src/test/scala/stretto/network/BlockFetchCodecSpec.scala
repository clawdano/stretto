package stretto.network

import munit.FunSuite
import scodec.bits.ByteVector
import scodec.bits.hex
import stretto.core.Point
import stretto.core.Types.*

class BlockFetchCodecSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // Test fixtures
  // ---------------------------------------------------------------------------

  private val hash1                    = Hash32.unsafeFrom(ByteVector.fill(32)(0x01))
  private val hash2                    = Hash32.unsafeFrom(ByteVector.fill(32)(0x02))
  private val bhh1                     = BlockHeaderHash(hash1)
  private val bhh2                     = BlockHeaderHash(hash2)
  private val point1: Point.BlockPoint = Point.BlockPoint(SlotNo(100L), bhh1)
  private val point2: Point.BlockPoint = Point.BlockPoint(SlotNo(200L), bhh2)

  private val sampleBlockData = ByteVector.fromValidHex("deadbeefcafebabe0102030405060708")

  // ---------------------------------------------------------------------------
  // MsgRequestRange
  // ---------------------------------------------------------------------------

  test("MsgRequestRange: encode then decode round-trips") {
    val msg     = BlockFetchMessage.MsgRequestRange(point1, point2)
    val encoded = BlockFetchMessage.encode(msg)
    val decoded = BlockFetchMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  test("MsgRequestRange: encoded starts with array(3) and tag 0") {
    val msg     = BlockFetchMessage.MsgRequestRange(point1, point2)
    val encoded = BlockFetchMessage.encode(msg)
    // array(3) = 0x83, uint(0) = 0x00
    assertEquals(encoded.take(2), hex"8300")
  }

  // ---------------------------------------------------------------------------
  // MsgClientDone
  // ---------------------------------------------------------------------------

  test("MsgClientDone: encode produces hex 8101") {
    val encoded = BlockFetchMessage.encode(BlockFetchMessage.MsgClientDone)
    assertEquals(encoded, hex"8101")
  }

  test("MsgClientDone: encode then decode round-trips") {
    val msg     = BlockFetchMessage.MsgClientDone
    val encoded = BlockFetchMessage.encode(msg)
    val decoded = BlockFetchMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // MsgStartBatch
  // ---------------------------------------------------------------------------

  test("MsgStartBatch: encode produces hex 8102") {
    val encoded = BlockFetchMessage.encode(BlockFetchMessage.MsgStartBatch)
    assertEquals(encoded, hex"8102")
  }

  test("MsgStartBatch: encode then decode round-trips") {
    val msg     = BlockFetchMessage.MsgStartBatch
    val encoded = BlockFetchMessage.encode(msg)
    val decoded = BlockFetchMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // MsgNoBlocks
  // ---------------------------------------------------------------------------

  test("MsgNoBlocks: encode produces hex 8103") {
    val encoded = BlockFetchMessage.encode(BlockFetchMessage.MsgNoBlocks)
    assertEquals(encoded, hex"8103")
  }

  test("MsgNoBlocks: encode then decode round-trips") {
    val msg     = BlockFetchMessage.MsgNoBlocks
    val encoded = BlockFetchMessage.encode(msg)
    val decoded = BlockFetchMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // MsgBlock
  // ---------------------------------------------------------------------------

  test("MsgBlock: encode then decode round-trips (block data preserved)") {
    val msg     = BlockFetchMessage.MsgBlock(sampleBlockData)
    val encoded = BlockFetchMessage.encode(msg)
    val decoded = BlockFetchMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  test("MsgBlock: encoded contains tag24 wrapper") {
    val msg     = BlockFetchMessage.MsgBlock(sampleBlockData)
    val encoded = BlockFetchMessage.encode(msg)
    // array(2) = 0x82, uint(4) = 0x04, tag(24) = 0xd8 0x18
    assertEquals(encoded.take(4), hex"8204d818")
  }

  test("MsgBlock: round-trips with empty block data") {
    val msg     = BlockFetchMessage.MsgBlock(ByteVector.empty)
    val encoded = BlockFetchMessage.encode(msg)
    val decoded = BlockFetchMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // MsgBatchDone
  // ---------------------------------------------------------------------------

  test("MsgBatchDone: encode produces hex 8105") {
    val encoded = BlockFetchMessage.encode(BlockFetchMessage.MsgBatchDone)
    assertEquals(encoded, hex"8105")
  }

  test("MsgBatchDone: encode then decode round-trips") {
    val msg     = BlockFetchMessage.MsgBatchDone
    val encoded = BlockFetchMessage.encode(msg)
    val decoded = BlockFetchMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // All simple messages known bytes verification
  // ---------------------------------------------------------------------------

  test("all simple messages have correct known byte representations") {
    assertEquals(BlockFetchMessage.encode(BlockFetchMessage.MsgClientDone), hex"8101")
    assertEquals(BlockFetchMessage.encode(BlockFetchMessage.MsgStartBatch), hex"8102")
    assertEquals(BlockFetchMessage.encode(BlockFetchMessage.MsgNoBlocks), hex"8103")
    assertEquals(BlockFetchMessage.encode(BlockFetchMessage.MsgBatchDone), hex"8105")
  }

  // ---------------------------------------------------------------------------
  // Error cases
  // ---------------------------------------------------------------------------

  test("decode: empty payload returns Left") {
    val decoded = BlockFetchMessage.decode(ByteVector.empty)
    assert(decoded.isLeft)
  }

  test("decode: unknown tag returns Left") {
    // array(1) + uint(99) = 0x81 0x18 0x63
    val decoded = BlockFetchMessage.decode(hex"811863")
    assert(decoded.isLeft)
    assert(decoded.left.toOption.get.contains("unknown BlockFetch tag"))
  }
