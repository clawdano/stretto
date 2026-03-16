package stretto.node

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.concurrent.Topic
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*
import stretto.network.{MiniProtocolId, MuxDemuxer}
import stretto.storage.RocksDbStore

/**
 * ChainSync N2C server — serves blocks to a single local client.
 *
 * Maintains a cursor (current block height) and serves blocks from RocksDB.
 * When the client catches up to the tip, subscribes to the ChainEvent topic
 * and waits for new blocks.
 *
 * Wire format difference from N2N: MsgRollForward sends full era-wrapped
 * blocks (not headers), without tag24 wrapping.
 */
final class ChainSyncServer(
    mux: MuxDemuxer,
    store: RocksDbStore,
    tipTopic: Topic[IO, ChainEvent]
):

  private val protoId = MiniProtocolId.ChainSyncN2C.id

  /** Maximum points allowed in a FindIntersect request. */
  private val MaxIntersectPoints = 100

  // CBOR encoding helpers (same approach as ChainSyncMessage)
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
      else ByteVector(0x5a.toByte) ++ ByteVector.fromInt(len.toInt, size = 4)
    hdr ++ bv

  private def encodePoint(p: Point): ByteVector = p match
    case Point.Origin =>
      cborArrayHeader(0)
    case Point.BlockPoint(slotNo, blockHash) =>
      cborArrayHeader(2) ++ cborUInt(slotNo.value) ++ cborByteString(blockHash.toHash32.hash32Bytes)

  private def encodeTip(t: Tip): ByteVector =
    cborArrayHeader(2) ++ encodePoint(t.point) ++ cborUInt(t.blockNo.blockNoValue)

  /**
   * Encode MsgRollForward for N2C: [2, eraWrappedBlock, tip].
   *
   * N2C sends the full era-wrapped block inline (no tag24 wrapping).
   * The block data from RocksDB is already in [era_tag, block_cbor] format.
   */
  private def encodeMsgRollForward(eraWrappedBlock: ByteVector, tip: Tip): ByteVector =
    cborArrayHeader(3) ++ cborUInt(2) ++ eraWrappedBlock ++ encodeTip(tip)

  /** Encode MsgAwaitReply: [1]. */
  private def encodeMsgAwaitReply: ByteVector =
    cborArrayHeader(1) ++ cborUInt(1)

  /** Encode MsgIntersectFound: [5, point, tip]. */
  private def encodeMsgIntersectFound(point: Point, tip: Tip): ByteVector =
    cborArrayHeader(3) ++ cborUInt(5) ++ encodePoint(point) ++ encodeTip(tip)

  /** Encode MsgIntersectNotFound: [6, tip]. */
  private def encodeMsgIntersectNotFound(tip: Tip): ByteVector =
    cborArrayHeader(2) ++ cborUInt(6) ++ encodeTip(tip)

  private def sendMsg(payload: ByteVector): IO[Unit] =
    mux.sendResponse(protoId, payload)

  /**
   * Run the ChainSync N2C server loop for a single client.
   *
   * The cursor tracks the client's current block height. When the client
   * is behind the tip, blocks are served from RocksDB. When at the tip,
   * the server subscribes to the topic and waits.
   */
  def serve: IO[Unit] =
    for
      cursorRef <- Ref.of[IO, BlockNo](BlockNo(0L))
      _         <- serverLoop(cursorRef)
    yield ()

  private def serverLoop(cursorRef: Ref[IO, BlockNo]): IO[Unit] =
    recvClientMsg.flatMap {
      case ClientMsg.FindIntersect(points) =>
        handleFindIntersect(cursorRef, points) *> serverLoop(cursorRef)
      case ClientMsg.RequestNext =>
        handleRequestNext(cursorRef) *> serverLoop(cursorRef)
      case ClientMsg.Done =>
        IO.unit // Client terminated
    }

  private def handleFindIntersect(
      cursorRef: Ref[IO, BlockNo],
      points: List[Point]
  ): IO[Unit] =
    store.getTip.flatMap { tipOpt =>
      val tip           = tipOpt.getOrElse(Tip.origin)
      val limitedPoints = points.take(MaxIntersectPoints)
      findBestIntersection(limitedPoints).flatMap {
        case Some((point, blockNo)) =>
          cursorRef.set(blockNo) *>
            sendMsg(encodeMsgIntersectFound(point, tip))
        case None =>
          cursorRef.set(BlockNo(0L)) *>
            sendMsg(encodeMsgIntersectNotFound(tip))
      }
    }

  /** Find the first point from the client's list that exists in our chain. */
  private def findBestIntersection(points: List[Point]): IO[Option[(Point, BlockNo)]] =
    points match
      case Nil => IO.pure(None)
      case Point.Origin :: _ =>
        IO.pure(Some((Point.Origin, BlockNo(0L))))
      case (bp: Point.BlockPoint) :: rest =>
        store.getHeader(bp).flatMap {
          case Some(_) =>
            findBlockNoForPoint(bp).map {
              case Some(bn) => Some((bp: Point, bn))
              case None     => Some((bp: Point, BlockNo(0L)))
            }
          case None =>
            findBestIntersection(rest)
        }

  /** Find the block number for a given point by scanning the height index. */
  private def findBlockNoForPoint(point: Point.BlockPoint): IO[Option[BlockNo]] =
    store.getMaxHeight.flatMap {
      case None => IO.pure(None)
      case Some(maxH) =>
        def scan(height: Long): IO[Option[BlockNo]] =
          if height < 0 then IO.pure(None)
          else
            store.getPointByHeight(BlockNo(height)).flatMap {
              case Some(p) if p == point => IO.pure(Some(BlockNo(height)))
              case _ =>
                if height > maxH.blockNoValue - 1000 then scan(height - 1)
                else IO.pure(None)
            }
        scan(maxH.blockNoValue)
    }

  private def handleRequestNext(cursorRef: Ref[IO, BlockNo]): IO[Unit] =
    for
      cursor <- cursorRef.get
      tipOpt <- store.getTip
      tip        = tipOpt.getOrElse(Tip.origin)
      nextHeight = BlockNo(cursor.blockNoValue + 1)
      maxHeight <- store.getMaxHeight
      _ <- maxHeight match
        case Some(maxH) if nextHeight.blockNoValue <= maxH.blockNoValue =>
          serveBlockAtHeight(cursorRef, nextHeight, tip)
        case _ =>
          sendMsg(encodeMsgAwaitReply) *>
            waitForNewBlock(cursorRef)
    yield ()

  /** Read block at the given height and send MsgRollForward. */
  private def serveBlockAtHeight(
      cursorRef: Ref[IO, BlockNo],
      height: BlockNo,
      tip: Tip
  ): IO[Unit] =
    store.getPointByHeight(height).flatMap {
      case Some(point) =>
        store.getBlock(point).flatMap {
          case Some(blockData) =>
            cursorRef.set(height) *>
              sendMsg(encodeMsgRollForward(blockData, tip))
          case None =>
            // Block data missing — still advance cursor
            cursorRef.set(height) *>
              sendMsg(encodeMsgRollForward(ByteVector.empty, tip))
        }
      case None =>
        sendMsg(encodeMsgAwaitReply)
    }

  /** Wait for a new block from the topic, then serve it. */
  private def waitForNewBlock(cursorRef: Ref[IO, BlockNo]): IO[Unit] =
    tipTopic
      .subscribe(16)
      .collectFirst { case ChainEvent.BlockAdded(point, tip) =>
        (point, tip)
      }
      .compile
      .lastOrError
      .flatMap { case (point, tip) =>
        store.getBlock(point).flatMap {
          case Some(blockData) =>
            cursorRef.update(bn => BlockNo(bn.blockNoValue + 1)) *>
              sendMsg(encodeMsgRollForward(blockData, tip))
          case None =>
            cursorRef.get.flatMap { cursor =>
              val nextHeight = BlockNo(cursor.blockNoValue + 1)
              serveBlockAtHeight(cursorRef, nextHeight, tip)
            }
        }
      }

  // ---------------------------------------------------------------------------
  // Client message decoding
  // ---------------------------------------------------------------------------

  private enum ClientMsg:
    case FindIntersect(points: List[Point])
    case RequestNext
    case Done

  private def recvClientMsg: IO[ClientMsg] =
    mux.recvProtocol(protoId).flatMap { payload =>
      decodeClientMsg(payload) match
        case Right(msg) => IO.pure(msg)
        case Left(err) =>
          IO.raiseError(new RuntimeException(s"N2C ChainSync client decode error: $err"))
    }

  private def decodeClientMsg(bytes: ByteVector): Either[String, ClientMsg] =
    if bytes.isEmpty then Left("empty payload")
    else
      for
        (_, afterArr)   <- readArrayHeader(bytes, 0)
        (tag, afterTag) <- readUInt(bytes, afterArr)
        result <- tag match
          case 0 => Right(ClientMsg.RequestNext)
          case 4 => decodeFindIntersectMsg(bytes, afterTag)
          case 7 => Right(ClientMsg.Done)
          case _ => Left(s"unknown N2C ChainSync client tag: $tag")
      yield result

  private def decodeFindIntersectMsg(
      bytes: ByteVector,
      offset: Int
  ): Either[String, ClientMsg] =
    for
      (count, afterArr) <- readArrayHeader(bytes, offset)
      (points, _)       <- readPoints(bytes, afterArr, count.toInt)
    yield ClientMsg.FindIntersect(points)

  // ---------------------------------------------------------------------------
  // CBOR decoding primitives
  // ---------------------------------------------------------------------------

  private def readUInt(bytes: ByteVector, offset: Int): Either[String, (Long, Int)] =
    if offset >= bytes.size then Left("unexpected end of input")
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
    if offset >= bytes.size then Left("unexpected end of input")
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
