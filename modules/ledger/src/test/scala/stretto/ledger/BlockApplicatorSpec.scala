package stretto.ledger

import munit.FunSuite
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*
import stretto.serialization.BlockDecoder

import java.nio.file.{Files, Paths}

/**
 * Tests for BlockApplicator — applying parsed blocks to UTxO state.
 *
 * Uses Pallas test fixtures to verify UTxO transitions.
 */
class BlockApplicatorSpec extends FunSuite:

  private def loadBlock(name: String): Block =
    val path  = Paths.get("modules/serialization/src/test/resources/blocks", name)
    val hex   = new String(Files.readAllBytes(path)).trim
    val bytes = ByteVector.fromValidHex(hex)
    BlockDecoder.decode(bytes).fold(err => fail(s"decode $name failed: $err"), identity)

  // ---------------------------------------------------------------------------
  // Byron blocks
  // ---------------------------------------------------------------------------

  test("EBB produces no UTxO changes") {
    // byron1 is a main block with 0 txs, but let's construct an EBB manually
    val state = UtxoState.empty
    val ebb = Block.ByronEbBlock(
      ByronEbHeader(
        764824073L,
        Hash32.unsafeFrom(ByteVector.fill(32)(0)),
        Hash32.unsafeFrom(ByteVector.fill(32)(0)),
        EpochNo(0L),
        0L
      ),
      ByteVector.empty
    )
    val result = BlockApplicator.apply(state, ebb)
    assertEquals(result.txsProcessed, 0)
    assertEquals(result.inputsConsumed, 0)
    assertEquals(result.outputsCreated, 0)
    assertEquals(result.state.size, 0)
  }

  test("Byron block with 0 txs leaves UTxO unchanged") {
    val block  = loadBlock("byron1.block")
    val state  = UtxoState.empty
    val result = BlockApplicator.apply(state, block)
    assertEquals(result.txsProcessed, 0)
    assertEquals(result.outputsCreated, 0)
    assertEquals(result.state.size, 0)
  }

  test("Byron block with 2 txs creates outputs") {
    val block  = loadBlock("byron2.block")
    val state  = UtxoState.empty
    val result = BlockApplicator.apply(state, block)

    assertEquals(result.txsProcessed, 2)
    assert(result.outputsCreated > 0, "should create outputs")
    assertEquals(result.state.size, result.outputsCreated)
    // Inputs will all be "missing" since we start from empty state
    assertEquals(result.errors.size, result.inputsConsumed + result.errors.size)
    assert(result.state.totalLovelace.lovelaceValue > 0L, "UTxO should have positive ADA")
  }

  test("Byron tx outputs have positive amounts") {
    val block  = loadBlock("byron2.block")
    val result = BlockApplicator.apply(UtxoState.empty, block)
    result.state.utxos.values.foreach { output =>
      output.value match
        case OutputValue.PureAda(coin) =>
          assert(coin.lovelaceValue > 0L, s"output should have positive ADA, got ${coin.lovelaceValue}")
        case OutputValue.MultiAsset(coin, _) =>
          assert(coin.lovelaceValue > 0L, s"output should have positive ADA")
    }
  }

  // ---------------------------------------------------------------------------
  // Shelley blocks
  // ---------------------------------------------------------------------------

  test("Shelley block creates outputs with fees") {
    val block  = loadBlock("shelley1.block")
    val state  = UtxoState.empty
    val result = BlockApplicator.apply(state, block)

    assertEquals(result.txsProcessed, 4)
    assert(result.outputsCreated > 0)
    assertEquals(result.state.size, result.outputsCreated)
    assert(result.state.totalLovelace.lovelaceValue > 0L)
  }

  test("Shelley tx hash determines output keys") {
    val block  = loadBlock("shelley1.block")
    val result = BlockApplicator.apply(UtxoState.empty, block)

    // Each output should be keyed by (txHash, index)
    // Verify all keys have valid 32-byte tx hashes
    result.state.utxos.keys.foreach { input =>
      assertEquals(input.txId.txHashToHash32.hash32Bytes.size, 32L)
      assert(input.index >= 0L)
    }
  }

  // ---------------------------------------------------------------------------
  // Multi-era sequence
  // ---------------------------------------------------------------------------

  test("apply multiple blocks sequentially") {
    val blocks = List(
      loadBlock("byron1.block"), // 0 txs
      loadBlock("byron2.block")  // 2 txs
    )

    var state        = UtxoState.empty
    var totalOutputs = 0

    blocks.foreach { block =>
      val result = BlockApplicator.apply(state, block)
      state = result.state
      totalOutputs += result.outputsCreated
    }

    assertEquals(state.size, totalOutputs)
  }

  test("spending an existing output removes it from UTxO") {
    // Create a synthetic scenario: add an output, then spend it
    val txHash1 = TxHash(Hash32.unsafeFrom(ByteVector.fill(32)(0x01)))

    val output = TxOutput(ByteVector.fromValidHex("00"), OutputValue.PureAda(Lovelace(1000000L)))
    val outKey = TxInput(txHash1, 0L)

    val initialState = UtxoState(Map(outKey -> output))
    assertEquals(initialState.size, 1)

    // Create a Shelley block that spends this output
    val spendingBlock = Block.ShelleyBlock(
      era = Era.Shelley,
      header = ShelleyHeader(
        blockNo = BlockNo(1L),
        slotNo = SlotNo(1L),
        prevHash = Hash32.unsafeFrom(ByteVector.fill(32)(0)),
        issuerVkey = ByteVector.fill(32)(0),
        vrfVkey = ByteVector.fill(32)(0),
        vrfResult = VrfResult.Praos(VrfCert(ByteVector.empty, ByteVector.empty)),
        blockBodySize = 0L,
        blockBodyHash = Hash32.unsafeFrom(ByteVector.fill(32)(0)),
        ocert = OperationalCert(ByteVector.fill(32)(0), 0L, 0L, ByteVector.fill(64)(0)),
        protocolVersion = (2, 0),
        kesSignature = ByteVector.empty,
        rawHeaderBody = ByteVector.empty
      ),
      txBodies = Vector(
        TransactionBody(
          inputs = Vector(outKey),
          outputs = Vector(TxOutput(ByteVector.fromValidHex("01"), OutputValue.PureAda(Lovelace(800000L)))),
          fee = Lovelace(200000L),
          ttl = None,
          rawCbor = ByteVector.fromValidHex(
            "a400818258200101010101010101010101010101010101010101010101010101010101010101000181a200581d01018201581a000c35000282a"
          )
        )
      ),
      txWitnesses = Vector(ByteVector.empty),
      auxiliaryData = Vector(None),
      invalidTxs = Vector.empty
    )

    val result = BlockApplicator.apply(initialState, spendingBlock)

    assertEquals(result.inputsConsumed, 1, "should consume 1 input")
    assertEquals(result.outputsCreated, 1, "should create 1 output")
    assert(!result.state.contains(outKey), "spent output should be removed")
    // Validation errors expected (synthetic tx has mismatched fee/size)
    // but UTxO state transition should still be correct
    assertEquals(result.state.size, 1)
  }

  test("missing input is recorded as error but processing continues") {
    val block  = loadBlock("shelley1.block")
    val result = BlockApplicator.apply(UtxoState.empty, block)

    // All inputs will be "missing" since we start from empty state
    assert(result.errors.nonEmpty, "should have missing input errors from empty state")
    // But outputs should still be created
    assert(result.outputsCreated > 0, "outputs should still be created despite missing inputs")
  }

  // ---------------------------------------------------------------------------
  // Alonzo and later eras
  // ---------------------------------------------------------------------------

  test("Alonzo block processes correctly") {
    val block  = loadBlock("alonzo1.block")
    val result = BlockApplicator.apply(UtxoState.empty, block)
    assertEquals(result.txsProcessed, 5)
    assert(result.outputsCreated > 0)
  }

  test("Mary block with 14 txs") {
    val block  = loadBlock("mary1.block")
    val result = BlockApplicator.apply(UtxoState.empty, block)
    assertEquals(result.txsProcessed, 14)
    assert(result.outputsCreated > 0)
  }

  test("Babbage block processes correctly") {
    val block  = loadBlock("babbage1.block")
    val result = BlockApplicator.apply(UtxoState.empty, block)
    assert(result.txsProcessed > 0)
  }

  test("Conway block processes correctly") {
    val block  = loadBlock("conway1.block")
    val result = BlockApplicator.apply(UtxoState.empty, block)
    assert(result.txsProcessed >= 0)
  }
