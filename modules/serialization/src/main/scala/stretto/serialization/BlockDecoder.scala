package stretto.serialization

import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

/**
 * Decodes era-wrapped Cardano blocks from raw CBOR bytes.
 *
 * Wire format: [era_tag, block_cbor] where block_cbor is inline CBOR.
 *   - Byron (tag 0): EBB — [header, body]
 *   - Byron (tag 1): main — [header, body, extra]
 *   - Shelley+ (tag 2-6): [header, txBodies, txWitnesses, auxData, ...]
 *
 * Uses manual CBOR parsing (no scodec) for performance and flexibility
 * with the complex nested Cardano block structures.
 *
 * Supports both definite-length and indefinite-length CBOR encoding
 * (additional info = 31, terminated by 0xFF break byte).
 */
object BlockDecoder:

  // Sentinel value indicating indefinite-length CBOR collection
  private val IndefiniteLength: Long = -1L

  // CBOR break byte (0xFF) terminates indefinite-length collections
  private val BreakByte: Int = 0xff

  /**
   * Decode only the header from an era-wrapped header (as received from ChainSync N2N).
   *
   * Wire format from ChainSync:
   *   - Byron (era 0): [0, [[sub_tag, param], tag24(headerBytes)]]
   *   - Shelley+ (era 1-6): [era_tag, tag24(headerBytes)]
   *
   * The tag24 wraps the raw header CBOR. For Shelley+ headers, we unwrap tag24,
   * then decode the header using the same logic as full block decoding.
   *
   * @param wrappedHeader the era-wrapped header bytes from ChainSync
   * @return Left for Byron (era <= 1), Right((era, header)) for Shelley+
   */
  def decodeHeaderOnly(wrappedHeader: ByteVector): Either[String, (Era, ShelleyHeader)] =
    for
      (arrLen, afterArr) <- readArrayHeader(wrappedHeader, 0)
      _                  <- require(arrLen == 2, s"expected 2-element era wrapper, got $arrLen")
      (eraTag, afterEra) <- readUInt(wrappedHeader, afterArr)
      result <- eraTag.toInt match
        case 0 => Left("Byron era headers predate Praos — skipping")
        case t if t >= 1 && t <= 6 =>
          val era = (t + 1) match
            case 2 => Era.Shelley
            case 3 => Era.Allegra
            case 4 => Era.Mary
            case 5 => Era.Alonzo
            case 6 => Era.Babbage
            case 7 => Era.Conway
            case _ => Era.Conway
          // Read tag24-wrapped header bytes
          readTag24ByteString(wrappedHeader, afterEra).flatMap { case (headerBytes, _) =>
            decodeShelleyHeader(headerBytes, 0, era).map { case (_, header) => (era, header) }
          }
        case t => Left(s"unknown era tag: $t")
    yield result

  /** Decode an era-wrapped block from hex-encoded CBOR. */
  def decodeHex(hex: String): Either[String, Block] =
    ByteVector.fromHex(hex) match
      case Some(bytes) => decode(bytes)
      case None        => Left("Invalid hex string")

  /** Decode an era-wrapped block: [era_tag, block_cbor]. */
  def decode(bytes: ByteVector): Either[String, Block] =
    for
      (arrLen, afterArr) <- readArrayHeader(bytes, 0)
      _                  <- require(arrLen == 2, s"expected 2-element era wrapper, got $arrLen")
      (eraTag, afterEra) <- readUInt(bytes, afterArr)
      block <- eraTag.toInt match
        case 0 => decodeByronEb(bytes, afterEra)
        case 1 => decodeByronMain(bytes, afterEra)
        case t if t >= 2 && t <= 6 =>
          val era = t match
            case 2 => Era.Shelley
            case 3 => Era.Allegra
            case 4 => Era.Mary
            case 5 => Era.Alonzo
            case 6 => Era.Babbage
            case _ => Era.Conway
          decodeShelleyBlockSequential(bytes, afterEra, era)
        case 7 => decodeShelleyBlockSequential(bytes, afterEra, Era.Conway)
        case t => Left(s"unknown era tag: $t")
    yield block

  // ---------------------------------------------------------------------------
  // Byron EBB: [header, body]
  // header = [protocolMagic, prevBlock, bodyProof, consensusData, extraData]
  // consensusData = [epochId, chainDifficulty]
  // ---------------------------------------------------------------------------

  private def decodeByronEb(bytes: ByteVector, offset: Int): Either[String, Block] =
    for
      (outerLen, afterOuter) <- readArrayHeader(bytes, offset)
      // EBB has 3 elements in some encodings, 2 in others; handle both
      (headerEnd, header) <- decodeByronEbHeader(bytes, afterOuter)
      bodyBytes           <- extractRawBytes(bytes, headerEnd)
    yield Block.ByronEbBlock(header, bodyBytes._1)

  private def decodeByronEbHeader(bytes: ByteVector, offset: Int): Either[String, (Int, ByronEbHeader)] =
    for
      (hdrLen, afterHdr)   <- readArrayHeader(bytes, offset)
      (magic, afterMagic)  <- readUInt(bytes, afterHdr)
      (prevBlk, afterPrev) <- readHash32(bytes, afterMagic)
      (proof, afterProof)  <- readHash32(bytes, afterPrev)
      // consensus data = [epochId, chainDifficulty]
      (_, afterCdArr)       <- readArrayHeader(bytes, afterProof)
      (epochId, afterEpoch) <- readUInt(bytes, afterCdArr)
      // chain difficulty is wrapped: [difficulty]
      (_, afterDiffArr) <- readArrayHeader(bytes, afterEpoch)
      (diff, afterDiff) <- readUInt(bytes, afterDiffArr)
      // skip extraData
      (_, afterExtra) <- skipItem(bytes, afterDiff)
    yield (afterExtra, ByronEbHeader(magic, prevBlk, proof, EpochNo(epochId), diff))

  // ---------------------------------------------------------------------------
  // Byron main: [header, body, extra]
  // header = [protocolMagic, prevBlock, bodyProof, consensusData, extraData]
  // body = [txPayload, sscPayload, dlgPayload, updPayload]
  // txPayload = [[tx1_tagged, tx2_tagged, ...]]
  // each tx_tagged = [tag24(tx), [witnesses...]]
  // tx = [inputs, outputs, attributes]
  // ---------------------------------------------------------------------------

  private def decodeByronMain(bytes: ByteVector, offset: Int): Either[String, Block] =
    for
      (outerLen, afterOuter) <- readArrayHeader(bytes, offset)
      (headerEnd, header)    <- decodeByronMainHeader(bytes, afterOuter)
      (bodyEnd, body)        <- decodeByronBody(bytes, headerEnd)
      (extraBytes, _)        <- extractRawBytes(bytes, bodyEnd)
    yield Block.ByronBlock(header, body, extraBytes)

  private def decodeByronMainHeader(bytes: ByteVector, offset: Int): Either[String, (Int, ByronHeader)] =
    for
      (hdrLen, afterHdr)   <- readArrayHeader(bytes, offset)
      (magic, afterMagic)  <- readUInt(bytes, afterHdr)
      (prevBlk, afterPrev) <- readHash32(bytes, afterMagic)
      (proof, afterProof)  <- skipItem(bytes, afterPrev) // body proof is complex, skip
      // consensus data = [[epoch, slot], pubKey, difficulty, signature]
      (_, afterCdArr) <- readArrayHeader(bytes, afterProof)
      // slot id = [epoch, slot]
      (_, afterSlotIdArr) <- readArrayHeader(bytes, afterCdArr)
      (_, afterEpoch)     <- readUInt(bytes, afterSlotIdArr) // epoch
      (slot, afterSlot)   <- readUInt(bytes, afterEpoch)     // slot
      // skip rest of consensus data and extra data
      (_, afterSlotId)    <- skipItem(bytes, afterCdArr)    // skip whole slotId
      (_, afterPubKey)    <- skipItem(bytes, afterSlotId)   // pubkey
      (_, afterDiffItem)  <- skipItem(bytes, afterPubKey)   // difficulty
      (_, afterSig)       <- skipItem(bytes, afterDiffItem) // signature
      (_, afterExtraData) <- skipItem(bytes, afterSig)      // extra header data
    yield (
      afterExtraData,
      ByronHeader(
        magic,
        prevBlk,
        Hash32.unsafeFrom(ByteVector.fill(32)(0)), // body proof skipped
        SlotNo(slot),
        0L // difficulty skipped
      )
    )

  private def decodeByronBody(bytes: ByteVector, offset: Int): Either[String, (Int, ByronBody)] =
    for
      (bodyLen, afterBodyArr) <- readArrayHeader(bytes, offset)
      // body = [txPayload, sscPayload, dlgPayload, updPayload]
      // txPayload = [[tx_with_witness, ...]]
      (txs, afterTxPayload) <- decodeByronTxPayload(bytes, afterBodyArr)
      // skip remaining payloads
      (_, afterSsc) <- skipItem(bytes, afterTxPayload)
      (_, afterDlg) <- skipItem(bytes, afterSsc)
      (_, afterUpd) <- skipItem(bytes, afterDlg)
    yield (afterUpd, ByronBody(txs))

  private def decodeByronTxPayload(bytes: ByteVector, offset: Int): Either[String, (Vector[ByronTx], Int)] =
    for
      (payloadLen, afterPayload) <- readArrayHeader(bytes, offset)
      result <-
        if payloadLen == IndefiniteLength then
          // Indefinite-length array: read until break byte
          readItemsUntilBreak(bytes, afterPayload) { (pos, acc: Vector[ByronTx]) =>
            decodeByronTxWithWitness(bytes, pos).map { case (tx, nextPos) =>
              (acc :+ tx, nextPos)
            }
          }(Vector.empty[ByronTx])
        else if payloadLen == 0 then Right((Vector.empty[ByronTx], afterPayload))
        else
          (0 until payloadLen.toInt).foldLeft(
            Right((Vector.empty[ByronTx], afterPayload)): Either[String, (Vector[ByronTx], Int)]
          ) {
            case (Left(err), _) => Left(err)
            case (Right((txs, pos)), _) =>
              decodeByronTxWithWitness(bytes, pos).map { case (tx, nextPos) =>
                (txs :+ tx, nextPos)
              }
          }
    yield result

  private def decodeByronTxWithWitness(bytes: ByteVector, offset: Int): Either[String, (ByronTx, Int)] =
    for
      // [tx_body, witnesses]
      (pairLen, afterPairArr) <- readArrayHeader(bytes, offset)
      // tx_body can be either:
      //   - tag24(cbor_bytes) containing the tx as embedded CBOR, OR
      //   - a direct CBOR array [inputs, outputs, attributes]
      // Check what we have at this position
      txResult <-
        val b     = bytes(afterPairArr.toLong) & 0xff
        val major = b >> 5
        if major == 6 || major == 2 then
          // tag24-wrapped or bare byte string — read embedded CBOR
          for
            (txBytes, afterTx) <- readTag24ByteString(bytes, afterPairArr)
            tx                 <- decodeByronTxBody(txBytes)
            // Hash is over the tag24/bstr bytes
            tag24Raw = bytes.slice(afterPairArr.toLong, afterTx.toLong)
            txHash   = TxHash(Hash32.unsafeFrom(Crypto.blake2b256(tag24Raw)))
          yield (tx._1, tx._2, txHash, afterTx)
        else
          // Direct CBOR array — tx body is inline
          // Capture raw bytes for hashing
          val txStart = afterPairArr
          for
            tx               <- decodeByronTxBody(bytes.slice(txStart.toLong, bytes.size))
            (_, afterTxBody) <- skipItem(bytes, txStart)
            // For inline tx bodies, the hash is blake2b256 of the raw CBOR of the tx body
            txRaw  = bytes.slice(txStart.toLong, afterTxBody.toLong)
            txHash = TxHash(Hash32.unsafeFrom(Crypto.blake2b256(txRaw)))
          yield (tx._1, tx._2, txHash, afterTxBody)
      (inputs, outputs, txHash, afterTx) = txResult
      // Skip witnesses
      (_, afterWitnesses) <- skipItem(bytes, afterTx)
    yield (ByronTx(txHash, inputs, outputs), afterWitnesses)

  private def decodeByronTxBody(txBytes: ByteVector): Either[String, (Vector[ByronTxIn], Vector[ByronTxOut])] =
    for
      // tx = [inputs, outputs, attributes]
      (txLen, afterTxArr) <- readArrayHeader(txBytes, 0)
      // inputs = [[tag, [txId, index]], ...]
      (inputs, afterInputs) <- decodeByronInputs(txBytes, afterTxArr)
      // outputs = [[address, amount], ...]
      (outputs, afterOutputs) <- decodeByronOutputs(txBytes, afterInputs)
    yield (inputs, outputs)

  private def decodeByronInputs(bytes: ByteVector, offset: Int): Either[String, (Vector[ByronTxIn], Int)] =
    for
      (inputCount, afterArr) <- readArrayHeader(bytes, offset)
      result <-
        if inputCount == IndefiniteLength then
          readItemsUntilBreak(bytes, afterArr) { (pos, acc: Vector[ByronTxIn]) =>
            decodeByronSingleInput(bytes, pos).map { case (inp, next) => (acc :+ inp, next) }
          }(Vector.empty[ByronTxIn])
        else
          (0 until inputCount.toInt).foldLeft(
            Right((Vector.empty[ByronTxIn], afterArr)): Either[String, (Vector[ByronTxIn], Int)]
          ) {
            case (Left(err), _) => Left(err)
            case (Right((inputs, pos)), _) =>
              decodeByronSingleInput(bytes, pos).map { case (inp, next) => (inputs :+ inp, next) }
          }
    yield result

  private def decodeByronSingleInput(bytes: ByteVector, pos: Int): Either[String, (ByronTxIn, Int)] =
    for
      (_, afterInArr)        <- readArrayHeader(bytes, pos)
      (_, afterTag)          <- readUInt(bytes, afterInArr) // tag byte (always 0)
      (inBytes, afterTagged) <- readTag24ByteString(bytes, afterTag)
      // Parse [txId, index] from inner bytes
      (_, afterInnerArr) <- readArrayHeader(inBytes, 0)
      (txId, afterTxId)  <- readHash32(inBytes, afterInnerArr)
      (idx, _)           <- readUInt(inBytes, afterTxId)
    yield (ByronTxIn(TxHash(txId), idx), afterTagged)

  private def decodeByronOutputs(bytes: ByteVector, offset: Int): Either[String, (Vector[ByronTxOut], Int)] =
    for
      (outputCount, afterArr) <- readArrayHeader(bytes, offset)
      result <-
        if outputCount == IndefiniteLength then
          readItemsUntilBreak(bytes, afterArr) { (pos, acc: Vector[ByronTxOut]) =>
            decodeByronSingleOutput(bytes, pos).map { case (out, next) => (acc :+ out, next) }
          }(Vector.empty[ByronTxOut])
        else
          (0 until outputCount.toInt).foldLeft(
            Right((Vector.empty[ByronTxOut], afterArr)): Either[String, (Vector[ByronTxOut], Int)]
          ) {
            case (Left(err), _) => Left(err)
            case (Right((outputs, pos)), _) =>
              decodeByronSingleOutput(bytes, pos).map { case (out, next) => (outputs :+ out, next) }
          }
    yield result

  private def decodeByronSingleOutput(bytes: ByteVector, pos: Int): Either[String, (ByronTxOut, Int)] =
    for
      (_, afterOutArr) <- readArrayHeader(bytes, pos)
      // Address is a complex CBOR structure — capture as raw bytes
      (addrRaw, afterAddr) <- extractRawBytes(bytes, afterOutArr)
      (amount, afterAmt)   <- readUInt(bytes, afterAddr)
    yield (ByronTxOut(addrRaw, Lovelace(amount)), afterAmt)

  // ---------------------------------------------------------------------------
  // Shelley+ blocks: [header, txBodies, txWitnesses, auxData, invalidTxs?]
  // header = [[header_body], sig]
  // header_body = [blockNo, slot, prevHash, issuerVkey, vrfVkey,
  //                nonce_vrf, leader_vrf, bodySize, bodyHash,
  //                operational_cert, protocol_version]
  // txBodies = [body1, body2, ...] — each body is a CBOR map
  // ---------------------------------------------------------------------------

  /** Parse Shelley+ block sequentially: header, txBodies, txWitnesses, auxData, [invalidTxs]. */
  private def decodeShelleyBlockSequential(
      bytes: ByteVector,
      offset: Int,
      era: Era
  ): Either[String, Block] =
    for
      // Outer block array
      (blockLen, afterBlockArr) <- readArrayHeader(bytes, offset)
      resolvedBlockLen = if blockLen == IndefiniteLength then 5 else blockLen.toInt
      // 1. Header
      (afterHeader, header) <- decodeShelleyHeader(bytes, afterBlockArr, era)
      // 2. Transaction bodies (array of CBOR maps)
      (afterBodies, txBodies) <- decodeShelleyTxBodies(bytes, afterHeader)
      // 3. Witnesses (array of witness sets — parse vkey witnesses, skip rest)
      (afterWitnesses, witnesses) <- decodeTxWitnessSets(bytes, afterBodies)
      // 4. Auxiliary data (map of index -> aux, skip)
      (_, afterAux) <- skipItem(bytes, afterWitnesses)
      // 5. Invalid transactions (Alonzo+ only, optional)
      invalidTxs <-
        if resolvedBlockLen >= 5 then readInvalidTxs(bytes, afterAux)
        else Right(Vector.empty[Int])
    yield Block.ShelleyBlock(
      era = era,
      header = header,
      txBodies = txBodies,
      txWitnesses = witnesses.map(encodeWitnessSetRaw),
      auxiliaryData = Vector.fill(txBodies.size)(None),
      invalidTxs = invalidTxs
    )

  /** Encode a TxWitnessSet back to a placeholder raw ByteVector (empty for now). */
  private def encodeWitnessSetRaw(@annotation.unused ws: TxWitnessSet): ByteVector = ByteVector.empty

  // ---------------------------------------------------------------------------
  // Witness set decoding
  // ---------------------------------------------------------------------------

  /** Decode an array of transaction witness sets. */
  private def decodeTxWitnessSets(
      bytes: ByteVector,
      offset: Int
  ): Either[String, (Int, Vector[TxWitnessSet])] =
    readArrayHeader(bytes, offset).flatMap { case (count, afterArr) =>
      if count == IndefiniteLength then
        readItemsUntilBreak(bytes, afterArr) { (p, acc: Vector[TxWitnessSet]) =>
          decodeWitnessSet(bytes, p).map { case (ws, next) => (acc :+ ws, next) }
        }(Vector.empty).map(_.swap)
      else
        (0 until count.toInt).foldLeft(
          Right((afterArr, Vector.empty[TxWitnessSet])): Either[String, (Int, Vector[TxWitnessSet])]
        ) {
          case (Left(err), _) => Left(err)
          case (Right((pos, acc)), _) =>
            decodeWitnessSet(bytes, pos).map { case (ws, next) => (next, acc :+ ws) }
        }
    }

  /**
   * Decode a single witness set (CBOR map with numeric keys).
   * Parses vkey witnesses (key 0) fully, captures other keys as raw CBOR.
   */
  def decodeWitnessSet(bytes: ByteVector, offset: Int): Either[String, (TxWitnessSet, Int)] =
    readMapHeader(bytes, offset).flatMap { case (mapLen, afterMap) =>
      parseWitnessMapEntries(bytes, afterMap, mapLen)
    }

  /** Parse witness map entries iteratively without non-local returns. */
  private def parseWitnessMapEntries(
      bytes: ByteVector,
      offset: Int,
      mapLen: Long
  ): Either[String, (TxWitnessSet, Int)] =
    var pos                               = offset
    var vkeys                             = Vector.empty[VkeyWitness]
    var bootstrapWit: Option[ByteVector]  = None
    var nativeScripts: Option[ByteVector] = None
    var plutusV1: Option[ByteVector]      = None
    var plutusData: Option[ByteVector]    = None
    var redeemers: Option[ByteVector]     = None
    var plutusV2: Option[ByteVector]      = None
    var plutusV3: Option[ByteVector]      = None
    var error: Option[String]             = None

    def parseEntry(): Unit =
      if error.isDefined then ()
      else
        readUInt(bytes, pos) match
          case Left(err) => error = Some(err)
          case Right((key, afterKey)) =>
            key.toInt match
              case 0 => // vkey witnesses
                decodeVkeyWitnessArray(bytes, afterKey) match
                  case Left(err)          => error = Some(err)
                  case Right((vks, next)) => vkeys = vks; pos = next
              case 1 => // native scripts
                extractRawBytes(bytes, afterKey) match
                  case Left(err)          => error = Some(err)
                  case Right((raw, next)) => nativeScripts = Some(raw); pos = next
              case 2 => // bootstrap witnesses
                extractRawBytes(bytes, afterKey) match
                  case Left(err)          => error = Some(err)
                  case Right((raw, next)) => bootstrapWit = Some(raw); pos = next
              case 3 => // plutus v1 scripts
                extractRawBytes(bytes, afterKey) match
                  case Left(err)          => error = Some(err)
                  case Right((raw, next)) => plutusV1 = Some(raw); pos = next
              case 4 => // plutus data (datums)
                extractRawBytes(bytes, afterKey) match
                  case Left(err)          => error = Some(err)
                  case Right((raw, next)) => plutusData = Some(raw); pos = next
              case 5 => // redeemers
                extractRawBytes(bytes, afterKey) match
                  case Left(err)          => error = Some(err)
                  case Right((raw, next)) => redeemers = Some(raw); pos = next
              case 6 => // plutus v2 scripts
                extractRawBytes(bytes, afterKey) match
                  case Left(err)          => error = Some(err)
                  case Right((raw, next)) => plutusV2 = Some(raw); pos = next
              case 7 => // plutus v3 scripts
                extractRawBytes(bytes, afterKey) match
                  case Left(err)          => error = Some(err)
                  case Right((raw, next)) => plutusV3 = Some(raw); pos = next
              case _ =>
                skipItem(bytes, afterKey) match
                  case Left(err)        => error = Some(err)
                  case Right((_, next)) => pos = next

    if mapLen == IndefiniteLength then
      while error.isEmpty && pos < bytes.size && (bytes(pos.toLong) & 0xff) != BreakByte do parseEntry()
      if error.isEmpty then pos += 1
    else
      var i = 0
      while error.isEmpty && i < mapLen.toInt do
        parseEntry()
        i += 1

    error match
      case Some(err) => Left(err)
      case None =>
        Right(
          (TxWitnessSet(vkeys, bootstrapWit, nativeScripts, plutusV1, plutusV2, plutusV3, plutusData, redeemers), pos)
        )

  /** Decode an array of VkeyWitnesses. */
  private def decodeVkeyWitnessArray(bytes: ByteVector, offset: Int): Either[String, (Vector[VkeyWitness], Int)] =
    readArrayHeader(bytes, offset).flatMap { case (count, afterArr) =>
      if count == IndefiniteLength then
        readItemsUntilBreak(bytes, afterArr) { (p, acc: Vector[VkeyWitness]) =>
          decodeVkeyWitness(bytes, p).map { case (vk, next) => (acc :+ vk, next) }
        }(Vector.empty)
      else
        (0 until count.toInt).foldLeft(
          Right((Vector.empty[VkeyWitness], afterArr)): Either[String, (Vector[VkeyWitness], Int)]
        ) {
          case (Left(err), _) => Left(err)
          case (Right((acc, p)), _) =>
            decodeVkeyWitness(bytes, p).map { case (vk, next) => (acc :+ vk, next) }
        }
    }

  /** Decode a single vkey witness: [vkey, signature] */
  private def decodeVkeyWitness(bytes: ByteVector, offset: Int): Either[String, (VkeyWitness, Int)] =
    for
      (arrLen, afterArr) <- readArrayHeader(bytes, offset)
      (vkey, afterVkey)  <- readByteString(bytes, afterArr)
      (sig, afterSig)    <- readByteString(bytes, afterVkey)
      afterEnd           <- if arrLen == IndefiniteLength then consumeBreak(bytes, afterSig) else Right(afterSig)
    yield (VkeyWitness(vkey, sig), afterEnd)

  // ---------------------------------------------------------------------------
  // Standalone transaction decoder (for N2C LocalTxSubmission)
  // ---------------------------------------------------------------------------

  /**
   * Decode a standalone transaction from raw CBOR.
   *
   * Conway wire format: [body, witnesses, isValid, auxiliaryData]
   * Where body and witnesses are inline CBOR (not tag24-wrapped).
   *
   * @param bytes raw transaction CBOR
   * @return decoded Transaction or error
   */
  def decodeTx(bytes: ByteVector): Either[String, Transaction] =
    for
      (arrLen, afterArr) <- readArrayHeader(bytes, 0)
      _                  <- require(arrLen == 4 || arrLen == IndefiniteLength, s"expected 4-element tx, got $arrLen")
      // 1. Transaction body
      (afterBody, txBody) <- decodeShelleyTxBody(bytes, afterArr)
      // 2. Witness set
      (witnesses, afterWit) <- decodeWitnessSet(bytes, afterBody)
      // 3. isValid (bool or uint 0/1)
      (isValid, afterValid) <- readIsValid(bytes, afterWit)
      // 4. Auxiliary data (skip, capture raw or null)
      (auxRaw, afterAux) <- extractOptionalRaw(bytes, afterValid)
      afterEnd           <- if arrLen == IndefiniteLength then consumeBreak(bytes, afterAux) else Right(afterAux)
    yield Transaction(
      body = txBody,
      witnesses = witnesses,
      isValid = isValid,
      auxiliaryData = auxRaw,
      rawTx = bytes
    )

  /** Decode a standalone transaction from hex CBOR. */
  def decodeTxHex(hex: String): Either[String, Transaction] =
    ByteVector.fromHex(hex) match
      case Some(bytes) => decodeTx(bytes)
      case None        => Left("Invalid hex string")

  /** Read isValid field — can be CBOR bool (simple value) or uint 0/1. */
  private def readIsValid(bytes: ByteVector, offset: Int): Either[String, (Boolean, Int)] =
    if offset >= bytes.size then Left("unexpected end reading isValid")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      if major == 7 then // simple value (bool)
        val ai = b & 0x1f
        if ai == 20 then Right((false, offset + 1))     // false
        else if ai == 21 then Right((true, offset + 1)) // true
        else Right((true, offset + 1))                  // other simple → true
      else                                              // uint
        readUInt(bytes, offset).map { case (v, next) => (v != 0, next) }

  /** Extract optional raw CBOR — None if CBOR null, Some(raw) otherwise. */
  private def extractOptionalRaw(bytes: ByteVector, offset: Int): Either[String, (Option[ByteVector], Int)] =
    if offset >= bytes.size then Right((None, offset))
    else
      val b = bytes(offset.toLong) & 0xff
      if b == 0xf6 then Right((None, offset + 1)) // CBOR null
      else extractRawBytes(bytes, offset).map { case (raw, next) => (Some(raw), next) }

  private def readInvalidTxs(bytes: ByteVector, offset: Int): Either[String, Vector[Int]] =
    // The invalid txs field may be absent at end of data or may be a break byte for indefinite block
    if offset >= bytes.size then Right(Vector.empty[Int])
    else
      val b = bytes(offset.toLong) & 0xff
      if b == BreakByte then Right(Vector.empty[Int]) // break byte ends indefinite block array
      else
        readArrayHeader(bytes, offset).flatMap { case (count, afterInvArr) =>
          if count == IndefiniteLength then
            readItemsUntilBreak(bytes, afterInvArr) { (pos, acc: Vector[Int]) =>
              readUInt(bytes, pos).map { case (v, next) => (acc :+ v.toInt, next) }
            }(Vector.empty[Int]).map(_._1)
          else
            (0 until count.toInt)
              .foldLeft(Right((Vector.empty[Int], afterInvArr)): Either[String, (Vector[Int], Int)]) {
                case (Left(err), _) => Left(err)
                case (Right((acc, pos)), _) =>
                  readUInt(bytes, pos).map { case (v, next) => (acc :+ v.toInt, next) }
              }
              .map(_._1)
        }

  /** Decode a VRF cert: 2-element array [proof, output]. */
  private def decodeVrfCert(bytes: ByteVector, offset: Int): Either[String, (VrfCert, Int)] =
    for
      (arrLen, afterArr) <- readArrayHeader(bytes, offset)
      _ <- require(arrLen == 2 || arrLen == IndefiniteLength, s"VRF cert expected 2-element array, got $arrLen")
      (output, afterOutput) <- readByteString(bytes, afterArr)
      (proof, afterProof)   <- readByteString(bytes, afterOutput)
      afterEnd              <- if arrLen == IndefiniteLength then consumeBreak(bytes, afterProof) else Right(afterProof)
    yield (VrfCert(proof = proof, output = output), afterEnd)

  /**
   * Decode an operational cert.
   * TPraos (Shelley-Alonzo): 4 flat fields in header body (hot_vkey, counter, kes_period, cold_sig).
   * Praos (Babbage+): 4-element array [hot_vkey, counter, kes_period, cold_sig].
   */
  private def decodeOperationalCertFlat(bytes: ByteVector, offset: Int): Either[String, (OperationalCert, Int)] =
    for
      (hotVkey, afterHot)      <- readByteString(bytes, offset)
      (counter, afterCounter)  <- readUInt(bytes, afterHot)
      (kesPeriod, afterPeriod) <- readUInt(bytes, afterCounter)
      (coldSig, afterSig)      <- readByteString(bytes, afterPeriod)
    yield (OperationalCert(hotVkey, counter, kesPeriod, coldSig), afterSig)

  private def decodeOperationalCertArray(bytes: ByteVector, offset: Int): Either[String, (OperationalCert, Int)] =
    for
      (arrLen, afterArr) <- readArrayHeader(bytes, offset)
      _ <- require(arrLen == 4 || arrLen == IndefiniteLength, s"OCert expected 4-element array, got $arrLen")
      (ocert, afterOcert) <- decodeOperationalCertFlat(bytes, afterArr)
      afterEnd            <- if arrLen == IndefiniteLength then consumeBreak(bytes, afterOcert) else Right(afterOcert)
    yield (ocert, afterEnd)

  /** Decode protocol version: Babbage+ as 2-element array, TPraos as 2 flat fields. */
  private def decodeProtocolVersionFlat(bytes: ByteVector, offset: Int): Either[String, ((Int, Int), Int)] =
    for
      (major, afterMajor) <- readUInt(bytes, offset)
      (minor, afterMinor) <- readUInt(bytes, afterMajor)
    yield ((major.toInt, minor.toInt), afterMinor)

  private def decodeProtocolVersionArray(bytes: ByteVector, offset: Int): Either[String, ((Int, Int), Int)] =
    for
      (arrLen, afterArr) <- readArrayHeader(bytes, offset)
      _ <- require(arrLen == 2 || arrLen == IndefiniteLength, s"ProtVer expected 2-element array, got $arrLen")
      (pv, afterPv) <- decodeProtocolVersionFlat(bytes, afterArr)
      afterEnd      <- if arrLen == IndefiniteLength then consumeBreak(bytes, afterPv) else Right(afterPv)
    yield (pv, afterEnd)

  private def decodeShelleyHeader(bytes: ByteVector, offset: Int, era: Era): Either[String, (Int, ShelleyHeader)] =
    for
      // header = [header_body, signature]
      (hdrOuterLen, afterHdrOuter) <- readArrayHeader(bytes, offset)
      // Record start of header_body for raw CBOR capture (for KES verification)
      hdrBodyStart = afterHdrOuter
      // header_body structure differs by era:
      //   Shelley/Allegra/Mary/Alonzo (era 2-5): 15 flat fields
      //     [blockNo, slot, prevHash, issuerVkey, vrfVkey,
      //      nonce_vrf, leader_vrf, bodySize, bodyHash,
      //      hot_vkey, seq_num, kes_period, sigma,
      //      protocol_major, protocol_minor]
      //   Babbage/Conway (era 6-7): 10 fields
      //     [blockNo, slot, prevHash, issuerVkey, vrfVkey,
      //      vrf_result, bodySize, bodyHash,
      //      operational_cert, protocol_version]
      (hdrBodyLen, afterHdrBody) <- readArrayHeader(bytes, afterHdrOuter)
      (blockNo, afterBlockNo)    <- readUInt(bytes, afterHdrBody)
      (slotNo, afterSlotNo)      <- readUInt(bytes, afterBlockNo)
      (prevHash, afterPrevHash)  <- readHash32(bytes, afterSlotNo)
      (issuerVkey, afterIssuer)  <- readByteString(bytes, afterPrevHash)
      (vrfVkey, afterVrfVkey)    <- readByteString(bytes, afterIssuer)
      // Era-specific VRF and remaining fields
      result <- era match
        case Era.Babbage | Era.Conway =>
          // Single vrf_result [output, proof], then bodySize, bodyHash, op_cert (array), prot_ver (array)
          for
            (vrfCert, afterVrf)       <- decodeVrfCert(bytes, afterVrfVkey)
            (bodySize, afterBodySize) <- readUInt(bytes, afterVrf)
            (bodyHash, afterBodyHash) <- readHash32(bytes, afterBodySize)
            (ocert, afterOpCert)      <- decodeOperationalCertArray(bytes, afterBodyHash)
            (protVer, afterProtVer)   <- decodeProtocolVersionArray(bytes, afterOpCert)
            afterHdrBodyEnd <-
              if hdrBodyLen == IndefiniteLength then consumeBreak(bytes, afterProtVer) else Right(afterProtVer)
          yield (afterHdrBodyEnd, bodySize, bodyHash, VrfResult.Praos(vrfCert), ocert, protVer)
        case _ =>
          // Two VRF certs (nonce_vrf, leader_vrf), then bodySize, bodyHash,
          // then 4 flat operational_cert fields, then 2 flat protocol_version fields
          for
            (nonceVrf, afterNonceVrf)   <- decodeVrfCert(bytes, afterVrfVkey)
            (leaderVrf, afterLeaderVrf) <- decodeVrfCert(bytes, afterNonceVrf)
            (bodySize, afterBodySize)   <- readUInt(bytes, afterLeaderVrf)
            (bodyHash, afterBodyHash)   <- readHash32(bytes, afterBodySize)
            (ocert, afterOpCert)        <- decodeOperationalCertFlat(bytes, afterBodyHash)
            (protVer, afterProtVer)     <- decodeProtocolVersionFlat(bytes, afterOpCert)
            afterHdrBodyEnd <-
              if hdrBodyLen == IndefiniteLength then consumeBreak(bytes, afterProtVer) else Right(afterProtVer)
          yield (afterHdrBodyEnd, bodySize, bodyHash, VrfResult.TPraos(nonceVrf, leaderVrf), ocert, protVer)
      (afterHdrBodyEnd, bodySize, bodyHash, vrfResult, ocert, protVer) = result
      // Capture raw header body CBOR (from hdrBodyStart to afterHdrBodyEnd)
      rawHeaderBody = bytes.slice(hdrBodyStart.toLong, afterHdrBodyEnd.toLong)
      // KES signature (byte string after header_body)
      (kesSignature, afterSignature) <- readByteString(bytes, afterHdrBodyEnd)
      // If header was indefinite-length, consume the break byte
      afterHdrEnd <-
        if hdrOuterLen == IndefiniteLength then consumeBreak(bytes, afterSignature) else Right(afterSignature)
    yield (
      afterHdrEnd,
      ShelleyHeader(
        blockNo = BlockNo(blockNo),
        slotNo = SlotNo(slotNo),
        prevHash = prevHash,
        issuerVkey = issuerVkey,
        vrfVkey = vrfVkey,
        vrfResult = vrfResult,
        blockBodySize = bodySize,
        blockBodyHash = bodyHash,
        ocert = ocert,
        protocolVersion = protVer,
        kesSignature = kesSignature,
        rawHeaderBody = rawHeaderBody
      )
    )

  private def decodeShelleyTxBodies(bytes: ByteVector, offset: Int): Either[String, (Int, Vector[TransactionBody])] =
    for
      (txCount, afterTxArr) <- readArrayHeader(bytes, offset)
      result <-
        if txCount == IndefiniteLength then
          readItemsUntilBreak(bytes, afterTxArr) { (pos, acc: Vector[TransactionBody]) =>
            decodeShelleyTxBody(bytes, pos).map { case (nextPos, body) =>
              (acc :+ body, nextPos)
            }
          }(Vector.empty[TransactionBody]).map(_.swap)
        else
          (0 until txCount.toInt).foldLeft(
            Right((afterTxArr, Vector.empty[TransactionBody])): Either[String, (Int, Vector[TransactionBody])]
          ) {
            case (Left(err), _) => Left(err)
            case (Right((pos, bodies)), _) =>
              decodeShelleyTxBody(bytes, pos).map { case (nextPos, body) =>
                (nextPos, bodies :+ body)
              }
          }
    yield result

  /** Intermediate state for tx body map parsing. */
  private final class TxBodyState:
    var inputs: Vector[TxInput]               = Vector.empty
    var outputs: Vector[TxOutput]             = Vector.empty
    var fee: Lovelace                         = Lovelace(0L)
    var ttl: Option[SlotNo]                   = None
    var validityIntervalStart: Option[SlotNo] = None
    var mint: Option[ByteVector]              = None
    var scriptDataHash: Option[Hash32]        = None
    var collateralInputs: Vector[TxInput]     = Vector.empty
    var requiredSigners: Vector[Hash28]       = Vector.empty
    var networkId: Option[Int]                = None
    var collateralReturn: Option[TxOutput]    = None
    var totalCollateral: Option[Lovelace]     = None
    var referenceInputs: Vector[TxInput]      = Vector.empty

  private def decodeShelleyTxBody(bytes: ByteVector, offset: Int): Either[String, (Int, TransactionBody)] =
    // tx_body is a CBOR map: {0: inputs, 1: outputs, 2: fee, 3: ttl?, ...}
    // Capture the raw CBOR for hashing, then parse key fields
    val rawStart = offset
    for
      (mapLen, afterMapHdr) <- readMapHeader(bytes, offset)
      parseResult           <- parseTxBodyMap(bytes, afterMapHdr, mapLen)
      (afterMap, st) = parseResult
      rawCbor        = bytes.slice(rawStart.toLong, afterMap.toLong)
    yield (
      afterMap,
      TransactionBody(
        inputs = st.inputs,
        outputs = st.outputs,
        fee = st.fee,
        ttl = st.ttl,
        validityIntervalStart = st.validityIntervalStart,
        mint = st.mint,
        scriptDataHash = st.scriptDataHash,
        collateralInputs = st.collateralInputs,
        requiredSigners = st.requiredSigners,
        networkId = st.networkId,
        collateralReturn = st.collateralReturn,
        totalCollateral = st.totalCollateral,
        referenceInputs = st.referenceInputs,
        rawCbor = rawCbor
      )
    )

  private def parseTxBodyMap(
      bytes: ByteVector,
      offset: Int,
      mapLen: Long
  ): Either[String, (Int, TxBodyState)] =
    var pos                   = offset
    val st                    = new TxBodyState
    var error: Option[String] = None

    if mapLen == IndefiniteLength then
      while error.isEmpty && pos < bytes.size && (bytes(pos.toLong) & 0xff) != BreakByte do
        parseTxBodyMapEntry(bytes, pos, st) match
          case Left(err)     => error = Some(err)
          case Right(newPos) => pos = newPos
      if error.isEmpty then
        if pos >= bytes.size then error = Some("unexpected end of input in indefinite map")
        else pos += 1
    else
      var i = 0
      while error.isEmpty && i < mapLen.toInt do
        parseTxBodyMapEntry(bytes, pos, st) match
          case Left(err)     => error = Some(err)
          case Right(newPos) => pos = newPos
        i += 1

    error match
      case Some(err) => Left(err)
      case None      => Right((pos, st))

  private def parseTxBodyMapEntry(
      bytes: ByteVector,
      pos: Int,
      st: TxBodyState
  ): Either[String, Int] =
    readUInt(bytes, pos) match
      case Left(err) => Left(err)
      case Right((key, afterKey)) =>
        key.toInt match
          case 0 => // inputs = set of [txId, index]
            decodeInputArray(bytes, afterKey).map { case (newInputs, next) =>
              st.inputs = newInputs
              next
            }
          case 1 => // outputs = [output, ...]
            decodeOutputArray(bytes, afterKey).map { case (newOutputs, next) =>
              st.outputs = newOutputs
              next
            }
          case 2 => // fee
            readUInt(bytes, afterKey).map { case (f, next) =>
              st.fee = Lovelace(f)
              next
            }
          case 3 => // ttl
            readUInt(bytes, afterKey).map { case (t, next) =>
              st.ttl = Some(SlotNo(t))
              next
            }
          case 8 => // validity_interval_start (Allegra+)
            readUInt(bytes, afterKey).map { case (s, next) =>
              st.validityIntervalStart = Some(SlotNo(s))
              next
            }
          case 9 => // mint (Mary+) — capture raw CBOR
            extractRawBytes(bytes, afterKey).map { case (raw, next) =>
              st.mint = Some(raw)
              next
            }
          case 11 => // script_data_hash (Alonzo+)
            readHash32(bytes, afterKey).map { case (h, next) =>
              st.scriptDataHash = Some(h)
              next
            }
          case 13 => // collateral inputs (Alonzo+)
            decodeInputArray(bytes, afterKey).map { case (inputs, next) =>
              st.collateralInputs = inputs
              next
            }
          case 14 => // required_signers (Alonzo+) — array of key hashes (28 bytes)
            readArrayHeader(bytes, afterKey).flatMap { case (count, afterArr) =>
              if count == IndefiniteLength then
                readItemsUntilBreak(bytes, afterArr) { (p, acc: Vector[Hash28]) =>
                  readByteString(bytes, p).map { case (bs, next) =>
                    (acc :+ Hash28.unsafeFrom(bs), next)
                  }
                }(Vector.empty).map { case (signers, next) =>
                  st.requiredSigners = signers
                  next
                }
              else
                (0 until count.toInt)
                  .foldLeft(
                    Right((Vector.empty[Hash28], afterArr)): Either[String, (Vector[Hash28], Int)]
                  ) {
                    case (Left(err), _) => Left(err)
                    case (Right((acc, p)), _) =>
                      readByteString(bytes, p).map { case (bs, next) =>
                        (acc :+ Hash28.unsafeFrom(bs), next)
                      }
                  }
                  .map { case (signers, next) =>
                    st.requiredSigners = signers
                    next
                  }
            }
          case 15 => // network_id (Alonzo+)
            readUInt(bytes, afterKey).map { case (n, next) =>
              st.networkId = Some(n.toInt)
              next
            }
          case 16 => // collateral_return (Babbage+)
            decodeTxOutput(bytes, afterKey).map { case (out, next) =>
              st.collateralReturn = Some(out)
              next
            }
          case 17 => // total_collateral (Babbage+)
            readUInt(bytes, afterKey).map { case (c, next) =>
              st.totalCollateral = Some(Lovelace(c))
              next
            }
          case 18 => // reference_inputs (Babbage+)
            decodeInputArray(bytes, afterKey).map { case (inputs, next) =>
              st.referenceInputs = inputs
              next
            }
          case _ => // skip unknown keys (certs, withdrawals, update, etc.)
            skipItem(bytes, afterKey).map { case (_, next) => next }

  /** Decode an array of TxInputs (used for inputs, collateral, reference inputs). */
  private def decodeInputArray(bytes: ByteVector, offset: Int): Either[String, (Vector[TxInput], Int)] =
    readArrayHeader(bytes, offset).flatMap { case (count, afterArr) =>
      if count == IndefiniteLength then
        readItemsUntilBreak(bytes, afterArr) { (p, acc: Vector[TxInput]) =>
          decodeTxInput(bytes, p).map { case (inp, next) => (acc :+ inp, next) }
        }(Vector.empty)
      else
        (0 until count.toInt).foldLeft(
          Right((Vector.empty[TxInput], afterArr)): Either[String, (Vector[TxInput], Int)]
        ) {
          case (Left(err), _) => Left(err)
          case (Right((acc, p)), _) =>
            decodeTxInput(bytes, p).map { case (inp, next) => (acc :+ inp, next) }
        }
    }

  /** Decode an array of TxOutputs. */
  private def decodeOutputArray(bytes: ByteVector, offset: Int): Either[String, (Vector[TxOutput], Int)] =
    readArrayHeader(bytes, offset).flatMap { case (count, afterArr) =>
      if count == IndefiniteLength then
        readItemsUntilBreak(bytes, afterArr) { (p, acc: Vector[TxOutput]) =>
          decodeTxOutput(bytes, p).map { case (out, next) => (acc :+ out, next) }
        }(Vector.empty)
      else
        (0 until count.toInt).foldLeft(
          Right((Vector.empty[TxOutput], afterArr)): Either[String, (Vector[TxOutput], Int)]
        ) {
          case (Left(err), _) => Left(err)
          case (Right((acc, p)), _) =>
            decodeTxOutput(bytes, p).map { case (out, next) => (acc :+ out, next) }
        }
    }

  private def decodeTxInput(bytes: ByteVector, offset: Int): Either[String, (TxInput, Int)] =
    for
      (_, afterArr)     <- readArrayHeader(bytes, offset)
      (txId, afterTxId) <- readHash32(bytes, afterArr)
      (idx, afterIdx)   <- readUInt(bytes, afterTxId)
    yield (TxInput(TxHash(txId), idx), afterIdx)

  private def decodeTxOutput(bytes: ByteVector, offset: Int): Either[String, (TxOutput, Int)] =
    // Output format varies by era:
    //   Shelley-Mary: [address, value]
    //   Alonzo: [address, value, datum_hash?]
    //   Babbage+: map {0: address, 1: value, ...} OR [address, value, ...]
    if offset >= bytes.size then Left("unexpected end of output")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      if major == 5 || (major == 5 && (b & 0x1f) == 31)
      then // map (Babbage+ post-Alonzo format), definite or indefinite
        decodeTxOutputMap(bytes, offset)
      else if major == 6 then // tagged output (Conway may use tags)
        // Skip the tag, then decode the output
        val ai = b & 0x1f
        readArgument(bytes, offset, ai).flatMap { case (_, afterTag) =>
          decodeTxOutput(bytes, afterTag)
        }
      else // array (Shelley through Alonzo)
        decodeTxOutputArray(bytes, offset)

  private def decodeTxOutputArray(bytes: ByteVector, offset: Int): Either[String, (TxOutput, Int)] =
    for
      (arrLen, afterArr)   <- readArrayHeader(bytes, offset)
      (addrRaw, afterAddr) <- extractRawBytes(bytes, afterArr)
      (value, afterVal)    <- decodeOutputValue(bytes, afterAddr)
      // Skip remaining fields (datum_hash, script_ref) if present
      afterRest <-
        if arrLen == IndefiniteLength then
          // Skip items until break
          skipUntilBreak(bytes, afterVal)
        else
          val remaining = arrLen.toInt - 2
          skipNItems(bytes, afterVal, remaining)
    yield (TxOutput(addrRaw, value), afterRest)

  private def decodeTxOutputMap(bytes: ByteVector, offset: Int): Either[String, (TxOutput, Int)] =
    for
      (mapLen, afterMap) <- readMapHeader(bytes, offset)
      result             <- parseTxOutputMapEntries(bytes, afterMap, mapLen)
    yield result

  private def parseTxOutputMapEntries(
      bytes: ByteVector,
      offset: Int,
      mapLen: Long
  ): Either[String, (TxOutput, Int)] =
    var pos                = offset
    var address            = ByteVector.empty
    var value: OutputValue = OutputValue.PureAda(Lovelace(0L))

    if mapLen == IndefiniteLength then
      while
        if pos >= bytes.size then return Left("unexpected end of input in indefinite map")
        val b = bytes(pos.toLong) & 0xff
        b != BreakByte
      do
        readUInt(bytes, pos) match
          case Left(err) => return Left(err)
          case Right((key, afterKey)) =>
            key.toInt match
              case 0 =>
                extractRawBytes(bytes, afterKey) match
                  case Left(err) => return Left(err)
                  case Right((raw, next)) =>
                    address = raw
                    pos = next
              case 1 =>
                decodeOutputValue(bytes, afterKey) match
                  case Left(err) => return Left(err)
                  case Right((v, next)) =>
                    value = v
                    pos = next
              case _ =>
                skipItem(bytes, afterKey) match
                  case Left(err)        => return Left(err)
                  case Right((_, next)) => pos = next
      pos += 1 // skip break byte
    else
      var i = 0
      while i < mapLen.toInt do
        readUInt(bytes, pos) match
          case Left(err) => return Left(err)
          case Right((key, afterKey)) =>
            key.toInt match
              case 0 => // address
                extractRawBytes(bytes, afterKey) match
                  case Left(err) => return Left(err)
                  case Right((raw, next)) =>
                    address = raw
                    pos = next
              case 1 => // value
                decodeOutputValue(bytes, afterKey) match
                  case Left(err) => return Left(err)
                  case Right((v, next)) =>
                    value = v
                    pos = next
              case _ =>
                skipItem(bytes, afterKey) match
                  case Left(err)        => return Left(err)
                  case Right((_, next)) => pos = next
        i += 1
    Right((TxOutput(address, value), pos))

  private def decodeOutputValue(bytes: ByteVector, offset: Int): Either[String, (OutputValue, Int)] =
    if offset >= bytes.size then Left("unexpected end of value")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      if major == 0 then // pure ADA (uint)
        readUInt(bytes, offset).map { case (coin, next) =>
          (OutputValue.PureAda(Lovelace(coin)), next)
        }
      else if major == 4 then // multi-asset: [coin, multiAssetMap]
        for
          (_, afterArr)            <- readArrayHeader(bytes, offset)
          (coin, afterCoin)        <- readUInt(bytes, afterArr)
          (assetsRaw, afterAssets) <- extractRawBytes(bytes, afterCoin)
        yield (OutputValue.MultiAsset(Lovelace(coin), assetsRaw), afterAssets)
      else if major == 6 then // tagged value (Conway)
        val ai = b & 0x1f
        readArgument(bytes, offset, ai).flatMap { case (_, afterTag) =>
          decodeOutputValue(bytes, afterTag)
        }
      else
        // Unknown format — skip and return zero
        skipItem(bytes, offset).map { case (_, next) =>
          (OutputValue.PureAda(Lovelace(0L)), next)
        }

  // ---------------------------------------------------------------------------
  // Low-level CBOR reading helpers (with indefinite-length support)
  // ---------------------------------------------------------------------------

  private def require(cond: Boolean, msg: String): Either[String, Unit] =
    if cond then Right(()) else Left(msg)

  /** Read a CBOR unsigned integer (major type 0). Also handles tags transparently. */
  private def readUInt(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left(s"unexpected end of input at offset $offset")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major == 6 then
        // Tag — skip it and read the uint inside
        readArgument(bytes, offset, ai).flatMap { case (_, afterTag) =>
          readUInt(bytes, afterTag)
        }
      else if major != 0 then Left(s"expected uint at offset $offset, got major $major")
      else readArgument(bytes, offset, ai)

  /**
   * Read a CBOR array header (major type 4).
   * Returns -1 for indefinite-length arrays (0x9F).
   */
  private def readArrayHeader(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left(s"unexpected end of input at offset $offset")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major == 6 then
        // Tag — skip it and read the array inside
        readArgument(bytes, offset, ai).flatMap { case (_, afterTag) =>
          readArrayHeader(bytes, afterTag)
        }
      else if major != 4 then Left(s"expected array at offset $offset, got major $major")
      else if ai == 31 then Right((IndefiniteLength, offset + 1)) // indefinite-length array (0x9F)
      else readArgument(bytes, offset, ai)

  /**
   * Read a CBOR map header (major type 5).
   * Returns -1 for indefinite-length maps (0xBF).
   */
  private def readMapHeader(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left(s"unexpected end of input at offset $offset")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major == 6 then
        // Tag — skip it and read the map inside
        readArgument(bytes, offset, ai).flatMap { case (_, afterTag) =>
          readMapHeader(bytes, afterTag)
        }
      else if major != 5 then Left(s"expected map at offset $offset, got major $major")
      else if ai == 31 then Right((IndefiniteLength, offset + 1)) // indefinite-length map (0xBF)
      else readArgument(bytes, offset, ai)

  private def readArgument(bytes: ByteVector, offset: Int, ai: Int): Either[String, (Long, Int)] =
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
    else if ai == 31 then Left(s"indefinite length not expected for this type at offset $offset")
    else Left(s"unsupported additional info: $ai at offset $offset")

  /** Read a CBOR byte string (major type 2), including indefinite-length. */
  private def readByteString(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major == 6 then
        // Tag — skip it and read the byte string inside
        readArgument(bytes, offset, ai).flatMap { case (_, afterTag) =>
          readByteString(bytes, afterTag)
        }
      else if major != 2 then Left(s"expected bstr at offset $offset, got major $major")
      else if ai == 31 then
        // Indefinite-length byte string: concatenate chunks until break
        readIndefiniteByteString(bytes, offset + 1)
      else
        readArgument(bytes, offset, ai).map { case (len, dataStart) =>
          (bytes.slice(dataStart.toLong, dataStart.toLong + len), dataStart + len.toInt)
        }

  /** Read chunks of an indefinite-length byte string until break byte. */
  private def readIndefiniteByteString(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    var pos    = offset
    var result = ByteVector.empty
    while
      if pos >= bytes.size then return Left("unexpected end in indefinite byte string")
      val b = bytes(pos.toLong) & 0xff
      b != BreakByte
    do
      val b     = bytes(pos.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 2 then return Left(s"expected bstr chunk at offset $pos, got major $major")
      readArgument(bytes, pos, ai) match
        case Left(err) => return Left(err)
        case Right((len, dataStart)) =>
          result = result ++ bytes.slice(dataStart.toLong, dataStart.toLong + len)
          pos = dataStart + len.toInt
    Right((result, pos + 1)) // +1 for break byte

  /** Read chunks of an indefinite-length text string until break byte. */
  private def readIndefiniteTextString(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    var pos    = offset
    var result = ByteVector.empty
    while
      if pos >= bytes.size then return Left("unexpected end in indefinite text string")
      val b = bytes(pos.toLong) & 0xff
      b != BreakByte
    do
      val b     = bytes(pos.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 3 then return Left(s"expected text chunk at offset $pos, got major $major")
      readArgument(bytes, pos, ai) match
        case Left(err) => return Left(err)
        case Right((len, dataStart)) =>
          result = result ++ bytes.slice(dataStart.toLong, dataStart.toLong + len)
          pos = dataStart + len.toInt
    Right((result, pos + 1)) // +1 for break byte

  private def readHash32(bytes: ByteVector, offset: Int): Either[String, (Hash32, Int)] =
    readByteString(bytes, offset).flatMap { case (bv, next) =>
      if bv.size == 32 then Right((Hash32.unsafeFrom(bv), next))
      else Left(s"expected 32-byte hash, got ${bv.size} bytes at offset $offset")
    }

  private def readTag24ByteString(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      if major == 6 then
        val ai = b & 0x1f
        readArgument(bytes, offset, ai).flatMap { case (_, afterTag) =>
          readByteString(bytes, afterTag)
        }
      else if major == 2 then readByteString(bytes, offset)
      else Left(s"expected tag or bstr at offset $offset, got major $major")

  /** Extract raw CBOR bytes for one item (without parsing it). */
  private def extractRawBytes(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    skipItem(bytes, offset).map { case (_, endOffset) =>
      (bytes.slice(offset.toLong, endOffset.toLong), endOffset)
    }

  /**
   * Skip one CBOR item, returning the next offset.
   * Handles all major types including indefinite-length encodings.
   */
  private def skipItem(bytes: ByteVector, offset: Int): Either[String, (Unit, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f

      major match
        case 0 | 1 => // unsigned int / negative int
          readArgument(bytes, offset, ai).map { case (_, next) => ((), next) }

        case 2 => // byte string
          if ai == 31 then
            // Indefinite-length byte string: skip chunks until break
            readIndefiniteByteString(bytes, offset + 1).map { case (_, next) => ((), next) }
          else readArgument(bytes, offset, ai).map { case (len, dataStart) => ((), dataStart + len.toInt) }

        case 3 => // text string
          if ai == 31 then
            // Indefinite-length text string: skip chunks until break
            readIndefiniteTextString(bytes, offset + 1).map { case (_, next) => ((), next) }
          else readArgument(bytes, offset, ai).map { case (len, dataStart) => ((), dataStart + len.toInt) }

        case 4 => // array
          if ai == 31 then
            // Indefinite-length array: skip items until break byte
            skipUntilBreak(bytes, offset + 1).map(next => ((), next))
          else
            readArgument(bytes, offset, ai).flatMap { case (count, after) =>
              skipNItems(bytes, after, count.toInt).map(next => ((), next))
            }

        case 5 => // map
          if ai == 31 then
            // Indefinite-length map: skip key-value pairs until break byte
            skipUntilBreak(bytes, offset + 1).map(next => ((), next))
          else
            readArgument(bytes, offset, ai).flatMap { case (count, after) =>
              skipNItems(bytes, after, count.toInt * 2).map(next => ((), next))
            }

        case 6 => // tag
          readArgument(bytes, offset, ai).flatMap { case (_, after) =>
            skipItem(bytes, after)
          }

        case 7 => // simple values and floats
          if ai == 31 then
            // Break code — should not appear as a standalone item to skip
            Left(s"unexpected break byte at offset $offset")
          else readArgument(bytes, offset, ai).map { case (_, next) => ((), next) }

        case _ => Left(s"unknown CBOR major type $major")

  /** Skip CBOR items until we hit the break byte (0xFF), then consume it. */
  private def skipUntilBreak(bytes: ByteVector, offset: Int): Either[String, Int] =
    var pos = offset
    while
      if pos >= bytes.size then return Left("unexpected end of input looking for break byte")
      val b = bytes(pos.toLong) & 0xff
      b != BreakByte
    do
      skipItem(bytes, pos) match
        case Right((_, next)) => pos = next
        case Left(err)        => return Left(err)
    Right(pos + 1) // +1 to skip the break byte itself

  /** Consume a break byte (0xFF) at the given offset. */
  private def consumeBreak(bytes: ByteVector, offset: Int): Either[String, Int] =
    if offset >= bytes.size then Left("unexpected end of input expecting break byte")
    else
      val b = bytes(offset.toLong) & 0xff
      if b == BreakByte then Right(offset + 1)
      else Right(offset) // Not a break byte — might be a definite-length structure, so no break to consume

  /** Skip N consecutive CBOR items. */
  private def skipNItems(bytes: ByteVector, offset: Int, count: Int): Either[String, Int] =
    var pos = offset
    var i   = 0
    while i < count do
      skipItem(bytes, pos) match
        case Right((_, next)) => pos = next
        case Left(err)        => return Left(err)
      i += 1
    Right(pos)

  /**
   * Read items from an indefinite-length collection until break byte (0xFF).
   * Calls the provided reader function for each item, accumulating results.
   */
  private def readItemsUntilBreak[A](bytes: ByteVector, offset: Int)(
      reader: (Int, A) => Either[String, (A, Int)]
  )(initial: A): Either[String, (A, Int)] =
    var pos = offset
    var acc = initial
    while
      if pos >= bytes.size then return Left("unexpected end of input looking for break byte")
      val b = bytes(pos.toLong) & 0xff
      b != BreakByte
    do
      reader(pos, acc) match
        case Right((newAcc, nextPos)) =>
          acc = newAcc
          pos = nextPos
        case Left(err) => return Left(err)
    Right((acc, pos + 1)) // +1 to skip the break byte
