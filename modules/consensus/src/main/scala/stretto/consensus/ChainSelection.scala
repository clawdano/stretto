package stretto.consensus

import scodec.bits.ByteVector
import stretto.core.Types.*

/**
 * Chain selection for Ouroboros Praos.
 *
 * Rules (in order of priority):
 * 1. Prefer the chain with more blocks (higher block number at tip)
 * 2. Tiebreaker: prefer higher OCert counter at the fork point
 * 3. Tiebreaker: prefer higher VRF output (as big-endian unsigned integer)
 *
 * Only consider forks within the last k blocks (security parameter).
 * Blocks with more than k confirmations are immutable.
 */
object ChainSelection:

  /** Comparison result between two chain candidates. */
  enum Preference:
    case PreferFirst
    case PreferSecond
    case Equal

  /** A chain candidate's tip information for comparison. */
  final case class ChainCandidate(
      blockNo: BlockNo,
      ocertCounter: Long,
      vrfOutput: ByteVector // 64-byte VRF output for tiebreaking
  )

  /**
   * Compare two chain candidates and return which is preferred.
   *
   * @param a First chain candidate
   * @param b Second chain candidate
   * @return Which chain is preferred
   */
  def compare(a: ChainCandidate, b: ChainCandidate): Preference =
    // 1. Primary: highest block number
    val blockCmp = Ordering[Long].compare(a.blockNo.blockNoValue, b.blockNo.blockNoValue)
    if blockCmp > 0 then return Preference.PreferFirst
    if blockCmp < 0 then return Preference.PreferSecond

    // 2. Tiebreaker: higher OCert counter
    val ocertCmp = Ordering[Long].compare(a.ocertCounter, b.ocertCounter)
    if ocertCmp > 0 then return Preference.PreferFirst
    if ocertCmp < 0 then return Preference.PreferSecond

    // 3. Tiebreaker: higher VRF output (big-endian unsigned)
    val vrfA   = if a.vrfOutput.nonEmpty then BigInt(1, a.vrfOutput.toArray) else BigInt(0)
    val vrfB   = if b.vrfOutput.nonEmpty then BigInt(1, b.vrfOutput.toArray) else BigInt(0)
    val vrfCmp = vrfA.compare(vrfB)
    if vrfCmp > 0 then Preference.PreferFirst
    else if vrfCmp < 0 then Preference.PreferSecond
    else Preference.Equal

  /**
   * Check if a fork point is within the security window.
   * Only forks within the last k blocks should be considered.
   *
   * @param ourTipBlockNo Current chain tip block number
   * @param forkBlockNo   Block number where the fork diverges
   * @param k             Security parameter (2160 on mainnet)
   * @return true if the fork is within k blocks of our tip
   */
  def isForkWithinWindow(ourTipBlockNo: BlockNo, forkBlockNo: BlockNo, k: Long = 2160L): Boolean =
    ourTipBlockNo.blockNoValue - forkBlockNo.blockNoValue <= k

  /**
   * Check if a block has enough confirmations to be considered immutable.
   *
   * @param blockNo    The block number to check
   * @param tipBlockNo Current chain tip block number
   * @param k          Security parameter (2160 on mainnet)
   * @return true if the block has > k confirmations
   */
  def isImmutable(blockNo: BlockNo, tipBlockNo: BlockNo, k: Long = 2160L): Boolean =
    tipBlockNo.blockNoValue - blockNo.blockNoValue > k
