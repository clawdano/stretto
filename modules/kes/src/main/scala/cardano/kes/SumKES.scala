package cardano.kes

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import scodec.bits.ByteVector

import scala.util.Try

/**
 * CompactSum KES (Key Evolving Signature) verification.
 *
 * Implements the CompactSumKES scheme used in Cardano's Ouroboros Praos.
 * This is a standalone, extraction-ready library for the JVM ecosystem.
 *
 * The scheme uses a binary Merkle tree of depth `d` over Ed25519 keys,
 * providing `2^d` KES periods per operational certificate.
 *
 * Cardano uses Sum6KES (depth=6, 64 periods) for Shelley through Conway.
 *
 * Signature layout for CompactSumKES at depth d:
 *   - 64 bytes: Ed25519 signature
 *   - 32 bytes: companion verification key at each level (d of them)
 *   - 32 bytes: Ed25519 leaf VK
 *   Total: 64 + 32 * d + 32 = 96 + 32*d bytes
 *
 * For Sum6KES: 96 + 32*6 = 288 bytes
 */
object SumKES:

  /** The depth used in Cardano (Sum6KES). */
  val CardanoDepth: Int = 6

  /** Expected signature size for Sum6KES: 64 (sig) + 32 (leaf vk) + 32*6 (companions) = 288. */
  val Sum6SignatureSize: Int = signatureSize(CardanoDepth)

  /** Calculate expected signature size for a given depth. */
  def signatureSize(depth: Int): Int = 64 + 32 + 32 * depth

  /**
   * Verify a CompactSumKES signature.
   *
   * @param vk       The root KES verification key (32 bytes, Blake2b-256 hash)
   * @param period   The KES period (0 to 2^depth - 1)
   * @param message  The signed message
   * @param signature The KES signature bytes
   * @param depth    Tree depth (default: 6 for Cardano)
   * @return true if the signature is valid
   */
  def verify(
      vk: ByteVector,
      period: Int,
      message: ByteVector,
      signature: ByteVector,
      depth: Int = CardanoDepth
  ): Boolean =
    val maxPeriod = 1 << depth
    if period < 0 || period >= maxPeriod then return false
    val expectedSize = signatureSize(depth)
    if signature.size != expectedSize then return false
    if vk.size != 32 then return false

    // Parse signature components
    val ed25519Sig = signature.take(64)
    val leafVk     = signature.slice(64, 96)
    // Companion VKs: signature.slice(96, 96 + 32*depth)
    val companions = (0 until depth).map(i => signature.slice(96 + 32L * i, 96 + 32L * (i + 1)))

    // 1. Verify Ed25519 signature at the leaf
    if !verifyEd25519(leafVk, message, ed25519Sig) then return false

    // 2. Walk the tree from leaf to root, checking hash at each level
    // Companions are stored bottom-up: companions(0) = leaf-level sibling, companions(depth-1) = root-level sibling
    // At each level i (0 = leaf, depth-1 = root):
    //   - bit = (period >> i) & 1
    //   - if bit == 0: current node is left child → parent = Blake2b-256(current || companion)
    //   - if bit == 1: current node is right child → parent = Blake2b-256(companion || current)
    var currentHash = leafVk
    for i <- 0 until depth do
      val bit       = (period >> i) & 1
      val companion = companions(i)
      currentHash =
        if bit == 0 then blake2b256(currentHash ++ companion)
        else blake2b256(companion ++ currentHash)

    // 3. Final hash must match the root VK
    currentHash == vk

  private def verifyEd25519(publicKey: ByteVector, message: ByteVector, signature: ByteVector): Boolean =
    if publicKey.size != 32 || signature.size != 64 then return false
    Try {
      val pubKeyParams = new Ed25519PublicKeyParameters(publicKey.toArray, 0)
      val verifier     = new Ed25519Signer()
      verifier.init(false, pubKeyParams)
      verifier.update(message.toArray, 0, message.size.toInt)
      verifier.verifySignature(signature.toArray)
    }.getOrElse(false)

  private def blake2b256(data: ByteVector): ByteVector =
    val digest = new Blake2bDigest(256)
    val input  = data.toArray
    digest.update(input, 0, input.length)
    val output = new Array[Byte](32)
    digest.doFinal(output, 0)
    ByteVector.view(output)
