package stretto.network

import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*

/**
 * ChainSync mini-protocol messages (N2N).
 *
 * Wire format: each message is a CBOR array whose first element is the tag.
 * See ouroboros-network spec for details.
 */
enum ChainSyncMessage:
  /** Client → Server: request the next header. Tag 0. */
  case MsgRequestNext

  /** Server → Client: no data yet, wait. Tag 1. */
  case MsgAwaitReply

  /** Server → Client: here is the next header. Tag 2. */
  case MsgRollForward(header: ByteVector, tip: Tip)

  /** Server → Client: rollback to this point. Tag 3. */
  case MsgRollBackward(point: Point, tip: Tip)

  /** Client → Server: find our common intersection. Tag 4. */
  case MsgFindIntersect(points: List[Point])

  /** Server → Client: intersection found. Tag 5. */
  case MsgIntersectFound(point: Point, tip: Tip)

  /** Server → Client: no intersection found. Tag 6. */
  case MsgIntersectNotFound(tip: Tip)

  /** Client → Server: terminate protocol. Tag 7. */
  case MsgDone

object ChainSyncMessage:

  // ---------------------------------------------------------------------------
  // CBOR encoding helpers (reuse the same low-level approach as Handshake)
  // ---------------------------------------------------------------------------

  private def cborUInt(n: Long): ByteVector =
    if n < 24 then ByteVector(n.toByte)
    else if n < 256 then ByteVector(0x18.toByte, n.toByte)
    else if n < 65536 then ByteVector(0x19.toByte) ++ ByteVector.fromShort(n.toShort, size = 2)
    else if n < 0x100000000L then ByteVector(0x1a.toByte) ++ ByteVector.fromInt(n.toInt, size = 4)
    else ByteVector(0x1b.toByte) ++ ByteVector.fromLong(n, size = 8)

  private def cborArrayHeader(len: Int): ByteVector =
    if len < 24 then ByteVector((0x80 | len).toByte)
    else ByteVector(0x98.toByte, len.toByte)

  private def cborByteString(bv: ByteVector): ByteVector =
    val len = bv.size
    val hdr =
      if len < 24 then ByteVector((0x40 | len.toInt).toByte)
      else if len < 256 then ByteVector(0x58.toByte, len.toByte)
      else ByteVector(0x59.toByte) ++ ByteVector.fromShort(len.toShort, size = 2)
    hdr ++ bv

  // ---------------------------------------------------------------------------
  // Point encoding: Origin = [] , BlockPoint = [slotNo, hash]
  // ---------------------------------------------------------------------------

  private def encodePoint(p: Point): ByteVector = p match
    case Point.Origin =>
      cborArrayHeader(0) // empty array
    case Point.BlockPoint(slotNo, blockHash) =>
      cborArrayHeader(2) ++ cborUInt(slotNo.value) ++ cborByteString(blockHash.toHash32.hash32Bytes)

  // ---------------------------------------------------------------------------
  // Tip encoding: [point, blockNo]
  // ---------------------------------------------------------------------------

  private def encodeTip(t: Tip): ByteVector =
    cborArrayHeader(2) ++ encodePoint(t.point) ++ cborUInt(t.blockNo.blockNoValue)

  // ---------------------------------------------------------------------------
  // Encode
  // ---------------------------------------------------------------------------

  def encode(msg: ChainSyncMessage): ByteVector = msg match
    case ChainSyncMessage.MsgRequestNext =>
      cborArrayHeader(1) ++ cborUInt(0)

    case ChainSyncMessage.MsgAwaitReply =>
      cborArrayHeader(1) ++ cborUInt(1)

    case ChainSyncMessage.MsgRollForward(header, tip) =>
      // [2, tag24(header), tip]
      val tag24 = ByteVector(0xd8.toByte, 0x18.toByte) ++ cborByteString(header)
      cborArrayHeader(3) ++ cborUInt(2) ++ tag24 ++ encodeTip(tip)

    case ChainSyncMessage.MsgRollBackward(point, tip) =>
      cborArrayHeader(3) ++ cborUInt(3) ++ encodePoint(point) ++ encodeTip(tip)

    case ChainSyncMessage.MsgFindIntersect(points) =>
      val pointsBytes = points.foldLeft(ByteVector.empty)((acc, p) => acc ++ encodePoint(p))
      cborArrayHeader(2) ++ cborUInt(4) ++ cborArrayHeader(points.size) ++ pointsBytes

    case ChainSyncMessage.MsgIntersectFound(point, tip) =>
      cborArrayHeader(3) ++ cborUInt(5) ++ encodePoint(point) ++ encodeTip(tip)

    case ChainSyncMessage.MsgIntersectNotFound(tip) =>
      cborArrayHeader(2) ++ cborUInt(6) ++ encodeTip(tip)

    case ChainSyncMessage.MsgDone =>
      cborArrayHeader(1) ++ cborUInt(7)

  // ---------------------------------------------------------------------------
  // Decode
  // ---------------------------------------------------------------------------

  def decode(bytes: ByteVector): Either[String, ChainSyncMessage] =
    if bytes.isEmpty then Left("empty payload")
    else
      for
        (_, afterArr)   <- readArrayHeader(bytes, 0)
        (tag, afterTag) <- readUInt(bytes, afterArr)
        result <- tag match
          case 0 => Right(ChainSyncMessage.MsgRequestNext)
          case 1 => Right(ChainSyncMessage.MsgAwaitReply)
          case 2 => decodeRollForward(bytes, afterTag)
          case 3 => decodeRollBackward(bytes, afterTag)
          case 4 => decodeFindIntersect(bytes, afterTag)
          case 5 => decodeIntersectFound(bytes, afterTag)
          case 6 => decodeIntersectNotFound(bytes, afterTag)
          case 7 => Right(ChainSyncMessage.MsgDone)
          case _ => Left(s"unknown ChainSync tag: $tag")
      yield result

  // --- Roll forward: [2, wrappedHeader, tip] ---
  // The header is era-wrapped in N2N: [era, tag24(headerBytes)]
  // We capture the entire CBOR item as raw bytes for downstream decoding.
  private def decodeRollForward(
      bytes: ByteVector,
      offset: Int
  ): Either[String, ChainSyncMessage] =
    for
      (header, afterHeader) <- skipCborItem(bytes, offset)
      (tip, _)              <- decodeTip(bytes, afterHeader)
    yield ChainSyncMessage.MsgRollForward(header, tip)

  // --- Roll backward: [3, point, tip] ---
  private def decodeRollBackward(
      bytes: ByteVector,
      offset: Int
  ): Either[String, ChainSyncMessage] =
    for
      (point, afterPoint) <- decodePoint(bytes, offset)
      (tip, afterTip)     <- decodeTip(bytes, afterPoint)
    yield ChainSyncMessage.MsgRollBackward(point, tip)

  // --- Find intersect: [4, [point, ...]] ---
  private def decodeFindIntersect(
      bytes: ByteVector,
      offset: Int
  ): Either[String, ChainSyncMessage] =
    for
      (count, afterArr) <- readArrayHeader(bytes, offset)
      (points, _)       <- readPoints(bytes, afterArr, count.toInt)
    yield ChainSyncMessage.MsgFindIntersect(points)

  // --- Intersect found: [5, point, tip] ---
  private def decodeIntersectFound(
      bytes: ByteVector,
      offset: Int
  ): Either[String, ChainSyncMessage] =
    for
      (point, afterPoint) <- decodePoint(bytes, offset)
      (tip, afterTip)     <- decodeTip(bytes, afterPoint)
    yield ChainSyncMessage.MsgIntersectFound(point, tip)

  // --- Intersect not found: [6, tip] ---
  private def decodeIntersectNotFound(
      bytes: ByteVector,
      offset: Int
  ): Either[String, ChainSyncMessage] =
    for (tip, _) <- decodeTip(bytes, offset)
    yield ChainSyncMessage.MsgIntersectNotFound(tip)

  // ---------------------------------------------------------------------------
  // Decoding primitives
  // ---------------------------------------------------------------------------

  private def readUInt(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input reading uint")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 0 then Left(s"expected major type 0 at offset $offset, got $major")
      else if ai < 24 then Right((ai.toLong, offset + 1))
      else if ai == 24 then Right(((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2))
      else if ai == 25 then
        val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
        Right((v.toLong, offset + 3))
      else if ai == 26 then
        val v =
          ((bytes(offset.toLong + 1) & 0xff).toLong << 24) |
            ((bytes(offset.toLong + 2) & 0xff).toLong << 16) |
            ((bytes(offset.toLong + 3) & 0xff).toLong << 8) |
            (bytes(offset.toLong + 4) & 0xff).toLong
        Right((v, offset + 5))
      else if ai == 27 then
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

  private def readArrayHeader(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input reading array header")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 4 then Left(s"expected CBOR array (major 4) at offset $offset, got major $major")
      else if ai < 24 then Right((ai.toLong, offset + 1))
      else if ai == 24 then Right(((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2))
      else if ai == 25 then
        val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
        Right((v.toLong, offset + 3))
      else Left(s"unsupported array header additional info: $ai")

  private def readByteString(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    if offset >= bytes.size then Left("unexpected end of input reading byte string")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 2 then Left(s"expected byte string (major 2) at offset $offset, got major $major")
      else
        val (len, dataOffset) =
          if ai < 24 then (ai.toLong, offset + 1)
          else if ai == 24 then ((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2)
          else if ai == 25 then
            val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
            (v.toLong, offset + 3)
          else if ai == 26 then
            val v =
              ((bytes(offset.toLong + 1) & 0xff).toLong << 24) |
                ((bytes(offset.toLong + 2) & 0xff).toLong << 16) |
                ((bytes(offset.toLong + 3) & 0xff).toLong << 8) |
                (bytes(offset.toLong + 4) & 0xff).toLong
            (v, offset + 5)
          else return Left(s"unsupported byte string additional info: $ai")
        Right((bytes.slice(dataOffset.toLong, dataOffset.toLong + len), (dataOffset.toLong + len).toInt))

  /**
   * Skip one complete CBOR item starting at offset.
   * Returns the raw bytes of the item and the offset after it.
   */
  private def skipCborItem(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    if offset >= bytes.size then Left("unexpected end of input skipping CBOR item")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f

      // Read the argument (length, count, or value) following the initial byte
      def argAndOffset: (Long, Int) =
        if ai < 24 then (ai.toLong, offset + 1)
        else if ai == 24 then ((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2)
        else if ai == 25 then
          val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
          (v.toLong, offset + 3)
        else if ai == 26 then
          val v =
            ((bytes(offset.toLong + 1) & 0xff).toLong << 24) |
              ((bytes(offset.toLong + 2) & 0xff).toLong << 16) |
              ((bytes(offset.toLong + 3) & 0xff).toLong << 8) |
              (bytes(offset.toLong + 4) & 0xff).toLong
          (v, offset + 5)
        else if ai == 27 then
          val v =
            ((bytes(offset.toLong + 1) & 0xff).toLong << 56) |
              ((bytes(offset.toLong + 2) & 0xff).toLong << 48) |
              ((bytes(offset.toLong + 3) & 0xff).toLong << 40) |
              ((bytes(offset.toLong + 4) & 0xff).toLong << 32) |
              ((bytes(offset.toLong + 5) & 0xff).toLong << 24) |
              ((bytes(offset.toLong + 6) & 0xff).toLong << 16) |
              ((bytes(offset.toLong + 7) & 0xff).toLong << 8) |
              (bytes(offset.toLong + 8) & 0xff).toLong
          (v, offset + 9)
        else (0L, offset + 1) // fallback

      major match
        case 0 | 1 | 7 => // uint, negint, simple — just the head
          val (_, next) = argAndOffset
          Right((bytes.slice(offset.toLong, next.toLong), next))
        case 2 | 3 => // byte string, text string — head + N bytes
          val (len, dataStart) = argAndOffset
          val end              = dataStart + len.toInt
          Right((bytes.slice(offset.toLong, end.toLong), end))
        case 4 => // array — head + N items
          val (count, after) = argAndOffset
          var cursor         = after
          var i              = 0L
          while i < count do
            skipCborItem(bytes, cursor) match
              case Right((_, next)) => cursor = next
              case Left(err)        => return Left(err)
            i += 1
          Right((bytes.slice(offset.toLong, cursor.toLong), cursor))
        case 5 => // map — head + 2*N items
          val (count, after) = argAndOffset
          var cursor         = after
          var i              = 0L
          while i < count * 2 do
            skipCborItem(bytes, cursor) match
              case Right((_, next)) => cursor = next
              case Left(err)        => return Left(err)
            i += 1
          Right((bytes.slice(offset.toLong, cursor.toLong), cursor))
        case 6 => // tag — head + 1 item
          val (_, after) = argAndOffset
          skipCborItem(bytes, after).map { case (_, end) =>
            (bytes.slice(offset.toLong, end.toLong), end)
          }
        case _ => Left(s"unknown CBOR major type $major at offset $offset")

  // ---------------------------------------------------------------------------
  // Point decoding: [] = Origin, [slotNo, hash] = BlockPoint
  // ---------------------------------------------------------------------------

  private def decodePoint(bytes: ByteVector, offset: Int): Either[String, (Point, Int)] =
    for (arrLen, afterArr) <- readArrayHeader(bytes, offset)
    yield
      if arrLen == 0 then (Point.Origin, afterArr)
      else
        // BlockPoint: [slotNo, hash]
        val Right((slot, afterSlot)) = readUInt(bytes, afterArr): @unchecked
        val Right((hash, afterHash)) = readByteString(bytes, afterSlot): @unchecked
        val bh                       = BlockHeaderHash(Hash32.unsafeFrom(hash))
        (Point.BlockPoint(SlotNo(slot), bh), afterHash)

  // ---------------------------------------------------------------------------
  // Tip decoding: [point, blockNo]
  // ---------------------------------------------------------------------------

  private def decodeTip(bytes: ByteVector, offset: Int): Either[String, (Tip, Int)] =
    for
      (arrLen, afterArr)  <- readArrayHeader(bytes, offset)
      _                   <- if arrLen == 2 then Right(()) else Left(s"expected 2-element tip array, got $arrLen")
      (point, afterPoint) <- decodePoint(bytes, afterArr)
      (blkNo, afterBlk)   <- readUInt(bytes, afterPoint)
    yield (Tip(point, BlockNo(blkNo)), afterBlk)

  private def readPoints(
      bytes: ByteVector,
      offset: Int,
      count: Int
  ): Either[String, (List[Point], Int)] =
    (0 until count)
      .foldLeft[Either[String, (List[Point], Int)]](Right((Nil, offset))) {
        case (Left(err), _) => Left(err)
        case (Right((acc, cursor)), _) =>
          decodePoint(bytes, cursor).map { case (p, next) =>
            (acc :+ p, next)
          }
      }
