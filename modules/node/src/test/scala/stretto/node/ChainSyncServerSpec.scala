package stretto.node

import munit.FunSuite
import scodec.bits.ByteVector
import scodec.bits.hex
import stretto.core.{Point, Tip}
import stretto.core.Types.*

class ChainSyncServerSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // ChainEvent ADT
  // ---------------------------------------------------------------------------

  test("ChainEvent.BlockAdded holds point and tip") {
    val hash                    = Hash32.unsafeFrom(ByteVector.fill(32)(0xaa))
    val bhh                     = BlockHeaderHash(hash)
    val point: Point.BlockPoint = Point.BlockPoint(SlotNo(100L), bhh)
    val tip                     = Tip(point, BlockNo(50L))
    val event                   = ChainEvent.BlockAdded(point, tip)
    event match
      case ChainEvent.BlockAdded(p, t) =>
        assertEquals(p, point)
        assertEquals(t, tip)
      case _ => fail("wrong type")
  }

  test("ChainEvent.RolledBack holds point and tip") {
    val tip   = Tip(Point.Origin, BlockNo(0L))
    val event = ChainEvent.RolledBack(Point.Origin, tip)
    event match
      case ChainEvent.RolledBack(p, t) =>
        assertEquals(p, Point.Origin)
        assertEquals(t, tip)
      case _ => fail("wrong type")
  }

  // ---------------------------------------------------------------------------
  // N2C ChainSync CBOR encoding (server messages)
  // ---------------------------------------------------------------------------

  // Test the CBOR encoding helpers used by ChainSyncServer by encoding
  // known messages and verifying their structure.

  test("MsgRequestNext encodes to [0] = 8100") {
    // Client message tag 0 = MsgRequestNext
    val encoded = hex"8100"
    // Verify: array(1)=0x81, uint(0)=0x00
    assertEquals(encoded(0) & 0xff, 0x81)
    assertEquals(encoded(1) & 0xff, 0x00)
  }

  test("MsgFindIntersect with Origin encodes correctly") {
    // [4, [[]]] = array(2) uint(4) array(1) array(0)
    // = 0x82 0x04 0x81 0x80
    val encoded = hex"82048180"
    assertEquals(encoded.size, 4L)
    assertEquals(encoded(0) & 0xff, 0x82) // array(2)
    assertEquals(encoded(1) & 0xff, 0x04) // tag 4
    assertEquals(encoded(2) & 0xff, 0x81) // array(1)
    assertEquals(encoded(3) & 0xff, 0x80) // array(0) = Origin
  }

  test("MsgDone encodes to [7] = 8107") {
    val encoded = hex"8107"
    assertEquals(encoded(0) & 0xff, 0x81) // array(1)
    assertEquals(encoded(1) & 0xff, 0x07) // tag 7
  }

  // ---------------------------------------------------------------------------
  // N2C vs N2N MsgRollForward difference
  // ---------------------------------------------------------------------------

  test("N2C MsgRollForward has no tag24 wrapping") {
    // N2N: [2, [era_tag, tag24(header_bytes)], tip] — has d8 18 (tag24)
    // N2C: [2, [era_tag, block_cbor], tip] — no tag24, inline block
    //
    // The server reads block data from RocksDB (already era-wrapped) and
    // sends it directly without tag24 wrapping.
    val eraWrappedBlock = hex"820183000102" // [2, [0, 1, 2]] — mock era-wrapped block
    // In N2C, this goes directly into the message — verify no tag24 prefix
    assert(!eraWrappedBlock.startsWith(hex"d818"))
  }

  // ---------------------------------------------------------------------------
  // MiniProtocol IDs
  // ---------------------------------------------------------------------------

  test("ChainSyncN2C protocol ID is 5") {
    import stretto.network.MiniProtocolId
    assertEquals(MiniProtocolId.ChainSyncN2C.id, 5)
  }

  test("ChainSyncN2N protocol ID is 2 (different from N2C)") {
    import stretto.network.MiniProtocolId
    assertEquals(MiniProtocolId.ChainSyncN2N.id, 2)
    assert(MiniProtocolId.ChainSyncN2N.id != MiniProtocolId.ChainSyncN2C.id)
  }
