package stretto.network

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}

/**
 * ChainSync N2N client — drives the protocol from the initiator side.
 *
 * Supports pipelined requests: sends a burst of MsgRequestNext upfront
 * and refills as responses arrive, maintaining a sliding window of
 * in-flight requests for maximum throughput.
 *
 * Usage:
 *   1. Call `findIntersect` with known points to locate a common ancestor.
 *   2. Use `pipelinedHeaderStream` for high-throughput syncing, or
 *      `requestNext` for single-step control.
 */
final class ChainSyncClient(mux: MuxDemuxer):

  private val protoId = MiniProtocolId.ChainSyncN2N.id

  /** Send a ChainSync message to the peer. */
  private def send(msg: ChainSyncMessage): IO[Unit] =
    mux.send(protoId, ChainSyncMessage.encode(msg))

  /** Receive the next ChainSync message from the peer via dedicated protocol queue. */
  private def recv: IO[ChainSyncMessage] =
    mux.recvProtocol(protoId).flatMap { payload =>
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
   * Request the next header from the peer (single request, no pipelining).
   */
  def requestNext: IO[ChainSyncResponse] =
    send(ChainSyncMessage.MsgRequestNext) *>
      recv.flatMap {
        case ChainSyncMessage.MsgAwaitReply =>
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
   * Pipelined header stream — high-throughput chain sync.
   *
   * Sends `windowSize` MsgRequestNext messages upfront, then for each
   * response received, sends another request to maintain the pipeline.
   * This eliminates the round-trip latency bottleneck.
   *
   * When we reach the tip (MsgAwaitReply), the pipeline drains and we
   * fall back to single-request mode.
   */
  def pipelinedHeaderStream(
      knownPoints: List[Point],
      windowSize: Int = 100
  ): Stream[IO, ChainSyncResponse] =
    Stream.eval(findIntersect(knownPoints)) >>
      Stream.eval(sendBurst(windowSize)).flatMap { inFlight =>
        pipelineLoop(inFlight, windowSize)
      }

  /** Send N MsgRequestNext messages in a burst. */
  private def sendBurst(n: Int): IO[Int] =
    (1 to n).toList.traverse_(_ => send(ChainSyncMessage.MsgRequestNext)).as(n)

  /**
   * Core pipeline loop: receive a response, send a new request to keep
   * the window full, emit the response downstream.
   */
  private def pipelineLoop(inFlight: Int, windowSize: Int): Stream[IO, ChainSyncResponse] =
    if inFlight <= 0 then Stream.empty
    else
      Stream.eval(recv).flatMap {
        case ChainSyncMessage.MsgAwaitReply =>
          // At the tip — drain remaining in-flight, then switch to single-request
          Stream.eval(recv).flatMap {
            case ChainSyncMessage.MsgRollForward(header, tip) =>
              Stream.emit(ChainSyncResponse.RollForward(header, tip)) ++
                drainAndContinue(inFlight - 1)
            case ChainSyncMessage.MsgRollBackward(point, tip) =>
              Stream.emit(ChainSyncResponse.RollBackward(point, tip)) ++
                drainAndContinue(inFlight - 1)
            case other =>
              Stream.raiseError[IO](
                new RuntimeException(s"Unexpected message after MsgAwaitReply: $other")
              )
          }
        case ChainSyncMessage.MsgRollForward(header, tip) =>
          // Got a response — send another request to refill the window
          Stream.eval(send(ChainSyncMessage.MsgRequestNext)) >>
            Stream.emit(ChainSyncResponse.RollForward(header, tip)) ++
            pipelineLoop(inFlight, windowSize)
        case ChainSyncMessage.MsgRollBackward(point, tip) =>
          Stream.eval(send(ChainSyncMessage.MsgRequestNext)) >>
            Stream.emit(ChainSyncResponse.RollBackward(point, tip)) ++
            pipelineLoop(inFlight, windowSize)
        case other =>
          Stream.raiseError[IO](
            new RuntimeException(s"Unexpected response in pipeline: $other")
          )
      }

  /**
   * Receive one response from the peer without sending a new request.
   * Handles MsgAwaitReply (server at tip, waiting for new block).
   */
  private def recvResponse: IO[ChainSyncResponse] =
    recv.flatMap {
      case ChainSyncMessage.MsgAwaitReply =>
        recv.flatMap {
          case ChainSyncMessage.MsgRollForward(header, tip) =>
            IO.pure(ChainSyncResponse.RollForward(header, tip))
          case ChainSyncMessage.MsgRollBackward(point, tip) =>
            IO.pure(ChainSyncResponse.RollBackward(point, tip))
          case other =>
            IO.raiseError(new RuntimeException(s"Unexpected message after MsgAwaitReply: $other"))
        }
      case ChainSyncMessage.MsgRollForward(header, tip) =>
        IO.pure(ChainSyncResponse.RollForward(header, tip))
      case ChainSyncMessage.MsgRollBackward(point, tip) =>
        IO.pure(ChainSyncResponse.RollBackward(point, tip))
      case other =>
        IO.raiseError(new RuntimeException(s"Unexpected message during drain: $other"))
    }

  /**
   * Transition from pipelined to single-request mode at tip.
   *
   * Consumes `remaining` old in-flight responses (recv-only, no new sends),
   * then switches to requestNext (send+recv) for tip-following.
   *
   * Each old response may take ~20s at tip (server waits for new block),
   * so we consume them one at a time and emit each immediately rather than
   * trying to drain all at once before switching modes.
   */
  private def drainAndContinue(remaining: Int): Stream[IO, ChainSyncResponse] =
    if remaining <= 0 then Stream.repeatEval(requestNext)
    else
      // Consume one old in-flight response (no new request sent)
      Stream.eval(recvResponse).flatMap { response =>
        Stream.emit(response) ++ drainAndContinue(remaining - 1)
      }

  /** Simple non-pipelined header stream (backward compat). */
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
