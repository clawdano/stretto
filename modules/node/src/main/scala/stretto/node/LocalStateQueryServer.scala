package stretto.node

import cats.effect.{IO, Ref}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*
import stretto.network.{MiniProtocolId, MuxDemuxer}
import stretto.storage.RocksDbStore

/**
 * LocalStateQuery N2C server — responds to client state queries.
 *
 * Supports the LSQ mini-protocol (protocol ID 7) state machine:
 *   StIdle → StAcquiring → StAcquired → StQuerying → StAcquired
 *
 * For a relay without full ledger state, this supports:
 *   - GetSystemStart, GetChainBlockNo, GetChainPoint (top-level)
 *   - GetCurrentEra (HardFork query)
 *   - GetLedgerTip, GetEpochNo, GetCurrentPParams (BlockQuery)
 *
 * Unsupported queries return an empty result (CBOR null).
 *
 * Reference: ouroboros-network local-state-query.cddl
 */
final class LocalStateQueryServer(
    mux: MuxDemuxer,
    store: RocksDbStore,
    genesis: GenesisConfig
):

  private val logger  = Slf4jLogger.getLoggerFromName[IO]("stretto.node.LSQ")
  private val protoId = MiniProtocolId.LocalStateQuery.id

  // -------------------------------------------------------------------------
  // Server state
  // -------------------------------------------------------------------------

  private enum ServerState:
    case Idle
    case Acquired(point: Point, tip: Tip)

  // -------------------------------------------------------------------------
  // CBOR encoding helpers
  // -------------------------------------------------------------------------

  private def cborUInt(n: Long): ByteVector =
    if n < 24 then ByteVector(n.toByte)
    else if n < 256 then ByteVector(0x18.toByte, n.toByte)
    else if n < 65536 then ByteVector(0x19.toByte) ++ ByteVector.fromShort(n.toShort, size = 2)
    else if n < 0x100000000L then ByteVector(0x1a.toByte) ++ ByteVector.fromInt(n.toInt, size = 4)
    else ByteVector(0x1b.toByte) ++ ByteVector.fromLong(n, size = 8)

  private def cborArrayHeader(len: Int): ByteVector =
    if len < 24 then ByteVector((0x80 | len).toByte)
    else ByteVector(0x98.toByte, len.toByte)

  private def cborMapHeader(len: Int): ByteVector =
    if len < 24 then ByteVector((0xa0 | len).toByte)
    else ByteVector(0xb8.toByte, len.toByte)

  private def cborByteString(bv: ByteVector): ByteVector =
    val len = bv.size
    val hdr =
      if len < 24 then ByteVector((0x40 | len.toInt).toByte)
      else if len < 256 then ByteVector(0x58.toByte, len.toByte)
      else if len < 65536 then ByteVector(0x59.toByte) ++ ByteVector.fromShort(len.toShort, size = 2)
      else ByteVector(0x5a.toByte) ++ ByteVector.fromInt(len.toInt, size = 4)
    hdr ++ bv

  private def cborTextString(s: String): ByteVector =
    val bytes = ByteVector.view(s.getBytes("UTF-8"))
    val len   = bytes.size
    val hdr =
      if len < 24 then ByteVector((0x60 | len.toInt).toByte)
      else if len < 256 then ByteVector(0x78.toByte, len.toByte)
      else ByteVector(0x79.toByte) ++ ByteVector.fromShort(len.toShort, size = 2)
    hdr ++ bytes

  private val cborNull: ByteVector = ByteVector(0xf6.toByte)

  /** Encode a rational number as tag 30 [numerator, denominator]. */
  private def cborRational(num: Long, den: Long): ByteVector =
    ByteVector(0xd8.toByte, 0x1e.toByte) ++ cborArrayHeader(2) ++ cborUInt(num) ++ cborUInt(den)

  private def encodePoint(p: Point): ByteVector = p match
    case Point.Origin =>
      cborArrayHeader(0)
    case Point.BlockPoint(slotNo, blockHash) =>
      cborArrayHeader(2) ++ cborUInt(slotNo.value) ++ cborByteString(blockHash.toHash32.hash32Bytes)

  private def sendMsg(payload: ByteVector): IO[Unit] =
    mux.sendResponse(protoId, payload)

  // -------------------------------------------------------------------------
  // Server messages
  // -------------------------------------------------------------------------

  /** MsgAcquired = [1] */
  private val msgAcquired: ByteVector = cborArrayHeader(1) ++ cborUInt(1)

  /** MsgFailure(PointNotOnChain) = [2, 1] */
  private val msgFailurePointNotOnChain: ByteVector = cborArrayHeader(2) ++ cborUInt(2) ++ cborUInt(1)

  /** MsgResult(result) = [4, result] */
  private def msgResult(result: ByteVector): ByteVector =
    cborArrayHeader(2) ++ cborUInt(4) ++ result

  // -------------------------------------------------------------------------
  // Main server loop
  // -------------------------------------------------------------------------

  def serve: IO[Unit] =
    for
      stateRef <- Ref.of[IO, ServerState](ServerState.Idle)
      _        <- serverLoop(stateRef)
    yield ()

  private def serverLoop(stateRef: Ref[IO, ServerState]): IO[Unit] =
    recvClientMsg
      .flatMap {
        case ClientMsg.Acquire(target) =>
          handleAcquire(stateRef, target) *> serverLoop(stateRef)
        case ClientMsg.Query(rawQuery) =>
          handleQuery(stateRef, rawQuery) *> serverLoop(stateRef)
        case ClientMsg.ReAcquire(target) =>
          handleAcquire(stateRef, target) *> serverLoop(stateRef)
        case ClientMsg.Release =>
          stateRef.set(ServerState.Idle) *> serverLoop(stateRef)
        case ClientMsg.Done =>
          logger.debug("LSQ client sent MsgDone")
      }
      .handleErrorWith { err =>
        logger.debug(s"LSQ server loop ended: ${err.getMessage}")
      }

  // -------------------------------------------------------------------------
  // Acquire handling
  // -------------------------------------------------------------------------

  private def handleAcquire(stateRef: Ref[IO, ServerState], target: AcquireTarget): IO[Unit] =
    target match
      case AcquireTarget.VolatileTip | AcquireTarget.ImmutableTip =>
        store.getTip.flatMap { tipOpt =>
          val tip = tipOpt.getOrElse(Tip.origin)
          stateRef.set(ServerState.Acquired(tip.point, tip)) *>
            logger.debug(s"LSQ acquired at tip: ${tip.point}") *>
            sendMsg(msgAcquired)
        }
      case AcquireTarget.SpecificPoint(point) =>
        store.getHeader(point).flatMap {
          case Some(_) =>
            store.getTip.flatMap { tipOpt =>
              val tip = tipOpt.getOrElse(Tip.origin)
              stateRef.set(ServerState.Acquired(point, tip)) *>
                logger.debug(s"LSQ acquired at point: $point") *>
                sendMsg(msgAcquired)
            }
          case None =>
            logger.debug(s"LSQ acquire failed: point not on chain") *>
              sendMsg(msgFailurePointNotOnChain)
        }

  // -------------------------------------------------------------------------
  // Query handling
  // -------------------------------------------------------------------------

  private def handleQuery(stateRef: Ref[IO, ServerState], rawQuery: ByteVector): IO[Unit] =
    stateRef.get.flatMap {
      case ServerState.Idle =>
        IO.raiseError(new RuntimeException("LSQ query in Idle state"))
      case ServerState.Acquired(point, tip) =>
        val query  = parseQuery(rawQuery)
        val result = processQuery(query, point, tip)
        result.flatMap(r => sendMsg(msgResult(r)))
    }

  private def processQuery(query: LSQuery, acquiredPoint: Point, tip: Tip): IO[ByteVector] =
    query match
      case LSQuery.GetSystemStart =>
        IO.pure(cborTextString(genesis.systemStart))

      case LSQuery.GetLedgerTip =>
        IO.pure(encodePoint(acquiredPoint))

      case LSQuery.GetChainBlockNo =>
        store.getMaxHeight.map {
          case Some(bn) => cborArrayHeader(1) ++ cborUInt(bn.blockNoValue)
          case None     => cborArrayHeader(1) ++ cborUInt(0)
        }

      case LSQuery.GetChainPoint =>
        IO.pure(encodePoint(tip.point))

      case LSQuery.GetCurrentEra =>
        // All networks are currently in Conway (era index 6)
        IO.pure(cborUInt(6))

      case LSQuery.GetEpochNo =>
        val slot = tip.point match
          case Point.BlockPoint(s, _) => s.value
          case Point.Origin           => 0L
        IO.pure(cborUInt(genesis.epochForSlot(slot)))

      case LSQuery.GetCurrentPParams =>
        IO.pure(encodeConwayPParams)

      case LSQuery.GetEraSummaries =>
        IO.pure(encodeEraSummaries)

      case LSQuery.Unsupported(desc) =>
        logger.debug(s"LSQ unsupported query: $desc") *>
          IO.pure(cborNull)

  // -------------------------------------------------------------------------
  // Protocol parameters encoding (Conway era, mainnet-like defaults)
  // -------------------------------------------------------------------------

  /** Encode Conway protocol parameters as a CBOR map with integer keys. */
  private def encodeConwayPParams: ByteVector =
    // Minimal set that Ogmios/clients typically need
    cborMapHeader(18) ++
      cborUInt(0) ++ cborUInt(44) ++           // minFeeA (per byte)
      cborUInt(1) ++ cborUInt(155381) ++       // minFeeB (constant)
      cborUInt(2) ++ cborUInt(90112) ++        // maxBlockBodySize
      cborUInt(3) ++ cborUInt(16384) ++        // maxTxSize
      cborUInt(4) ++ cborUInt(1100) ++         // maxBlockHeaderSize
      cborUInt(5) ++ cborUInt(2000000) ++      // keyDeposit
      cborUInt(6) ++ cborUInt(500000000) ++    // poolDeposit
      cborUInt(7) ++ cborUInt(18) ++           // eMax (max epochs for pool retirement)
      cborUInt(8) ++ cborUInt(500) ++          // nOpt (desired number of pools)
      cborUInt(9) ++ cborRational(3, 10) ++    // a0 (pool pledge influence)
      cborUInt(10) ++ cborRational(3, 1000) ++ // rho (monetary expansion)
      cborUInt(11) ++ cborRational(2, 10) ++   // tau (treasury growth)
      cborUInt(16) ++ cborUInt(170000000) ++   // minPoolCost
      cborUInt(17) ++ cborUInt(4310) ++        // coinsPerUtxoByte
      cborUInt(18) ++ cborMapHeader(0) ++      // costModels (empty for now)
      cborUInt(22) ++ cborUInt(5000) ++        // maxValSize
      cborUInt(23) ++ cborUInt(150) ++         // collateralPercentage
      cborUInt(24) ++ cborUInt(3)              // maxCollateralInputs

  // -------------------------------------------------------------------------
  // Era summaries encoding (for GetInterpreter / eraSummaries query)
  // -------------------------------------------------------------------------

  private def encodeEraSummaries: ByteVector =
    // Return a simplified era summary list
    // Each entry: { "start": { "time": secs, "slot": n, "epoch": n }, "end": ..., "parameters": { "epochLength": n, "slotLength": n } }
    // Encode as CBOR array of arrays
    val shelleyStart = genesis.shelleyStartSlot
    val byronTimeSec = genesis.byronShelleyTransitionEpoch * genesis.byronEpochLength * genesis.byronSlotLength

    // Byron era summary
    val byron = cborArrayHeader(3) ++
      // start: {time: 0, slot: 0, epoch: 0}
      cborArrayHeader(3) ++ cborUInt(0) ++ cborUInt(0) ++ cborUInt(0) ++
      // end: {time: byronTimeSec, slot: shelleyStart, epoch: byronTransitionEpoch}
      cborArrayHeader(3) ++ cborUInt(byronTimeSec) ++ cborUInt(shelleyStart) ++ cborUInt(
        genesis.byronShelleyTransitionEpoch
      ) ++
      // params: {epochLength: byronEpochLen * byronSlotLen, slotLength: byronSlotLen}
      cborArrayHeader(2) ++ cborUInt(genesis.byronEpochLength * genesis.byronSlotLength) ++ cborRational(
        genesis.byronSlotLength,
        1
      )

    // Shelley+ era summary (open-ended — no end)
    val shelley = cborArrayHeader(2) ++
      cborArrayHeader(3) ++ cborUInt(byronTimeSec) ++ cborUInt(shelleyStart) ++ cborUInt(
        genesis.byronShelleyTransitionEpoch
      ) ++
      cborArrayHeader(2) ++ cborUInt(genesis.shelleyEpochLength) ++ cborRational(genesis.shelleySlotLength, 1)

    cborArrayHeader(2) ++ byron ++ shelley

  // -------------------------------------------------------------------------
  // Client message decoding
  // -------------------------------------------------------------------------

  private enum ClientMsg:
    case Acquire(target: AcquireTarget)
    case Query(rawQuery: ByteVector)
    case ReAcquire(target: AcquireTarget)
    case Release
    case Done

  private enum AcquireTarget:
    case VolatileTip
    case SpecificPoint(point: Point.BlockPoint)
    case ImmutableTip

  private enum LSQuery:
    case GetSystemStart
    case GetChainBlockNo
    case GetChainPoint
    case GetCurrentEra
    case GetEpochNo
    case GetLedgerTip
    case GetCurrentPParams
    case GetEraSummaries
    case Unsupported(desc: String)

  private def recvClientMsg: IO[ClientMsg] =
    mux.recvProtocol(protoId).flatMap { payload =>
      decodeClientMsg(payload) match
        case Right(msg) => IO.pure(msg)
        case Left(err) =>
          IO.raiseError(new RuntimeException(s"LSQ decode error: $err (payload: ${payload.take(32).toHex})"))
    }

  private def decodeClientMsg(bytes: ByteVector): Either[String, ClientMsg] =
    if bytes.isEmpty then Left("empty payload")
    else
      for
        (_, afterArr)   <- readArrayHeader(bytes, 0)
        (tag, afterTag) <- readUInt(bytes, afterArr)
        result <- tag match
          // MsgAcquire variants
          case 0  => decodeAcquireTarget(bytes, afterTag).map(ClientMsg.Acquire.apply)
          case 8  => Right(ClientMsg.Acquire(AcquireTarget.VolatileTip))
          case 10 => Right(ClientMsg.Acquire(AcquireTarget.ImmutableTip))
          // MsgQuery
          case 3 => Right(ClientMsg.Query(bytes.drop(afterTag.toLong)))
          // MsgRelease
          case 5 => Right(ClientMsg.Release)
          // MsgReAcquire variants
          case 6  => decodeAcquireTarget(bytes, afterTag).map(ClientMsg.ReAcquire.apply)
          case 9  => Right(ClientMsg.ReAcquire(AcquireTarget.VolatileTip))
          case 11 => Right(ClientMsg.ReAcquire(AcquireTarget.ImmutableTip))
          // MsgDone
          case 7 => Right(ClientMsg.Done)
          case _ => Left(s"unknown LSQ client tag: $tag")
      yield result

  private def decodeAcquireTarget(bytes: ByteVector, offset: Int): Either[String, AcquireTarget] =
    decodePoint(bytes, offset).map {
      case (Point.Origin, _)         => AcquireTarget.VolatileTip
      case (bp: Point.BlockPoint, _) => AcquireTarget.SpecificPoint(bp)
    }

  // -------------------------------------------------------------------------
  // Query parsing (3-level nesting)
  // -------------------------------------------------------------------------

  private def parseQuery(rawQuery: ByteVector): LSQuery =
    if rawQuery.isEmpty then return LSQuery.Unsupported("empty query")

    // Level 1: Request wrapper
    readArrayHeader(rawQuery, 0) match
      case Right((arrLen, afterArr)) =>
        readUInt(rawQuery, afterArr) match
          case Right((1, _))           => LSQuery.GetSystemStart
          case Right((2, _))           => LSQuery.GetChainBlockNo
          case Right((3, _))           => LSQuery.GetChainPoint
          case Right((0, afterReqTag)) =>
            // LedgerQuery — Level 2
            parseLedgerQuery(rawQuery, afterReqTag)
          case Right((tag, _)) => LSQuery.Unsupported(s"request tag $tag")
          case Left(_)         => LSQuery.Unsupported("failed to read request tag")
      case Left(_) => LSQuery.Unsupported("failed to read query array")

  private def parseLedgerQuery(bytes: ByteVector, offset: Int): LSQuery =
    readArrayHeader(bytes, offset) match
      case Right((_, afterArr)) =>
        readUInt(bytes, afterArr) match
          case Right((0, afterTag)) =>
            // QueryIfCurrent(BlockQuery) — Level 3
            parseBlockQuery(bytes, afterTag)
          case Right((1, _)) =>
            // QueryAnytime — GetEraStart
            LSQuery.Unsupported("QueryAnytime")
          case Right((2, afterTag)) =>
            // QueryHardFork
            parseHardForkQuery(bytes, afterTag)
          case Right((tag, _)) => LSQuery.Unsupported(s"ledger query tag $tag")
          case Left(_)         => LSQuery.Unsupported("failed to read ledger query tag")
      case Left(_) => LSQuery.Unsupported("failed to read ledger query array")

  private def parseHardForkQuery(bytes: ByteVector, offset: Int): LSQuery =
    readArrayHeader(bytes, offset) match
      case Right((_, afterArr)) =>
        readUInt(bytes, afterArr) match
          case Right((0, _))   => LSQuery.GetEraSummaries // GetInterpreter
          case Right((1, _))   => LSQuery.GetCurrentEra   // GetCurrentEra
          case Right((tag, _)) => LSQuery.Unsupported(s"hardfork query tag $tag")
          case Left(_)         => LSQuery.Unsupported("failed to read hardfork query tag")
      case Left(_) => LSQuery.Unsupported("failed to read hardfork query array")

  private def parseBlockQuery(bytes: ByteVector, offset: Int): LSQuery =
    // Unwrap era index: [era_index, [block_query_tag, ...]]
    readArrayHeader(bytes, offset) match
      case Right((_, afterArr)) =>
        readUInt(bytes, afterArr) match
          case Right((_, afterEra)) =>
            // Now read the inner block query
            readArrayHeader(bytes, afterEra.toInt) match
              case Right((_, afterInner)) =>
                readUInt(bytes, afterInner) match
                  case Right((0, _))   => LSQuery.GetLedgerTip
                  case Right((1, _))   => LSQuery.GetEpochNo
                  case Right((3, _))   => LSQuery.GetCurrentPParams
                  case Right((tag, _)) => LSQuery.Unsupported(s"block query tag $tag")
                  case Left(_)         => LSQuery.Unsupported("failed to read block query tag")
              case Left(_) => LSQuery.Unsupported("failed to read block query inner array")
          case Left(_) => LSQuery.Unsupported("failed to read era index")
      case Left(_) => LSQuery.Unsupported("failed to read block query array")

  // -------------------------------------------------------------------------
  // CBOR decoding primitives
  // -------------------------------------------------------------------------

  private def readUInt(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 0 then Left(s"expected uint at offset $offset, got major $major")
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
      else Left(s"unsupported uint ai: $ai")

  private def readArrayHeader(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 4 then Left(s"expected array at offset $offset, got major $major")
      else if ai < 24 then Right((ai.toLong, offset + 1))
      else if ai == 24 then Right(((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2))
      else if ai == 25 then
        val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
        Right((v.toLong, offset + 3))
      else Left(s"unsupported array header: $ai")

  private def readByteString(bytes: ByteVector, offset: Int): Either[String, (ByteVector, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
    else
      val b     = bytes(offset.toLong) & 0xff
      val major = b >> 5
      val ai    = b & 0x1f
      if major != 2 then Left(s"expected byte string at offset $offset, got major $major")
      else
        val (len, dataOffset) =
          if ai < 24 then (ai.toLong, offset + 1)
          else if ai == 24 then ((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2)
          else if ai == 25 then
            val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
            (v.toLong, offset + 3)
          else return Left(s"unsupported byte string ai: $ai")
        Right((bytes.slice(dataOffset.toLong, dataOffset.toLong + len), (dataOffset.toLong + len).toInt))

  private def decodePoint(bytes: ByteVector, offset: Int): Either[String, (Point, Int)] =
    for (arrLen, afterArr) <- readArrayHeader(bytes, offset)
    yield
      if arrLen == 0 then (Point.Origin, afterArr)
      else
        val Right((slot, afterSlot)) = readUInt(bytes, afterArr): @unchecked
        val Right((hash, afterHash)) = readByteString(bytes, afterSlot): @unchecked
        val bh                       = BlockHeaderHash(Hash32.unsafeFrom(hash))
        (Point.BlockPoint(SlotNo(slot), bh), afterHash)
