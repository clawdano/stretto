package stretto.ledger

import stretto.core.Types.*

/**
 * Protocol parameters that govern ledger validation rules.
 *
 * These values change per era and can be updated via governance.
 * For now, we use hardcoded defaults per era — protocol parameter
 * updates from the chain will be added later.
 *
 * Reference: Shelley formal spec §17, Alonzo spec §1
 */
final case class ProtocolParameters(
    /** Minimum fee coefficient (per byte). Shelley spec §10.2: a */
    minFeeA: Long,
    /** Minimum fee constant. Shelley spec §10.2: b */
    minFeeB: Long,
    /** Minimum UTxO value in lovelace. Shelley spec §9.2 */
    minUtxoValue: Lovelace,
    /** Lovelace per UTxO byte (Alonzo+, replaces minUtxoValue). */
    coinsPerUtxoByte: Option[Lovelace],
    /** Maximum transaction size in bytes. */
    maxTxSize: Long,
    /** Maximum value size in bytes (Alonzo+). */
    maxValueSize: Option[Long]
):

  /**
   * Calculate the minimum fee for a transaction.
   * Shelley spec §10.2: minFee(tx) = a * size(tx) + b
   */
  def minFee(txSizeBytes: Long): Lovelace =
    Lovelace(minFeeA * txSizeBytes + minFeeB)

  /**
   * Calculate the minimum lovelace for a UTxO output.
   *
   * - Shelley-Mary: flat minUtxoValue
   * - Alonzo+: coinsPerUtxoByte * (utxoEntrySizeWithoutVal + outputSize + 160)
   *
   * For simplicity, we use the flat value for Shelley-Mary and
   * coinsPerUtxoByte * (outputSize + 160) for Alonzo+.
   */
  def minLovelaceForOutput(outputSizeBytes: Long): Lovelace =
    coinsPerUtxoByte match
      case Some(perByte) =>
        // Alonzo+ formula: coinsPerUtxoByte * (utxoEntrySizeWithoutVal + 160)
        // Simplified: perByte * (outputSize + 160)
        Lovelace(perByte.lovelaceValue * (outputSizeBytes + 160))
      case None =>
        minUtxoValue

object ProtocolParameters:

  /** Byron era — no fee formula validation (fees are implicit in inputs - outputs). */
  val Byron: ProtocolParameters = ProtocolParameters(
    minFeeA = 0L,
    minFeeB = 0L,
    minUtxoValue = Lovelace(0L),
    coinsPerUtxoByte = None,
    maxTxSize = 65536L,
    maxValueSize = None
  )

  /** Shelley era defaults (mainnet genesis values). */
  val Shelley: ProtocolParameters = ProtocolParameters(
    minFeeA = 44L,
    minFeeB = 155381L,
    minUtxoValue = Lovelace(1000000L),
    coinsPerUtxoByte = None,
    maxTxSize = 16384L,
    maxValueSize = None
  )

  /** Allegra era — same fee params as Shelley. */
  val Allegra: ProtocolParameters = Shelley

  /** Mary era — same base fee params, higher max tx size for multi-asset. */
  val Mary: ProtocolParameters = Shelley.copy(
    maxTxSize = 16384L
  )

  /** Alonzo era — introduces coinsPerUtxoByte, script execution. */
  val Alonzo: ProtocolParameters = ProtocolParameters(
    minFeeA = 44L,
    minFeeB = 155381L,
    minUtxoValue = Lovelace(0L),
    coinsPerUtxoByte = Some(Lovelace(4310L)),
    maxTxSize = 16384L,
    maxValueSize = Some(5000L)
  )

  /** Babbage era — lower coinsPerUtxoByte. */
  val Babbage: ProtocolParameters = ProtocolParameters(
    minFeeA = 44L,
    minFeeB = 155381L,
    minUtxoValue = Lovelace(0L),
    coinsPerUtxoByte = Some(Lovelace(4310L)),
    maxTxSize = 16384L,
    maxValueSize = Some(5000L)
  )

  /** Conway era — same as Babbage initially. */
  val Conway: ProtocolParameters = Babbage

  /** Look up protocol parameters by era. */
  def forEra(era: stretto.core.Era): ProtocolParameters = era match
    case stretto.core.Era.Byron   => Byron
    case stretto.core.Era.Shelley => Shelley
    case stretto.core.Era.Allegra => Allegra
    case stretto.core.Era.Mary    => Mary
    case stretto.core.Era.Alonzo  => Alonzo
    case stretto.core.Era.Babbage => Babbage
    case stretto.core.Era.Conway  => Conway
