package stretto.consensus

import munit.FunSuite
import scodec.bits.ByteVector
import stretto.core.Types.*

class ChainSelectionSpec extends FunSuite:

  import ChainSelection.*

  test("prefer chain with higher block number") {
    val a = ChainCandidate(BlockNo(100L), ocertCounter = 0, vrfOutput = ByteVector.fill(64)(0))
    val b = ChainCandidate(BlockNo(99L), ocertCounter = 0, vrfOutput = ByteVector.fill(64)(0))
    assertEquals(compare(a, b), Preference.PreferFirst)
    assertEquals(compare(b, a), Preference.PreferSecond)
  }

  test("same block number: prefer higher OCert counter") {
    val a = ChainCandidate(BlockNo(100L), ocertCounter = 5, vrfOutput = ByteVector.fill(64)(0))
    val b = ChainCandidate(BlockNo(100L), ocertCounter = 3, vrfOutput = ByteVector.fill(64)(0))
    assertEquals(compare(a, b), Preference.PreferFirst)
    assertEquals(compare(b, a), Preference.PreferSecond)
  }

  test("same block number and OCert: prefer higher VRF output") {
    val a = ChainCandidate(BlockNo(100L), ocertCounter = 5, vrfOutput = ByteVector.fill(64)(0xff.toByte))
    val b = ChainCandidate(BlockNo(100L), ocertCounter = 5, vrfOutput = ByteVector.fill(64)(0x01))
    assertEquals(compare(a, b), Preference.PreferFirst)
    assertEquals(compare(b, a), Preference.PreferSecond)
  }

  test("identical candidates are equal") {
    val a = ChainCandidate(BlockNo(100L), ocertCounter = 5, vrfOutput = ByteVector.fill(64)(0xab.toByte))
    assertEquals(compare(a, a), Preference.Equal)
  }

  test("fork within window is accepted") {
    assert(isForkWithinWindow(BlockNo(3000L), BlockNo(1000L), k = 2160L))
  }

  test("fork outside window is rejected") {
    assert(!isForkWithinWindow(BlockNo(5000L), BlockNo(1000L), k = 2160L))
  }

  test("fork exactly at k boundary is accepted") {
    assert(isForkWithinWindow(BlockNo(3160L), BlockNo(1000L), k = 2160L))
  }

  test("block with > k confirmations is immutable") {
    assert(isImmutable(BlockNo(100L), BlockNo(2361L), k = 2160L))
  }

  test("block with <= k confirmations is not immutable") {
    assert(!isImmutable(BlockNo(100L), BlockNo(2260L), k = 2160L))
  }

  test("block with exactly k confirmations is not immutable") {
    assert(!isImmutable(BlockNo(100L), BlockNo(2260L), k = 2160L))
  }

  test("tip block is never immutable") {
    assert(!isImmutable(BlockNo(100L), BlockNo(100L), k = 2160L))
  }
