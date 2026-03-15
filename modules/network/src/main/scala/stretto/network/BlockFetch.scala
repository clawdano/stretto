package stretto.network

import scodec.bits.ByteVector
import stretto.core.Point
import stretto.core.Types.*

/**
 * BlockFetch mini-protocol messages (N2N).
 *
 * Wire format: each message is a CBOR array whose first element is the tag.
 * See ouroboros-network spec section 6 and block-fetch.cddl.
 *
 * State machine:
 *   StIdle (Client)  → MsgRequestRange → StBusy (Server)
 *   StIdle (Client)  → MsgClientDone   → StDone (Nobody)
 *   StBusy (Server)  → MsgStartBatch   → StStreaming (Server)
 *   StBusy (Server)  → MsgNoBlocks     → StIdle (Client)
 *   StStreaming (Svr) → MsgBlock        → StStreaming (Server)
 *   StStreaming (Svr) → MsgBatchDone    → StIdle (Client)
 */
enum BlockFetchMessage:
  /** Client → Server: request blocks in range [from, to] inclusive. Tag 0. */
  case MsgRequestRange(from: Point.BlockPoint, to: Point.BlockPoint)

  /** Client → Server: terminate protocol. Tag 1. */
  case MsgClientDone

  /** Server → Client: batch is starting. Tag 2. */
  case MsgStartBatch

  /** Server → Client: requested range not available. Tag 3. */
  case MsgNoBlocks

  /** Server → Client: one block. Tag 4, wrapped in tag24. */
  case MsgBlock(blockData: ByteVector)

  /** Server → Client: batch complete. Tag 5. */
  case MsgBatchDone

object BlockFetchMessage:

  // ---------------------------------------------------------------------------
  // CBOR encoding helpers (same pattern as ChainSync)
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
      else if len < 65536 then ByteVector(0x59.toByte) ++ ByteVector.fromShort(len.toShort, size = 2)
      else if len < 0x100000000L then ByteVector(0x5a.toByte) ++ ByteVector.fromInt(len.toInt, size = 4)
      else ByteVector(0x5b.toByte) ++ ByteVector.fromLong(len, size = 8)
    hdr ++ bv

  // ---------------------------------------------------------------------------
  // Point encoding: BlockPoint = [slotNo, hash]
  // ---------------------------------------------------------------------------

  private def encodeBlockPoint(p: Point.BlockPoint): ByteVector =
    cborArrayHeader(2) ++ cborUInt(p.slotNo.value) ++ cborByteString(p.blockHash.toHash32.hash32Bytes)

  // ---------------------------------------------------------------------------
  // Encode
  // ---------------------------------------------------------------------------

  def encode(msg: BlockFetchMessage): ByteVector = msg match
    case BlockFetchMessage.MsgRequestRange(from, to) =>
      cborArrayHeader(3) ++ cborUInt(0) ++ encodeBlockPoint(from) ++ encodeBlockPoint(to)

    case BlockFetchMessage.MsgClientDone =>
      cborArrayHeader(1) ++ cborUInt(1)

    case BlockFetchMessage.MsgStartBatch =>
      cborArrayHeader(1) ++ cborUInt(2)

    case BlockFetchMessage.MsgNoBlocks =>
      cborArrayHeader(1) ++ cborUInt(3)

    case BlockFetchMessage.MsgBlock(blockData) =>
      // [4, tag24(blockData)]
      val tag24 = ByteVector(0xd8.toByte, 0x18.toByte) ++ cborByteString(blockData)
      cborArrayHeader(2) ++ cborUInt(4) ++ tag24

    case BlockFetchMessage.MsgBatchDone =>
      cborArrayHeader(1) ++ cborUInt(5)

  // ---------------------------------------------------------------------------
  // Decode
  // ---------------------------------------------------------------------------

  def decode(bytes: ByteVector): Either[String, BlockFetchMessage] =
    if bytes.isEmpty then Left("empty payload")
    else
      for
        (_, afterArr)   <- readArrayHeader(bytes, 0)
        (tag, afterTag) <- readUInt(bytes, afterArr)
        result <- tag match
          case 0 => decodeRequestRange(bytes, afterTag)
          case 1 => Right(BlockFetchMessage.MsgClientDone)
          case 2 => Right(BlockFetchMessage.MsgStartBatch)
          case 3 => Right(BlockFetchMessage.MsgNoBlocks)
          case 4 => decodeMsgBlock(bytes, afterTag)
          case 5 => Right(BlockFetchMessage.MsgBatchDone)
          case _ => Left(s"unknown BlockFetch tag: $tag")
      yield result

  // --- MsgRequestRange: [0, point_from, point_to] ---
  private def decodeRequestRange(
      bytes: ByteVector,
      offset: Int
  ): Either[String, BlockFetchMessage] =
    for
      (from, afterFrom) <- decodeBlockPoint(bytes, offset)
      (to, _)           <- decodeBlockPoint(bytes, afterFrom)
    yield BlockFetchMessage.MsgRequestRange(from, to)

  // --- MsgBlock: [4, tag24(blockBytes)] ---
  // The block is wrapped in CBOR tag 24 (encoded CBOR data item).
  // Inside tag24 is a byte string containing the era-wrapped block.
  private def decodeMsgBlock(
      bytes: ByteVector,
      offset: Int
  ): Either[String, BlockFetchMessage] =
    // Read tag 24
    readTag(bytes, offset).flatMap { case (tagNum, afterTag) =>
      if tagNum != 24 then Left(s"expected CBOR tag 24, got $tagNum")
      else
        readByteString(bytes, afterTag).map { case (blockData, _) =>
          BlockFetchMessage.MsgBlock(blockData)
        }
    }

  // ---------------------------------------------------------------------------
  // BlockPoint decoding: [slotNo, hash]
  // ---------------------------------------------------------------------------

  private def decodeBlockPoint(bytes: ByteVector, offset: Int): Either[String, (Point.BlockPoint, Int)] =
    for
      (arrLen, afterArr) <- readArrayHeader(bytes, offset)
      _                  <- if arrLen == 2 then Right(()) else Left(s"expected 2-element point array, got $arrLen")
      (slot, afterSlot)  <- readUInt(bytes, afterArr)
      (hash, afterHash)  <- readByteString(bytes, afterSlot)
    yield
      val bh = BlockHeaderHash(Hash32.unsafeFrom(hash))
      (Point.BlockPoint(SlotNo(slot), bh), afterHash)

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

  private def readTag(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input reading tag")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 6 then Left(s"expected CBOR tag (major 6) at offset $offset, got major $major")
      else if ai < 24 then Right((ai.toLong, offset + 1))
      else if ai == 24 then Right(((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2))
      else if ai == 25 then
        val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
        Right((v.toLong, offset + 3))
      else Left(s"unsupported tag additional info: $ai")
