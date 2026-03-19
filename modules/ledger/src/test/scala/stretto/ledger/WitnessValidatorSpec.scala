package stretto.ledger

import munit.FunSuite
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

class WitnessValidatorSpec extends FunSuite:

  // Generate a deterministic key pair for testing
  private val testVkey = ByteVector.fill(32)(0x42) // dummy vkey
  private val testSig  = ByteVector.fill(64)(0x00) // dummy signature (won't verify)

  private val txHash1 = TxHash(Hash32.unsafeFrom(ByteVector.fill(32)(0x01)))

  // Compute blake2b224 of testVkey to get the key hash
  private def blake2b224(data: ByteVector): Hash28 =
    import org.bouncycastle.crypto.digests.Blake2bDigest
    val digest = new Blake2bDigest(224)
    val input  = data.toArray
    digest.update(input, 0, input.length)
    val output = new Array[Byte](28)
    digest.doFinal(output, 0)
    Hash28.unsafeFrom(ByteVector.view(output))

  test("empty witnesses produces missing witness for key-hash inputs") {
    val keyHash = blake2b224(testVkey)
    // Shelley base address: type 0 (key, key), network 0
    val address = ByteVector(0x00.toByte) ++ keyHash.hash28Bytes ++ ByteVector.fill(28)(0x00)
    val input   = TxInput(txHash1, 0L)
    val utxos   = Map(input -> TxOutput(address, OutputValue.PureAda(Lovelace(1000000L))))
    val body = TransactionBody(
      inputs = Vector(input),
      outputs = Vector.empty,
      fee = Lovelace(200000L),
      ttl = None,
      validityIntervalStart = None,
      mint = None,
      scriptDataHash = None,
      collateralInputs = Vector.empty,
      requiredSigners = Vector.empty,
      networkId = None,
      collateralReturn = None,
      totalCollateral = None,
      referenceInputs = Vector.empty,
      rawCbor = ByteVector.fill(100)(0x00)
    )

    val errors = WitnessValidator.verify(body, Vector.empty, utxos, txHash1)
    assert(errors.exists(_.isInstanceOf[WitnessValidator.WitnessError.MissingWitness]))
  }

  test("witness with invalid signature is reported") {
    val body = TransactionBody(
      inputs = Vector.empty,
      outputs = Vector.empty,
      fee = Lovelace(200000L),
      ttl = None,
      validityIntervalStart = None,
      mint = None,
      scriptDataHash = None,
      collateralInputs = Vector.empty,
      requiredSigners = Vector.empty,
      networkId = None,
      collateralReturn = None,
      totalCollateral = None,
      referenceInputs = Vector.empty,
      rawCbor = ByteVector.fill(100)(0x00)
    )

    val badWitness = VkeyWitness(testVkey, testSig)
    val errors     = WitnessValidator.verify(body, Vector(badWitness), Map.empty, txHash1)
    assert(errors.exists(_.isInstanceOf[WitnessValidator.WitnessError.InvalidSignature]))
  }

  test("required signer not covered produces error") {
    val keyHash = blake2b224(testVkey)
    val body = TransactionBody(
      inputs = Vector.empty,
      outputs = Vector.empty,
      fee = Lovelace(200000L),
      ttl = None,
      validityIntervalStart = None,
      mint = None,
      scriptDataHash = None,
      collateralInputs = Vector.empty,
      requiredSigners = Vector(keyHash),
      networkId = None,
      collateralReturn = None,
      totalCollateral = None,
      referenceInputs = Vector.empty,
      rawCbor = ByteVector.fill(100)(0x00)
    )

    val errors = WitnessValidator.verify(body, Vector.empty, Map.empty, txHash1)
    assert(errors.exists(_.isInstanceOf[WitnessValidator.WitnessError.RequiredSignerMissing]))
  }

  test("script credential inputs don't require vkey witnesses") {
    val keyHash = blake2b224(testVkey)
    // Shelley base address: type 1 (script, key)
    val address = ByteVector(0x10.toByte) ++ keyHash.hash28Bytes ++ ByteVector.fill(28)(0x00)
    val input   = TxInput(txHash1, 0L)
    val utxos   = Map(input -> TxOutput(address, OutputValue.PureAda(Lovelace(1000000L))))
    val body = TransactionBody(
      inputs = Vector(input),
      outputs = Vector.empty,
      fee = Lovelace(200000L),
      ttl = None,
      validityIntervalStart = None,
      mint = None,
      scriptDataHash = None,
      collateralInputs = Vector.empty,
      requiredSigners = Vector.empty,
      networkId = None,
      collateralReturn = None,
      totalCollateral = None,
      referenceInputs = Vector.empty,
      rawCbor = ByteVector.fill(100)(0x00)
    )

    // No witnesses needed for script credential
    val errors = WitnessValidator.verify(body, Vector.empty, utxos, txHash1)
    val missingWitErrors = errors.collect { case e: WitnessValidator.WitnessError.MissingWitness => e }
    assert(missingWitErrors.isEmpty, "script credentials should not require vkey witnesses")
  }
