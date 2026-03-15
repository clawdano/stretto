package stretto.network

import cats.effect.IO
import fs2.Stream
import scodec.bits.ByteVector
import stretto.core.Point

/**
 * BlockFetch N2N client — downloads full blocks from a peer.
 *
 * Usage:
 *   1. Use ChainSync to discover block headers and their points.
 *   2. Call `fetchRange` with the range of blocks to download.
 *   3. Each block is returned as raw era-wrapped CBOR bytes.
 *
 * The client supports multiple sequential range requests.
 * Call `done` when finished to terminate the protocol cleanly.
 */
final class BlockFetchClient(mux: MuxDemuxer):

  private val protoId = MiniProtocolId.BlockFetch.id

  /** Send a BlockFetch message to the peer. */
  private def send(msg: BlockFetchMessage): IO[Unit] =
    mux.send(protoId, BlockFetchMessage.encode(msg))

  /** Receive the next BlockFetch message from the peer. */
  private def recv: IO[BlockFetchMessage] =
    mux.recvProtocol(protoId).flatMap { payload =>
      BlockFetchMessage.decode(payload) match
        case Right(msg) => IO.pure(msg)
        case Left(err)  => IO.raiseError(new RuntimeException(s"BlockFetch decode error: $err"))
    }

  /**
   * Fetch a range of blocks [from, to] inclusive.
   *
   * Returns a stream of raw era-wrapped block bytes.
   * The stream completes when MsgBatchDone is received.
   * Returns empty stream if the server responds with MsgNoBlocks.
   */
  def fetchRange(from: Point.BlockPoint, to: Point.BlockPoint): Stream[IO, ByteVector] =
    Stream.eval(send(BlockFetchMessage.MsgRequestRange(from, to))) >>
      Stream.eval(recv).flatMap {
        case BlockFetchMessage.MsgStartBatch =>
          receiveBlocks
        case BlockFetchMessage.MsgNoBlocks =>
          Stream.empty
        case other =>
          Stream.raiseError[IO](
            new RuntimeException(s"Unexpected response to MsgRequestRange: $other")
          )
      }

  /**
   * Fetch multiple ranges sequentially, concatenating block streams.
   */
  def fetchRanges(ranges: List[(Point.BlockPoint, Point.BlockPoint)]): Stream[IO, ByteVector] =
    Stream.emits(ranges).flatMap { case (from, to) => fetchRange(from, to) }

  /** Receive blocks until MsgBatchDone. */
  private def receiveBlocks: Stream[IO, ByteVector] =
    Stream.eval(recv).flatMap {
      case BlockFetchMessage.MsgBlock(blockData) =>
        Stream.emit(blockData) ++ receiveBlocks
      case BlockFetchMessage.MsgBatchDone =>
        Stream.empty
      case other =>
        Stream.raiseError[IO](
          new RuntimeException(s"Unexpected message in streaming state: $other")
        )
    }

  /** Gracefully terminate the BlockFetch protocol. */
  def done: IO[Unit] = send(BlockFetchMessage.MsgClientDone)
