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

/** VRF certificate — proof + output. */
final case class VrfCert(proof: ByteVector, output: ByteVector)

/** Operational certificate for block production. */
final case class OperationalCert(
    hotVkey: ByteVector, // KES verification key (32 bytes)
    counter: Long,       // monotonic per pool
    startKesPeriod: Long,
    coldSignature: ByteVector // Ed25519 sig by cold key (issuerVkey)
)

/** Shelley+ block header (common fields across all post-Byron eras). */
final case class ShelleyHeader(
    blockNo: BlockNo,
    slotNo: SlotNo,
    prevHash: Hash32,
    issuerVkey: ByteVector, // Ed25519 pool cold VK (32 bytes)
    vrfVkey: ByteVector,    // VRF VK (32 bytes)
    vrfResult: VrfResult,   // VRF cert(s) — era-specific
    blockBodySize: Long,
    blockBodyHash: Hash32,
    ocert: OperationalCert,
    protocolVersion: (Int, Int), // (major, minor)
    kesSignature: ByteVector,    // KES sig over header body
    rawHeaderBody: ByteVector    // raw CBOR of header body for KES verification
)

/** VRF result — era-specific: pre-Babbage has two VRF certs (nonce + leader), Babbage+ has one. */
enum VrfResult:
  /** TPraos (Shelley-Alonzo): separate nonce and leader VRF proofs. */
  case TPraos(nonceVrf: VrfCert, leaderVrf: VrfCert)

  /** Praos (Babbage+): single unified VRF result. */
  case Praos(vrfCert: VrfCert)

/**
 * Transaction body — common across Shelley+ eras.
 * Fields added per era are represented as Option or raw bytes.
 */
final case class TransactionBody(
    inputs: Vector[TxInput],
    outputs: Vector[TxOutput],
    fee: Lovelace,
    ttl: Option[SlotNo],                         // key 3: absent in some eras
    validityIntervalStart: Option[SlotNo],       // key 8: Allegra+
    mint: Option[ByteVector],                    // key 9: Mary+ (raw multi-asset CBOR)
    scriptDataHash: Option[Hash32],              // key 11: Alonzo+
    collateralInputs: Vector[TxInput],           // key 13: Alonzo+
    requiredSigners: Vector[Hash28],             // key 14: Alonzo+
    networkId: Option[Int],                      // key 15: Alonzo+
    collateralReturn: Option[TxOutput],          // key 16: Babbage+
    totalCollateral: Option[Lovelace],           // key 17: Babbage+
    referenceInputs: Vector[TxInput],            // key 18: Babbage+
    rawCbor: ByteVector                          // full raw CBOR for hashing
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

/**
 * Full transaction — body + witnesses + metadata.
 * Used for standalone tx decoding (N2C LocalTxSubmission).
 *
 * Conway wire format: [body, witnesses, isValid, auxiliaryData]
 */
final case class Transaction(
    body: TransactionBody,
    witnesses: TxWitnessSet,
    isValid: Boolean,
    auxiliaryData: Option[ByteVector], // raw CBOR
    rawTx: ByteVector                 // full raw CBOR for the entire tx
)

/**
 * Transaction witness set — parsed from CBOR map with numeric keys.
 *
 * CBOR map keys:
 *   0 → vkey witnesses
 *   1 → native scripts (raw)
 *   2 → bootstrap witnesses (raw)
 *   3 → plutus v1 scripts (raw)
 *   4 → plutus data / datums (raw)
 *   5 → redeemers (raw)
 *   6 → plutus v2 scripts (raw)
 *   7 → plutus v3 scripts (raw)
 */
final case class TxWitnessSet(
    vkeyWitnesses: Vector[VkeyWitness],
    bootstrapWitnesses: Option[ByteVector], // raw CBOR
    nativeScripts: Option[ByteVector],      // raw CBOR
    plutusV1Scripts: Option[ByteVector],    // raw CBOR
    plutusV2Scripts: Option[ByteVector],    // raw CBOR
    plutusV3Scripts: Option[ByteVector],    // raw CBOR
    datums: Option[ByteVector],             // raw CBOR (plutus data)
    redeemers: Option[ByteVector]           // raw CBOR
)

object TxWitnessSet:
  val empty: TxWitnessSet = TxWitnessSet(
    Vector.empty, None, None, None, None, None, None, None
  )

/**
 * VKey witness — Ed25519 public key + signature.
 * Used for verifying transaction authorization.
 */
final case class VkeyWitness(
    vkey: ByteVector,      // 32-byte Ed25519 public key
    signature: ByteVector  // 64-byte Ed25519 signature
)
