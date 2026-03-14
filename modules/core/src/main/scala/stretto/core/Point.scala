package stretto.core

import stretto.core.Types.*

enum Point:
  case Origin
  case BlockPoint(slotNo: SlotNo, blockHash: BlockHeaderHash)

object Point:
  given Ordering[Point] = Ordering.fromLessThan:
    case (Point.Origin, Point.Origin)       => false
    case (Point.Origin, _)                  => true
    case (_, Point.Origin)                  => false
    case (Point.BlockPoint(s1, _), Point.BlockPoint(s2, _)) =>
      summon[Ordering[SlotNo]].lt(s1, s2)
