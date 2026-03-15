package stretto.network

import scodec.bits.ByteVector
import scodec.bits.hex

/**
 * Concrete CBOR test vectors for Cardano mini-protocol messages.
 *
 * Sources:
 *   - CDDL specs from ouroboros-network (IntersectMBO/ouroboros-network, cardano-diffusion/protocols/cddl/specs/)
 *   - Codec implementations from Pallas (txpipe/pallas, pallas-network/src/miniprotocols/)
 *   - Stretto's own codec implementations
 *   - Hand-constructed per CBOR RFC 8949 and the Ouroboros mini-protocol specs
 *
 * CDDL definitions (from ouroboros-network):
 *
 *   -- BlockFetch --
 *   msgRequestRange = [0, point, point]
 *   msgClientDone   = [1]
 *   msgStartBatch   = [2]
 *   msgNoBlocks     = [3]
 *   msgBlock        = [4, block]       -- block wrapped in tag24
 *   msgBatchDone    = [5]
 *
 *   -- ChainSync --
 *   msgRequestNext       = [0]
 *   msgAwaitReply        = [1]
 *   msgRollForward       = [2, header, tip]    -- header wrapped in tag24
 *   msgRollBackward      = [3, point, tip]
 *   msgFindIntersect     = [4, points]
 *   msgIntersectFound    = [5, point, tip]
 *   msgIntersectNotFound = [6, tip]
 *   chainSyncMsgDone     = [7]
 *
 *   -- Handshake (N2N v14+) --
 *   msgProposeVersions = [0, versionTable]
 *   msgAcceptVersion   = [1, versionNumber, nodeToNodeVersionData]
 *   msgRefuse          = [2, refuseReason]
 *   msgQueryReply      = [3, versionTable]
 *
 *   -- Shared types --
 *   point  = any  (Origin = [], BlockPoint = [slotNo, hash])
 *   tip    = any  (tip = [point, blockNo])
 *   nodeToNodeVersionData = [networkMagic, initiatorOnlyDiffusionMode, peerSharing, query]
 *
 *   -- MuxFrame --
 *   8 bytes header: [4B timestamp, 2B proto_id|direction, 2B payload_length] + payload
 */
object MiniProtocolTestVectors:

  // ===========================================================================
  // Reusable hash constants
  // ===========================================================================

  /** 32 bytes of 0x01 */
  val hash32_01: ByteVector = ByteVector.fill(32)(0x01)

  /** 32 bytes of 0x02 */
  val hash32_02: ByteVector = ByteVector.fill(32)(0x02)

  /** 32 bytes of 0xab */
  val hash32_ab: ByteVector = ByteVector.fill(32)(0xab.toByte)

  /** 32 bytes of 0x00 */
  val hash32_00: ByteVector = ByteVector.fill(32)(0x00)

  /** A realistic-looking block hash (from Pallas preprod test: block 1654413) */
  val realisticBlockHash: ByteVector =
    ByteVector.fromValidHex("7de1f036df5a133ce68a82877d14354d0ba6de7625ab918e75f3e2ecb29771c2")

  // ===========================================================================
  // CBOR encoding building blocks
  // ===========================================================================

  // CBOR array headers: major type 4 (0x80 | len)
  // array(0) = 0x80, array(1) = 0x81, array(2) = 0x82, array(3) = 0x83, array(4) = 0x84

  // CBOR uint: major type 0
  // 0..23 = single byte, 24 = 0x18+byte, 25 = 0x19+2bytes, 26 = 0x1a+4bytes, 27 = 0x1b+8bytes

  // CBOR bstr(32): major type 2, 0x5820 + 32 bytes

  // CBOR tag(24) = 0xd818

  // CBOR map headers: major type 5 (0xa0 | len)

  // CBOR bool: true = 0xf5, false = 0xf4

  // ===========================================================================
  // BlockFetch Test Vectors
  // ===========================================================================

  object BlockFetch:

    // -------------------------------------------------------------------------
    // MsgClientDone = [1]
    // CBOR: array(1) uint(1)
    // -------------------------------------------------------------------------
    val msgClientDone: ByteVector = hex"8101"
    val msgClientDone_description = "BlockFetch MsgClientDone = [1]"

    // -------------------------------------------------------------------------
    // MsgStartBatch = [2]
    // CBOR: array(1) uint(2)
    // -------------------------------------------------------------------------
    val msgStartBatch: ByteVector = hex"8102"
    val msgStartBatch_description = "BlockFetch MsgStartBatch = [2]"

    // -------------------------------------------------------------------------
    // MsgNoBlocks = [3]
    // CBOR: array(1) uint(3)
    // -------------------------------------------------------------------------
    val msgNoBlocks: ByteVector = hex"8103"
    val msgNoBlocks_description = "BlockFetch MsgNoBlocks = [3]"

    // -------------------------------------------------------------------------
    // MsgBatchDone = [5]
    // CBOR: array(1) uint(5)
    // -------------------------------------------------------------------------
    val msgBatchDone: ByteVector = hex"8105"
    val msgBatchDone_description = "BlockFetch MsgBatchDone = [5]"

    // -------------------------------------------------------------------------
    // MsgRequestRange = [0, point_from, point_to]
    //
    // point = [slotNo, hash_bstr32]
    // Using: from = (slot=100, hash=32*0x01), to = (slot=200, hash=32*0x02)
    //
    // Breakdown:
    //   83                    -- array(3)
    //   00                    -- uint(0) = tag
    //   82                    -- array(2) = point_from
    //     1864                -- uint(100)
    //     5820 0101...01      -- bstr(32) = hash1
    //   82                    -- array(2) = point_to
    //     18c8                -- uint(200)
    //     5820 0202...02      -- bstr(32) = hash2
    // -------------------------------------------------------------------------
    val msgRequestRange: ByteVector =
      hex"8300" ++                // array(3), tag 0
        hex"82" ++ hex"1864" ++   // array(2), slot=100
        hex"5820" ++ hash32_01 ++ // bstr(32) hash1
        hex"82" ++ hex"18c8" ++   // array(2), slot=200
        hex"5820" ++ hash32_02    // bstr(32) hash2

    val msgRequestRange_description =
      "BlockFetch MsgRequestRange from (slot=100, hash=32*0x01) to (slot=200, hash=32*0x02)"

    // -------------------------------------------------------------------------
    // MsgRequestRange with large slot numbers (slot > 65535)
    //
    // from = (slot=1000000, hash=32*0xab), to = (slot=2000000, hash=32*0x00)
    //
    // Breakdown:
    //   83 00                 -- array(3), tag 0
    //   82 1a000f4240         -- array(2), slot=1000000 (uint32)
    //     5820 abab...ab      -- hash
    //   82 1a001e8480         -- array(2), slot=2000000
    //     5820 0000...00      -- hash
    // -------------------------------------------------------------------------
    val msgRequestRange_largeSlots: ByteVector =
      hex"8300" ++
        hex"82" ++ hex"1a000f4240" ++ // slot=1_000_000
        hex"5820" ++ hash32_ab ++
        hex"82" ++ hex"1a001e8480" ++ // slot=2_000_000
        hex"5820" ++ hash32_00

    val msgRequestRange_largeSlots_description =
      "BlockFetch MsgRequestRange from (slot=1000000) to (slot=2000000)"

    // -------------------------------------------------------------------------
    // MsgBlock = [4, tag24(blockData)]
    //
    // Using blockData = deadbeefcafebabe0102030405060708 (16 bytes)
    //
    // Breakdown:
    //   82                    -- array(2)
    //   04                    -- uint(4) = tag
    //   d818                  -- CBOR tag(24)
    //     50                  -- bstr(16)
    //     deadbeefcafebabe0102030405060708
    // -------------------------------------------------------------------------
    val sampleBlockData: ByteVector = hex"deadbeefcafebabe0102030405060708"

    val msgBlock: ByteVector =
      hex"8204" ++                 // array(2), tag 4
        hex"d818" ++               // CBOR tag(24)
        hex"50" ++ sampleBlockData // bstr(16) + data

    val msgBlock_description = "BlockFetch MsgBlock with 16-byte payload wrapped in tag24"

    // -------------------------------------------------------------------------
    // MsgBlock with empty body = [4, tag24(empty)]
    //
    //   82 04 d818 40         -- array(2), tag 4, tag(24), bstr(0)
    // -------------------------------------------------------------------------
    val msgBlock_empty: ByteVector = hex"8204d81840"
    val msgBlock_empty_description = "BlockFetch MsgBlock with empty block body"

  // ===========================================================================
  // ChainSync Test Vectors
  // ===========================================================================

  object ChainSync:

    // -------------------------------------------------------------------------
    // MsgRequestNext = [0]
    // CBOR: array(1) uint(0)
    // -------------------------------------------------------------------------
    val msgRequestNext: ByteVector = hex"8100"
    val msgRequestNext_description = "ChainSync MsgRequestNext = [0]"

    // -------------------------------------------------------------------------
    // MsgAwaitReply = [1]
    // CBOR: array(1) uint(1)
    // -------------------------------------------------------------------------
    val msgAwaitReply: ByteVector = hex"8101"
    val msgAwaitReply_description = "ChainSync MsgAwaitReply = [1]"

    // -------------------------------------------------------------------------
    // MsgDone = [7]
    // CBOR: array(1) uint(7)
    // -------------------------------------------------------------------------
    val msgDone: ByteVector = hex"8107"
    val msgDone_description = "ChainSync MsgDone = [7]"

    // -------------------------------------------------------------------------
    // MsgFindIntersect with empty point list = [4, []]
    //
    //   82 04 80              -- array(2), tag 4, array(0)
    // -------------------------------------------------------------------------
    val msgFindIntersect_empty: ByteVector = hex"820480"
    val msgFindIntersect_empty_description = "ChainSync MsgFindIntersect with empty points list"

    // -------------------------------------------------------------------------
    // MsgFindIntersect with Origin = [4, [[]]]
    //
    //   82 04 81 80           -- array(2), tag 4, array(1), array(0)=Origin
    // -------------------------------------------------------------------------
    val msgFindIntersect_origin: ByteVector = hex"82048180"
    val msgFindIntersect_origin_description = "ChainSync MsgFindIntersect([Origin])"

    // -------------------------------------------------------------------------
    // MsgFindIntersect with one BlockPoint = [4, [[slot, hash]]]
    //
    // Point: slot=0, hash=32*0x00
    //
    //   82                    -- array(2)
    //   04                    -- tag 4
    //   81                    -- array(1) = points list
    //     82                  -- array(2) = BlockPoint
    //       00                -- uint(0) = slot
    //       5820 0000...00    -- bstr(32) = hash
    // -------------------------------------------------------------------------
    val msgFindIntersect_onePoint: ByteVector =
      hex"820481" ++           // array(2), tag 4, array(1)
        hex"82" ++ hex"00" ++  // array(2), slot=0
        hex"5820" ++ hash32_00 // bstr(32) hash

    val msgFindIntersect_onePoint_description =
      "ChainSync MsgFindIntersect with one BlockPoint (slot=0, hash=32*0x00)"

    // -------------------------------------------------------------------------
    // MsgFindIntersect with slot=42, hash=32*0xab
    //
    //   82 04 81 82 182a 5820 abab...ab
    // -------------------------------------------------------------------------
    val msgFindIntersect_slot42: ByteVector =
      hex"820481" ++
        hex"82" ++ hex"182a" ++ // slot=42
        hex"5820" ++ hash32_ab

    val msgFindIntersect_slot42_description =
      "ChainSync MsgFindIntersect([BlockPoint(slot=42, hash=32*0xab)])"

    // -------------------------------------------------------------------------
    // MsgFindIntersect with multiple points including Origin
    // [4, [[42, hash_ab], [], [999, hash_cd]]]
    //
    // hash_cd = 32*0xcd
    //
    //   82 04 83              -- array(2), tag 4, array(3) = 3 points
    //     82 182a 5820 abab..ab  -- BlockPoint(42, hash_ab)
    //     80                     -- Origin
    //     82 1903e7 5820 cdcd..cd  -- BlockPoint(999, hash_cd)
    // -------------------------------------------------------------------------
    val hash32_cd: ByteVector = ByteVector.fill(32)(0xcd.toByte)

    val msgFindIntersect_multi: ByteVector =
      hex"820483" ++                                      // array(2), tag 4, array(3)
        hex"82" ++ hex"182a" ++ hex"5820" ++ hash32_ab ++ // point1
        hex"80" ++                                        // origin
        hex"82" ++ hex"1903e7" ++ hex"5820" ++ hash32_cd  // point2(slot=999)

    val msgFindIntersect_multi_description =
      "ChainSync MsgFindIntersect([BlockPoint(42,ab..), Origin, BlockPoint(999,cd..)])"

    // -------------------------------------------------------------------------
    // MsgRollForward = [2, tag24(header), tip]
    //
    // header = deadbeefcafebabe (8 bytes)
    // tip = [BlockPoint(slot=42, hash=32*0xab), blockNo=100]
    //
    //   83                    -- array(3)
    //   02                    -- tag 2
    //   d818                  -- CBOR tag(24)
    //     48                  -- bstr(8)
    //     deadbeefcafebabe    -- header bytes
    //   82                    -- array(2) = tip
    //     82                  -- array(2) = point
    //       182a              -- uint(42) = slot
    //       5820 abab...ab    -- bstr(32) = hash
    //     1864                -- uint(100) = blockNo
    // -------------------------------------------------------------------------
    val sampleHeader: ByteVector = hex"deadbeefcafebabe"

    val msgRollForward: ByteVector =
      hex"8302" ++                                        // array(3), tag 2
        hex"d818" ++ hex"48" ++ sampleHeader ++           // tag24(bstr(8, header))
        hex"82" ++                                        // tip array(2)
        hex"82" ++ hex"182a" ++ hex"5820" ++ hash32_ab ++ // tip.point
        hex"1864"                                         // tip.blockNo=100

    val msgRollForward_description =
      "ChainSync MsgRollForward(header=deadbeefcafebabe, tip=(slot=42,hash=ab..,blockNo=100))"

    // -------------------------------------------------------------------------
    // MsgRollBackward = [3, point, tip]
    //
    // point = BlockPoint(slot=42, hash=32*0xab)
    // tip = [BlockPoint(slot=42, hash=32*0xab), blockNo=100]
    //
    //   83 03                 -- array(3), tag 3
    //   82 182a 5820 abab..   -- point
    //   82 82 182a 5820 abab.. 1864  -- tip
    // -------------------------------------------------------------------------
    val msgRollBackward: ByteVector =
      hex"8303" ++
        hex"82" ++ hex"182a" ++ hex"5820" ++ hash32_ab ++ // point
        hex"82" ++                                        // tip
        hex"82" ++ hex"182a" ++ hex"5820" ++ hash32_ab ++ // tip.point
        hex"1864"                                         // tip.blockNo=100

    val msgRollBackward_description =
      "ChainSync MsgRollBackward(point=(slot=42,ab..), tip=(slot=42,ab..,blockNo=100))"

    // -------------------------------------------------------------------------
    // MsgRollBackward to Origin = [3, [], [[], 0]]
    //
    //   83 03 80 82 80 00
    // -------------------------------------------------------------------------
    val msgRollBackward_origin: ByteVector = hex"830380828000"

    val msgRollBackward_origin_description =
      "ChainSync MsgRollBackward(Origin, tip=(Origin, blockNo=0))"

    // -------------------------------------------------------------------------
    // MsgIntersectFound = [5, point, tip]
    //
    // Same structure as MsgRollBackward but tag 5
    // point = BlockPoint(slot=42, hash=32*0xab)
    // tip = [BlockPoint(slot=42, hash=32*0xab), blockNo=100]
    // -------------------------------------------------------------------------
    val msgIntersectFound: ByteVector =
      hex"8305" ++
        hex"82" ++ hex"182a" ++ hex"5820" ++ hash32_ab ++ // point
        hex"82" ++                                        // tip
        hex"82" ++ hex"182a" ++ hex"5820" ++ hash32_ab ++ // tip.point
        hex"1864"                                         // tip.blockNo=100

    val msgIntersectFound_description =
      "ChainSync MsgIntersectFound(point=(slot=42,ab..), tip=(slot=42,ab..,blockNo=100))"

    // -------------------------------------------------------------------------
    // MsgIntersectNotFound = [6, tip]
    //
    //   82 06                 -- array(2), tag 6
    //   82 82 182a 5820 abab.. 1864  -- tip
    // -------------------------------------------------------------------------
    val msgIntersectNotFound: ByteVector =
      hex"8206" ++
        hex"82" ++
        hex"82" ++ hex"182a" ++ hex"5820" ++ hash32_ab ++
        hex"1864"

    val msgIntersectNotFound_description =
      "ChainSync MsgIntersectNotFound(tip=(slot=42,ab..,blockNo=100))"

    // -------------------------------------------------------------------------
    // MsgIntersectNotFound at Origin = [6, [[], 0]]
    //
    //   82 06 82 80 00
    // -------------------------------------------------------------------------
    val msgIntersectNotFound_origin: ByteVector = hex"8206828000"

    val msgIntersectNotFound_origin_description =
      "ChainSync MsgIntersectNotFound(tip=(Origin, blockNo=0))"

  // ===========================================================================
  // Handshake Test Vectors
  // ===========================================================================

  object Handshake:

    // -------------------------------------------------------------------------
    // MsgProposeVersions for Preprod (magic=1), versions 11-13
    //
    // CDDL: [0, {version => versionData, ...}]
    // nodeToNodeVersionData = [networkMagic, initiatorOnlyDiffusionMode, peerSharing, query]
    //
    // For all versions 11-13: versionData = [1, true, 0, false] = [84 01 f5 00 f4]
    //
    //   82                    -- array(2)
    //   00                    -- uint(0) = tag
    //   a3                    -- map(3)
    //     0b                  -- uint(11) = version key
    //     84 01 f5 00 f4      -- array(4): [magic=1, diffusion=true, peerSharing=0, query=false]
    //     0c                  -- uint(12) = version key
    //     84 01 f5 00 f4      -- array(4): same
    //     0d                  -- uint(13) = version key
    //     84 01 f5 00 f4      -- array(4): same
    // -------------------------------------------------------------------------
    val versionData_preprod: ByteVector = hex"8401f500f4"

    val msgProposeVersions_preprod: ByteVector =
      hex"8200" ++                        // array(2), tag 0
        hex"a3" ++                        // map(3)
        hex"0b" ++ versionData_preprod ++ // version 11
        hex"0c" ++ versionData_preprod ++ // version 12
        hex"0d" ++ versionData_preprod    // version 13

    val msgProposeVersions_preprod_description =
      "Handshake MsgProposeVersions for preprod (magic=1), versions 11-13"

    // -------------------------------------------------------------------------
    // MsgProposeVersions for Mainnet (magic=764824073)
    //
    // magic = 764824073 = 0x2D964A09
    // cborUInt(764824073) = 1a 2d964a09
    //
    // versionData = [764824073, true, 0, false] = [84 1a2d964a09 f5 00 f4]
    // -------------------------------------------------------------------------
    val versionData_mainnet: ByteVector = hex"841a2d964a09f500f4"

    val msgProposeVersions_mainnet: ByteVector =
      hex"8200" ++
        hex"a3" ++
        hex"0b" ++ versionData_mainnet ++
        hex"0c" ++ versionData_mainnet ++
        hex"0d" ++ versionData_mainnet

    val msgProposeVersions_mainnet_description =
      "Handshake MsgProposeVersions for mainnet (magic=764824073), versions 11-13"

    // -------------------------------------------------------------------------
    // MsgProposeVersions for Preview (magic=2), single version 13
    //
    //   82 00 a1 0d 84 02 f5 00 f4
    // -------------------------------------------------------------------------
    val msgProposeVersions_preview_v13: ByteVector =
      hex"8200" ++
        hex"a1" ++                 // map(1)
        hex"0d" ++ hex"8402f500f4" // version 13, [2, true, 0, false]

    val msgProposeVersions_preview_v13_description =
      "Handshake MsgProposeVersions for preview (magic=2), version 13 only"

    // -------------------------------------------------------------------------
    // MsgProposeVersions with version 14 (N2N v14+)
    //
    // CDDL: versionNumber_v14 = 14 / 15
    // version 14 = 0x0e
    // For preprod (magic=1): [1, true, 0, false]
    //
    //   82 00 a1 0e 84 01 f5 00 f4
    // -------------------------------------------------------------------------
    val msgProposeVersions_preprod_v14: ByteVector =
      hex"8200" ++
        hex"a1" ++
        hex"0e" ++ versionData_preprod // version 14

    val msgProposeVersions_preprod_v14_description =
      "Handshake MsgProposeVersions for preprod, version 14"

    // -------------------------------------------------------------------------
    // MsgAcceptVersion = [1, versionNumber, nodeToNodeVersionData]
    //
    // Accept version 13, preprod params [1, true, 0, false]
    //
    //   83                    -- array(3)
    //   01                    -- uint(1) = tag
    //   0d                    -- uint(13) = version
    //   84 01 f5 00 f4        -- array(4) = version data
    // -------------------------------------------------------------------------
    val msgAcceptVersion_v13_preprod: ByteVector =
      hex"83010d" ++ versionData_preprod

    val msgAcceptVersion_v13_preprod_description =
      "Handshake MsgAcceptVersion(version=13, preprod params)"

    // -------------------------------------------------------------------------
    // MsgAcceptVersion for mainnet, version 13
    //
    //   83 01 0d 84 1a2d964a09 f5 00 f4
    // -------------------------------------------------------------------------
    val msgAcceptVersion_v13_mainnet: ByteVector =
      hex"83010d" ++ versionData_mainnet

    val msgAcceptVersion_v13_mainnet_description =
      "Handshake MsgAcceptVersion(version=13, mainnet params)"

    // -------------------------------------------------------------------------
    // MsgAcceptVersion for version 14, preprod
    //
    //   83 01 0e 84 01 f5 00 f4
    // -------------------------------------------------------------------------
    val msgAcceptVersion_v14_preprod: ByteVector =
      hex"83010e" ++ versionData_preprod

    val msgAcceptVersion_v14_preprod_description =
      "Handshake MsgAcceptVersion(version=14, preprod params)"

    // -------------------------------------------------------------------------
    // MsgRefuse: VersionMismatch = [2, [0, [supported_versions]]]
    //
    // refuseReasonVersionMismatch = [0, [11, 12, 13]]
    //
    //   82                    -- array(2)
    //   02                    -- uint(2) = tag
    //   82                    -- array(2) = reason
    //     00                  -- uint(0) = VersionMismatch
    //     83 0b 0c 0d         -- array(3): [11, 12, 13]
    // -------------------------------------------------------------------------
    val msgRefuse_versionMismatch: ByteVector =
      hex"8202" ++    // array(2), tag 2
        hex"8200" ++  // array(2), reason tag 0 = VersionMismatch
        hex"830b0c0d" // [11, 12, 13]

    val msgRefuse_versionMismatch_description =
      "Handshake MsgRefuse(VersionMismatch([11,12,13]))"

    // -------------------------------------------------------------------------
    // MsgRefuse: HandshakeDecodeError = [2, [1, versionNumber, errorMsg]]
    //
    // [1, 13, "decode error"]
    // text("decode error") = 6c 6465636f6465206572726f72
    //
    //   82 02 83 01 0d 6c6465636f6465206572726f72
    // -------------------------------------------------------------------------
    val msgRefuse_decodeError: ByteVector =
      hex"8202" ++
        hex"83" ++ hex"01" ++ hex"0d" ++ // array(3), reason tag 1, version 13
        hex"6c" ++ ByteVector("decode error".getBytes("UTF-8"))

    val msgRefuse_decodeError_description =
      "Handshake MsgRefuse(HandshakeDecodeError(version=13, \"decode error\"))"

    // -------------------------------------------------------------------------
    // MsgRefuse: Refused = [2, [2, versionNumber, reason_text]]
    //
    // [2, 13, "refused"]
    // text("refused") = 67 72656675736564
    //
    //   82 02 83 02 0d 6772656675736564
    // -------------------------------------------------------------------------
    val msgRefuse_refused: ByteVector =
      hex"8202" ++
        hex"83" ++ hex"02" ++ hex"0d" ++
        hex"67" ++ ByteVector("refused".getBytes("UTF-8"))

    val msgRefuse_refused_description =
      "Handshake MsgRefuse(Refused(version=13, \"refused\"))"

    // -------------------------------------------------------------------------
    // MsgQueryReply = [3, versionTable]
    //
    // With single version 13, preprod
    //
    //   82 03 a1 0d 84 01 f5 00 f4
    // -------------------------------------------------------------------------
    val msgQueryReply_preprod: ByteVector =
      hex"8203" ++
        hex"a1" ++ hex"0d" ++ versionData_preprod

    val msgQueryReply_preprod_description =
      "Handshake MsgQueryReply({13: preprod_params})"

  // ===========================================================================
  // MuxFrame Test Vectors
  // ===========================================================================

  object MuxFrame:

    // -------------------------------------------------------------------------
    // All-zeros: time=0, proto=0, initiator, empty payload
    // -------------------------------------------------------------------------
    val frame_zeros: ByteVector = hex"0000000000000000"

    val frame_zeros_description =
      "MuxFrame: time=0, proto=0(handshake), initiator, empty payload"

    // -------------------------------------------------------------------------
    // time=1, proto=2(chainsync), responder, 4-byte payload
    //
    // time:   00 00 00 01
    // proto:  80 02     (0x8000 | 2 = responder + chainsync)
    // length: 00 04
    // data:   de ad be ef
    // -------------------------------------------------------------------------
    val frame_chainsync_response: ByteVector = hex"0000000180020004deadbeef"

    val frame_chainsync_response_description =
      "MuxFrame: time=1, proto=2(chainsync), responder, payload=deadbeef"

    // -------------------------------------------------------------------------
    // Handshake initiator sending MsgProposeVersions (small)
    // time=0, proto=0(handshake), initiator, payload = MsgRequestNext (2 bytes)
    //
    // time:   00 00 00 00
    // proto:  00 00     (initiator + handshake)
    // length: 00 02
    // data:   81 00     (MsgRequestNext as example payload)
    // -------------------------------------------------------------------------
    val frame_handshake_initiator: ByteVector = hex"000000000000000281 00"

    val frame_handshake_initiator_description =
      "MuxFrame: time=0, proto=0(handshake), initiator, 2-byte payload"

    // -------------------------------------------------------------------------
    // ChainSync initiator frame with MsgRequestNext
    // time=0, proto=2(chainsync), initiator, payload=8100
    //
    // 00000000 0002 0002 8100
    // -------------------------------------------------------------------------
    val frame_chainsync_requestNext: ByteVector = hex"00000000000200028100"

    val frame_chainsync_requestNext_description =
      "MuxFrame: chainsync initiator carrying MsgRequestNext"

    // -------------------------------------------------------------------------
    // BlockFetch initiator frame with MsgClientDone
    // time=0, proto=3(blockfetch), initiator, payload=8101
    //
    // 00000000 0003 0002 8101
    // -------------------------------------------------------------------------
    val frame_blockfetch_clientDone: ByteVector = hex"00000000000300028101"

    val frame_blockfetch_clientDone_description =
      "MuxFrame: blockfetch initiator carrying MsgClientDone"

    // -------------------------------------------------------------------------
    // BlockFetch responder frame with MsgStartBatch
    // time=0, proto=3(blockfetch), responder, payload=8102
    //
    // 00000000 8003 0002 8102
    // -------------------------------------------------------------------------
    val frame_blockfetch_startBatch: ByteVector = hex"00000000800300028102"

    val frame_blockfetch_startBatch_description =
      "MuxFrame: blockfetch responder carrying MsgStartBatch"

    // -------------------------------------------------------------------------
    // Large timestamp: time=0xdeadbeef, proto=5(keepalive), initiator, empty
    //
    // deadbeef 0005 0000
    // -------------------------------------------------------------------------
    val frame_keepalive_largeTime: ByteVector = hex"deadbeef00050000"

    val frame_keepalive_largeTime_description =
      "MuxFrame: time=0xdeadbeef, proto=5(keepalive), initiator, empty"

    // -------------------------------------------------------------------------
    // Max protocol ID: proto=0x7fff, initiator
    //
    // 00000000 7fff 0000
    // -------------------------------------------------------------------------
    val frame_maxProtoId: ByteVector = hex"000000007fff0000"

    val frame_maxProtoId_description =
      "MuxFrame: proto=0x7fff(max), initiator, empty"

    // -------------------------------------------------------------------------
    // Max protocol ID: proto=0x7fff, responder
    //
    // 00000000 ffff 0000
    // -------------------------------------------------------------------------
    val frame_maxProtoId_responder: ByteVector = hex"00000000ffff0000"

    val frame_maxProtoId_responder_description =
      "MuxFrame: proto=0x7fff(max), responder, empty"

    // -------------------------------------------------------------------------
    // Well-known protocol IDs (from Ouroboros spec):
    //   0 = Handshake
    //   2 = ChainSync (N2N)
    //   3 = BlockFetch (N2N)
    //   5 = KeepAlive
    //   7 = TxSubmission2
    // -------------------------------------------------------------------------

  // ===========================================================================
  // Cross-protocol: Full wire sequence examples
  // ===========================================================================

  object WireSequences:

    // -------------------------------------------------------------------------
    // Complete handshake + chainsync startup sequence on the wire
    //
    // 1. Initiator sends MsgProposeVersions in a mux frame (proto=0)
    // 2. Responder sends MsgAcceptVersion in a mux frame (proto=0)
    // 3. Initiator sends MsgFindIntersect in a mux frame (proto=2)
    //
    // These are the individual mux frames you'd see on tcpdump.
    // -------------------------------------------------------------------------

    /** Step 1: Mux frame carrying MsgProposeVersions for preprod v13-only */
    val step1_propose: ByteVector =
      val payload    = Handshake.msgProposeVersions_preview_v13
      val payloadLen = payload.size.toInt
      hex"00000000" ++ // time=0
        hex"0000" ++   // proto=0 (handshake), initiator
        ByteVector.fromShort(payloadLen.toShort, size = 2) ++
        payload

    /** Step 2: Mux frame carrying MsgAcceptVersion for v13 preprod */
    val step2_accept: ByteVector =
      val payload    = Handshake.msgAcceptVersion_v13_preprod
      val payloadLen = payload.size.toInt
      hex"00000000" ++
        hex"8000" ++ // proto=0 (handshake), responder
        ByteVector.fromShort(payloadLen.toShort, size = 2) ++
        payload

    /** Step 3: Mux frame carrying MsgFindIntersect with one point */
    val step3_findIntersect: ByteVector =
      val payload    = ChainSync.msgFindIntersect_slot42
      val payloadLen = payload.size.toInt
      hex"00000000" ++
        hex"0002" ++ // proto=2 (chainsync), initiator
        ByteVector.fromShort(payloadLen.toShort, size = 2) ++
        payload

  // ===========================================================================
  // Source: Pallas (txpipe/pallas) test data from protocols.rs
  // ===========================================================================

  object PallasTestData:

    /** Block hash used in Pallas ChainSync intersection tests (block 1654413 preprod) */
    val pallasBlockHash: ByteVector =
      ByteVector.fromValidHex("7de1f036df5a133ce68a82877d14354d0ba6de7625ab918e75f3e2ecb29771c2")

    val pallasBlockHash_description =
      "Source: pallas-network/tests/protocols.rs - known preprod block hash at height 1654413"

    /** Mock header content from Pallas tests */
    val pallasMockHeader: ByteVector = hex"c0ffeec0ffeec0ffee"

    val pallasMockHeader_description =
      "Source: pallas-network/tests/protocols.rs - mock header for chainsync rollforward"

    /** Mock block body from Pallas tests */
    val pallasMockBlockBody: ByteVector = hex"deadbeefdeadbeef"

    val pallasMockBlockBody_description =
      "Source: pallas-network/tests/protocols.rs - mock block body for blockfetch"

    /** Mainnet transaction hash used in Pallas TxSubmission test (Babbage era) */
    val pallasMainnetTxHash: ByteVector =
      ByteVector.fromValidHex("8b6e50e09376b5021e93fe688ba9e7100e3682cebcb39970af5f4e5962bc5a3d")

    val pallasMainnetTxHash_description =
      "Source: pallas-network/tests/protocols.rs - mainnet Babbage tx hash"

  // ===========================================================================
  // Source: Pallas LocalStateQuery CBOR test data
  // ===========================================================================

  object PallasLocalStateQuery:

    /** GetUTxOWhole query CBOR (without 8203 prefix) */
    val getUtxoWholeQuery: ByteVector = hex"8200820082068107"

    val getUtxoWholeQuery_description =
      "Source: pallas protocols.rs - GetUTxOWhole query payload"

    /** GetFilteredDelegationsAndRewardAccounts query */
    val getDelegationsQuery: ByteVector =
      hex"820082008206820a818200581c1218f563e4e10958fdabbdfb470b2f9d386215763cc89273d9bdfffa"

    val getDelegationsQuery_description =
      "Source: pallas protocols.rs - GetFilteredDelegationsAndRewardAccounts query"

    /** GetUTxOWhole response with legacy + post-Alonzo outputs */
    val getUtxoWholeResponse: ByteVector = ByteVector.fromValidHex(
      "A28258201610F289E36C9D83C464F85A0AADD59101DDDB0E89592A92809D95D68D79EED9" ++
        "0282581D60C0359EBB7D0688D79064BD118C99C8B87B5853E3AF59245BB97E84D2" ++
        "1A00BD81D1825820A7BED2F5FCD72BA4CEFDA7C2CC94D119279A17D71BFFC4D90D" ++
        "D4272B93E8A88F00A300581D603F2728EC78EF8B0F356E91A5662FF3124ADD324A7B" ++
        "7F5AEED69362F4011A001B5BC0028201D81856D8799FD8799F1AE06755BBFF1B00000193B36BC9F0FF"
    )

    val getUtxoWholeResponse_description =
      "Source: pallas protocols.rs - GetUTxOWhole response with inline datum"

    /** GetFilteredDelegationsAndRewardAccounts response */
    val getDelegationsResponse: ByteVector = ByteVector.fromValidHex(
      "82a18200581c1218f563e4e10958fdabbdfb470b2f9d386215763cc89273d9bdfffa" ++
        "581c1e3105f23f2ac91b3fb4c35fa4fe301421028e356e114944e902005b" ++
        "a18200581c1218f563e4e10958fdabbdfb470b2f9d386215763cc89273d9bdfffa1a0eeebb3b"
    )

    val getDelegationsResponse_description =
      "Source: pallas protocols.rs - delegation and reward account response"

  // ===========================================================================
  // Edge cases / error vectors
  // ===========================================================================

  object ErrorCases:

    /** Empty payload - should fail decode for all protocols */
    val emptyPayload: ByteVector = ByteVector.empty

    /** Unknown tag 99 encoded as CBOR: array(1) uint(99) = 0x81 0x18 0x63 */
    val unknownTag99: ByteVector = hex"811863"
    val unknownTag99_description = "array(1) with unknown tag 99 - should produce decode error"

    /** Not a CBOR array (starts with uint) */
    val notAnArray: ByteVector = hex"00"
    val notAnArray_description = "bare uint(0) - not an array, should fail"

    /** Truncated MsgRequestRange (array header but no content) */
    val truncatedRequestRange: ByteVector = hex"8300"
    val truncatedRequestRange_description = "Truncated MsgRequestRange - missing point data"

    /** MsgBlock with wrong tag inside (not tag24) */
    val msgBlockWrongTag: ByteVector = hex"820440"
    val msgBlockWrongTag_description = "MsgBlock with bare bstr instead of tag24-wrapped"
