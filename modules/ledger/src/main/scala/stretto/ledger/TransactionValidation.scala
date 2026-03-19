package stretto.ledger

import stretto.core.*
import stretto.core.Types.*

/**
 * Transaction-level validation rules per the Cardano ledger specs.
 *
 * Each rule returns either a validation error or Unit.
 * Rules are applied per-era with appropriate protocol parameters.
 *
 * References:
 *   - Shelley spec §9.1 (value preservation)
 *   - Shelley spec §10.2 (fee adequacy)
 *   - Shelley spec §8.1 (TTL)
 *   - Shelley spec §9.2 (minimum UTxO)
 *   - Alonzo spec §1 (coinsPerUtxoByte)
 */
object TransactionValidation:

  /** A validation error with context. */
  enum ValidationError:

    /** Inputs consumed more ADA than outputs + fee produced. Shelley spec §9.1 */
    case ValueNotPreserved(
        txHash: TxHash,
        inputValue: Lovelace,
        outputValue: Lovelace,
        fee: Lovelace
    )

    /** Fee is below the minimum required. Shelley spec §10.2 */
    case FeeTooSmall(
        txHash: TxHash,
        actualFee: Lovelace,
        minimumFee: Lovelace
    )

    /** Transaction validity interval has expired. Shelley spec §8.1 */
    case ExpiredTx(
        txHash: TxHash,
        ttl: SlotNo,
        currentSlot: SlotNo
    )

    /** Output has less ADA than the minimum. Shelley spec §9.2 */
    case OutputTooSmall(
        txHash: TxHash,
        outputIndex: Int,
        actualLovelace: Lovelace,
        minimumLovelace: Lovelace
    )

    /** Transaction exceeds maximum size. */
    case TxTooLarge(
        txHash: TxHash,
        actualSize: Long,
        maxSize: Long
    )

    /** An input referenced by the transaction doesn't exist in the UTxO set. */
    case InputNotFound(
        txHash: TxHash,
        input: TxInput
    )

    /** Witness verification failed — missing or invalid signature. */
    case WitnessVerificationFailed(
        txHash: TxHash,
        detail: String
    )

    /** A required signer key hash is not covered by any witness. */
    case RequiredSignerMissing(
        txHash: TxHash,
        keyHash: Hash28
    )

    /** Validity interval start not yet reached. Allegra+ */
    case ValidityIntervalStartNotReached(
        txHash: TxHash,
        validFrom: SlotNo,
        currentSlot: SlotNo
    )

  /**
   * Validate a Shelley+ transaction against the UTxO state and protocol parameters.
   *
   * Returns a list of all validation errors (empty if valid).
   */
  def validateShelleyTx(
      tx: TransactionBody,
      txHash: TxHash,
      utxos: Map[TxInput, TxOutput],
      params: ProtocolParameters,
      currentSlot: SlotNo
  ): Vector[ValidationError] =
    var errors = Vector.empty[ValidationError]

    // 1. Check all inputs exist (Shelley spec §9.1 precondition)
    tx.inputs.foreach { input =>
      if !utxos.contains(input) then errors = errors :+ ValidationError.InputNotFound(txHash, input)
    }

    // 2. Value preservation: sum(inputs) = sum(outputs) + fee (Shelley spec §9.1)
    val inputSum = tx.inputs.foldLeft(Lovelace(0L)) { (acc, input) =>
      utxos.get(input) match
        case Some(out) =>
          val coin = out.value match
            case OutputValue.PureAda(c)       => c
            case OutputValue.MultiAsset(c, _) => c
          Lovelace(acc.lovelaceValue + coin.lovelaceValue)
        case None => acc // already reported as InputNotFound
    }
    val outputSum = tx.outputs.foldLeft(Lovelace(0L)) { (acc, out) =>
      val coin = out.value match
        case OutputValue.PureAda(c)       => c
        case OutputValue.MultiAsset(c, _) => c
      Lovelace(acc.lovelaceValue + coin.lovelaceValue)
    }
    // Only check value preservation if all inputs were found
    val allInputsFound = tx.inputs.forall(utxos.contains)
    if allInputsFound then
      val expected = Lovelace(outputSum.lovelaceValue + tx.fee.lovelaceValue)
      if inputSum.lovelaceValue != expected.lovelaceValue then
        errors = errors :+ ValidationError.ValueNotPreserved(txHash, inputSum, outputSum, tx.fee)

    // 3. Fee adequacy: fee >= minFee(tx) (Shelley spec §10.2)
    val minFee = params.minFee(tx.rawCbor.size)
    if tx.fee.lovelaceValue < minFee.lovelaceValue then
      errors = errors :+ ValidationError.FeeTooSmall(txHash, tx.fee, minFee)

    // 4. TTL: currentSlot < ttl (Shelley spec §8.1)
    tx.ttl.foreach { ttl =>
      if currentSlot.value >= ttl.value then errors = errors :+ ValidationError.ExpiredTx(txHash, ttl, currentSlot)
    }

    // 4b. Validity interval start: currentSlot >= validFrom (Allegra+)
    tx.validityIntervalStart.foreach { validFrom =>
      if currentSlot.value < validFrom.value then
        errors = errors :+ ValidationError.ValidityIntervalStartNotReached(txHash, validFrom, currentSlot)
    }

    // 5. Minimum UTxO: each output >= minLovelace (Shelley spec §9.2)
    tx.outputs.zipWithIndex.foreach { case (out, idx) =>
      val coin = out.value match
        case OutputValue.PureAda(c)       => c
        case OutputValue.MultiAsset(c, _) => c
      // Estimate output size from address + value for minLovelace calculation
      val outputSize  = out.address.size + 8 // address + coin bytes (rough estimate)
      val minLovelace = params.minLovelaceForOutput(outputSize)
      if coin.lovelaceValue < minLovelace.lovelaceValue then
        errors = errors :+ ValidationError.OutputTooSmall(txHash, idx, coin, minLovelace)
    }

    // 6. Max transaction size
    if tx.rawCbor.size > params.maxTxSize then
      errors = errors :+ ValidationError.TxTooLarge(txHash, tx.rawCbor.size, params.maxTxSize)

    errors

  /**
   * Validate a Byron transaction.
   *
   * Byron has simpler rules: just check that inputs exist.
   * Fee = sum(inputs) - sum(outputs) — implicit, always valid if positive.
   */
  def validateByronTx(
      tx: ByronTx,
      utxos: Map[TxInput, TxOutput]
  ): Vector[ValidationError] =
    var errors = Vector.empty[ValidationError]

    tx.inputs.foreach { input =>
      val utxoKey = TxInput(input.txId, input.index)
      if !utxos.contains(utxoKey) then errors = errors :+ ValidationError.InputNotFound(tx.txId, utxoKey)
    }

    // Byron fee = sum(inputs) - sum(outputs), implicit if positive
    val inputSum = tx.inputs.foldLeft(0L) { (acc, input) =>
      val utxoKey = TxInput(input.txId, input.index)
      utxos.get(utxoKey) match
        case Some(out) =>
          val coin = out.value match
            case OutputValue.PureAda(c)       => c
            case OutputValue.MultiAsset(c, _) => c
          acc + coin.lovelaceValue
        case None => acc
    }
    val outputSum = tx.outputs.foldLeft(0L)((acc, out) => acc + out.amount.lovelaceValue)

    val allInputsFound = tx.inputs.forall(i => utxos.contains(TxInput(i.txId, i.index)))
    if allInputsFound && inputSum < outputSum then
      // Negative fee — outputs exceed inputs
      errors = errors :+ ValidationError.ValueNotPreserved(
        tx.txId,
        Lovelace(inputSum),
        Lovelace(outputSum),
        Lovelace(inputSum - outputSum)
      )

    errors

  /**
   * Full transaction validation including witness verification.
   *
   * Combines UTxO validation with Ed25519 witness checks.
   * Used for LocalTxSubmission.
   */
  def validateFullTx(
      tx: TransactionBody,
      witnesses: Vector[VkeyWitness],
      txHash: TxHash,
      utxos: Map[TxInput, TxOutput],
      params: ProtocolParameters,
      currentSlot: SlotNo
  ): Vector[ValidationError] =
    // Run standard UTxO validation
    val utxoErrors = validateShelleyTx(tx, txHash, utxos, params, currentSlot)

    // Run witness verification
    val witnessErrors = WitnessValidator.verify(tx, witnesses, utxos, txHash)
    val mappedWitnessErrors = witnessErrors.map {
      case WitnessValidator.WitnessError.MissingWitness(h, keyHash) =>
        ValidationError.WitnessVerificationFailed(h, s"missing witness for key hash ${keyHash.hash28Hex}")
      case WitnessValidator.WitnessError.InvalidSignature(h, vkey) =>
        ValidationError.WitnessVerificationFailed(h, s"invalid signature for vkey ${vkey.toHex.take(16)}...")
      case WitnessValidator.WitnessError.RequiredSignerMissing(h, keyHash) =>
        ValidationError.RequiredSignerMissing(h, keyHash)
    }

    utxoErrors ++ mappedWitnessErrors
