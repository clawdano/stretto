package stretto.mempool

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*
import stretto.ledger.{ProtocolParameters, TransactionValidation}

/**
 * Concurrent transaction mempool with UTxO overlay.
 *
 * Maintains a virtual UTxO view: chain UTxO + mempool outputs - mempool inputs.
 * Validates transactions against this overlay to detect double-spends.
 *
 * Thread-safe via cats-effect Ref.
 */
final class Mempool private (
    stateRef: Ref[IO, MempoolState],
    maxSize: Int,
    utxoLookup: TxInput => IO[Option[TxOutput]]
):

  /**
   * Add a transaction to the mempool after validation.
   *
   * @param tx         parsed transaction body
   * @param witnesses  vkey witnesses
   * @param rawTx      raw tx CBOR (for size tracking and relay)
   * @param txHash     pre-computed tx hash
   * @param params     current protocol parameters
   * @param currentSlot current slot for TTL/validity checks
   * @return Right(txHash) if accepted, Left(errors) if rejected
   */
  def addTx(
      tx: TransactionBody,
      witnesses: Vector[VkeyWitness],
      rawTx: ByteVector,
      txHash: TxHash,
      params: ProtocolParameters,
      currentSlot: SlotNo
  ): IO[Either[Vector[String], TxHash]] =
    for
      state <- stateRef.get
      // Check if already in mempool
      result <-
        if state.txs.contains(txHash) then
          IO.pure(Right(txHash)) // idempotent
        else
          // Build virtual UTxO view: chain + mempool overlay
          buildUtxoView(tx.inputs, state).flatMap { virtualUtxos =>
            val errors = TransactionValidation.validateFullTx(
              tx, witnesses, txHash, virtualUtxos, params, currentSlot
            )
            if errors.nonEmpty then
              IO.pure(Left(errors.map(_.toString)))
            else
              // Add to mempool
              val mempoolTx = MempoolTx(
                txHash = txHash,
                body = tx,
                witnesses = witnesses,
                rawTx = rawTx,
                addedAt = currentSlot,
                feePerByte = if rawTx.size > 0 then tx.fee.lovelaceValue.toDouble / rawTx.size else 0.0
              )
              stateRef.modify { st =>
                // Double check no concurrent add
                if st.txs.contains(txHash) then (st, Right(txHash))
                else
                  val consumed = tx.inputs.toSet
                  val produced = tx.outputs.zipWithIndex.map { case (out, idx) =>
                    TxInput(txHash, idx.toLong) -> out
                  }.toMap
                  val newState = st.copy(
                    txs = st.txs + (txHash -> mempoolTx),
                    consumedInputs = st.consumedInputs ++ consumed,
                    producedOutputs = st.producedOutputs ++ produced,
                    totalBytes = st.totalBytes + rawTx.size
                  )
                  // Evict if over capacity
                  val evictedState = if newState.txs.size > maxSize then evict(newState) else newState
                  (evictedState, Right(txHash))
              }
          }
    yield result

  /**
   * Remove confirmed transactions (called when a block is applied).
   */
  def removeTxs(confirmed: Set[TxHash]): IO[Unit] =
    stateRef.update { st =>
      val toRemove = st.txs.filter { case (h, _) => confirmed.contains(h) }
      val removedInputs  = toRemove.values.flatMap(_.body.inputs).toSet
      val removedOutputs = toRemove.values.flatMap { tx =>
        tx.body.outputs.zipWithIndex.map { case (_, idx) => TxInput(tx.txHash, idx.toLong) }
      }.toSet
      val removedBytes = toRemove.values.map(_.rawTx.size).sum
      st.copy(
        txs = st.txs -- confirmed,
        consumedInputs = st.consumedInputs -- removedInputs,
        producedOutputs = st.producedOutputs -- removedOutputs,
        totalBytes = st.totalBytes - removedBytes
      )
    }

  /**
   * Get transactions ordered by fee density (for future block production).
   */
  def getTxs(maxBytes: Long): IO[Vector[MempoolTx]] =
    stateRef.get.map { st =>
      val sorted = st.txs.values.toVector.sortBy(-_.feePerByte)
      var bytes  = 0L
      sorted.takeWhile { tx =>
        bytes += tx.rawTx.size
        bytes <= maxBytes
      }
    }

  /** Get the current mempool size. */
  def size: IO[Int] = stateRef.get.map(_.txs.size)

  /** Get the current mempool state snapshot. */
  def snapshot: IO[MempoolState] = stateRef.get

  /** Build virtual UTxO view for the given inputs. */
  private def buildUtxoView(
      inputs: Vector[TxInput],
      state: MempoolState
  ): IO[Map[TxInput, TxOutput]] =
    inputs.toList.traverse { input =>
      // Check mempool overlay first (produced outputs)
      state.producedOutputs.get(input) match
        case Some(output) => IO.pure(Some(input -> output))
        case None =>
          // Check if consumed by another mempool tx (double-spend)
          if state.consumedInputs.contains(input) then IO.pure(None)
          else
            // Look up from chain UTxO
            utxoLookup(input).map(_.map(out => input -> out))
    }.map(_.flatten.toMap)

  /** Evict lowest-fee-density transaction to make room. */
  private def evict(st: MempoolState): MempoolState =
    if st.txs.isEmpty then st
    else
      val lowestFee = st.txs.values.minBy(_.feePerByte)
      val inputs    = lowestFee.body.inputs.toSet
      val outputs   = lowestFee.body.outputs.zipWithIndex.map { case (_, idx) =>
        TxInput(lowestFee.txHash, idx.toLong)
      }.toSet
      st.copy(
        txs = st.txs - lowestFee.txHash,
        consumedInputs = st.consumedInputs -- inputs,
        producedOutputs = st.producedOutputs -- outputs,
        totalBytes = st.totalBytes - lowestFee.rawTx.size
      )

  // Use cats traverse via import cats.syntax.all.*

object Mempool:

  /**
   * Create a new empty mempool.
   *
   * @param maxSize    maximum number of transactions
   * @param utxoLookup function to look up UTxOs from the chain (RocksDB)
   */
  def create(
      maxSize: Int = 4096,
      utxoLookup: TxInput => IO[Option[TxOutput]]
  ): IO[Mempool] =
    Ref.of[IO, MempoolState](MempoolState.empty).map { ref =>
      new Mempool(ref, maxSize, utxoLookup)
    }
