package stretto.core

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import scodec.bits.ByteVector

import scala.util.Try

/** Cryptographic primitives used throughout Cardano. */
object Crypto:

  /** Compute Blake2b-256 hash. Used for block header hashes, tx hashes, etc. */
  def blake2b256(data: ByteVector): ByteVector =
    val digest = new Blake2bDigest(256)
    val input  = data.toArray
    digest.update(input, 0, input.length)
    val output = new Array[Byte](32)
    digest.doFinal(output, 0)
    ByteVector.view(output)

  /** Ed25519 signature verification. */
  object Ed25519:

    /**
     * Verify an Ed25519 signature.
     * @param publicKey 32-byte Ed25519 public key
     * @param message   the signed message
     * @param signature 64-byte Ed25519 signature
     * @return true if the signature is valid
     */
    def verify(publicKey: ByteVector, message: ByteVector, signature: ByteVector): Boolean =
      if publicKey.size != 32 || signature.size != 64 then return false
      Try {
        val pubKeyParams = new Ed25519PublicKeyParameters(publicKey.toArray, 0)
        val verifier     = new Ed25519Signer()
        verifier.init(false, pubKeyParams)
        verifier.update(message.toArray, 0, message.size.toInt)
        verifier.verifySignature(signature.toArray)
      }.getOrElse(false)

  /**
   * VRF (ECVRF-ED25519-SHA512-Elligator2) verification via libsodium/lazysodium.
   *
   * Lazily loaded — if libsodium is not available, verify returns None and
   * proofToHash returns None. This allows the node to compile and run without
   * libsodium for non-consensus tasks.
   */
  object VRF:
    import com.sun.jna.{Library, Native}

    /** JNA binding to libsodium's VRF functions (ietf draft 03). */
    private trait Sodium extends Library:
      def crypto_vrf_proofbytes(): Int
      def crypto_vrf_outputbytes(): Int
      def crypto_vrf_publickeybytes(): Int

      /** Verify VRF proof. Returns 0 on success, -1 on failure. */
      def crypto_vrf_verify(
          output: Array[Byte],
          pk: Array[Byte],
          proof: Array[Byte],
          msg: Array[Byte],
          msgLen: Long
      ): Int

      /** Convert proof to hash output. Returns 0 on success. */
      def crypto_vrf_proof_to_hash(hash: Array[Byte], proof: Array[Byte]): Int

    private lazy val sodium: Option[Sodium] =
      Try(Native.load("sodium", classOf[Sodium])).toOption

    /**
     * Verify a VRF proof and return the VRF output if valid.
     * @param vrfVkey 32-byte VRF public key
     * @param input   VRF input (the message)
     * @param proof   80-byte VRF proof
     * @return Some(64-byte output) if valid, None if invalid or libsodium unavailable
     */
    def verify(vrfVkey: ByteVector, input: ByteVector, proof: ByteVector): Option[ByteVector] =
      sodium.flatMap { lib =>
        val output = new Array[Byte](lib.crypto_vrf_outputbytes())
        val result = lib.crypto_vrf_verify(
          output,
          vrfVkey.toArray,
          proof.toArray,
          input.toArray,
          input.size
        )
        if result == 0 then Some(ByteVector.view(output))
        else None
      }

    /**
     * Extract 64-byte VRF output hash from 80-byte proof.
     * @param proof 80-byte VRF proof
     * @return Some(64-byte output) if successful, None if libsodium unavailable
     */
    def proofToHash(proof: ByteVector): Option[ByteVector] =
      sodium.flatMap { lib =>
        val hash   = new Array[Byte](lib.crypto_vrf_outputbytes())
        val result = lib.crypto_vrf_proof_to_hash(hash, proof.toArray)
        if result == 0 then Some(ByteVector.view(hash))
        else None
      }

    /** Whether libsodium is available for VRF operations. */
    def isAvailable: Boolean = sodium.isDefined
