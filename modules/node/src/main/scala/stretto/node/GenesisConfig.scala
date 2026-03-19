package stretto.node

import scodec.bits.ByteVector

/**
 * Per-network genesis configuration needed for LocalStateQuery responses.
 *
 * Contains the system start time and epoch/slot parameters for computing
 * epoch numbers from slot numbers without full ledger state.
 *
 * shelleyGenesisHash is the Blake2b-256 hash of the Shelley genesis JSON file,
 * used as the initial epoch nonce for consensus state initialization.
 */
final case class GenesisConfig(
    systemStart: String,
    byronEpochLength: Long,
    byronSlotLength: Long,
    shelleyEpochLength: Long,
    shelleySlotLength: Long,
    byronShelleyTransitionEpoch: Long,
    shelleyGenesisHash: ByteVector
):

  /** First Shelley slot = Byron epochs * Byron epoch length * (Byron slot length / Shelley slot length). */
  def shelleyStartSlot: Long =
    byronShelleyTransitionEpoch * byronEpochLength * (byronSlotLength / shelleySlotLength)

  /** Compute epoch number from an absolute slot. */
  def epochForSlot(slot: Long): Long =
    val shelleyStart = shelleyStartSlot
    if slot < shelleyStart then slot / (byronEpochLength * byronSlotLength)
    else byronShelleyTransitionEpoch + (slot - shelleyStart) / shelleyEpochLength

object GenesisConfig:

  // Shelley genesis hashes (Blake2b-256 of the Shelley genesis JSON file)
  // These serve as the initial epoch nonce for consensus state.
  private val MainnetShelleyGenesisHash: ByteVector =
    ByteVector.fromValidHex("1a3be38bcbb7911969283716ad7aa550250226b76a61fc51cc9a9a35d9276d81")

  private val PreprodShelleyGenesisHash: ByteVector =
    ByteVector.fromValidHex("409b8ad44ab4e0ead05ad10ae988e9068f91483d657bbd7b8d457d4220767e3a")

  private val PreviewShelleyGenesisHash: ByteVector =
    ByteVector.fromValidHex("363498d1024f84bb39d3fa9593ce391571c81f0c40a163c22a4c7f4e404d02eb")

  val Mainnet: GenesisConfig = GenesisConfig(
    systemStart = "2017-09-23T21:44:51Z",
    byronEpochLength = 21600,
    byronSlotLength = 20,
    shelleyEpochLength = 432000,
    shelleySlotLength = 1,
    byronShelleyTransitionEpoch = 208,
    shelleyGenesisHash = MainnetShelleyGenesisHash
  )

  val Preprod: GenesisConfig = GenesisConfig(
    systemStart = "2022-04-01T00:00:00Z",
    byronEpochLength = 4320,
    byronSlotLength = 20,
    shelleyEpochLength = 36000,
    shelleySlotLength = 1,
    byronShelleyTransitionEpoch = 4,
    shelleyGenesisHash = PreprodShelleyGenesisHash
  )

  val Preview: GenesisConfig = GenesisConfig(
    systemStart = "2022-11-01T00:00:00Z",
    byronEpochLength = 4320,
    byronSlotLength = 20,
    shelleyEpochLength = 86400,
    shelleySlotLength = 1,
    byronShelleyTransitionEpoch = 0,
    shelleyGenesisHash = PreviewShelleyGenesisHash
  )

  def forNetwork(name: String): GenesisConfig = name.toLowerCase match
    case "mainnet" => Mainnet
    case "preprod" => Preprod
    case "preview" => Preview
    case _         => Preprod // default fallback
