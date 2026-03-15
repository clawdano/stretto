package stretto.network

import scodec.bits.ByteVector
import stretto.core.Crypto
import stretto.core.Types.*

/**
 * Extracts minimal metadata from era-wrapped block headers.
 *
 * Era-wrapped format from ChainSync N2N:
 *   - Byron (era 0): [0, [[sub_tag, param], tag24(headerBytes)]]
 *   - Shelley+ (era 1-6): [era_tag, tag24(headerBytes)]
 *
 * Byron has an inner array with a sub-tag (0=EBB, 1=main block).
 * Shelley+ eras wrap the header directly in tag24.
 *
 * Block hash = Blake2b-256 of the CBOR bytes inside tag24.
 */
object HeaderParser:

  /** Parsed header metadata — just enough to store and index. */
  final case class HeaderMeta(
      era: Int,
      slotNo: SlotNo,
      blockHash: BlockHeaderHash
  )

  /**
   * Parse an era-wrapped header to extract era, slot, and block hash.
   */
  def parse(wrappedHeader: ByteVector): Either[String, HeaderMeta] =
    for
      (arrLen, afterArr) <- readArrayHeader(wrappedHeader, 0)
      _                  <- if arrLen == 2 then Right(()) else Left(s"expected 2-element era wrapper, got $arrLen")
      (eraTag, afterEra) <- readUInt(wrappedHeader, afterArr)
      result <-
        if eraTag == 0 then parseByron(wrappedHeader, afterEra)
        else parseShelleyPlus(wrappedHeader, afterEra, eraTag.toInt)
    yield result

  /** Byron: [0, [[sub_tag, param], tag24(headerBytes)]] */
  private def parseByron(bytes: ByteVector, offset: Int): Either[String, HeaderMeta] =
    for
      (innerLen, afterInnerArr) <- readArrayHeader(bytes, offset)
      _                <- if innerLen == 2 then Right(()) else Left(s"expected 2-element inner wrapper, got $innerLen")
      (_, afterIdArr)  <- readArrayHeader(bytes, afterInnerArr)
      (subTag, _)      <- readUInt(bytes, afterIdArr)
      (_, afterIdItem) <- skipItem(bytes, afterInnerArr)
      (headerBytes, _) <- readTag24ByteString(bytes, afterIdItem)
      // Byron header hash requires prepending a CBOR wrapper before hashing:
      //   EBB (subTag 0): hash(0x82 0x00 ++ headerBytes)
      //   Regular (subTag 1): hash(0x82 0x01 ++ headerBytes)
      // This matches the original cardano-sl encoding where the block type
      // discriminator was part of the hashed bytes.
      wrappedForHash = ByteVector(0x82.toByte, subTag.toByte) ++ headerBytes
      blockHash      = Crypto.blake2b256(wrappedForHash)
      bh             = BlockHeaderHash(Hash32.unsafeFrom(blockHash))
      // subTag 0 = EBB, subTag 1 = main block
      effectiveEra = subTag.toInt
      slotNo <- extractSlot(effectiveEra, headerBytes)
    yield HeaderMeta(effectiveEra, slotNo, bh)

  /** Shelley+ (era 1-6): [era_tag, tag24(headerBytes)] */
  private def parseShelleyPlus(bytes: ByteVector, offset: Int, eraTag: Int): Either[String, HeaderMeta] =
    for
      (headerBytes, _) <- readTag24ByteString(bytes, offset)
      blockHash = Crypto.blake2b256(headerBytes)
      bh        = BlockHeaderHash(Hash32.unsafeFrom(blockHash))
      // eraTag in wire format: 1=Shelley, 2=Allegra, 3=Mary, 4=Alonzo, 5=Babbage, 6=Conway
      // We map to effective era 2+ for Shelley+ slot extraction
      effectiveEra = eraTag + 1
      slotNo <- extractSlot(effectiveEra, headerBytes)
    yield HeaderMeta(effectiveEra, slotNo, bh)

  // ---------------------------------------------------------------------------
  // Slot extraction per era
  // ---------------------------------------------------------------------------

  private def extractSlot(effectiveEra: Int, headerBytes: ByteVector): Either[String, SlotNo] =
    effectiveEra match
      case 0 =>
        // Byron EBB — epoch boundary block. EBB has no slot; derive from epoch.
        // EBB header = [protocolMagicId, prevBlock, bodyProof, consensusData, extraData]
        // consensusData for EBB = [epoch_id, chain_difficulty]
        // Convention: EBB slot = epoch * 21600 (Byron slots per epoch on preprod/mainnet)
        extractByronEbbSlot(headerBytes)
      case 1 =>
        // Byron main block: header[3] = consensus_data
        // consensus_data = [[epoch, slot], pubKey, difficulty, signature...]
        extractByronSlot(headerBytes)
      case _ =>
        // Shelley+: header[0] = header_body, header_body[1] = slot
        extractShelleySlot(headerBytes)

  /** Byron EBB: header[3] = consensusData = [epochId, chainDifficulty]. */
  private def extractByronEbbSlot(headerBytes: ByteVector): Either[String, SlotNo] =
    for
      (_, afterArr)   <- readArrayHeader(headerBytes, 0)
      (_, afterElem0) <- skipItem(headerBytes, afterArr)   // protocol_magic
      (_, afterElem1) <- skipItem(headerBytes, afterElem0) // prev_block
      (_, afterElem2) <- skipItem(headerBytes, afterElem1) // body_proof
      // consensus_data = [epochId, chainDifficulty]
      (_, afterCdArr) <- readArrayHeader(headerBytes, afterElem2)
      (epochId, _)    <- readUInt(headerBytes, afterCdArr)
    yield
      // Convention: EBB slot = epoch * 21600 (Byron epoch length)
      SlotNo(epochId * 21600L)

  /** Byron main: header[3] = consensus_data, consensus_data[0] = [epoch, slot]. */
  private def extractByronSlot(headerBytes: ByteVector): Either[String, SlotNo] =
    for
      (_, afterArr)   <- readArrayHeader(headerBytes, 0)
      (_, afterElem0) <- skipItem(headerBytes, afterArr)   // protocol_magic
      (_, afterElem1) <- skipItem(headerBytes, afterElem0) // prev_block
      (_, afterElem2) <- skipItem(headerBytes, afterElem1) // body_proof
      // consensus_data = [[epoch, slot], pubKey, difficulty, ...]
      (_, afterCdArr) <- readArrayHeader(headerBytes, afterElem2)
      // First element is slotId = [epoch, slot]
      (_, afterSlotIdArr) <- readArrayHeader(headerBytes, afterCdArr)
      (_, afterEpoch)     <- skipItem(headerBytes, afterSlotIdArr) // epoch
      (slot, _)           <- readUInt(headerBytes, afterEpoch)     // slot
    yield SlotNo(slot)

  /** Shelley+: header[0] = header_body array, header_body[1] = slot. */
  private def extractShelleySlot(headerBytes: ByteVector): Either[String, SlotNo] =
    for
      (_, afterOuterArr) <- readArrayHeader(headerBytes, 0)             // [header_body, sig]
      (_, afterBodyArr)  <- readArrayHeader(headerBytes, afterOuterArr) // header_body array
      (_, afterBlockNo)  <- skipItem(headerBytes, afterBodyArr)         // block_number
      (slot, _)          <- readUInt(headerBytes, afterBlockNo)         // slot
    yield SlotNo(slot)

  // ---------------------------------------------------------------------------
  // Low-level CBOR reading helpers
  // ---------------------------------------------------------------------------

  private def readUInt(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 0 then Left(s"expected uint at offset $offset, got major $major")
      else readArgument(bytes, offset, ai)

  private def readArrayHeader(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 4 then Left(s"expected array at offset $offset, got major $major")
      else readArgument(bytes, offset, ai)

  private def readArgument(bytes: ByteVector, offset: Int, ai: Int): Either[String, (Long, Int)] =
    if ai < 24 then Right((ai.toLong, offset + 1))
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

  /** Read tag24 wrapping a byte string: tag(24) followed by bstr. */
  private def readTag24ByteString(
      bytes: ByteVector,
      offset: Int
  ): Either[String, (ByteVector, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      if major == 6 then
        // Tag — skip the tag number, then read byte string
        val ai = b & 0x1f
        val afterTag =
          if ai < 24 then offset + 1
          else if ai == 24 then offset + 2
          else if ai == 25 then offset + 3
          else if ai == 26 then offset + 5
          else offset + 9
        readByteString(bytes, afterTag)
      else if major == 2 then
        // Bare byte string (tag24 omitted)
        readByteString(bytes, offset)
      else Left(s"expected tag or bstr at offset $offset, got major $major")

  private def readByteString(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 2 then Left(s"expected bstr at offset $offset, got major $major")
      else
        val (len, dataStart) =
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
          else return Left(s"unsupported bstr additional info: $ai")
        Right((bytes.slice(dataStart.toLong, dataStart.toLong + len), (dataStart + len.toInt)))

  /**
   * Skip one CBOR item. Returns (rawBytes, nextOffset).
   * Handles all major types recursively.
   */
  private def skipItem(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f

      def argAndOffset: (Long, Int) =
        if ai < 24 then (ai.toLong, offset + 1)
        else if ai == 24 then ((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2)
        else if ai == 25 then
          (((bytes(offset.toLong + 1) & 0xff) << 8 | (bytes(offset.toLong + 2) & 0xff)).toLong, offset + 3)
        else if ai == 26 then
          (
            ((bytes(offset.toLong + 1) & 0xff).toLong << 24) |
              ((bytes(offset.toLong + 2) & 0xff).toLong << 16) |
              ((bytes(offset.toLong + 3) & 0xff).toLong << 8) |
              (bytes(offset.toLong + 4) & 0xff).toLong,
            offset + 5
          )
        else if ai == 27 then
          (
            ((bytes(offset.toLong + 1) & 0xff).toLong << 56) |
              ((bytes(offset.toLong + 2) & 0xff).toLong << 48) |
              ((bytes(offset.toLong + 3) & 0xff).toLong << 40) |
              ((bytes(offset.toLong + 4) & 0xff).toLong << 32) |
              ((bytes(offset.toLong + 5) & 0xff).toLong << 24) |
              ((bytes(offset.toLong + 6) & 0xff).toLong << 16) |
              ((bytes(offset.toLong + 7) & 0xff).toLong << 8) |
              (bytes(offset.toLong + 8) & 0xff).toLong,
            offset + 9
          )
        else (0L, offset + 1)

      major match
        case 0 | 1 | 7 =>
          val (_, next) = argAndOffset
          Right((bytes.slice(offset.toLong, next.toLong), next))
        case 2 | 3 =>
          val (len, dataStart) = argAndOffset
          val end              = dataStart + len.toInt
          Right((bytes.slice(offset.toLong, end.toLong), end))
        case 4 =>
          val (count, after) = argAndOffset
          var cursor         = after
          var i              = 0L
          while i < count do
            skipItem(bytes, cursor) match
              case Right((_, next)) => cursor = next
              case Left(err)        => return Left(err)
            i += 1
          Right((bytes.slice(offset.toLong, cursor.toLong), cursor))
        case 5 =>
          val (count, after) = argAndOffset
          var cursor         = after
          var i              = 0L
          while i < count * 2 do
            skipItem(bytes, cursor) match
              case Right((_, next)) => cursor = next
              case Left(err)        => return Left(err)
            i += 1
          Right((bytes.slice(offset.toLong, cursor.toLong), cursor))
        case 6 =>
          val (_, after) = argAndOffset
          skipItem(bytes, after).map { case (_, end) =>
            (bytes.slice(offset.toLong, end.toLong), end)
          }
        case _ => Left(s"unknown CBOR major type $major")
