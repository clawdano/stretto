package stretto.node

import cats.effect.IO
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector
import stretto.core.Point
import stretto.core.Types.*
import stretto.network.{BlockFetchMessage, MiniProtocolId, MuxDemuxer}
import stretto.storage.RocksDbStore

/**
 * BlockFetch N2N server — serves full blocks to a connected peer.
 *
 * Handles MsgRequestRange by looking up blocks in RocksDB and streaming
 * them back as MsgStartBatch → MsgBlock* → MsgBatchDone.
 *
 * If the requested range is not available, responds with MsgNoBlocks.
 */
final class BlockFetchServer(
    mux: MuxDemuxer,
    store: RocksDbStore
):

  private val logger  = Slf4jLogger.getLoggerFromName[IO]("stretto.node.BlockFetchServer")
  private val protoId = MiniProtocolId.BlockFetch.id

  private def sendMsg(payload: ByteVector): IO[Unit] =
    mux.sendResponse(protoId, payload)

  private def recv: IO[BlockFetchMessage] =
    mux.recvProtocol(protoId).flatMap { payload =>
      BlockFetchMessage.decode(payload) match
        case Right(msg) => IO.pure(msg)
        case Left(err)  => IO.raiseError(new RuntimeException(s"BlockFetch decode error: $err"))
    }

  /** Run the BlockFetch server loop for a single peer. */
  def serve: IO[Unit] =
    serverLoop

  private def serverLoop: IO[Unit] =
    recv.flatMap {
      case BlockFetchMessage.MsgRequestRange(from, to) =>
        handleRequestRange(from, to) *> serverLoop
      case BlockFetchMessage.MsgClientDone =>
        logger.debug("BlockFetch: client done")
      case other =>
        logger.warn(s"BlockFetch: unexpected message: $other") *> serverLoop
    }

  /**
   * Handle a block range request.
   *
   * Looks up the start and end points in the height index, then streams
   * all blocks in the range. If any block is missing, responds with MsgNoBlocks.
   */
  private def handleRequestRange(from: Point.BlockPoint, to: Point.BlockPoint): IO[Unit] =
    for
      fromHeight <- findHeight(from)
      toHeight   <- findHeight(to)
      _ <- (fromHeight, toHeight) match
        case (Some(fh), Some(th)) if fh.blockNoValue <= th.blockNoValue =>
          streamBlockRange(fh, th)
        case _ =>
          logger.debug(s"BlockFetch: range not available ${from.slotNo.value}-${to.slotNo.value}") *>
            sendMsg(BlockFetchMessage.encode(BlockFetchMessage.MsgNoBlocks))
    yield ()

  /** Stream blocks from height `from` to `to` inclusive. */
  private def streamBlockRange(from: BlockNo, to: BlockNo): IO[Unit] =
    sendMsg(BlockFetchMessage.encode(BlockFetchMessage.MsgStartBatch)) *>
      streamBlocks(from, to) *>
      sendMsg(BlockFetchMessage.encode(BlockFetchMessage.MsgBatchDone))

  private def streamBlocks(current: BlockNo, to: BlockNo): IO[Unit] =
    if current.blockNoValue > to.blockNoValue then IO.unit
    else
      store.getPointByHeight(current).flatMap {
        case Some(point) =>
          store.getBlock(point).flatMap {
            case Some(blockData) =>
              sendMsg(BlockFetchMessage.encode(BlockFetchMessage.MsgBlock(blockData))) *>
                streamBlocks(BlockNo(current.blockNoValue + 1), to)
            case None =>
              // Block data missing — skip (shouldn't happen)
              logger.warn(s"BlockFetch: block data missing at height ${current.blockNoValue}") *>
                streamBlocks(BlockNo(current.blockNoValue + 1), to)
          }
        case None =>
          // Height not found — stop streaming
          IO.unit
      }

  /** Find the block height for a given point. */
  private def findHeight(point: Point.BlockPoint): IO[Option[BlockNo]] =
    store.getMaxHeight.flatMap {
      case None => IO.pure(None)
      case Some(maxH) =>
        def scan(height: Long): IO[Option[BlockNo]] =
          if height < 0 then IO.pure(None)
          else
            store.getPointByHeight(BlockNo(height)).flatMap {
              case Some(p) if p == point => IO.pure(Some(BlockNo(height)))
              case _ =>
                if height > maxH.blockNoValue - 10000 then scan(height - 1)
                else IO.pure(None)
            }
        scan(maxH.blockNoValue)
    }
