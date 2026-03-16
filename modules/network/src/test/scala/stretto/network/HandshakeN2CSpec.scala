package stretto.network

import munit.FunSuite
import scodec.bits.ByteVector
import scodec.bits.hex

class HandshakeN2CSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // N2C version constants
  // ---------------------------------------------------------------------------

  test("N2C version constants use bit-15 offset") {
    assertEquals(HandshakeMessage.N2C_V16, 32784)
    assertEquals(HandshakeMessage.N2C_V17, 32785)
    assertEquals(HandshakeMessage.N2C_V18, 32786)
    assertEquals(HandshakeMessage.N2C_V19, 32787)
    // Verify bit-15 offset: 0x8000 + 16 = 32784
    assertEquals(HandshakeMessage.N2C_V16, 0x8000 + 16)
  }

  test("n2cVersions contains V16 through V19") {
    assertEquals(
      HandshakeMessage.n2cVersions,
      Set(32784, 32785, 32786, 32787)
    )
  }

  // ---------------------------------------------------------------------------
  // N2C version data encoding
  // ---------------------------------------------------------------------------

  test("n2cVersionData: encodes [networkMagic, false] for preprod") {
    val vdata = HandshakeMessage.n2cVersionData(NetworkMagic.Preprod)
    // CBOR: array(2) = 0x82, uint(1) = 0x01, false = 0xf4
    assertEquals(vdata, hex"8201f4")
  }

  test("n2cVersionData: encodes [networkMagic, false] for mainnet") {
    val vdata = HandshakeMessage.n2cVersionData(NetworkMagic.Mainnet)
    // array(2) = 0x82, uint(764824073) = 0x1a 2d964a09, false = 0xf4
    val first = vdata(0) & 0xff
    assertEquals(first, 0x82)             // array(2)
    assert(vdata.size > 3)                // magic takes multiple bytes
    assertEquals(vdata.last & 0xff, 0xf4) // false
  }

  // ---------------------------------------------------------------------------
  // MsgAcceptVersion encoding for N2C
  // ---------------------------------------------------------------------------

  test("MsgAcceptVersion with N2C version encodes and decodes") {
    val vdata   = HandshakeMessage.n2cVersionData(NetworkMagic.Preprod)
    val msg     = HandshakeMessage.MsgAcceptVersion(HandshakeMessage.N2C_V16, vdata)
    val encoded = HandshakeMessage.encode(msg)
    val decoded = HandshakeMessage.decode(encoded)
    assert(decoded.isRight)
    val HandshakeMessage.MsgAcceptVersion(version, params) = decoded.toOption.get: @unchecked
    assertEquals(version, HandshakeMessage.N2C_V16)
    assertEquals(params, vdata)
  }

  // ---------------------------------------------------------------------------
  // MsgProposeVersions decoding
  // ---------------------------------------------------------------------------

  test("decodeProposeVersions: decode a single-version N2C proposal") {
    // Manually build: [0, {32784: [1, false]}]
    // array(2) uint(0) map(1) uint(32784) array(2) uint(1) false
    val version = 32784L
    val vdata   = hex"8201f4" // [1, false]
    val encoded =
      hex"82" ++ hex"00" ++                                           // array(2) + tag 0
        hex"a1" ++                                                    // map(1)
        hex"19" ++ ByteVector.fromShort(version.toShort, size = 2) ++ // uint(32784)
        vdata
    val result = HandshakeMessage.decodeProposeVersions(encoded)
    assert(result.isRight, s"decode failed: $result")
    val versions = result.toOption.get
    assertEquals(versions.size, 1)
    assert(versions.contains(32784))
    assertEquals(versions(32784), vdata)
  }

  test("decodeProposeVersions: decode multi-version N2C proposal") {
    // Build: [0, {32784: vdata, 32785: vdata}]
    val vdata = hex"8201f4"
    val encoded =
      hex"82" ++ hex"00" ++ // array(2) + tag 0
        hex"a2" ++          // map(2)
        hex"19" ++ ByteVector.fromShort(32784.toShort, size = 2) ++ vdata ++
        hex"19" ++ ByteVector.fromShort(32785.toShort, size = 2) ++ vdata
    val result = HandshakeMessage.decodeProposeVersions(encoded)
    assert(result.isRight, s"decode failed: $result")
    val versions = result.toOption.get
    assertEquals(versions.size, 2)
    assert(versions.contains(32784))
    assert(versions.contains(32785))
  }

  test("decodeProposeVersions: empty payload returns Left") {
    val result = HandshakeMessage.decodeProposeVersions(ByteVector.empty)
    assert(result.isLeft)
  }

  test("decodeProposeVersions: wrong tag returns Left") {
    // array(2) + uint(1) = MsgAcceptVersion tag, not MsgProposeVersions
    val result = HandshakeMessage.decodeProposeVersions(hex"8201")
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("expected tag 0"))
  }

  // ---------------------------------------------------------------------------
  // Version negotiation logic
  // ---------------------------------------------------------------------------

  test("n2cVersions intersection finds mutual versions") {
    val clientVersions = Set(32784, 32785, 32786)
    val mutual         = clientVersions.intersect(HandshakeMessage.n2cVersions)
    assertEquals(mutual, Set(32784, 32785, 32786))
    assertEquals(mutual.max, 32786)
  }

  test("n2cVersions intersection with no overlap is empty") {
    val clientVersions = Set(11, 12, 13) // N2N versions
    val mutual         = clientVersions.intersect(HandshakeMessage.n2cVersions)
    assert(mutual.isEmpty)
  }
