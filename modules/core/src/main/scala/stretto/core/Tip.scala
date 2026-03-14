package stretto.core

import stretto.core.Types.*

/** The tip of a chain as reported by a peer: a point plus the block number. */
final case class Tip(point: Point, blockNo: BlockNo)

object Tip:
  val origin: Tip = Tip(Point.Origin, BlockNo(0L))
