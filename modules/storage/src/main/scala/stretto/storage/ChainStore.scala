package stretto.storage

import cats.effect.IO
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*

/**
 * Abstract interface for persistent chain storage.
 *
 * Stores block headers, tracks the chain tip, and supports lookup by
 * slot+hash (point) or by block number.
 */
trait ChainStore:

  /** Persist a block header. */
  def putHeader(point: Point.BlockPoint, header: ByteVector): IO[Unit]

  /** Retrieve a block header by point. */
  def getHeader(point: Point.BlockPoint): IO[Option[ByteVector]]

  /** Persist the current chain tip. */
  def putTip(tip: Tip): IO[Unit]

  /** Retrieve the stored chain tip, if any. */
  def getTip: IO[Option[Tip]]

  /** Get the most recent N headers (by block number), newest first. */
  def recentPoints(count: Int): IO[List[Point.BlockPoint]]

  /** Store multiple headers with metadata in a single atomic batch. */
  def putBatch(
      entries: List[(Point.BlockPoint, ByteVector, BlockNo)],
      tip: Tip
  ): IO[Unit]

  /** Persist a full block body. */
  def putBlock(point: Point.BlockPoint, blockData: ByteVector): IO[Unit]

  /** Retrieve a full block body by point. */
  def getBlock(point: Point.BlockPoint): IO[Option[ByteVector]]

  /** Store headers + blocks + height index + tip in a single atomic batch. */
  def putBatchWithBlocks(
      entries: List[(Point.BlockPoint, ByteVector, BlockNo, ByteVector)],
      tip: Tip
  ): IO[Unit]
