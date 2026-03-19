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

  /** Expected compact signature size for Sum6KES: 64 (sig) + 32 (leaf vk) + 32*6 (companions) = 288. */
  val Sum6SignatureSize: Int = compactSignatureSize(CardanoDepth)

  /** Expected standard (wire) signature size for Sum6KES: 64 + 6*64 = 448. */
  val Sum6StandardSignatureSize: Int = standardSignatureSize(CardanoDepth)

  /** Calculate expected compact signature size for a given depth. */
  def compactSignatureSize(depth: Int): Int = 64 + 32 + 32 * depth

  /**
   * Calculate expected standard (wire) signature size for a given depth.
   *  Standard format: at each level stores (innerSig, leftVK(32), rightVK(32)).
   *  Size(0) = 64 (Ed25519), Size(d) = Size(d-1) + 64 = 64 + d*64
   */
  def standardSignatureSize(depth: Int): Int = 64 + depth * 64

  // Keep backward compat
  def signatureSize(depth: Int): Int = compactSignatureSize(depth)

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
    if vk.size != 32 then return false

    val stdSize     = standardSignatureSize(depth)
    val compactSize = compactSignatureSize(depth)

    if signature.size == stdSize && stdSize != compactSize then verifyStandard(vk, period, message, signature, depth)
    else if signature.size == compactSize && stdSize != compactSize then
      verifyCompact(vk, period, message, signature, depth)
    else if signature.size == stdSize then
      // Sizes are equal (e.g. depth 1) — try both
      verifyStandard(vk, period, message, signature, depth) ||
      verifyCompact(vk, period, message, signature, depth)
    else false

  /**
   * Verify a standard (wire format) SumKES signature.
   *
   * Standard format is recursive: SumKES_Sig(d) = (SumKES_Sig(d-1), leftVK(32), rightVK(32))
   * Base case: Ed25519 sig (64 bytes).
   *
   * Total: 64 + d*64 = 64*(d+1) bytes.
   * For Sum6KES: 448 bytes.
   */
  private def verifyStandard(
      vk: ByteVector,
      period: Int,
      message: ByteVector,
      signature: ByteVector,
      depth: Int
  ): Boolean =
    // Standard SumKES signature layout (recursive, inner-first):
    //   [Ed25519_sig(64)] [vk_0_1(32), vk_1_1(32)] ... [vk_0_d(32), vk_1_d(32)]
    //
    // Matches the Haskell SumKES scheme (cardano-crypto-class):
    //   SigSumKES sigma vk_0 vk_1
    //   where sigma = SigSumKES of inner level (or Ed25519 sig at depth 0)
    //
    // At each level i (0-indexed from inner), vk_0 and vk_1 are constant (never
    // swapped by updateKES). vk_0 covers the first half of periods at that level
    // and vk_1 covers the second half. The parent hash is hash(vk_0 ++ vk_1).
    //
    // The verify function at each level checks:
    //   hash(vk_0 ++ vk_1) == parentVK
    //   if t < halfPeriods: recurse into vk_0 with period t
    //   else: recurse into vk_1 with period (t - halfPeriods)
    //
    // We implement this top-down (recursive) to match the Haskell semantics exactly.
    val ed25519Sig = signature.take(64)

    // Extract VK pairs at each level (innermost first in the wire format)
    val vkPairs = (0 until depth).map { i =>
      val offset = 64 + i.toLong * 64
      val vk0    = signature.slice(offset, offset + 32)
      val vk1    = signature.slice(offset + 32, offset + 64)
      (vk0, vk1)
    }

    // Top-down recursive verification, matching the Haskell verifyKES:
    // At the outermost level (vkPairs[depth-1]), check hash(vk_0 ++ vk_1) == root_vk,
    // then recurse inward. At each level, determine which subtree to follow based on
    // whether the period (relative to this level) falls in the first or second half.
    verifyStandardRecursive(vk, period, message, ed25519Sig, vkPairs, depth - 1)

  /** Recursive top-down verification matching Haskell SumKES.verifyKES. */
  private def verifyStandardRecursive(
      parentVk: ByteVector,
      period: Int,
      message: ByteVector,
      ed25519Sig: ByteVector,
      vkPairs: IndexedSeq[(ByteVector, ByteVector)],
      level: Int // index into vkPairs, from depth-1 (outermost) down to 0 (innermost)
  ): Boolean =
    val (vk0, vk1) = vkPairs(level)

    // Check: hash(vk_0 ++ vk_1) must equal the parent VK
    if blake2b256(vk0 ++ vk1) != parentVk then false
    else if level == 0 then
      // Base case: Ed25519 leaf level
      // _T = totalPeriods of inner scheme = 1 (Ed25519 has 1 period)
      // period must be 0 or 1 at this level
      val leafVk = if period < 1 then vk0 else vk1
      verifyEd25519(leafVk, message, ed25519Sig)
    else
      // Recursive case: determine which subtree to follow
      // _T = totalPeriods of inner scheme = 2^level (each inner level has 2^level periods)
      val halfPeriods = 1 << level
      if period < halfPeriods then verifyStandardRecursive(vk0, period, message, ed25519Sig, vkPairs, level - 1)
      else verifyStandardRecursive(vk1, period - halfPeriods, message, ed25519Sig, vkPairs, level - 1)

  /**
   * Verify a CompactSumKES signature.
   *
   * Compact format: ed25519_sig(64) + leafVK(32) + companion_0(32) + ... + companion_{d-1}(32)
   * Total: 96 + 32*d bytes. For Sum6KES: 288 bytes.
   */
  private def verifyCompact(
      vk: ByteVector,
      period: Int,
      message: ByteVector,
      signature: ByteVector,
      depth: Int
  ): Boolean =
    val ed25519Sig = signature.take(64)
    val leafVk     = signature.slice(64, 96)
    val companions = (0 until depth).map(i => signature.slice(96 + 32L * i, 96 + 32L * (i + 1)))

    // 1. Verify Ed25519 signature at the leaf
    if !verifyEd25519(leafVk, message, ed25519Sig) then return false

    // 2. Walk the tree from leaf to root
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
