package stretto.consensus

import munit.FunSuite
import scodec.bits.ByteVector

class EpochNonceSpec extends FunSuite:

  test("mkEpochNonce produces 32-byte hash") {
    val candidate = ByteVector.fill(32)(0x01)
    val lastBlock = ByteVector.fill(32)(0x02)
    val nonce     = EpochNonce.mkEpochNonce(candidate, lastBlock)
    assertEquals(nonce.size, 32L)
  }

  test("mkEpochNonce is deterministic") {
    val candidate = ByteVector.fill(32)(0xab)
    val lastBlock = ByteVector.fill(32)(0xcd)
    val n1        = EpochNonce.mkEpochNonce(candidate, lastBlock)
    val n2        = EpochNonce.mkEpochNonce(candidate, lastBlock)
    assertEquals(n1, n2)
  }

  test("mkEpochNonce changes with different inputs") {
    val candidate = ByteVector.fill(32)(0x01)
    val n1        = EpochNonce.mkEpochNonce(candidate, ByteVector.fill(32)(0x02))
    val n2        = EpochNonce.mkEpochNonce(candidate, ByteVector.fill(32)(0x03))
    assertNotEquals(n1, n2)
  }

  test("accumulateNonce produces 32-byte hash") {
    val current   = ByteVector.fill(32)(0x01)
    val vrfOutput = ByteVector.fill(64)(0xff)
    val result    = EpochNonce.accumulateNonce(current, vrfOutput)
    assertEquals(result.size, 32L)
  }

  test("accumulateNonce is order-dependent") {
    val nonce = ByteVector.fill(32)(0x00)
    val vrf1  = ByteVector.fill(64)(0x01)
    val vrf2  = ByteVector.fill(64)(0x02)
    // Apply in different orders
    val r1 = EpochNonce.accumulateNonce(EpochNonce.accumulateNonce(nonce, vrf1), vrf2)
    val r2 = EpochNonce.accumulateNonce(EpochNonce.accumulateNonce(nonce, vrf2), vrf1)
    assertNotEquals(r1, r2)
  }

  test("EpochNonceState: updates evolving nonce within stability window") {
    val state   = EpochNonceState.initial(ByteVector.fill(32)(0x01))
    val vrfOut  = ByteVector.fill(64)(0xab)
    val updated = state.updateWithBlock(vrfOut, slotInEpoch = 100L, stabilityWindow = 129600L)
    assertNotEquals(updated.evolvingNonce, state.evolvingNonce)
    assert(!updated.frozen)
  }

  test("EpochNonceState: freezes after stability window") {
    val state   = EpochNonceState.initial(ByteVector.fill(32)(0x01))
    val vrfOut  = ByteVector.fill(64)(0xab)
    val updated = state.updateWithBlock(vrfOut, slotInEpoch = 129600L, stabilityWindow = 129600L)
    assert(updated.frozen)
    assertEquals(updated.evolvingNonce, state.evolvingNonce, "nonce should not change after freeze")
  }

  test("EpochNonceState: stays frozen once frozen") {
    val state  = EpochNonceState.initial(ByteVector.fill(32)(0x01))
    val frozen = state.copy(frozen = true)
    val vrfOut = ByteVector.fill(64)(0xff)
    val result = frozen.updateWithBlock(vrfOut, slotInEpoch = 0L, stabilityWindow = 129600L)
    assert(result.frozen)
    assertEquals(result.evolvingNonce, frozen.evolvingNonce)
  }
