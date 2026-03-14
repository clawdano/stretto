package stretto.core

import scodec.bits.ByteVector
import stretto.core.Types.*

class PointSpec extends munit.FunSuite:

  private val hash1 = BlockHeaderHash(Hash32.unsafeFrom(ByteVector.fill(32)(0x01)))
  private val hash2 = BlockHeaderHash(Hash32.unsafeFrom(ByteVector.fill(32)(0x02)))

  private val ord = summon[Ordering[Point]]

  test("Origin equals Origin") {
    assertEquals(Point.Origin, Point.Origin)
  }

  test("Origin is less than any BlockPoint") {
    assert(ord.lt(Point.Origin, Point.BlockPoint(SlotNo(0L), hash1)))
  }

  test("BlockPoint is greater than Origin") {
    assert(ord.gt(Point.BlockPoint(SlotNo(0L), hash1), Point.Origin))
  }

  test("Origin compared to Origin is neither less nor greater") {
    assert(!ord.lt(Point.Origin, Point.Origin))
    assert(!ord.gt(Point.Origin, Point.Origin))
  }

  test("BlockPoint ordering by slot number — lower slot is less") {
    val p1 = Point.BlockPoint(SlotNo(10L), hash1)
    val p2 = Point.BlockPoint(SlotNo(20L), hash2)
    assert(ord.lt(p1, p2))
  }

  test("BlockPoint ordering by slot number — higher slot is greater") {
    val p1 = Point.BlockPoint(SlotNo(100L), hash1)
    val p2 = Point.BlockPoint(SlotNo(50L), hash2)
    assert(ord.gt(p1, p2))
  }

  test("BlockPoints with same slot are equivalent in ordering") {
    val p1 = Point.BlockPoint(SlotNo(5L), hash1)
    val p2 = Point.BlockPoint(SlotNo(5L), hash2)
    assert(ord.equiv(p1, p2))
  }

  test("BlockPoint equality requires same slot and hash") {
    val p1 = Point.BlockPoint(SlotNo(5L), hash1)
    val p2 = Point.BlockPoint(SlotNo(5L), hash1)
    assertEquals(p1, p2)
  }

  test("BlockPoints with different hashes are not equal") {
    val p1 = Point.BlockPoint(SlotNo(5L), hash1)
    val p2 = Point.BlockPoint(SlotNo(5L), hash2)
    assertNotEquals(p1, p2)
  }
