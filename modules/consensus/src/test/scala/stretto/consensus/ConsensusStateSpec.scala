package stretto.consensus

import munit.FunSuite
import scodec.bits.ByteVector
import stretto.core.Types.*

class ConsensusStateSpec extends FunSuite:

  private val genesisNonce = ByteVector.fill(32)(0x01)
  private val pool1Hash    = ByteVector.fill(32)(0xaa)
  private val pool2Hash    = ByteVector.fill(32)(0xbb)

  private val genesisStake = StakeDistribution(
    poolStakes = Map(pool1Hash -> 500000L, pool2Hash -> 300000L),
    totalActiveStake = 1000000L
  )

  private val vrfKeyHashes = Map(
    pool1Hash -> Hash32.unsafeFrom(ByteVector.fill(32)(0x11)),
    pool2Hash -> Hash32.unsafeFrom(ByteVector.fill(32)(0x22))
  )

  private def mkState(): ConsensusState =
    ConsensusState.initial(genesisNonce, genesisStake, vrfKeyHashes)

  test("initial state has epoch 0") {
    val state = mkState()
    assertEquals(state.currentEpoch.epochNoValue, 0L)
  }

  test("lookupPool returns info for registered pool") {
    val state = mkState()
    val info  = state.lookupPool(pool1Hash)
    assert(info.isDefined)
    assertEquals(info.get.relativeStakeNum, 500000L)
    assertEquals(info.get.relativeStakeDen, 1000000L)
  }

  test("lookupPool returns None for unregistered pool") {
    val state = mkState()
    val info  = state.lookupPool(ByteVector.fill(32)(0xff))
    assert(info.isEmpty)
  }

  test("processBlock updates OCert counter") {
    val state  = mkState()
    val result = state.processBlock(pool1Hash, ocertCounter = 1, ByteVector.fill(64)(0), slotInEpoch = 100L)
    assert(result.isRight)
    assertEquals(result.toOption.get.ocertCounters(pool1Hash), 1L)
  }

  test("processBlock rejects non-increasing OCert counter") {
    val state = mkState()
    val s1    = state.processBlock(pool1Hash, ocertCounter = 5, ByteVector.fill(64)(0), slotInEpoch = 100L).toOption.get
    val result = s1.processBlock(pool1Hash, ocertCounter = 5, ByteVector.fill(64)(0), slotInEpoch = 200L)
    assert(result.isLeft)
  }

  test("processBlock accepts strictly increasing OCert counter") {
    val state = mkState()
    val s1    = state.processBlock(pool1Hash, ocertCounter = 1, ByteVector.fill(64)(0), slotInEpoch = 100L).toOption.get
    val result = s1.processBlock(pool1Hash, ocertCounter = 2, ByteVector.fill(64)(0), slotInEpoch = 200L)
    assert(result.isRight)
  }

  test("processBlock updates evolving nonce") {
    val state   = mkState()
    val vrfOut  = ByteVector.fill(64)(0xab)
    val updated = state.processBlock(pool1Hash, 1, vrfOut, slotInEpoch = 100L).toOption.get
    assertNotEquals(updated.nonceState.evolvingNonce, state.nonceState.evolvingNonce)
  }

  test("epochTransition advances epoch number") {
    val state = mkState()
    val next  = state.epochTransition(EpochNo(1L), StakeDistribution.empty)
    assertEquals(next.currentEpoch.epochNoValue, 1L)
  }

  test("epochTransition rotates stake snapshots") {
    val state    = mkState()
    val newStake = StakeDistribution(Map(pool1Hash -> 999L), 999L)
    val s1       = state.epochTransition(EpochNo(1L), newStake)
    // After rotation: go=old_set=genesisStake, set=old_mark=genesisStake, mark=newStake
    assertEquals(s1.stakeSnapshots.go.totalActiveStake, genesisStake.totalActiveStake)
    assertEquals(s1.stakeSnapshots.mark.totalActiveStake, 999L)

    // Another rotation
    val newStake2 = StakeDistribution(Map(pool1Hash -> 777L), 777L)
    val s2        = s1.epochTransition(EpochNo(2L), newStake2)
    // Now go should be genesisStake (was set), set should be newStake (was mark)
    assertEquals(s2.stakeSnapshots.go.totalActiveStake, genesisStake.totalActiveStake)
    assertEquals(s2.stakeSnapshots.set.totalActiveStake, 999L)

    // Third rotation: go finally gets newStake
    val s3 = s2.epochTransition(EpochNo(3L), StakeDistribution.empty)
    assertEquals(s3.stakeSnapshots.go.totalActiveStake, 999L)
  }

  test("epochTransition computes new epoch nonce") {
    val state = mkState()
    val next  = state.epochTransition(EpochNo(1L), StakeDistribution.empty)
    assertNotEquals(next.nonceState.epochNonce, state.nonceState.epochNonce)
    assertEquals(next.nonceState.epochNonce.size, 32L)
  }

  test("epochTransition resets frozen state") {
    val state  = mkState()
    val frozen = state.copy(nonceState = state.nonceState.copy(frozen = true))
    val next   = frozen.epochTransition(EpochNo(1L), StakeDistribution.empty)
    assert(!next.nonceState.frozen)
  }
