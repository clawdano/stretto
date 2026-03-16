package stretto.ledger

import stretto.core.*
import stretto.core.Types.*

/**
 * UTxO set — the unspent transaction output set.
 *
 * Maps (TxHash, output index) → TxOutput for all unspent outputs.
 * This is the core ledger state that gets updated block by block.
 */
final case class UtxoState(utxos: Map[TxInput, TxOutput]):

  /** Number of unspent outputs. */
  def size: Int = utxos.size

  /** Look up an unspent output. */
  def get(input: TxInput): Option[TxOutput] = utxos.get(input)

  /** Check if an output exists (is unspent). */
  def contains(input: TxInput): Boolean = utxos.contains(input)

  /** Total ADA in the UTxO set. */
  def totalLovelace: Lovelace =
    utxos.values.foldLeft(Lovelace(0L)) { (acc, out) =>
      val coin = out.value match
        case OutputValue.PureAda(c)       => c
        case OutputValue.MultiAsset(c, _) => c
      Lovelace(acc.lovelaceValue + coin.lovelaceValue)
    }

object UtxoState:
  val empty: UtxoState = UtxoState(Map.empty)
