package stretto.storage

import cats.effect.IO
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*

/**
 * Syncs full blocks (headers + block bodies) from a combined
 * ChainSync + BlockFetch stream into persistent storage.
 *
 * Mirrors the HeaderSyncer pattern but stores block data alongside headers.
 */
final class BlockSyncer(val store: RocksDbStore):

  /**
   * Persist a batch of headers + blocks atomically.
   * Each entry contains (point, headerBytes, blockNo, blockBytes).
   */
  def rollForwardBatchWithBlocks(
      entries: List[(Point.BlockPoint, ByteVector, BlockNo, ByteVector)],
      tip: Tip
  ): IO[Unit] =
    if entries.isEmpty then IO.unit
    else store.putBatchWithBlocks(entries, tip)

  /**
   * Handle a MsgRollBackward — just update the tip.
   */
  def rollBackward(tip: Tip): IO[Unit] =
    store.putTip(tip)

  /** Get the stored tip to resume syncing after restart. */
  def currentTip: IO[Option[Tip]] =
    store.getTip

  /**
   * Build a list of known points for MsgFindIntersect.
   * Uses exponential back-off: tip, tip-1, tip-2, tip-4, tip-8, ..., origin.
   */
  def knownPoints: IO[List[Point]] =
    store.recentPoints(100).map { points =>
      if points.isEmpty then List(Point.Origin)
      else
        val selected = exponentialBackoff(points)
        selected :+ Point.Origin
    }

  private def exponentialBackoff(points: List[Point.BlockPoint]): List[Point] =
    val indexed = points.zipWithIndex
    val picks = Iterator
      .iterate(0)(_ * 2 max 1)
      .takeWhile(_ < points.size)
      .toList
      .distinct
    picks.flatMap(i => indexed.lift(i).map(_._1))
