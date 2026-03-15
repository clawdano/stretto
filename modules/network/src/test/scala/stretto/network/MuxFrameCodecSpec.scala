package stretto.network

import munit.FunSuite
import scodec.bits.ByteVector
import scodec.bits.hex

class MuxFrameCodecSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // encode / decodeHeader round-trip
  // ---------------------------------------------------------------------------

  test("encode then decodeHeader round-trips") {
    val frame = MuxFrame(
      transmissionTime = 12345L,
      miniProtocolId = 2,
      isResponse = false,
      payload = hex"deadbeef"
    )
    val encoded                         = MuxFrame.encode(frame)
    val header                          = encoded.take(MuxFrame.HeaderSize.toLong)
    val (time, protoId, isResp, payLen) = MuxFrame.decodeHeader(header)
    assertEquals(time, 12345L)
    assertEquals(protoId, 2)
    assertEquals(isResp, false)
    assertEquals(payLen, 4)
  }

  test("encode then decodeHeader round-trips with response flag") {
    val frame = MuxFrame(
      transmissionTime = 0L,
      miniProtocolId = 5,
      isResponse = true,
      payload = hex"01020304050607"
    )
    val encoded                         = MuxFrame.encode(frame)
    val header                          = encoded.take(MuxFrame.HeaderSize.toLong)
    val (time, protoId, isResp, payLen) = MuxFrame.decodeHeader(header)
    assertEquals(time, 0L)
    assertEquals(protoId, 5)
    assertEquals(isResp, true)
    assertEquals(payLen, 7)
  }

  // ---------------------------------------------------------------------------
  // Direction bit
  // ---------------------------------------------------------------------------

  test("direction bit: isResponse=false clears bit 15 of protocol ID word") {
    val frame = MuxFrame(
      transmissionTime = 0L,
      miniProtocolId = 3,
      isResponse = false,
      payload = ByteVector.empty
    )
    val encoded = MuxFrame.encode(frame)
    // Bytes 4-5 are the protocol ID word
    val idWord = ((encoded(4) & 0xff) << 8) | (encoded(5) & 0xff)
    assertEquals(idWord & 0x8000, 0)
    assertEquals(idWord & 0x7fff, 3)
  }

  test("direction bit: isResponse=true sets bit 15 of protocol ID word") {
    val frame = MuxFrame(
      transmissionTime = 0L,
      miniProtocolId = 3,
      isResponse = true,
      payload = ByteVector.empty
    )
    val encoded = MuxFrame.encode(frame)
    val idWord  = ((encoded(4) & 0xff) << 8) | (encoded(5) & 0xff)
    assert((idWord & 0x8000) != 0, "bit 15 should be set for response")
    assertEquals(idWord & 0x7fff, 3)
  }

  // ---------------------------------------------------------------------------
  // Protocol ID preservation
  // ---------------------------------------------------------------------------

  test("protocol ID 0 round-trips") {
    val frame              = MuxFrame(0L, 0, false, ByteVector.empty)
    val encoded            = MuxFrame.encode(frame)
    val header             = encoded.take(MuxFrame.HeaderSize.toLong)
    val (_, protoId, _, _) = MuxFrame.decodeHeader(header)
    assertEquals(protoId, 0)
  }

  test("protocol ID 0x7fff (max) round-trips") {
    val frame              = MuxFrame(0L, 0x7fff, false, ByteVector.empty)
    val encoded            = MuxFrame.encode(frame)
    val header             = encoded.take(MuxFrame.HeaderSize.toLong)
    val (_, protoId, _, _) = MuxFrame.decodeHeader(header)
    assertEquals(protoId, 0x7fff)
  }

  test("well-known protocol IDs round-trip: handshake=0, chainsync=2, blockfetch=3") {
    for pid <- List(0, 2, 3, 5) do
      val frame              = MuxFrame(0L, pid, false, ByteVector.empty)
      val encoded            = MuxFrame.encode(frame)
      val header             = encoded.take(MuxFrame.HeaderSize.toLong)
      val (_, protoId, _, _) = MuxFrame.decodeHeader(header)
      assertEquals(protoId, pid, s"protocol ID $pid did not round-trip")
  }

  // ---------------------------------------------------------------------------
  // Payload length encoding
  // ---------------------------------------------------------------------------

  test("payload length is encoded in bytes 6-7 as big-endian uint16") {
    val payload           = ByteVector.fill(300)(0xaa)
    val frame             = MuxFrame(0L, 1, false, payload)
    val encoded           = MuxFrame.encode(frame)
    val header            = encoded.take(MuxFrame.HeaderSize.toLong)
    val (_, _, _, payLen) = MuxFrame.decodeHeader(header)
    assertEquals(payLen, 300)
  }

  test("empty payload encodes length as 0") {
    val frame             = MuxFrame(0L, 1, false, ByteVector.empty)
    val encoded           = MuxFrame.encode(frame)
    val header            = encoded.take(MuxFrame.HeaderSize.toLong)
    val (_, _, _, payLen) = MuxFrame.decodeHeader(header)
    assertEquals(payLen, 0)
  }

  // ---------------------------------------------------------------------------
  // Known frame header bytes
  // ---------------------------------------------------------------------------

  test("known header bytes: time=0, proto=0, initiator, empty payload") {
    val frame   = MuxFrame(0L, 0, false, ByteVector.empty)
    val encoded = MuxFrame.encode(frame)
    // 4 bytes time (0) + 2 bytes proto (0) + 2 bytes len (0)
    assertEquals(encoded, hex"0000000000000000")
  }

  test("known header bytes: time=1, proto=2, responder, 4-byte payload") {
    val frame   = MuxFrame(1L, 2, true, hex"deadbeef")
    val encoded = MuxFrame.encode(frame)
    // time: 00000001, proto: 8002 (0x8000 | 2), len: 0004
    val expectedHeader = hex"0000000180020004"
    assertEquals(encoded.take(8), expectedHeader)
    assertEquals(encoded.drop(8), hex"deadbeef")
  }

  // ---------------------------------------------------------------------------
  // Header size constant
  // ---------------------------------------------------------------------------

  test("HeaderSize is 8") {
    assertEquals(MuxFrame.HeaderSize, 8)
  }

  test("encoded frame size equals HeaderSize + payload size") {
    val payload = hex"0102030405"
    val frame   = MuxFrame(0L, 0, false, payload)
    val encoded = MuxFrame.encode(frame)
    assertEquals(encoded.size, (MuxFrame.HeaderSize + payload.size.toInt).toLong)
  }
