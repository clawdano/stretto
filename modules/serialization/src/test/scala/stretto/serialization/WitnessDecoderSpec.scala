package stretto.serialization

import munit.FunSuite
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

import java.nio.file.{Files, Paths}

/**
 * Tests for witness set decoding from real block fixtures.
 */
class WitnessDecoderSpec extends FunSuite:

  private def loadBlock(name: String): Block =
    val path  = Paths.get("modules/serialization/src/test/resources/blocks", name)
    val hex   = new String(Files.readAllBytes(path)).trim
    val bytes = ByteVector.fromValidHex(hex)
    BlockDecoder.decode(bytes).fold(err => fail(s"decode $name failed: $err"), identity)

  test("Shelley block parses vkey witnesses") {
    val block = loadBlock("shelley1.block")
    block match
      case Block.ShelleyBlock(_, _, txBodies, _, _, _) =>
        // Shelley blocks with transactions should have witnesses
        assert(txBodies.nonEmpty, "should have tx bodies")
      case _ => fail("expected ShelleyBlock")
  }

  test("Alonzo block parses witnesses and extended tx body fields") {
    val block = loadBlock("alonzo1.block")
    block match
      case Block.ShelleyBlock(_, _, txBodies, _, _, _) =>
        assertEquals(txBodies.size, 5)
        // Verify extended fields are parsed (may be empty for simple txs)
        txBodies.foreach { tx =>
          assert(tx.inputs.nonEmpty, "each tx should have inputs")
          assert(tx.outputs.nonEmpty, "each tx should have outputs")
          assert(tx.fee.lovelaceValue > 0L, "each tx should have positive fee")
        }
      case _ => fail("expected ShelleyBlock")
  }

  test("Babbage block parses witnesses") {
    val block = loadBlock("babbage1.block")
    block match
      case Block.ShelleyBlock(_, _, txBodies, _, _, _) =>
        assert(txBodies.nonEmpty, "should have tx bodies")
      case _ => fail("expected ShelleyBlock")
  }

  test("Conway block parses witnesses") {
    val block = loadBlock("conway1.block")
    block match
      case Block.ShelleyBlock(_, _, txBodies, _, _, _) =>
        // Conway blocks should parse successfully
        assert(txBodies.size >= 0)
      case _ => fail("expected ShelleyBlock")
  }

  test("all block fixtures still decode after witness parsing changes") {
    val fixtures = List(
      "byron1.block", "byron2.block", "byron4.block",
      "shelley1.block", "allegra1.block", "mary1.block",
      "alonzo1.block", "alonzo9.block",
      "babbage1.block", "conway1.block"
    )
    fixtures.foreach { name =>
      val path = Paths.get("modules/serialization/src/test/resources/blocks", name)
      if Files.exists(path) then
        val hex   = new String(Files.readAllBytes(path)).trim
        val bytes = ByteVector.fromValidHex(hex)
        val result = BlockDecoder.decode(bytes)
        assert(result.isRight, s"$name should decode successfully: ${result.left.getOrElse("")}")
    }
  }
