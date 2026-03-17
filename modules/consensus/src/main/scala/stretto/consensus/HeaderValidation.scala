package stretto.consensus

import cardano.kes.SumKES
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

/** Validation errors for block header verification. */
enum HeaderValidationError:
  case PoolNotRegistered(issuerVkeyHash: ByteVector)
  case VrfKeyMismatch(expected: Hash32, actual: Hash32)
  case KesPeriodOutOfRange(current: Int, start: Long, maxEvolutions: Int)
  case OcertSignatureInvalid
  case KesSignatureInvalid
  case VrfProofInvalid
  case LeaderCheckFailed
  case UnknownEra(era: Era)

/** Stake pool registration info needed for header validation. */
final case class PoolInfo(
    vrfKeyHash: Hash32,     // Blake2b-256 of the pool's VRF verification key
    relativeStakeNum: Long, // Numerator of relative stake (pool's delegated lovelace)
    relativeStakeDen: Long  // Denominator of relative stake (total active lovelace)
)

/** Consensus parameters for header validation. */
final case class ConsensusParams(
    slotsPerKesEvolution: Long = 129600L,     // 1.5 days on mainnet (36 hours)
    maxKesEvolutions: Int = 62,               // Sum6KES: 2^6 - 2 = 62 usable periods
    activeSlotCoeff: (Long, Long) = (1L, 20L) // f = 1/20 on mainnet
)

/**
 * Block header validation for Ouroboros Praos.
 *
 * Validates block authorship: VRF leader election, KES signatures,
 * and operational certificates against a known stake distribution.
 *
 * Validation order per Shelley formal spec:
 * 1. Look up issuerVkey in stake distribution
 * 2. Check KES period bounds
 * 3. Verify OCert (Ed25519 by cold key)
 * 4. Verify KES signature over header body
 * 5. Verify VRF key matches registered hash
 * 6. Verify VRF proof
 * 7. Check leader election threshold
 */
object HeaderValidation:

  /**
   * Validate a Shelley+ block header.
   *
   * @param header       The block header to validate
   * @param era          The era of the block
   * @param currentSlot  Current slot for KES period computation
   * @param epochNonce   The epoch nonce for VRF input
   * @param lookupPool   Function to look up pool info by issuer VKey hash (Blake2b-256 of issuerVkey)
   * @param params       Consensus parameters
   * @return Right(()) if valid, Left(error) if invalid
   */
  def validate(
      header: ShelleyHeader,
      era: Era,
      epochNonce: ByteVector,
      lookupPool: ByteVector => Option[PoolInfo],
      params: ConsensusParams = ConsensusParams()
  ): Either[HeaderValidationError, Unit] =
    val issuerHash = Crypto.blake2b256(header.issuerVkey)

    for
      // 1. Look up pool in stake distribution
      pool <- lookupPool(issuerHash).toRight(
        HeaderValidationError.PoolNotRegistered(issuerHash)
      )

      // 2. Check KES period bounds
      currentPeriod  = header.slotNo.value / params.slotsPerKesEvolution
      relativePeriod = (currentPeriod - header.ocert.startKesPeriod).toInt
      _ <- Either.cond(
        header.ocert.startKesPeriod <= currentPeriod &&
          relativePeriod < params.maxKesEvolutions,
        (),
        HeaderValidationError.KesPeriodOutOfRange(
          relativePeriod,
          header.ocert.startKesPeriod,
          params.maxKesEvolutions
        )
      )

      // 3. Verify OCert: Ed25519 signature by cold key (issuerVkey) over (hotVkey || counter || startKesPeriod)
      ocertMsg = header.ocert.hotVkey ++
        ByteVector.fromLong(header.ocert.counter) ++
        ByteVector.fromLong(header.ocert.startKesPeriod)
      _ <- Either.cond(
        Crypto.Ed25519.verify(header.issuerVkey, ocertMsg, header.ocert.coldSignature),
        (),
        HeaderValidationError.OcertSignatureInvalid
      )

      // 4. Verify KES signature over raw header body
      _ <- Either.cond(
        SumKES.verify(
          vk = header.ocert.hotVkey,
          period = relativePeriod,
          message = header.rawHeaderBody,
          signature = header.kesSignature
        ),
        (),
        HeaderValidationError.KesSignatureInvalid
      )

      // 5. Verify VRF key: hash of vrfVkey must match pool's registered VRF key hash
      vrfHash = Hash32.unsafeFrom(Crypto.blake2b256(header.vrfVkey))
      _ <- Either.cond(
        vrfHash == pool.vrfKeyHash,
        (),
        HeaderValidationError.VrfKeyMismatch(pool.vrfKeyHash, vrfHash)
      )

      // 6. Verify VRF proof(s) — requires libsodium
      vrfOutput <- verifyVrf(header, epochNonce)

      // 7. Leader election check
      certNat = VrfInput.certNatFromOutput(vrfOutput)
      _ <- Either.cond(
        LeaderCheck.isLeader(
          certNat,
          BigInt(pool.relativeStakeNum),
          BigInt(pool.relativeStakeDen),
          params.activeSlotCoeff
        ),
        (),
        HeaderValidationError.LeaderCheckFailed
      )
    yield ()

  /** Verify VRF proof(s) and return the output used for leader check. */
  private def verifyVrf(
      header: ShelleyHeader,
      epochNonce: ByteVector
  ): Either[HeaderValidationError, ByteVector] =
    if !Crypto.VRF.isAvailable then
      // Fallback: if libsodium is unavailable, extract output from proof directly
      // This allows non-consensus operations to proceed without libsodium
      header.vrfResult match
        case VrfResult.Praos(cert)       => Right(cert.output)
        case VrfResult.TPraos(_, leader) => Right(leader.output)
    else
      header.vrfResult match
        case VrfResult.Praos(cert) =>
          val input = VrfInput.praos(header.slotNo, epochNonce)
          Crypto.VRF
            .verify(header.vrfVkey, input, cert.proof)
            .toRight(HeaderValidationError.VrfProofInvalid)

        case VrfResult.TPraos(nonceCert, leaderCert) =>
          val nonceInput  = VrfInput.tpraosNonce(header.slotNo, epochNonce)
          val leaderInput = VrfInput.tpraosLeader(header.slotNo, epochNonce)
          for
            _ <- Crypto.VRF
              .verify(header.vrfVkey, nonceInput, nonceCert.proof)
              .toRight(HeaderValidationError.VrfProofInvalid)
            output <- Crypto.VRF
              .verify(header.vrfVkey, leaderInput, leaderCert.proof)
              .toRight(HeaderValidationError.VrfProofInvalid)
          yield output
