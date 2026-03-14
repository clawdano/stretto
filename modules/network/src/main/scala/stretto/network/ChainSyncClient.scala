package stretto.network

import cats.effect.IO
import fs2.Stream
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}

/**
 * ChainSync N2N client — drives the protocol from the initiator side.
 *
 * Usage:
 *   1. Call `findIntersect` with known points to locate a common ancestor.
 *   2. Call `requestNext` in a loop (or use `headerStream`) to receive headers.
 *
 * The client filters mux frames to mini-protocol ID 2 (ChainSyncN2N).
 */
final class ChainSyncClient(mux: MuxDemuxer):

  private val protoId = MiniProtocolId.ChainSyncN2N.id

  /** Send a ChainSync message to the peer. */
  private def send(msg: ChainSyncMessage): IO[Unit] =
    mux.send(protoId, ChainSyncMessage.encode(msg))

  /** Receive the next ChainSync message from the peer. */
  private def recv: IO[ChainSyncMessage] =
    mux.receive
      .filter(_._1 == protoId)
      .head
      .compile
      .lastOrError
      .flatMap { case (_, payload) =>
        ChainSyncMessage.decode(payload) match
          case Right(msg) => IO.pure(msg)
          case Left(err)  => IO.raiseError(new RuntimeException(s"ChainSync decode error: $err"))
      }

  /**
   * Find the most recent intersection between our chain and the peer's.
   *
   * Returns `Right((intersectionPoint, peerTip))` if found, or
   * `Left(peerTip)` if no intersection exists (must sync from genesis).
   */
  def findIntersect(points: List[Point]): IO[Either[Tip, (Point, Tip)]] =
    send(ChainSyncMessage.MsgFindIntersect(points)) *>
      recv.flatMap {
        case ChainSyncMessage.MsgIntersectFound(point, tip) =>
          IO.pure(Right((point, tip)))
        case ChainSyncMessage.MsgIntersectNotFound(tip) =>
          IO.pure(Left(tip))
        case other =>
          IO.raiseError(
            new RuntimeException(s"Unexpected response to MsgFindIntersect: $other")
          )
      }

  /**
   * Request the next header from the peer.
   *
   * Returns one of:
   *   - `RollForward(headerBytes, tip)` — new header available
   *   - `RollBackward(point, tip)` — chain reorg, roll back
   *   - `AwaitReply` — at tip, server will send when new block arrives
   */
  def requestNext: IO[ChainSyncResponse] =
    send(ChainSyncMessage.MsgRequestNext) *>
      recv.flatMap {
        case ChainSyncMessage.MsgAwaitReply =>
          // Server has no data yet — wait for the follow-up message
          recv.flatMap {
            case ChainSyncMessage.MsgRollForward(header, tip) =>
              IO.pure(ChainSyncResponse.RollForward(header, tip))
            case ChainSyncMessage.MsgRollBackward(point, tip) =>
              IO.pure(ChainSyncResponse.RollBackward(point, tip))
            case other =>
              IO.raiseError(
                new RuntimeException(s"Unexpected message after MsgAwaitReply: $other")
              )
          }
        case ChainSyncMessage.MsgRollForward(header, tip) =>
          IO.pure(ChainSyncResponse.RollForward(header, tip))
        case ChainSyncMessage.MsgRollBackward(point, tip) =>
          IO.pure(ChainSyncResponse.RollBackward(point, tip))
        case other =>
          IO.raiseError(
            new RuntimeException(s"Unexpected response to MsgRequestNext: $other")
          )
      }

  /**
   * Infinite stream of chain sync responses starting from the current position.
   * First finds an intersection (or starts from origin), then streams headers.
   */
  def headerStream(knownPoints: List[Point]): Stream[IO, ChainSyncResponse] =
    Stream.eval(findIntersect(knownPoints)) >>
      Stream.repeatEval(requestNext)

  /** Gracefully terminate the ChainSync protocol. */
  def done: IO[Unit] = send(ChainSyncMessage.MsgDone)

/** Responses from the ChainSync protocol. */
enum ChainSyncResponse:
  /** A new header is available; the peer's tip may have advanced. */
  case RollForward(header: ByteVector, tip: Tip)

  /** A rollback occurred; resync from this point. */
  case RollBackward(point: Point, tip: Tip)
