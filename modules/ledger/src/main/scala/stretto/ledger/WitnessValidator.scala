package stretto.ledger

import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

/**
 * Verifies Ed25519 witnesses on transactions.
 *
 * For each input's address, extracts the payment key hash and verifies
 * that a corresponding vkey witness exists with a valid signature over
 * the transaction body hash.
 *
 * Reference: Shelley formal spec §13 (witnesses)
 */
object WitnessValidator:

  /** Witness validation errors. */
  enum WitnessError:
    /** No valid vkey witness found for the given payment key hash. */
    case MissingWitness(txHash: TxHash, keyHash: Hash28)

    /** Vkey witness has invalid Ed25519 signature. */
    case InvalidSignature(txHash: TxHash, vkey: ByteVector)

    /** Required signer key hash not covered by any witness. */
    case RequiredSignerMissing(txHash: TxHash, keyHash: Hash28)

  /**
   * Verify vkey witnesses against the transaction body hash.
   *
   * Steps:
   *   1. Compute txBodyHash = blake2b256(body.rawCbor)
   *   2. For each vkey witness, verify Ed25519(vkey, txBodyHash, signature)
   *   3. Compute witnessKeyHashes = { blake2b224(vkey) | valid witness }
   *   4. For each input, extract payment key hash from address
   *   5. Verify each payment key hash is in witnessKeyHashes
   *   6. Verify each required signer is in witnessKeyHashes
   *
   * @param body       transaction body
   * @param witnesses  vkey witnesses from the witness set
   * @param utxos      UTxO lookup for input addresses
   * @param txHash     the tx hash (for error reporting)
   * @return vector of all witness errors (empty if valid)
   */
  def verify(
      body: TransactionBody,
      witnesses: Vector[VkeyWitness],
      utxos: Map[TxInput, TxOutput],
      txHash: TxHash
  ): Vector[WitnessError] =
    var errors = Vector.empty[WitnessError]

    // 1. Compute tx body hash
    val txBodyHash = Crypto.blake2b256(body.rawCbor)

    // 2. Verify each witness signature and collect valid key hashes
    val validKeyHashes = witnesses.flatMap { w =>
      if Crypto.Ed25519.verify(w.vkey, txBodyHash, w.signature) then
        // blake2b224 of the vkey = payment key hash
        Some(blake2b224(w.vkey))
      else
        errors = errors :+ WitnessError.InvalidSignature(txHash, w.vkey)
        None
    }.toSet

    // 3. For each input, check that its payment key hash is covered
    body.inputs.foreach { input =>
      utxos.get(input).foreach { output =>
        Address.extractPaymentCredential(output.address) match
          case Some(PaymentCredential.PubKeyCredential(keyHash)) =>
            if !validKeyHashes.contains(keyHash) then errors = errors :+ WitnessError.MissingWitness(txHash, keyHash)
          case _ => () // Script credentials or Byron — skip for now
      }
    }

    // 4. Check required signers
    body.requiredSigners.foreach { keyHash =>
      if !validKeyHashes.contains(keyHash) then errors = errors :+ WitnessError.RequiredSignerMissing(txHash, keyHash)
    }

    errors

  /** Blake2b-224 hash (28 bytes), used for key hashes in Cardano. */
  private def blake2b224(data: ByteVector): Hash28 =
    import org.bouncycastle.crypto.digests.Blake2bDigest
    val digest = new Blake2bDigest(224)
    val input  = data.toArray
    digest.update(input, 0, input.length)
    val output = new Array[Byte](28)
    digest.doFinal(output, 0)
    Hash28.unsafeFrom(ByteVector.view(output))
