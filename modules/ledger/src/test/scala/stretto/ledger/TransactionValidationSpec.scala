package stretto.ledger

import munit.FunSuite
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

class TransactionValidationSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // Test fixtures
  // ---------------------------------------------------------------------------

  private val txHash1 = TxHash(Hash32.unsafeFrom(ByteVector.fill(32)(0x01)))
  private val txHash2 = TxHash(Hash32.unsafeFrom(ByteVector.fill(32)(0x02)))

  private val addr1 = ByteVector.fromValidHex("00" * 29)
  private val addr2 = ByteVector.fromValidHex("01" * 29)

  private def mkInput(hash: TxHash, idx: Long): TxInput = TxInput(hash, idx)

  private def mkOutput(addr: ByteVector, lovelace: Long): TxOutput =
    TxOutput(addr, OutputValue.PureAda(Lovelace(lovelace)))

  private val params = ProtocolParameters.Shelley

  /** Build a minimal Shelley tx body with raw CBOR of given size. */
  private def mkTxBody(
      inputs: Vector[TxInput],
      outputs: Vector[TxOutput],
      fee: Long,
      ttl: Option[Long] = None,
      cborSize: Int = 300
  ): TransactionBody =
    TransactionBody(
      inputs = inputs,
      outputs = outputs,
      fee = Lovelace(fee),
      ttl = ttl.map(SlotNo.apply),
      rawCbor = ByteVector.fill(cborSize.toLong)(0x00)
    )

  // ---------------------------------------------------------------------------
  // Value preservation (Shelley spec §9.1)
  // ---------------------------------------------------------------------------

  test("valid tx: inputs = outputs + fee passes value preservation") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 9_800_000L)),
      fee = 200_000L
    )
    val errors             = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(0L))
    val preservationErrors = errors.collect { case e: TransactionValidation.ValidationError.ValueNotPreserved => e }
    assert(preservationErrors.isEmpty, s"unexpected: $preservationErrors")
  }

  test("invalid tx: outputs + fee > inputs fails value preservation") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 10_000_000L)),
      fee = 200_000L
    )
    val errors             = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(0L))
    val preservationErrors = errors.collect { case e: TransactionValidation.ValidationError.ValueNotPreserved => e }
    assertEquals(preservationErrors.size, 1)
  }

  test("value preservation skipped when inputs missing from UTxO") {
    val utxos = Map.empty[TxInput, TxOutput]
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 5_000_000L)),
      fee = 200_000L
    )
    val errors             = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(0L))
    val preservationErrors = errors.collect { case e: TransactionValidation.ValidationError.ValueNotPreserved => e }
    assert(preservationErrors.isEmpty, "should skip preservation check when inputs missing")
    val inputErrors = errors.collect { case e: TransactionValidation.ValidationError.InputNotFound => e }
    assertEquals(inputErrors.size, 1)
  }

  // ---------------------------------------------------------------------------
  // Fee adequacy (Shelley spec §10.2)
  // ---------------------------------------------------------------------------

  test("fee below minimum is rejected") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    // minFee = 44 * 300 + 155381 = 168581
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 9_900_000L)),
      fee = 100_000L, // below min
      cborSize = 300
    )
    val errors    = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(0L))
    val feeErrors = errors.collect { case e: TransactionValidation.ValidationError.FeeTooSmall => e }
    assertEquals(feeErrors.size, 1)
    assertEquals(feeErrors.head.minimumFee, params.minFee(300L))
  }

  test("fee at exact minimum passes") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val minFee = params.minFee(300L) // 44*300 + 155381 = 168581
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 10_000_000L - minFee.lovelaceValue)),
      fee = minFee.lovelaceValue,
      cborSize = 300
    )
    val errors    = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(0L))
    val feeErrors = errors.collect { case e: TransactionValidation.ValidationError.FeeTooSmall => e }
    assert(feeErrors.isEmpty)
  }

  // ---------------------------------------------------------------------------
  // TTL validation (Shelley spec §8.1)
  // ---------------------------------------------------------------------------

  test("tx with TTL in the future passes") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 9_800_000L)),
      fee = 200_000L,
      ttl = Some(1000L)
    )
    val errors    = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(500L))
    val ttlErrors = errors.collect { case e: TransactionValidation.ValidationError.ExpiredTx => e }
    assert(ttlErrors.isEmpty)
  }

  test("tx with TTL in the past fails") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 9_800_000L)),
      fee = 200_000L,
      ttl = Some(500L)
    )
    val errors    = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(600L))
    val ttlErrors = errors.collect { case e: TransactionValidation.ValidationError.ExpiredTx => e }
    assertEquals(ttlErrors.size, 1)
    assertEquals(ttlErrors.head.ttl, SlotNo(500L))
    assertEquals(ttlErrors.head.currentSlot, SlotNo(600L))
  }

  test("tx with TTL equal to current slot fails (not strictly less)") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 9_800_000L)),
      fee = 200_000L,
      ttl = Some(500L)
    )
    val errors    = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(500L))
    val ttlErrors = errors.collect { case e: TransactionValidation.ValidationError.ExpiredTx => e }
    assertEquals(ttlErrors.size, 1)
  }

  test("tx without TTL skips TTL check") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 9_800_000L)),
      fee = 200_000L,
      ttl = None
    )
    val errors    = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(999999L))
    val ttlErrors = errors.collect { case e: TransactionValidation.ValidationError.ExpiredTx => e }
    assert(ttlErrors.isEmpty)
  }

  // ---------------------------------------------------------------------------
  // Minimum UTxO (Shelley spec §9.2)
  // ---------------------------------------------------------------------------

  test("output below minUtxoValue fails") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 500_000L)), // below 1 ADA min
      fee = 9_500_000L
    )
    val errors    = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(0L))
    val minErrors = errors.collect { case e: TransactionValidation.ValidationError.OutputTooSmall => e }
    assertEquals(minErrors.size, 1)
    assertEquals(minErrors.head.outputIndex, 0)
  }

  test("output at exactly 1 ADA passes minUtxoValue") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 1_000_000L)),
      fee = 9_000_000L
    )
    val errors    = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(0L))
    val minErrors = errors.collect { case e: TransactionValidation.ValidationError.OutputTooSmall => e }
    assert(minErrors.isEmpty)
  }

  // ---------------------------------------------------------------------------
  // Max transaction size
  // ---------------------------------------------------------------------------

  test("tx exceeding max size is rejected") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 10_000_000L)
    )
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L)),
      outputs = Vector(mkOutput(addr2, 9_800_000L)),
      fee = 200_000L,
      cborSize = 20_000 // above Shelley maxTxSize of 16384
    )
    val errors     = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(0L))
    val sizeErrors = errors.collect { case e: TransactionValidation.ValidationError.TxTooLarge => e }
    assertEquals(sizeErrors.size, 1)
  }

  // ---------------------------------------------------------------------------
  // Protocol parameters
  // ---------------------------------------------------------------------------

  test("minFee calculation: a * size + b") {
    val fee = params.minFee(300L)
    // 44 * 300 + 155381 = 13200 + 155381 = 168581
    assertEquals(fee, Lovelace(168581L))
  }

  test("Alonzo coinsPerUtxoByte minLovelace") {
    val alonzoParams = ProtocolParameters.Alonzo
    // coinsPerUtxoByte = 4310, outputSize = 50 bytes
    // minLovelace = 4310 * (50 + 160) = 4310 * 210 = 905100
    val minLovelace = alonzoParams.minLovelaceForOutput(50L)
    assertEquals(minLovelace, Lovelace(905100L))
  }

  test("forEra returns correct params for each era") {
    assertEquals(ProtocolParameters.forEra(Era.Byron).minFeeA, 0L)
    assertEquals(ProtocolParameters.forEra(Era.Shelley).minFeeA, 44L)
    assertEquals(ProtocolParameters.forEra(Era.Alonzo).coinsPerUtxoByte, Some(Lovelace(4310L)))
    assertEquals(ProtocolParameters.forEra(Era.Conway).coinsPerUtxoByte, Some(Lovelace(4310L)))
  }

  // ---------------------------------------------------------------------------
  // Byron validation
  // ---------------------------------------------------------------------------

  test("Byron tx with negative fee (outputs > inputs) fails") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 1_000_000L)
    )
    val tx = ByronTx(
      txId = txHash2,
      inputs = Vector(ByronTxIn(txHash1, 0L)),
      outputs = Vector(ByronTxOut(addr2, Lovelace(2_000_000L)))
    )
    val errors             = TransactionValidation.validateByronTx(tx, utxos)
    val preservationErrors = errors.collect { case e: TransactionValidation.ValidationError.ValueNotPreserved => e }
    assertEquals(preservationErrors.size, 1)
  }

  test("Byron tx with positive fee passes") {
    val utxos = Map(
      mkInput(txHash1, 0L) -> mkOutput(addr1, 2_000_000L)
    )
    val tx = ByronTx(
      txId = txHash2,
      inputs = Vector(ByronTxIn(txHash1, 0L)),
      outputs = Vector(ByronTxOut(addr2, Lovelace(1_800_000L)))
    )
    val errors             = TransactionValidation.validateByronTx(tx, utxos)
    val preservationErrors = errors.collect { case e: TransactionValidation.ValidationError.ValueNotPreserved => e }
    assert(preservationErrors.isEmpty)
  }

  // ---------------------------------------------------------------------------
  // Multiple errors accumulate
  // ---------------------------------------------------------------------------

  test("multiple validation errors are all reported") {
    val utxos = Map.empty[TxInput, TxOutput]
    val tx = mkTxBody(
      inputs = Vector(mkInput(txHash1, 0L), mkInput(txHash1, 1L)),
      outputs = Vector(mkOutput(addr2, 100L)), // below min UTxO
      fee = 100L,                              // below min fee
      ttl = Some(10L),                         // expired
      cborSize = 300
    )
    val errors = TransactionValidation.validateShelleyTx(tx, txHash2, utxos, params, SlotNo(100L))
    // Should have: 2 InputNotFound + 1 FeeTooSmall + 1 ExpiredTx + 1 OutputTooSmall
    assert(errors.size >= 4, s"expected at least 4 errors, got ${errors.size}: $errors")
  }
