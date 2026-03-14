package stretto.network

import scodec.bits.ByteVector

/** Network magic constants for well-known Cardano networks. */
object NetworkMagic:
  val Mainnet: Long = 764824073L
  val Preprod: Long = 1L
  val Preview: Long = 2L

/** Messages exchanged during the Ouroboros handshake mini-protocol. */
enum HandshakeMessage:
  case MsgProposeVersions(versions: Map[Int, ByteVector])
  case MsgAcceptVersion(version: Int, params: ByteVector)
  case MsgRefuse(reason: ByteVector)

object HandshakeMessage:

  /** Encode a CBOR unsigned integer (major type 0). */
  private def cborUInt(n: Long): ByteVector =
    if n < 24 then ByteVector(n.toByte)
    else if n < 256 then ByteVector(0x18.toByte, n.toByte)
    else if n < 65536 then ByteVector(0x19.toByte) ++ ByteVector.fromShort(n.toShort, size = 2)
    else ByteVector(0x1a.toByte) ++ ByteVector.fromInt(n.toInt, size = 4)

  /** Encode a CBOR boolean (major type 7, simple values). */
  private def cborBool(b: Boolean): ByteVector =
    if b then ByteVector(0xf5.toByte) else ByteVector(0xf4.toByte)

  /** Encode a CBOR definite-length array header (major type 4). */
  private def cborArrayHeader(len: Int): ByteVector =
    if len < 24 then ByteVector((0x80 | len).toByte)
    else ByteVector(0x98.toByte, len.toByte)

  /** Encode a CBOR definite-length map header (major type 5). */
  private def cborMapHeader(len: Int): ByteVector =
    if len < 24 then ByteVector((0xa0 | len).toByte)
    else ByteVector(0xb8.toByte, len.toByte)

  /**
   * Build N2N version data for versions 11-13.
   *
   * CBOR array: [networkMagic, initiatorAndResponderDiffusionMode, peerSharing, query]
   */
  private def n2nVersionData(
      versionNum: Int,
      networkMagic: Long
  ): ByteVector =
    val magic         = cborUInt(networkMagic)
    val diffusionMode = cborBool(true)
    val peerSharing   = cborUInt(0)
    val query         = cborBool(false)
    if versionNum >= 13 then cborArrayHeader(4) ++ magic ++ diffusionMode ++ peerSharing ++ query
    else if versionNum >= 11 then cborArrayHeader(4) ++ magic ++ diffusionMode ++ peerSharing ++ query
    else cborArrayHeader(2) ++ magic ++ diffusionMode

  /** Encode a HandshakeMessage to CBOR bytes. */
  def encode(msg: HandshakeMessage): ByteVector = msg match
    case HandshakeMessage.MsgProposeVersions(versions) =>
      val mapBytes = versions.toList.sortBy(_._1).foldLeft(ByteVector.empty) { case (acc, (ver, vdata)) =>
        acc ++ cborUInt(ver.toLong) ++ vdata
      }
      cborArrayHeader(2) ++ cborUInt(0) ++ cborMapHeader(
        versions.size
      ) ++ mapBytes

    case HandshakeMessage.MsgAcceptVersion(version, params) =>
      cborArrayHeader(3) ++ cborUInt(1) ++ cborUInt(version.toLong) ++ params

    case HandshakeMessage.MsgRefuse(reason) =>
      cborArrayHeader(2) ++ cborUInt(2) ++ reason

  /**
   * Attempt to decode a CBOR-encoded handshake response.
   *
   * This is a minimal decoder that handles MsgAcceptVersion and MsgRefuse.
   */
  def decode(bytes: ByteVector): Either[String, HandshakeMessage] =
    if bytes.isEmpty then Left("empty payload")
    else
      // Read outer array tag
      val firstByte = bytes(0) & 0xff
      if firstByte < 0x80 || firstByte > 0x9f then Left(s"expected CBOR array, got 0x${bytes(0).toHexString}")
      else
        val (arrayLen, afterArrayHdr) =
          if (firstByte & 0x1f) < 24 then ((firstByte & 0x1f).toLong, 1)
          else (bytes(1).toLong & 0xff, 2)

        readCborUInt(bytes, afterArrayHdr) match
          case Right((0, offset)) =>
            // MsgProposeVersions — not expected as a response but handle gracefully
            Left("unexpected MsgProposeVersions in response")
          case Right((1, offset)) =>
            // MsgAcceptVersion: [1, version, params...]
            readCborUInt(bytes, offset) match
              case Right((version, paramOffset)) =>
                val params = bytes.drop(paramOffset.toLong)
                Right(
                  HandshakeMessage.MsgAcceptVersion(version.toInt, params)
                )
              case Left(err) => Left(s"failed to read version: $err")
          case Right((2, offset)) =>
            // MsgRefuse: [2, reason]
            Right(HandshakeMessage.MsgRefuse(bytes.drop(offset.toLong)))
          case Right((tag, _)) =>
            Left(s"unknown handshake message tag: $tag")
          case Left(err) => Left(err)

  /** Read a CBOR unsigned int at the given byte offset. Returns (value, nextOffset). */
  private def readCborUInt(
      bytes: ByteVector,
      offset: Int
  ): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b          = bytes(offset.toLong) & 0xff
      val major      = b >> 5
      val additional = b & 0x1f
      if major != 0 then Left(s"expected major type 0, got $major")
      else if additional < 24 then Right((additional.toLong, offset + 1))
      else if additional == 24 then Right(((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2))
      else if additional == 25 then
        val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(
          offset.toLong + 2
        ) & 0xff)
        Right((v.toLong, offset + 3))
      else if additional == 26 then
        val v =
          ((bytes(offset.toLong + 1) & 0xff).toLong << 24) |
            ((bytes(offset.toLong + 2) & 0xff).toLong << 16) |
            ((bytes(offset.toLong + 3) & 0xff).toLong << 8) |
            (bytes(offset.toLong + 4) & 0xff).toLong
        Right((v, offset + 5))
      else Left(s"unsupported additional info: $additional")

  /** Build a MsgProposeVersions for N2N with versions 11-13. */
  def handshakeClient(networkMagic: Long): HandshakeMessage =
    val versions = (11 to 13).map { v =>
      v -> n2nVersionData(v, networkMagic)
    }.toMap
    HandshakeMessage.MsgProposeVersions(versions)
