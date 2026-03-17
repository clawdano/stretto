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
 * When approaching the peer's tip, the pipeline automatically reduces
 * the window to avoid a long drain of in-flight requests at tip speed
 * (~20s per block). This is detected by comparing the response's slot
 * with the peer's tip slot.
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
   * Pipelined header stream — high-throughput chain sync with adaptive window.
   *
   * Sends `windowSize` MsgRequestNext messages upfront, then for each
   * response received, sends another request to maintain the pipeline.
   * This eliminates the round-trip latency bottleneck during bulk sync.
   *
   * When the response's slot approaches the peer's tip slot (within
   * `nearTipSlotThreshold` slots), the pipeline stops sending replacement
   * requests, naturally draining the window to 0 before reaching tip.
   * This avoids the ~33min drain that occurred with a fixed 100-request window.
   *
   * Once drained, falls back to single-request mode for tip-following.
   */
  def pipelinedHeaderStream(
      knownPoints: List[Point],
      windowSize: Int = 100,
      nearTipSlotThreshold: Long = 600 // ~10 min of slots: stop refilling when within this distance
  ): Stream[IO, ChainSyncResponse] =
    Stream.eval(findIntersect(knownPoints)).flatMap { intersectResult =>
      // Determine if we're already near tip from the intersection result
      val peerTip = intersectResult match
        case Right((_, tip)) => tip
        case Left(tip)       => tip
      val intersectSlot = intersectResult match
        case Right((Point.BlockPoint(slot, _), _)) => slot.value
        case _                                     => 0L
      val tipSlot = peerTip.point match
        case bp: Point.BlockPoint => bp.slotNo.value
        case _                    => Long.MaxValue
      // If we're already near tip, use minimal window to avoid massive drain
      val effectiveWindow = if (tipSlot - intersectSlot) < nearTipSlotThreshold then 1 else windowSize

      Stream.eval(sendBurst(effectiveWindow)).flatMap { inFlight =>
        pipelineLoop(inFlight, nearTipSlotThreshold)
      }
    }

  /** Send N MsgRequestNext messages in a burst. */
  private def sendBurst(n: Int): IO[Int] =
    (1 to n).toList.traverse_(_ => send(ChainSyncMessage.MsgRequestNext)).as(n)

  /** Extract slot number from a raw era-wrapped header for tip-proximity check. */
  private def extractSlotFromHeader(header: ByteVector): Option[Long] =
    HeaderParser.parse(header).toOption.map(_.slotNo.value)

  /**
   * Core pipeline loop: receive a response, optionally send a new request
   * to keep the window full, emit the response downstream.
   *
   * Automatically reduces the window when approaching the peer's tip,
   * preventing a long drain of in-flight requests.
   */
  private def pipelineLoop(inFlight: Int, nearTipSlotThreshold: Long): Stream[IO, ChainSyncResponse] =
    if inFlight <= 0 then
      // Window fully drained — switch to single-request mode for tip-following
      Stream.repeatEval(requestNext)
    else
      Stream.eval(recv).flatMap {
        case ChainSyncMessage.MsgAwaitReply =>
          // At the tip — receive the awaited response, then drain remaining without sending
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
          // Check if we're close to the peer's tip
          val tipSlot = tip.point match
            case bp: Point.BlockPoint => bp.slotNo.value
            case _                    => Long.MaxValue
          val headerSlot = extractSlotFromHeader(header).getOrElse(0L)
          val nearTip    = (tipSlot - headerSlot) < nearTipSlotThreshold

          if nearTip then
            // Close to tip: don't send replacement request, let window shrink
            Stream.emit(ChainSyncResponse.RollForward(header, tip)) ++
              pipelineLoop(inFlight - 1, nearTipSlotThreshold)
          else
            // Far from tip: send replacement to maintain pipeline throughput
            Stream.eval(send(ChainSyncMessage.MsgRequestNext)) >>
              Stream.emit(ChainSyncResponse.RollForward(header, tip)) ++
              pipelineLoop(inFlight, nearTipSlotThreshold)

        case ChainSyncMessage.MsgRollBackward(point, tip) =>
          // On rollback, don't refill (conservative)
          Stream.emit(ChainSyncResponse.RollBackward(point, tip)) ++
            pipelineLoop(inFlight - 1, nearTipSlotThreshold)
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
   * Drain remaining in-flight responses (recv-only, no new sends),
   * then continue with single-request mode for tip-following.
   */
  private def drainAndContinue(remaining: Int): Stream[IO, ChainSyncResponse] =
    if remaining <= 0 then Stream.repeatEval(requestNext)
    else
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
