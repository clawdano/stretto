package stretto.network

import cats.effect.IO
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
      if firstByte < 0x80 || firstByte > 0x9f then
        Left(s"expected CBOR array, got 0x${String.format("%02x", bytes(0))}")
      else
        val (_, afterArrayHdr) =
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

  // ---------------------------------------------------------------------------
  // N2C version constants and encoding
  // ---------------------------------------------------------------------------

  /** N2C versions use bit-15 offset: V16=32784 (0x8010) through V19=32787 (0x8013). */
  val N2C_V16: Int = 32784
  val N2C_V17: Int = 32785
  val N2C_V18: Int = 32786
  val N2C_V19: Int = 32787

  val n2cVersions: Set[Int] = Set(N2C_V16, N2C_V17, N2C_V18, N2C_V19)

  /**
   * Build N2C version data.
   *
   * CBOR array: [networkMagic, query_bool] — 2 fields (N2N has 4).
   */
  def n2cVersionData(networkMagic: Long): ByteVector =
    cborArrayHeader(2) ++ cborUInt(networkMagic) ++ cborBool(false)

  /**
   * Decode a MsgProposeVersions from raw CBOR bytes.
   * Returns the version map: version number → version data bytes.
   */
  def decodeProposeVersions(bytes: ByteVector): Either[String, Map[Int, ByteVector]] =
    if bytes.isEmpty then Left("empty payload")
    else
      for
        (_, afterArr)   <- readArrayHeader(bytes, 0)
        (tag, afterTag) <- readCborUInt(bytes, afterArr)
        _               <- if tag == 0 then Right(()) else Left(s"expected tag 0 (MsgProposeVersions), got $tag")
        result          <- readVersionMap(bytes, afterTag)
      yield result

  private def readArrayHeader(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input reading array header")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 4 then Left(s"expected CBOR array at offset $offset, got major $major")
      else if ai < 24 then Right((ai.toLong, offset + 1))
      else if ai == 24 then Right(((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2))
      else if ai == 25 then
        val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
        Right((v.toLong, offset + 3))
      else Left(s"unsupported array header additional info: $ai")

  private def readMapHeader(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input reading map header")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 5 then Left(s"expected CBOR map at offset $offset, got major $major")
      else if ai < 24 then Right((ai.toLong, offset + 1))
      else if ai == 24 then Right(((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2))
      else if ai == 25 then
        val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
        Right((v.toLong, offset + 3))
      else Left(s"unsupported map header additional info: $ai")

  /** Skip one complete CBOR item, returning the offset after it. */
  private def skipCborItem(bytes: ByteVector, offset: Int): Either[String, Int] =
    if offset >= bytes.size then Left("need more data")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f

      readArgAndOffset(bytes, offset, ai) match
        case Left(err) => Left(err)
        case Right((arg, nextOffset)) =>
          major match
            case 0 | 1 | 7 => Right(nextOffset)
            case 2 | 3 =>
              val end = nextOffset + arg.toInt
              if end > bytes.size then Left("need more data")
              else Right(end)
            case 4 =>
              var cursor = nextOffset
              var i      = 0L
              while i < arg do
                skipCborItem(bytes, cursor) match
                  case Right(next) => cursor = next
                  case Left(err)   => return Left(err)
                i += 1
              Right(cursor)
            case 5 =>
              var cursor = nextOffset
              var i      = 0L
              while i < arg * 2 do
                skipCborItem(bytes, cursor) match
                  case Right(next) => cursor = next
                  case Left(err)   => return Left(err)
                i += 1
              Right(cursor)
            case 6 =>
              skipCborItem(bytes, nextOffset)
            case _ => Left(s"unknown major type $major")

  private def readArgAndOffset(bytes: ByteVector, offset: Int, ai: Int): Either[String, (Long, Int)] =
    if ai < 24 then Right((ai.toLong, offset + 1))
    else if ai == 24 then
      if offset + 2 > bytes.size then Left("need more data")
      else Right(((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2))
    else if ai == 25 then
      if offset + 3 > bytes.size then Left("need more data")
      else
        val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
        Right((v.toLong, offset + 3))
    else if ai == 26 then
      if offset + 5 > bytes.size then Left("need more data")
      else
        val v =
          ((bytes(offset.toLong + 1) & 0xff).toLong << 24) |
            ((bytes(offset.toLong + 2) & 0xff).toLong << 16) |
            ((bytes(offset.toLong + 3) & 0xff).toLong << 8) |
            (bytes(offset.toLong + 4) & 0xff).toLong
        Right((v, offset + 5))
    else if ai == 27 then
      if offset + 9 > bytes.size then Left("need more data")
      else
        val v =
          ((bytes(offset.toLong + 1) & 0xff).toLong << 56) |
            ((bytes(offset.toLong + 2) & 0xff).toLong << 48) |
            ((bytes(offset.toLong + 3) & 0xff).toLong << 40) |
            ((bytes(offset.toLong + 4) & 0xff).toLong << 32) |
            ((bytes(offset.toLong + 5) & 0xff).toLong << 24) |
            ((bytes(offset.toLong + 6) & 0xff).toLong << 16) |
            ((bytes(offset.toLong + 7) & 0xff).toLong << 8) |
            (bytes(offset.toLong + 8) & 0xff).toLong
        Right((v, offset + 9))
    else Left(s"unsupported additional info: $ai")

  private def readVersionMap(bytes: ByteVector, offset: Int): Either[String, Map[Int, ByteVector]] =
    for
      (count, afterMap) <- readMapHeader(bytes, offset)
      result <- (0 until count.toInt).foldLeft[Either[String, (Map[Int, ByteVector], Int)]](
        Right((Map.empty, afterMap))
      ) { case (acc, _) =>
        acc.flatMap { case (m, cursor) =>
          for
            (key, afterKey) <- readCborUInt(bytes, cursor)
            afterValue      <- skipCborItem(bytes, afterKey)
          yield (m + (key.toInt -> bytes.slice(afterKey.toLong, afterValue.toLong)), afterValue)
        }
      }
    yield result._1

  /**
   * Server-side N2C handshake: receive MsgProposeVersions, negotiate, send MsgAcceptVersion.
   *
   * Returns the accepted version number.
   */
  def handshakeN2CServer(mux: MuxDemuxer, networkMagic: Long): IO[Int] =
    import cats.effect.IO
    for
      payload <- mux.recvProtocol(MiniProtocolId.Handshake.id)
      versions <- IO.fromEither(
        decodeProposeVersions(payload).left.map(e => new RuntimeException(s"N2C handshake decode error: $e"))
      )
      mutualVersions = versions.keySet.intersect(n2cVersions)
      _ <- IO.raiseWhen(mutualVersions.isEmpty)(
        new RuntimeException(s"No mutual N2C version found. Client proposed: ${versions.keySet.mkString(", ")}")
      )
      acceptedVersion = mutualVersions.max
      versionData     = n2cVersionData(networkMagic)
      response        = encode(HandshakeMessage.MsgAcceptVersion(acceptedVersion, versionData))
      _ <- mux.sendResponse(MiniProtocolId.Handshake.id, response)
    yield acceptedVersion

  /** Build a MsgProposeVersions for N2N with versions 11-14. */
  def handshakeClient(networkMagic: Long): HandshakeMessage =
    val versions = (11 to 14).map { v =>
      v -> n2nVersionData(v, networkMagic)
    }.toMap
    HandshakeMessage.MsgProposeVersions(versions)
