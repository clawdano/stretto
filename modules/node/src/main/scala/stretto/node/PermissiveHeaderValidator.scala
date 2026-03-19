package stretto.node

import cats.effect.{IO, Ref}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector
import stretto.consensus.*
import stretto.core.*
import stretto.core.Types.*
import stretto.serialization.BlockDecoder

/**
 * Permissive header validator for Ouroboros Praos.
 *
 * Runs header validation in fire-and-forget mode during block sync:
 *   - Decodes the ShelleyHeader from the era-wrapped header bytes
 *   - Runs all validation steps (collecting errors, not short-circuiting)
 *   - Updates ConsensusState (epoch transitions, OCert counters, nonce evolution)
 *   - Logs sampled warnings during bulk sync (1 in 1000)
 *   - Updates metrics counters
 *
 * Never blocks or rejects blocks — this is observational/permissive mode.
 */
final class PermissiveHeaderValidator(
    stateRef: Ref[IO, ConsensusState],
    metricsRef: Ref[IO, MetricsServer.Metrics],
    genesisConfig: GenesisConfig,
    params: ConsensusParams = ConsensusParams()
):

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.node.PermissiveHeaderValidator")

  /** Sampling rate for failure logging during bulk sync. */
  private val LogSampleRate = 1000L

  /**
   * Validate a header permissively — fire-and-forget, never rejects.
   *
   * @param wrappedHeader the era-wrapped header bytes from ChainSync
   * @param era           the effective era tag (from HeaderParser)
   * @param genesisConfig the genesis configuration for epoch calculation
   */
  def validatePermissive(wrappedHeader: ByteVector, era: Int): IO[Unit] =
    // Skip Byron headers (era <= 1 means Byron EBB or Byron main)
    if era <= 1 then IO.unit
    else
      BlockDecoder.decodeHeaderOnly(wrappedHeader) match
        case Left(_) =>
          // Decode failure — silently skip (Byron headers, malformed, etc.)
          IO.unit
        case Right((decodedEra, header)) =>
          validateAndUpdate(decodedEra, header)

  /**
   * Run validation, update state and metrics.
   */
  private def validateAndUpdate(era: Era, header: ShelleyHeader): IO[Unit] =
    for
      state <- stateRef.get

      // Determine current epoch for this slot
      currentEpoch = genesisConfig.epochForSlot(header.slotNo.value)

      // Handle epoch transition if needed
      updatedState <-
        if currentEpoch > state.currentEpoch.epochNoValue then
          val transitioned = state.epochTransition(
            EpochNo(currentEpoch),
            StakeDistribution.empty, // No real stake data in permissive mode
            ByteVector.fill(32)(0)
          )
          stateRef.set(transitioned).as(transitioned)
        else IO.pure(state)

      // Run all validation steps (non-short-circuiting)
      epochNonce = updatedState.nonceState.epochNonce
      errors = HeaderValidation.validateAll(
        header = header,
        era = era,
        epochNonce = epochNonce,
        lookupPool = updatedState.lookupPool,
        params = params
      )


      // Extract VRF output for nonce evolution (best-effort)
      vrfOutput = header.vrfResult match
        case VrfResult.Praos(cert)       => cert.output
        case VrfResult.TPraos(_, leader) => leader.output

      // Update consensus state: OCert counter + nonce evolution
      issuerHash = Crypto.blake2b256(header.issuerVkey)
      shelleyStart = genesisConfig.shelleyStartSlot
      slotInEpoch = header.slotNo.value - shelleyStart -
        (currentEpoch - genesisConfig.byronShelleyTransitionEpoch) * genesisConfig.shelleyEpochLength
      _ <- updatedState
        .processBlock(issuerHash, header.ocert.counter, vrfOutput, math.max(0L, slotInEpoch)) match
        case Right(newState) => stateRef.set(newState)
        case Left(_)         => IO.unit // OCert counter not increasing — expected during initial sync

      // Update metrics
      passed = errors.isEmpty
      _ <- metricsRef.update { m =>
        val errorMap = errors.foldLeft(m.validationErrorCounts) { case (acc, err) =>
          val key = err.productPrefix // e.g. "PoolNotRegistered", "KesSignatureInvalid"
          acc.updated(key, acc.getOrElse(key, 0L) + 1L)
        }
        m.copy(
          headersValidated = m.headersValidated + 1L,
          headersPassed = m.headersPassed + (if passed then 1L else 0L),
          headersFailed = m.headersFailed + (if passed then 0L else 1L),
          validationErrorCounts = errorMap
        )
      }

      // Sampled logging for failures
      _ <-
        if errors.nonEmpty then
          metricsRef.get.flatMap { m =>
            if m.headersFailed % LogSampleRate == 1L then
              logger.warn(
                s"Header validation failed at slot ${header.slotNo.value} " +
                  s"(block ${header.blockNo.blockNoValue}): " +
                  errors.map(_.productPrefix).mkString(", ") +
                  s" [sampled 1/${LogSampleRate}, total failures: ${m.headersFailed}]"
              )
            else IO.unit
          }
        else IO.unit
    yield ()

object PermissiveHeaderValidator:

  /**
   * Create a new PermissiveHeaderValidator with initial consensus state
   * derived from the genesis nonce and empty stake distribution.
   */
  def create(
      metricsRef: Ref[IO, MetricsServer.Metrics],
      genesisConfig: GenesisConfig,
      params: ConsensusParams = ConsensusParams()
  ): IO[PermissiveHeaderValidator] =
    for stateRef <- Ref.of[IO, ConsensusState](
        ConsensusState.initial(
          genesisNonce = genesisConfig.shelleyGenesisHash,
          genesisStake = StakeDistribution.empty
        )
      )
    yield new PermissiveHeaderValidator(stateRef, metricsRef, genesisConfig, params)
