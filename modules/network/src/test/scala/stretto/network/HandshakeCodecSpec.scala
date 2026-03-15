package stretto.network

import munit.FunSuite
import scodec.bits.ByteVector
import scodec.bits.hex

class HandshakeCodecSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // MsgProposeVersions
  // ---------------------------------------------------------------------------

  test("MsgProposeVersions: encode for preprod (magic=1) starts with array(2) tag(0)") {
    val msg     = HandshakeMessage.handshakeClient(NetworkMagic.Preprod)
    val encoded = HandshakeMessage.encode(msg)
    // Outer: array(2) = 0x82, uint(0) = 0x00
    assertEquals(encoded.take(2), hex"8200")
  }

  test("MsgProposeVersions: encode for preprod produces valid CBOR map") {
    val msg     = HandshakeMessage.handshakeClient(NetworkMagic.Preprod)
    val encoded = HandshakeMessage.encode(msg)
    // After array(2) + uint(0) we should have a map header
    // map(4) for versions 11, 12, 13, 14 = 0xa4
    assertEquals(encoded(2) & 0xff, 0xa4)
  }

  test("handshakeClient(1): produces MsgProposeVersions with versions 11-14") {
    val msg                                           = HandshakeMessage.handshakeClient(1L)
    val HandshakeMessage.MsgProposeVersions(versions) = msg: @unchecked
    assertEquals(versions.keySet, Set(11, 12, 13, 14))
  }

  test("handshakeClient: version data contains network magic") {
    val msg                                           = HandshakeMessage.handshakeClient(NetworkMagic.Preprod)
    val HandshakeMessage.MsgProposeVersions(versions) = msg: @unchecked
    // Each version data should be a CBOR array starting with the magic (1)
    versions.values.foreach { vdata =>
      // First byte should be array header (0x84 = array(4))
      assertEquals(vdata(0) & 0xff, 0x84)
      // Second byte should be uint(1) = network magic for preprod
      assertEquals(vdata(1) & 0xff, 0x01)
    }
  }

  // ---------------------------------------------------------------------------
  // MsgAcceptVersion
  // ---------------------------------------------------------------------------

  test("MsgAcceptVersion: encode then decode round-trips") {
    val params  = ByteVector.fromValidHex("8401f5f4f4")
    val msg     = HandshakeMessage.MsgAcceptVersion(13, params)
    val encoded = HandshakeMessage.encode(msg)
    val decoded = HandshakeMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  test("MsgAcceptVersion: decode known bytes") {
    // array(3) + uint(1) + uint(13) + params
    // 0x83 0x01 0x0d + some params
    val params  = hex"8401f5f4f4"
    val encoded = hex"83010d" ++ params
    val decoded = HandshakeMessage.decode(encoded)
    assert(decoded.isRight)
    val HandshakeMessage.MsgAcceptVersion(version, decodedParams) = decoded.toOption.get: @unchecked
    assertEquals(version, 13)
    assertEquals(decodedParams, params)
  }

  // ---------------------------------------------------------------------------
  // MsgRefuse
  // ---------------------------------------------------------------------------

  test("MsgRefuse: decode known bytes") {
    // array(2) + uint(2) + reason bytes
    val reason  = hex"8363666f6f" // some CBOR reason data
    val encoded = hex"8202" ++ reason
    val decoded = HandshakeMessage.decode(encoded)
    assert(decoded.isRight)
    val HandshakeMessage.MsgRefuse(decodedReason) = decoded.toOption.get: @unchecked
    assertEquals(decodedReason, reason)
  }

  // ---------------------------------------------------------------------------
  // Round-trip encode → decode
  // ---------------------------------------------------------------------------

  test("MsgProposeVersions: encode produces decodable output (decode rejects as response)") {
    // MsgProposeVersions is tag 0, which the decoder rejects as "unexpected in response"
    val msg     = HandshakeMessage.handshakeClient(NetworkMagic.Mainnet)
    val encoded = HandshakeMessage.encode(msg)
    val decoded = HandshakeMessage.decode(encoded)
    assert(decoded.isLeft)
    assert(decoded.left.toOption.get.contains("MsgProposeVersions"))
  }

  test("MsgRefuse: encode then decode round-trips") {
    val reason  = hex"63666f6f" // text "foo"
    val msg     = HandshakeMessage.MsgRefuse(reason)
    val encoded = HandshakeMessage.encode(msg)
    val decoded = HandshakeMessage.decode(encoded)
    assertEquals(decoded, Right(msg))
  }

  // ---------------------------------------------------------------------------
  // Network magic constants
  // ---------------------------------------------------------------------------

  test("NetworkMagic constants are correct") {
    assertEquals(NetworkMagic.Mainnet, 764824073L)
    assertEquals(NetworkMagic.Preprod, 1L)
    assertEquals(NetworkMagic.Preview, 2L)
  }

  // ---------------------------------------------------------------------------
  // Error cases
  // ---------------------------------------------------------------------------

  test("decode: empty payload returns Left") {
    val decoded = HandshakeMessage.decode(ByteVector.empty)
    assert(decoded.isLeft)
  }

  test("decode: non-array first byte returns Left") {
    // uint(0) = 0x00, not a CBOR array
    val decoded = HandshakeMessage.decode(hex"00")
    assert(decoded.isLeft)
    assert(decoded.left.toOption.get.contains("expected CBOR array"))
  }
