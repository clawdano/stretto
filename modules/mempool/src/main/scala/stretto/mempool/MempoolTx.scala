package stretto.mempool

import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

/** A transaction held in the mempool with metadata. */
final case class MempoolTx(
    txHash: TxHash,
    body: TransactionBody,
    witnesses: Vector[VkeyWitness],
    rawTx: ByteVector,
    addedAt: SlotNo,
    feePerByte: Double
)

/** Internal mempool state. */
final case class MempoolState(
    txs: Map[TxHash, MempoolTx],
    consumedInputs: Set[TxInput],
    producedOutputs: Map[TxInput, TxOutput],
    totalBytes: Long
)

object MempoolState:
  val empty: MempoolState = MempoolState(Map.empty, Set.empty, Map.empty, 0L)
