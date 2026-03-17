package stretto.consensus

import scodec.bits.ByteVector
import stretto.core.Types.*

/**
 * Stake distribution for leader election.
 *
 * Cardano uses a three-snapshot pipeline (mark → set → go):
 *   - `go`  = active distribution for leader election (two epochs old)
 *   - `set` = previous snapshot (will become `go` next epoch)
 *   - `mark` = current UTxO snapshot (will become `set` next epoch)
 *
 * At each epoch boundary: go = old_set, set = old_mark, mark = current UTxO snapshot.
 */
final case class StakeDistribution(
    poolStakes: Map[ByteVector, Long], // issuerVkeyHash → delegated stake in lovelace
    totalActiveStake: Long
):

  /**
   * Look up a pool's relative stake for leader election.
   *
   * @param issuerVkeyHash Blake2b-256 hash of the pool's issuer (cold) verification key
   * @return Some(PoolInfo) if registered, None otherwise
   */
  def lookupPool(issuerVkeyHash: ByteVector, vrfKeyHashes: Map[ByteVector, Hash32]): Option[PoolInfo] =
    for
      stake      <- poolStakes.get(issuerVkeyHash)
      vrfKeyHash <- vrfKeyHashes.get(issuerVkeyHash)
    yield PoolInfo(
      vrfKeyHash = vrfKeyHash,
      relativeStakeNum = stake,
      relativeStakeDen = totalActiveStake
    )

object StakeDistribution:
  val empty: StakeDistribution = StakeDistribution(Map.empty, 0L)

/** Three-snapshot pipeline for stake distribution rotation. */
final case class StakeSnapshots(
    go: StakeDistribution,  // Active: used for current epoch's leader election
    set: StakeDistribution, // Previous: will become `go` next epoch
    mark: StakeDistribution // Current: being accumulated from live UTxO
):

  /**
   * Rotate snapshots at epoch boundary.
   * @param newMark Fresh snapshot from current UTxO state
   */
  def rotate(newMark: StakeDistribution): StakeSnapshots =
    StakeSnapshots(go = set, set = mark, mark = newMark)

object StakeSnapshots:

  def initial(genesis: StakeDistribution): StakeSnapshots =
    StakeSnapshots(go = genesis, set = genesis, mark = genesis)
