package stretto.consensus

import scodec.bits.ByteVector
import stretto.core.Crypto
import stretto.core.Types.*

/**
 * Epoch nonce evolution for Ouroboros Praos.
 *
 * The epoch nonce determines VRF inputs for leader election. It evolves across epochs:
 *   epochNonce(e) = Blake2b_256(candidateNonce(e-1) || lastEpochBlockNonce(e-1))
 *
 * Within an epoch, a candidate nonce accumulates VRF outputs from blocks in the
 * first `stabilityWindow` slots (3k/f = 129,600 slots on mainnet). After that
 * window, the candidate nonce is frozen for the remainder of the epoch.
 */
object EpochNonce:

  /**
   * Compute the epoch nonce for epoch e from the previous epoch's accumulated nonces.
   *
   * @param candidateNonce   Accumulated nonce from VRF outputs in the stability window of epoch e-1
   * @param lastBlockNonce   The extra entropy / block VRF nonce from epoch e-1 (often all zeros pre-Alonzo,
   *                         or the accumulated VRF nonce from the last block of epoch e-1)
   * @return The epoch nonce for epoch e
   */
  def mkEpochNonce(candidateNonce: ByteVector, lastBlockNonce: ByteVector): ByteVector =
    Crypto.blake2b256(candidateNonce ++ lastBlockNonce)

  /**
   * Accumulate a block's VRF output into the evolving nonce.
   *
   * @param currentNonce The current evolving nonce (32 bytes)
   * @param vrfOutput    The VRF output from the new block (64 bytes)
   * @return Updated evolving nonce
   */
  def accumulateNonce(currentNonce: ByteVector, vrfOutput: ByteVector): ByteVector =
    Crypto.blake2b256(currentNonce ++ Crypto.blake2b256(vrfOutput))

/** Tracks the evolving epoch nonce state within a single epoch. */
final case class EpochNonceState(
    epochNonce: ByteVector,    // The fixed nonce for this epoch (used for VRF inputs)
    evolvingNonce: ByteVector, // Accumulates VRF outputs from blocks in stability window
    frozen: Boolean            // Whether the stability window has passed
):

  /**
   * Update with a new block's VRF output if still within the stability window.
   *
   * @param vrfOutput       The VRF output from the new block
   * @param slotInEpoch     The slot's position within the current epoch
   * @param stabilityWindow Number of slots in the stability window (3k/f)
   * @return Updated state
   */
  def updateWithBlock(vrfOutput: ByteVector, slotInEpoch: Long, stabilityWindow: Long): EpochNonceState =
    if frozen || slotInEpoch >= stabilityWindow then copy(frozen = true)
    else copy(evolvingNonce = EpochNonce.accumulateNonce(evolvingNonce, vrfOutput))

object EpochNonceState:

  /**
   * Initialize state for the first epoch or after a restart.
   * The genesis epoch nonce is typically derived from the genesis hash.
   */
  def initial(epochNonce: ByteVector): EpochNonceState =
    EpochNonceState(
      epochNonce = epochNonce,
      evolvingNonce = epochNonce,
      frozen = false
    )
