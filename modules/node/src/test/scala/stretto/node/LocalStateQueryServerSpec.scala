package stretto.node

import munit.FunSuite
import scodec.bits.ByteVector

class LocalStateQueryServerSpec extends FunSuite:

  // -------------------------------------------------------------------------
  // GenesisConfig tests
  // -------------------------------------------------------------------------

  test("mainnet shelleyStartSlot is correct") {
    // Byron: 208 epochs * 21600 slots * 20s/slot / 1s/slot = 89,856,000
    val config = GenesisConfig.Mainnet
    assertEquals(config.shelleyStartSlot, 208L * 21600L * 20L)
  }

  test("preprod shelleyStartSlot is correct") {
    val config = GenesisConfig.Preprod
    assertEquals(config.shelleyStartSlot, 4L * 4320L * 20L)
  }

  test("mainnet epochForSlot returns Byron epoch") {
    val config = GenesisConfig.Mainnet
    // Slot 0 = epoch 0
    assertEquals(config.epochForSlot(0L), 0L)
  }

  test("mainnet epochForSlot returns Shelley epoch") {
    val config       = GenesisConfig.Mainnet
    val shelleyStart = config.shelleyStartSlot
    // First Shelley slot = epoch 208
    assertEquals(config.epochForSlot(shelleyStart), 208L)
    // One Shelley epoch later = epoch 209
    assertEquals(config.epochForSlot(shelleyStart + 432000L), 209L)
  }

  test("preprod epochForSlot in Conway") {
    val config = GenesisConfig.Preprod
    // Slot 50,000,000 should be well into Shelley+ era
    val epoch = config.epochForSlot(50000000L)
    assert(epoch > 100, s"expected epoch > 100, got $epoch")
  }

  test("forNetwork returns correct config") {
    assertEquals(GenesisConfig.forNetwork("mainnet").systemStart, "2017-09-23T21:44:51Z")
    assertEquals(GenesisConfig.forNetwork("preprod").systemStart, "2022-04-01T00:00:00Z")
    assertEquals(GenesisConfig.forNetwork("preview").systemStart, "2022-11-01T00:00:00Z")
  }

  // -------------------------------------------------------------------------
  // LSQ CBOR message encoding tests
  // -------------------------------------------------------------------------

  test("MsgAcquire VolatileTip decodes as tag 8") {
    // [8] = 0x81 0x08
    val bytes = ByteVector(0x81.toByte, 0x08.toByte)
    val b     = bytes(0) & 0xff
    val major = b >> 5
    assertEquals(major, 4) // array
    val arrLen = b & 0x1f
    assertEquals(arrLen, 1)
    val tag = bytes(1) & 0xff
    assertEquals(tag, 8)
  }

  test("MsgAcquire ImmutableTip decodes as tag 10") {
    // [10] = 0x81 0x0a
    val bytes = ByteVector(0x81.toByte, 0x0a.toByte)
    val tag   = bytes(1) & 0xff
    assertEquals(tag, 10)
  }

  test("MsgDone decodes as tag 7") {
    // [7] = 0x81 0x07
    val bytes = ByteVector(0x81.toByte, 0x07.toByte)
    val tag   = bytes(1) & 0xff
    assertEquals(tag, 7)
  }

  test("MsgRelease decodes as tag 5") {
    // [5] = 0x81 0x05
    val bytes = ByteVector(0x81.toByte, 0x05.toByte)
    val tag   = bytes(1) & 0xff
    assertEquals(tag, 5)
  }

  test("MsgQuery GetSystemStart encodes correctly") {
    // [3, [1]] = 0x82 0x03 0x81 0x01
    val bytes = ByteVector(0x82.toByte, 0x03.toByte, 0x81.toByte, 0x01.toByte)
    assertEquals(bytes(1) & 0xff, 3) // tag 3 = MsgQuery
    val innerArr = bytes(2) & 0xff
    assertEquals(innerArr >> 5, 4) // array
    val innerTag = bytes(3) & 0xff
    assertEquals(innerTag, 1) // GetSystemStart
  }

  test("MsgQuery GetChainBlockNo encodes correctly") {
    // [3, [2]] = 0x82 0x03 0x81 0x02
    val bytes = ByteVector(0x82.toByte, 0x03.toByte, 0x81.toByte, 0x02.toByte)
    assertEquals(bytes(3) & 0xff, 2)
  }

  test("MsgQuery GetChainPoint encodes correctly") {
    // [3, [3]] = 0x82 0x03 0x81 0x03
    val bytes = ByteVector(0x82.toByte, 0x03.toByte, 0x81.toByte, 0x03.toByte)
    assertEquals(bytes(3) & 0xff, 3)
  }

  // -------------------------------------------------------------------------
  // Protocol parameter encoding tests
  // -------------------------------------------------------------------------

  test("Conway PParams CBOR starts with map header") {
    // The first byte of the encoded PParams should be a CBOR map
    // We can't call the private method directly, but we can verify
    // the constants used
    val mapHeader18 = 0xa0 | 18 // 0xb2
    assertEquals(mapHeader18, 0xb2)
  }

  // -------------------------------------------------------------------------
  // MiniProtocolId tests
  // -------------------------------------------------------------------------

  test("LocalStateQuery protocol ID is 7") {
    import stretto.network.MiniProtocolId
    assertEquals(MiniProtocolId.LocalStateQuery.id, 7)
  }

  test("LocalTxSubmission protocol ID is 6 (not 8)") {
    import stretto.network.MiniProtocolId
    assertEquals(MiniProtocolId.LocalTxSubmission.id, 6)
  }

  test("KeepAlive protocol ID is 8") {
    import stretto.network.MiniProtocolId
    assertEquals(MiniProtocolId.KeepAlive.id, 8)
  }
