package stretto.core

import scodec.bits.ByteVector
import stretto.core.Types.*

/** Parsed Cardano block — era-specific ADT. */
enum Block:

  /** Byron epoch boundary block (EBB). */
  case ByronEbBlock(
      header: ByronEbHeader,
      body: ByteVector // EBB body is just the list of stake issuer keys
  )

  /** Byron main block. */
  case ByronBlock(
      header: ByronHeader,
      body: ByronBody,
      extra: ByteVector
  )

  /** Shelley-era block (also used as base for Allegra, Mary, Alonzo, Babbage, Conway). */
  case ShelleyBlock(
      era: Era,
      header: ShelleyHeader,
      txBodies: Vector[TransactionBody],
      txWitnesses: Vector[ByteVector],           // raw witness CBOR for now
      auxiliaryData: Vector[Option[ByteVector]], // raw auxiliary CBOR
      invalidTxs: Vector[Int]                    // indices of invalid txs (Alonzo+)
  )

/** Byron EBB header. */
final case class ByronEbHeader(
    protocolMagic: Long,
    prevBlock: Hash32,
    bodyProof: Hash32,
    epochId: EpochNo,
    chainDifficulty: Long
)

/** Byron main block header. */
final case class ByronHeader(
    protocolMagic: Long,
    prevBlock: Hash32,
    bodyProof: Hash32,
    slot: SlotNo,
    difficulty: Long
)

/** Byron block body. */
final case class ByronBody(
    txPayload: Vector[ByronTx]
)

/** Byron transaction. */
final case class ByronTx(
    txId: TxHash,
    inputs: Vector[ByronTxIn],
    outputs: Vector[ByronTxOut]
)

/** Byron transaction input. */
final case class ByronTxIn(
    txId: TxHash,
    index: Long
)

/** Byron transaction output. */
final case class ByronTxOut(
    address: ByteVector, // raw Byron address CBOR
    amount: Lovelace
)

/** Shelley+ block header (common fields across all post-Byron eras). */
final case class ShelleyHeader(
    blockNo: BlockNo,
    slotNo: SlotNo,
    prevHash: Hash32,
    issuerVkey: ByteVector,
    vrfVkey: ByteVector,
    blockBodySize: Long,
    blockBodyHash: Hash32
)

/**
 * Transaction body — common across Shelley+ eras.
 * Fields added per era are represented as Option or raw bytes.
 */
final case class TransactionBody(
    inputs: Vector[TxInput],
    outputs: Vector[TxOutput],
    fee: Lovelace,
    ttl: Option[SlotNo], // absent in some eras
    rawCbor: ByteVector  // full raw CBOR for hashing
)

/** Transaction input (all eras). */
final case class TxInput(
    txId: TxHash,
    index: Long
)

/** Transaction output — simplified, captures coin value. */
final case class TxOutput(
    address: ByteVector, // raw address bytes (can be decoded separately)
    value: OutputValue
)

/** Output value — either pure ADA or ADA + multi-asset. */
enum OutputValue:
  case PureAda(coin: Lovelace)
  case MultiAsset(coin: Lovelace, assets: ByteVector) // raw multi-asset CBOR for now
