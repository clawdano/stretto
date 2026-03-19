package stretto.consensus

import cardano.kes.SumKES
import munit.FunSuite
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*
import stretto.serialization.BlockDecoder

import java.nio.file.{Files, Paths}

/**
 * End-to-end KES conformance tests using real Cardano block data.
 *
 * These tests decode real blocks from test fixtures (sourced from Pallas/Rust),
 * extract the header, and run the pool-independent validation steps:
 *   1. KES period bounds check
 *   2. OCert Ed25519 signature verification
 *   3. KES signature verification (Sum6KES)
 *
 * The full header validation pipeline (HeaderValidation.validateAll) is also
 * tested, with pool lookup always returning None (so pool-dependent steps like
 * VRF and leader check are skipped, but the pool-independent steps 2-4 must pass).
 *
 * Test fixtures sourced from Pallas (Rust Cardano client):
 *   https://github.com/txpipe/pallas (Apache 2.0)
 */
class KesConformanceSpec extends FunSuite:

  private val maxKesEvolutions = 62

  /** Common slotsPerKesEvolution values across Cardano networks. */
  private val commonSlotsPerKes = List(129600L, 86400L)

  /** Find the slotsPerKesEvolution that makes KES verification pass for a given header. */
  private def findSlotsPerKes(header: ShelleyHeader): Long =
    commonSlotsPerKes
      .find { spk =>
        val currentPeriod  = header.slotNo.value / spk
        val relativePeriod = (currentPeriod - header.ocert.startKesPeriod).toInt
        relativePeriod >= 0 && relativePeriod < 64 &&
        SumKES.verify(
          vk = header.ocert.hotVkey,
          period = relativePeriod,
          message = header.rawHeaderBody,
          signature = header.kesSignature,
          depth = SumKES.CardanoDepth
        )
      }
      .getOrElse(129600L) // default

  private def loadBlock(name: String): ByteVector =
    val path = Paths.get("modules/serialization/src/test/resources/blocks", name)
    val hex  = new String(Files.readAllBytes(path)).trim
    ByteVector.fromValidHex(hex)

  private def decodeShelleyBlock(name: String): (Era, ShelleyHeader) =
    val bytes = loadBlock(name)
    val block = BlockDecoder.decode(bytes)
    assert(block.isRight, s"$name: decode failed: ${block.left.getOrElse("")}")
    block.toOption.get match
      case Block.ShelleyBlock(era, header, _, _, _, _) => (era, header)
      case other                                       => fail(s"$name: expected ShelleyBlock, got $other")

  // ===========================================================================
  // Direct KES verification on decoded headers
  // ===========================================================================

  private def verifyKesForBlock(name: String, expectedEra: Era): Unit =
    val (era, header) = decodeShelleyBlock(name)
    assertEquals(era, expectedEra, s"$name: unexpected era")

    // Auto-detect slotsPerKesEvolution for this block's network
    val slotsPerKesEvolution = findSlotsPerKes(header)

    // Compute relative KES period
    val currentPeriod  = header.slotNo.value / slotsPerKesEvolution
    val relativePeriod = (currentPeriod - header.ocert.startKesPeriod).toInt

    // 1. KES period bounds
    assert(
      header.ocert.startKesPeriod <= currentPeriod,
      s"$name: startKesPeriod (${header.ocert.startKesPeriod}) > currentPeriod ($currentPeriod)"
    )
    assert(
      relativePeriod < maxKesEvolutions,
      s"$name: relativePeriod ($relativePeriod) >= maxKesEvolutions ($maxKesEvolutions)"
    )
    assert(
      relativePeriod >= 0,
      s"$name: relativePeriod ($relativePeriod) is negative"
    )

    // 2. OCert Ed25519 verification
    val ocertMsg = header.ocert.hotVkey ++
      ByteVector.fromLong(header.ocert.counter) ++
      ByteVector.fromLong(header.ocert.startKesPeriod)
    val ocertValid = Crypto.Ed25519.verify(header.issuerVkey, ocertMsg, header.ocert.coldSignature)
    assert(ocertValid, s"$name: OCert signature verification failed")

    // 3. KES signature verification
    assertEquals(header.kesSignature.size, 448L, s"$name: KES signature should be 448 bytes (Sum6KES)")
    val kesValid = SumKES.verify(
      vk = header.ocert.hotVkey,
      period = relativePeriod,
      message = header.rawHeaderBody,
      signature = header.kesSignature,
      depth = SumKES.CardanoDepth
    )
    assert(kesValid, s"$name: KES signature verification failed (period=$relativePeriod)")

  test("Shelley block: full KES pipeline passes (OCert + KES sig)") {
    verifyKesForBlock("shelley1.block", Era.Shelley)
  }

  test("Allegra block: full KES pipeline passes (OCert + KES sig)") {
    verifyKesForBlock("allegra1.block", Era.Allegra)
  }

  test("Mary block: full KES pipeline passes (OCert + KES sig)") {
    verifyKesForBlock("mary1.block", Era.Mary)
  }

  test("Alonzo block: full KES pipeline passes (OCert + KES sig)") {
    verifyKesForBlock("alonzo1.block", Era.Alonzo)
  }

  test("Alonzo block (old format): full KES pipeline passes") {
    verifyKesForBlock("alonzo9.block", Era.Alonzo)
  }

  test("Babbage block: full KES pipeline passes (OCert + KES sig)") {
    verifyKesForBlock("babbage1.block", Era.Babbage)
  }

  test("Conway block 1: full KES pipeline passes (OCert + KES sig)") {
    verifyKesForBlock("conway1.block", Era.Conway)
  }

  test("Conway block 2: full KES pipeline passes (OCert + KES sig)") {
    verifyKesForBlock("conway2.block", Era.Conway)
  }

  // ===========================================================================
  // HeaderValidation.validateAll integration tests
  // ===========================================================================

  /**
   * Run validateAll and check that steps 2-4 (KES period, OCert, KES sig) pass.
   *  Pool lookup returns None, so step 1 will produce PoolNotRegistered, which is expected.
   *  Steps 5-7 (VRF) are skipped since pool is not found.
   */
  private def validateAllForBlock(name: String, expectedEra: Era): Unit =
    val (era, header) = decodeShelleyBlock(name)
    assertEquals(era, expectedEra, s"$name: unexpected era")

    // Auto-detect slotsPerKesEvolution for this block's network
    val slotsPerKesEvolution = findSlotsPerKes(header)
    val params               = ConsensusParams(slotsPerKesEvolution, maxKesEvolutions)

    val errors = HeaderValidation.validateAll(
      header = header,
      era = era,
      epochNonce = ByteVector.fill(32)(0),
      lookupPool = _ => None,
      params = params
    )

    // The only expected error is PoolNotRegistered (because we pass _ => None)
    val poolErrors  = errors.collect { case e: HeaderValidationError.PoolNotRegistered => e }
    val otherErrors = errors.filterNot(_.isInstanceOf[HeaderValidationError.PoolNotRegistered])

    // PoolNotRegistered is expected
    assertEquals(poolErrors.size, 1, s"$name: expected exactly 1 PoolNotRegistered error")

    // Steps 2-4 should produce NO errors
    assert(
      otherErrors.isEmpty,
      s"$name: unexpected validation errors (steps 2-4 should pass): " +
        otherErrors.map(_.productPrefix).mkString(", ")
    )

  test("validateAll: Shelley block passes steps 2-4 (KES period, OCert, KES sig)") {
    validateAllForBlock("shelley1.block", Era.Shelley)
  }

  test("validateAll: Allegra block passes steps 2-4") {
    validateAllForBlock("allegra1.block", Era.Allegra)
  }

  test("validateAll: Mary block passes steps 2-4") {
    validateAllForBlock("mary1.block", Era.Mary)
  }

  test("validateAll: Alonzo block passes steps 2-4") {
    validateAllForBlock("alonzo1.block", Era.Alonzo)
  }

  test("validateAll: Babbage block passes steps 2-4") {
    validateAllForBlock("babbage1.block", Era.Babbage)
  }

  test("validateAll: Conway block 1 passes steps 2-4") {
    validateAllForBlock("conway1.block", Era.Conway)
  }

  test("validateAll: Conway block 2 passes steps 2-4") {
    validateAllForBlock("conway2.block", Era.Conway)
  }

  // ===========================================================================
  // Cross-era header field consistency tests
  // ===========================================================================

  test("all Shelley+ blocks have 32-byte hotVkey, 64-byte coldSig, 448-byte KES sig") {
    val fixtures = List(
      ("shelley1.block", Era.Shelley),
      ("allegra1.block", Era.Allegra),
      ("mary1.block", Era.Mary),
      ("alonzo1.block", Era.Alonzo),
      ("alonzo9.block", Era.Alonzo),
      ("babbage1.block", Era.Babbage),
      ("conway1.block", Era.Conway),
      ("conway2.block", Era.Conway)
    )
    fixtures.foreach { case (name, expectedEra) =>
      val (era, header) = decodeShelleyBlock(name)
      assertEquals(era, expectedEra, s"$name: era")
      assertEquals(header.ocert.hotVkey.size, 32L, s"$name: hotVkey size")
      assertEquals(header.ocert.coldSignature.size, 64L, s"$name: coldSignature size")
      assertEquals(header.kesSignature.size, 448L, s"$name: KES signature size")
      assertEquals(header.issuerVkey.size, 32L, s"$name: issuerVkey size")
    }
  }

  test("TPraos eras (Shelley-Alonzo) have two VRF certs, Praos (Babbage+) has one") {
    val tpraosFixtures = List("shelley1.block", "allegra1.block", "mary1.block", "alonzo1.block")
    tpraosFixtures.foreach { name =>
      val (_, header) = decodeShelleyBlock(name)
      header.vrfResult match
        case VrfResult.TPraos(_, _) => () // expected
        case other                  => fail(s"$name: expected TPraos VRF result, got ${other.productPrefix}")
    }

    val praosFixtures = List("babbage1.block", "conway1.block", "conway2.block")
    praosFixtures.foreach { name =>
      val (_, header) = decodeShelleyBlock(name)
      header.vrfResult match
        case VrfResult.Praos(_) => () // expected
        case other              => fail(s"$name: expected Praos VRF result, got ${other.productPrefix}")
    }
  }
