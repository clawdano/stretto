package stretto.consensus

import scodec.bits.ByteVector
import stretto.core.Types.*

/**
 * Full consensus state tracking across epochs.
 *
 * Tracks:
 *   - Epoch nonce evolution
 *   - Stake distribution snapshots (3-pipeline)
 *   - OCert counters per pool (monotonicity enforcement)
 *   - Consensus parameters
 */
final case class ConsensusState(
    currentEpoch: EpochNo,
    nonceState: EpochNonceState,
    stakeSnapshots: StakeSnapshots,
    ocertCounters: Map[ByteVector, Long], // issuerVkeyHash → last seen counter
    params: ConsensusParams,
    vrfKeyHashes: Map[ByteVector, Hash32], // issuerVkeyHash → registered VRF key hash
    stabilityWindow: Long                  // 3k/f slots (129,600 on mainnet)
):

  /**
   * Check OCert counter monotonicity and update.
   *
   * @param issuerVkeyHash Pool issuer key hash
   * @param counter        The OCert counter from the new block
   * @return Right(updatedState) if counter is valid (strictly increasing or first seen), Left(error) otherwise
   */
  def checkAndUpdateOcertCounter(
      issuerVkeyHash: ByteVector,
      counter: Long
  ): Either[String, ConsensusState] =
    ocertCounters.get(issuerVkeyHash) match
      case Some(lastCounter) if counter <= lastCounter =>
        Left(s"OCert counter $counter not greater than last seen $lastCounter for pool ${issuerVkeyHash.toHex}")
      case _ =>
        Right(copy(ocertCounters = ocertCounters.updated(issuerVkeyHash, counter)))

  /**
   * Process a new block: update evolving nonce and OCert counter.
   *
   * @param issuerVkeyHash Pool issuer key hash
   * @param ocertCounter   OCert counter from the block header
   * @param vrfOutput      VRF output from the block header
   * @param slotInEpoch    Slot offset within the current epoch
   * @return Right(updatedState) or Left(error)
   */
  def processBlock(
      issuerVkeyHash: ByteVector,
      ocertCounter: Long,
      vrfOutput: ByteVector,
      slotInEpoch: Long
  ): Either[String, ConsensusState] =
    for updated <- checkAndUpdateOcertCounter(issuerVkeyHash, ocertCounter)
    yield updated.copy(
      nonceState = updated.nonceState.updateWithBlock(vrfOutput, slotInEpoch, stabilityWindow)
    )

  /**
   * Transition to a new epoch.
   *
   * @param newEpoch      The new epoch number
   * @param newMark       Fresh stake snapshot from current UTxO
   * @param extraEntropy  Extra entropy contribution (all zeros if none)
   * @return Updated state for the new epoch
   */
  def epochTransition(
      newEpoch: EpochNo,
      newMark: StakeDistribution,
      extraEntropy: ByteVector = ByteVector.fill(32)(0)
  ): ConsensusState =
    val newEpochNonce = EpochNonce.mkEpochNonce(nonceState.evolvingNonce, extraEntropy)
    copy(
      currentEpoch = newEpoch,
      nonceState = EpochNonceState.initial(newEpochNonce),
      stakeSnapshots = stakeSnapshots.rotate(newMark)
    )

  /** Look up pool info from the active (go) stake distribution. */
  def lookupPool(issuerVkeyHash: ByteVector): Option[PoolInfo] =
    stakeSnapshots.go.lookupPool(issuerVkeyHash, vrfKeyHashes)

object ConsensusState:

  /** Default mainnet parameters. */
  val MainnetK: Long                 = 2160L
  val MainnetSlotsPerEpoch: Long     = 432000L
  val MainnetActiveSlotCoeff: Double = 0.05    // f = 1/20
  val MainnetStabilityWindow: Long   = 129600L // 3k/f = 3 * 2160 / 0.05

  /** Create initial consensus state (e.g., from genesis). */
  def initial(
      genesisNonce: ByteVector,
      genesisStake: StakeDistribution,
      vrfKeyHashes: Map[ByteVector, Hash32] = Map.empty,
      params: ConsensusParams = ConsensusParams(),
      stabilityWindow: Long = MainnetStabilityWindow
  ): ConsensusState =
    ConsensusState(
      currentEpoch = EpochNo(0L),
      nonceState = EpochNonceState.initial(genesisNonce),
      stakeSnapshots = StakeSnapshots.initial(genesisStake),
      ocertCounters = Map.empty,
      params = params,
      vrfKeyHashes = vrfKeyHashes,
      stabilityWindow = stabilityWindow
    )
