package stretto.storage

import cats.effect.IO
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*

/**
 * Syncs block headers from a ChainSync stream into persistent storage.
 *
 * This is a pure data-processing component — it doesn't know about the
 * network layer directly. Feed it (header, tip, blockNo) tuples from the
 * ChainSync client.
 */
final class HeaderSyncer(store: RocksDbStore):

  /**
   * Persist a new header that arrived via MsgRollForward.
   *
   * Extracts the point from the header bytes (era-wrapped format),
   * stores the header, updates the height index, and advances the tip.
   */
  def rollForward(
      point: Point.BlockPoint,
      header: ByteVector,
      blockNo: BlockNo,
      tip: Tip
  ): IO[Unit] =
    store.putHeaderWithMeta(point, header, blockNo, tip)

  /**
   * Handle a MsgRollBackward.
   *
   * For now, just update the tip. A full implementation would also
   * remove rolled-back headers and height index entries.
   */
  def rollBackward(tip: Tip): IO[Unit] =
    store.putTip(tip)

  /** Get the stored tip to resume syncing after restart. */
  def currentTip: IO[Option[Tip]] =
    store.getTip

  /**
   * Build a list of known points for MsgFindIntersect.
   *
   * Uses exponential back-off: tip, tip-1, tip-2, tip-4, tip-8, ..., origin.
   * This allows O(log n) intersection finding.
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
