package stretto.node

/**
 * Per-network genesis configuration needed for LocalStateQuery responses.
 *
 * Contains the system start time and epoch/slot parameters for computing
 * epoch numbers from slot numbers without full ledger state.
 */
final case class GenesisConfig(
    systemStart: String,
    byronEpochLength: Long,
    byronSlotLength: Long,
    shelleyEpochLength: Long,
    shelleySlotLength: Long,
    byronShelleyTransitionEpoch: Long
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

  val Mainnet: GenesisConfig = GenesisConfig(
    systemStart = "2017-09-23T21:44:51Z",
    byronEpochLength = 21600,
    byronSlotLength = 20,
    shelleyEpochLength = 432000,
    shelleySlotLength = 1,
    byronShelleyTransitionEpoch = 208
  )

  val Preprod: GenesisConfig = GenesisConfig(
    systemStart = "2022-04-01T00:00:00Z",
    byronEpochLength = 4320,
    byronSlotLength = 20,
    shelleyEpochLength = 36000,
    shelleySlotLength = 1,
    byronShelleyTransitionEpoch = 4
  )

  val Preview: GenesisConfig = GenesisConfig(
    systemStart = "2022-11-01T00:00:00Z",
    byronEpochLength = 4320,
    byronSlotLength = 20,
    shelleyEpochLength = 86400,
    shelleySlotLength = 1,
    byronShelleyTransitionEpoch = 0
  )

  def forNetwork(name: String): GenesisConfig = name.toLowerCase match
    case "mainnet" => Mainnet
    case "preprod" => Preprod
    case "preview" => Preview
    case _         => Preprod // default fallback
