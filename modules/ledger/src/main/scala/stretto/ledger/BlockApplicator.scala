package stretto.ledger

import stretto.core.*
import stretto.core.Types.*

/**
 * Applies blocks to the UTxO state with validation.
 *
 * For each transaction in a block:
 *   1. Validate against ledger rules (value preservation, fees, TTL, min UTxO)
 *   2. Remove spent inputs from the UTxO set
 *   3. Add new outputs to the UTxO set (keyed by tx hash + index)
 *
 * Returns the updated state and any validation errors encountered.
 * Currently permissive — logs errors but continues processing.
 * A strict mode can be added later that rejects invalid transactions.
 *
 * Reference: Shelley formal spec §9 (UTxO transition), §10 (fees)
 */
object BlockApplicator:

  /** Result of applying a block. */
  final case class ApplyResult(
      state: UtxoState,
      txsProcessed: Int,
      inputsConsumed: Int,
      outputsCreated: Int,
      errors: Vector[ApplyError]
  )

  /** Errors that can occur during block application. */
  enum ApplyError:
    case MissingInput(txId: TxHash, input: TxInput)
    case DuplicateOutput(txId: TxHash, index: Long)
    case Validation(error: TransactionValidation.ValidationError)

  /**
   * Apply a parsed block to the UTxO state.
   *
   * @param state       current UTxO state
   * @param block       parsed block to apply
   * @param currentSlot the slot of this block (for TTL validation)
   * @param params      protocol parameters for this era (None = skip validation)
   */
  def apply(
      state: UtxoState,
      block: Block,
      currentSlot: SlotNo = SlotNo(0L),
      params: Option[ProtocolParameters] = None
  ): ApplyResult =
    block match
      case Block.ByronEbBlock(_, _) =>
        // EBBs have no transactions
        ApplyResult(state, 0, 0, 0, Vector.empty)

      case Block.ByronBlock(_, body, _) =>
        applyByronTxs(state, body.txPayload, params)

      case Block.ShelleyBlock(era, header, txBodies, _, _, _) =>
        val effectiveSlot   = if currentSlot.value > 0 then currentSlot else header.slotNo
        val effectiveParams = params.orElse(Some(ProtocolParameters.forEra(era)))
        applyShelleyTxs(state, txBodies, effectiveSlot, effectiveParams)

  /** Backward-compatible overload without validation params. */
  def apply(state: UtxoState, block: Block): ApplyResult =
    apply(state, block, SlotNo(0L), None)

  /** Apply Byron transactions to UTxO state. */
  private def applyByronTxs(
      state: UtxoState,
      txs: Vector[ByronTx],
      params: Option[ProtocolParameters]
  ): ApplyResult =
    var utxos          = state.utxos
    var inputsConsumed = 0
    var outputsCreated = 0
    var errors         = Vector.empty[ApplyError]

    txs.foreach { tx =>
      // Validate if params provided
      params.foreach { p =>
        val validationErrors = TransactionValidation.validateByronTx(tx, utxos)
        errors = errors ++ validationErrors.map(ApplyError.Validation.apply)
      }

      // 1. Remove spent inputs
      tx.inputs.foreach { input =>
        val utxoKey = TxInput(input.txId, input.index)
        if utxos.contains(utxoKey) then
          utxos = utxos - utxoKey
          inputsConsumed += 1
        else errors = errors :+ ApplyError.MissingInput(tx.txId, utxoKey)
      }

      // 2. Add new outputs
      tx.outputs.zipWithIndex.foreach { case (output, idx) =>
        val outKey = TxInput(tx.txId, idx.toLong)
        utxos = utxos + (outKey -> TxOutput(output.address, OutputValue.PureAda(output.amount)))
        outputsCreated += 1
      }
    }

    ApplyResult(UtxoState(utxos), txs.size, inputsConsumed, outputsCreated, errors)

  /** Apply Shelley+ transactions to UTxO state. */
  private def applyShelleyTxs(
      state: UtxoState,
      txBodies: Vector[TransactionBody],
      currentSlot: SlotNo,
      params: Option[ProtocolParameters]
  ): ApplyResult =
    var utxos          = state.utxos
    var inputsConsumed = 0
    var outputsCreated = 0
    var errors         = Vector.empty[ApplyError]

    txBodies.foreach { tx =>
      // Compute tx hash from raw CBOR
      val txHash = TxHash(Hash32.unsafeFrom(Crypto.blake2b256(tx.rawCbor)))

      // Validate if params provided
      params.foreach { p =>
        val validationErrors =
          TransactionValidation.validateShelleyTx(tx, txHash, utxos, p, currentSlot)
        errors = errors ++ validationErrors.map(ApplyError.Validation.apply)
      }

      // 1. Remove spent inputs
      tx.inputs.foreach { input =>
        if utxos.contains(input) then
          utxos = utxos - input
          inputsConsumed += 1
        else errors = errors :+ ApplyError.MissingInput(txHash, input)
      }

      // 2. Add new outputs
      tx.outputs.zipWithIndex.foreach { case (output, idx) =>
        val outKey = TxInput(txHash, idx.toLong)
        utxos = utxos + (outKey -> output)
        outputsCreated += 1
      }
    }

    ApplyResult(UtxoState(utxos), txBodies.size, inputsConsumed, outputsCreated, errors)
