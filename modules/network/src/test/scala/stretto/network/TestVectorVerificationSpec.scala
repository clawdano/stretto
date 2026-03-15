package stretto.network

import munit.FunSuite
import scodec.bits.ByteVector
import scodec.bits.hex
import stretto.core.Point
import stretto.core.Types.*

/**
 * Verifies that the test vectors in MiniProtocolTestVectors decode correctly
 * through our actual codec implementations, and that our encoders produce
 * the expected bytes.
 */
class TestVectorVerificationSpec extends FunSuite:

  // ===========================================================================
  // BlockFetch vectors
  // ===========================================================================

  test("BlockFetch: MsgClientDone vector matches encoder output") {
    assertEquals(
      BlockFetchMessage.encode(BlockFetchMessage.MsgClientDone),
      MiniProtocolTestVectors.BlockFetch.msgClientDone
    )
  }

  test("BlockFetch: MsgStartBatch vector matches encoder output") {
    assertEquals(
      BlockFetchMessage.encode(BlockFetchMessage.MsgStartBatch),
      MiniProtocolTestVectors.BlockFetch.msgStartBatch
    )
  }

  test("BlockFetch: MsgNoBlocks vector matches encoder output") {
    assertEquals(
      BlockFetchMessage.encode(BlockFetchMessage.MsgNoBlocks),
      MiniProtocolTestVectors.BlockFetch.msgNoBlocks
    )
  }

  test("BlockFetch: MsgBatchDone vector matches encoder output") {
    assertEquals(
      BlockFetchMessage.encode(BlockFetchMessage.MsgBatchDone),
      MiniProtocolTestVectors.BlockFetch.msgBatchDone
    )
  }

  test("BlockFetch: MsgRequestRange vector decodes correctly") {
    val decoded = BlockFetchMessage.decode(MiniProtocolTestVectors.BlockFetch.msgRequestRange)
    assert(decoded.isRight, s"Failed to decode: $decoded")
    val BlockFetchMessage.MsgRequestRange(from, to) = decoded.toOption.get: @unchecked
    assertEquals(from.slotNo.value, 100L)
    assertEquals(to.slotNo.value, 200L)
    assertEquals(from.blockHash.toHash32.hash32Bytes, MiniProtocolTestVectors.hash32_01)
    assertEquals(to.blockHash.toHash32.hash32Bytes, MiniProtocolTestVectors.hash32_02)
  }

  test("BlockFetch: MsgRequestRange vector round-trips through encoder") {
    val hash1 = Hash32.unsafeFrom(MiniProtocolTestVectors.hash32_01)
    val hash2 = Hash32.unsafeFrom(MiniProtocolTestVectors.hash32_02)
    val msg = BlockFetchMessage.MsgRequestRange(
      Point.BlockPoint(SlotNo(100L), BlockHeaderHash(hash1)),
      Point.BlockPoint(SlotNo(200L), BlockHeaderHash(hash2))
    )
    assertEquals(
      BlockFetchMessage.encode(msg),
      MiniProtocolTestVectors.BlockFetch.msgRequestRange
    )
  }

  test("BlockFetch: MsgRequestRange with large slots decodes correctly") {
    val decoded = BlockFetchMessage.decode(MiniProtocolTestVectors.BlockFetch.msgRequestRange_largeSlots)
    assert(decoded.isRight, s"Failed to decode: $decoded")
    val BlockFetchMessage.MsgRequestRange(from, to) = decoded.toOption.get: @unchecked
    assertEquals(from.slotNo.value, 1000000L)
    assertEquals(to.slotNo.value, 2000000L)
  }

  test("BlockFetch: MsgBlock vector decodes correctly") {
    val decoded = BlockFetchMessage.decode(MiniProtocolTestVectors.BlockFetch.msgBlock)
    assert(decoded.isRight, s"Failed to decode: $decoded")
    val BlockFetchMessage.MsgBlock(data) = decoded.toOption.get: @unchecked
    assertEquals(data, MiniProtocolTestVectors.BlockFetch.sampleBlockData)
  }

  test("BlockFetch: MsgBlock empty vector decodes correctly") {
    val decoded = BlockFetchMessage.decode(MiniProtocolTestVectors.BlockFetch.msgBlock_empty)
    assert(decoded.isRight, s"Failed to decode: $decoded")
    val BlockFetchMessage.MsgBlock(data) = decoded.toOption.get: @unchecked
    assertEquals(data, ByteVector.empty)
  }

  test("BlockFetch: all simple message vectors decode correctly") {
    assertEquals(
      BlockFetchMessage.decode(MiniProtocolTestVectors.BlockFetch.msgClientDone),
      Right(BlockFetchMessage.MsgClientDone)
    )
    assertEquals(
      BlockFetchMessage.decode(MiniProtocolTestVectors.BlockFetch.msgStartBatch),
      Right(BlockFetchMessage.MsgStartBatch)
    )
    assertEquals(
      BlockFetchMessage.decode(MiniProtocolTestVectors.BlockFetch.msgNoBlocks),
      Right(BlockFetchMessage.MsgNoBlocks)
    )
    assertEquals(
      BlockFetchMessage.decode(MiniProtocolTestVectors.BlockFetch.msgBatchDone),
      Right(BlockFetchMessage.MsgBatchDone)
    )
  }

  // ===========================================================================
  // ChainSync vectors
  // ===========================================================================

  test("ChainSync: MsgRequestNext vector matches encoder output") {
    assertEquals(
      ChainSyncMessage.encode(ChainSyncMessage.MsgRequestNext),
      MiniProtocolTestVectors.ChainSync.msgRequestNext
    )
  }

  test("ChainSync: MsgAwaitReply vector matches encoder output") {
    assertEquals(
      ChainSyncMessage.encode(ChainSyncMessage.MsgAwaitReply),
      MiniProtocolTestVectors.ChainSync.msgAwaitReply
    )
  }

  test("ChainSync: MsgDone vector matches encoder output") {
    assertEquals(
      ChainSyncMessage.encode(ChainSyncMessage.MsgDone),
      MiniProtocolTestVectors.ChainSync.msgDone
    )
  }

  test("ChainSync: MsgFindIntersect empty vector matches encoder") {
    assertEquals(
      ChainSyncMessage.encode(ChainSyncMessage.MsgFindIntersect(Nil)),
      MiniProtocolTestVectors.ChainSync.msgFindIntersect_empty
    )
  }

  test("ChainSync: MsgFindIntersect Origin vector matches encoder") {
    assertEquals(
      ChainSyncMessage.encode(ChainSyncMessage.MsgFindIntersect(List(Point.Origin))),
      MiniProtocolTestVectors.ChainSync.msgFindIntersect_origin
    )
  }

  test("ChainSync: MsgFindIntersect one-point vector matches encoder") {
    val hash  = Hash32.unsafeFrom(MiniProtocolTestVectors.hash32_00)
    val point = Point.BlockPoint(SlotNo(0L), BlockHeaderHash(hash))
    assertEquals(
      ChainSyncMessage.encode(ChainSyncMessage.MsgFindIntersect(List(point))),
      MiniProtocolTestVectors.ChainSync.msgFindIntersect_onePoint
    )
  }

  test("ChainSync: MsgFindIntersect slot42 vector decodes correctly") {
    val decoded = ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgFindIntersect_slot42)
    assert(decoded.isRight, s"Failed: $decoded")
    val ChainSyncMessage.MsgFindIntersect(points) = decoded.toOption.get: @unchecked
    assertEquals(points.size, 1)
    val Point.BlockPoint(slot, bhh) = points.head: @unchecked
    assertEquals(slot.value, 42L)
  }

  test("ChainSync: MsgFindIntersect multi-point vector decodes correctly") {
    val decoded = ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgFindIntersect_multi)
    assert(decoded.isRight, s"Failed: $decoded")
    val ChainSyncMessage.MsgFindIntersect(points) = decoded.toOption.get: @unchecked
    assertEquals(points.size, 3)
    assertEquals(points(1), Point.Origin)
  }

  test("ChainSync: MsgRollForward vector decodes (tip preserved)") {
    val decoded = ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgRollForward)
    assert(decoded.isRight, s"Failed: $decoded")
    val ChainSyncMessage.MsgRollForward(_, tip) = decoded.toOption.get: @unchecked
    assertEquals(tip.blockNo.blockNoValue, 100L)
  }

  test("ChainSync: MsgRollBackward vector decodes correctly") {
    val decoded = ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgRollBackward)
    assert(decoded.isRight, s"Failed: $decoded")
    val ChainSyncMessage.MsgRollBackward(point, tip) = decoded.toOption.get: @unchecked
    val Point.BlockPoint(slot, _)                    = point: @unchecked
    assertEquals(slot.value, 42L)
    assertEquals(tip.blockNo.blockNoValue, 100L)
  }

  test("ChainSync: MsgRollBackward origin vector decodes correctly") {
    val decoded = ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgRollBackward_origin)
    assert(decoded.isRight, s"Failed: $decoded")
    val ChainSyncMessage.MsgRollBackward(point, tip) = decoded.toOption.get: @unchecked
    assertEquals(point, Point.Origin)
    assertEquals(tip.blockNo.blockNoValue, 0L)
  }

  test("ChainSync: MsgIntersectFound vector decodes correctly") {
    val decoded = ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgIntersectFound)
    assert(decoded.isRight, s"Failed: $decoded")
    val ChainSyncMessage.MsgIntersectFound(point, tip) = decoded.toOption.get: @unchecked
    val Point.BlockPoint(slot, _)                      = point: @unchecked
    assertEquals(slot.value, 42L)
  }

  test("ChainSync: MsgIntersectNotFound vector decodes correctly") {
    val decoded = ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgIntersectNotFound)
    assert(decoded.isRight, s"Failed: $decoded")
    val ChainSyncMessage.MsgIntersectNotFound(tip) = decoded.toOption.get: @unchecked
    assertEquals(tip.blockNo.blockNoValue, 100L)
  }

  test("ChainSync: MsgIntersectNotFound origin vector decodes correctly") {
    val decoded = ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgIntersectNotFound_origin)
    assert(decoded.isRight, s"Failed: $decoded")
    val ChainSyncMessage.MsgIntersectNotFound(tip) = decoded.toOption.get: @unchecked
    assertEquals(tip.point, Point.Origin)
    assertEquals(tip.blockNo.blockNoValue, 0L)
  }

  test("ChainSync: all simple message vectors decode correctly") {
    assertEquals(
      ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgRequestNext),
      Right(ChainSyncMessage.MsgRequestNext)
    )
    assertEquals(
      ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgAwaitReply),
      Right(ChainSyncMessage.MsgAwaitReply)
    )
    assertEquals(ChainSyncMessage.decode(MiniProtocolTestVectors.ChainSync.msgDone), Right(ChainSyncMessage.MsgDone))
  }

  // ===========================================================================
  // Handshake vectors
  // ===========================================================================

  test("Handshake: MsgAcceptVersion v13 preprod vector decodes correctly") {
    val decoded = HandshakeMessage.decode(MiniProtocolTestVectors.Handshake.msgAcceptVersion_v13_preprod)
    assert(decoded.isRight, s"Failed: $decoded")
    val HandshakeMessage.MsgAcceptVersion(version, params) = decoded.toOption.get: @unchecked
    assertEquals(version, 13)
  }

  test("Handshake: MsgAcceptVersion v13 mainnet vector decodes correctly") {
    val decoded = HandshakeMessage.decode(MiniProtocolTestVectors.Handshake.msgAcceptVersion_v13_mainnet)
    assert(decoded.isRight, s"Failed: $decoded")
    val HandshakeMessage.MsgAcceptVersion(version, _) = decoded.toOption.get: @unchecked
    assertEquals(version, 13)
  }

  test("Handshake: MsgAcceptVersion v14 preprod vector decodes correctly") {
    val decoded = HandshakeMessage.decode(MiniProtocolTestVectors.Handshake.msgAcceptVersion_v14_preprod)
    assert(decoded.isRight, s"Failed: $decoded")
    val HandshakeMessage.MsgAcceptVersion(version, _) = decoded.toOption.get: @unchecked
    assertEquals(version, 14)
  }

  test("Handshake: MsgRefuse VersionMismatch vector decodes as MsgRefuse") {
    val decoded = HandshakeMessage.decode(MiniProtocolTestVectors.Handshake.msgRefuse_versionMismatch)
    assert(decoded.isRight, s"Failed: $decoded")
    assert(decoded.toOption.get.isInstanceOf[HandshakeMessage.MsgRefuse])
  }

  test("Handshake: MsgProposeVersions preprod vector starts with 8200") {
    val vec = MiniProtocolTestVectors.Handshake.msgProposeVersions_preprod
    assertEquals(vec.take(2), hex"8200")
  }

  test("Handshake: MsgProposeVersions mainnet vector contains magic 0x2d964a09") {
    val vec = MiniProtocolTestVectors.Handshake.msgProposeVersions_mainnet
    // The mainnet magic 764824073 = 0x2d964a09 should appear in the payload
    val magicBytes = hex"2d964a09"
    assert(
      vec.containsSlice(magicBytes),
      s"Mainnet magic not found in vector"
    )
  }

  // ===========================================================================
  // MuxFrame vectors
  // ===========================================================================

  test("MuxFrame: zeros vector decodes to time=0, proto=0, initiator, len=0") {
    val header                          = MiniProtocolTestVectors.MuxFrame.frame_zeros.take(8)
    val (time, protoId, isResp, payLen) = stretto.network.MuxFrame.decodeHeader(header)
    assertEquals(time, 0L)
    assertEquals(protoId, 0)
    assertEquals(isResp, false)
    assertEquals(payLen, 0)
  }

  test("MuxFrame: chainsync response vector decodes correctly") {
    val vec                             = MiniProtocolTestVectors.MuxFrame.frame_chainsync_response
    val header                          = vec.take(8)
    val (time, protoId, isResp, payLen) = stretto.network.MuxFrame.decodeHeader(header)
    assertEquals(time, 1L)
    assertEquals(protoId, 2)
    assertEquals(isResp, true)
    assertEquals(payLen, 4)
    assertEquals(vec.drop(8), hex"deadbeef")
  }

  test("MuxFrame: blockfetch startBatch response vector decodes correctly") {
    val vec                             = MiniProtocolTestVectors.MuxFrame.frame_blockfetch_startBatch
    val header                          = vec.take(8)
    val (time, protoId, isResp, payLen) = stretto.network.MuxFrame.decodeHeader(header)
    assertEquals(protoId, 3)
    assertEquals(isResp, true)
    assertEquals(payLen, 2)
    assertEquals(vec.drop(8), hex"8102")
  }

  test("MuxFrame: keepalive with large timestamp decodes correctly") {
    val vec                             = MiniProtocolTestVectors.MuxFrame.frame_keepalive_largeTime
    val header                          = vec.take(8)
    val (time, protoId, isResp, payLen) = stretto.network.MuxFrame.decodeHeader(header)
    assertEquals(time, 0xdeadbeefL)
    assertEquals(protoId, 5)
    assertEquals(isResp, false)
    assertEquals(payLen, 0)
  }

  test("MuxFrame: max proto ID initiator decodes correctly") {
    val header                  = MiniProtocolTestVectors.MuxFrame.frame_maxProtoId.take(8)
    val (_, protoId, isResp, _) = stretto.network.MuxFrame.decodeHeader(header)
    assertEquals(protoId, 0x7fff)
    assertEquals(isResp, false)
  }

  test("MuxFrame: max proto ID responder decodes correctly") {
    val header                  = MiniProtocolTestVectors.MuxFrame.frame_maxProtoId_responder.take(8)
    val (_, protoId, isResp, _) = stretto.network.MuxFrame.decodeHeader(header)
    assertEquals(protoId, 0x7fff)
    assertEquals(isResp, true)
  }

  // ===========================================================================
  // Error case vectors
  // ===========================================================================

  test("Error: empty payload fails for all protocols") {
    assert(BlockFetchMessage.decode(MiniProtocolTestVectors.ErrorCases.emptyPayload).isLeft)
    assert(ChainSyncMessage.decode(MiniProtocolTestVectors.ErrorCases.emptyPayload).isLeft)
    assert(HandshakeMessage.decode(MiniProtocolTestVectors.ErrorCases.emptyPayload).isLeft)
  }

  test("Error: unknown tag 99 fails for BlockFetch") {
    val decoded = BlockFetchMessage.decode(MiniProtocolTestVectors.ErrorCases.unknownTag99)
    assert(decoded.isLeft)
  }

  test("Error: unknown tag 99 fails for ChainSync") {
    val decoded = ChainSyncMessage.decode(MiniProtocolTestVectors.ErrorCases.unknownTag99)
    assert(decoded.isLeft)
  }
