package cardano.kes

import munit.FunSuite
import scodec.bits.ByteVector
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.{
  Ed25519KeyGenerationParameters,
  Ed25519PrivateKeyParameters,
  Ed25519PublicKeyParameters
}
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.digests.Blake2bDigest
import java.security.SecureRandom

class SumKESSpec extends FunSuite:

  // Helper to generate an Ed25519 keypair
  private def genEd25519KeyPair(): (Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters) =
    val gen = new Ed25519KeyPairGenerator()
    gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()))
    val kp = gen.generateKeyPair()
    (kp.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters], kp.getPublic.asInstanceOf[Ed25519PublicKeyParameters])

  private def ed25519Sign(sk: Ed25519PrivateKeyParameters, msg: Array[Byte]): Array[Byte] =
    val signer = new Ed25519Signer()
    signer.init(true, sk)
    signer.update(msg, 0, msg.length)
    signer.generateSignature()

  private def blake2b256(data: ByteVector): ByteVector =
    val digest = new Blake2bDigest(256)
    val input  = data.toArray
    digest.update(input, 0, input.length)
    val output = new Array[Byte](32)
    digest.doFinal(output, 0)
    ByteVector.view(output)

  /** Build a CompactSumKES tree at depth 1 (2 periods) and produce a signature for the given period. */
  private def buildDepth1(period: Int, message: ByteVector): (ByteVector, ByteVector) =
    require(period == 0 || period == 1)
    // Generate two Ed25519 keypairs (left = period 0, right = period 1)
    val (skLeft, pkLeft)   = genEd25519KeyPair()
    val (skRight, pkRight) = genEd25519KeyPair()
    val vkLeft             = ByteVector.view(pkLeft.getEncoded)
    val vkRight            = ByteVector.view(pkRight.getEncoded)
    // Root VK = blake2b256(vkLeft || vkRight)
    val rootVk = blake2b256(vkLeft ++ vkRight)
    // Sign with the appropriate key
    val (sk, leafVk, companion) =
      if period == 0 then (skLeft, vkLeft, vkRight)
      else (skRight, vkRight, vkLeft)
    val sig = ByteVector.view(ed25519Sign(sk, message.toArray))
    // Signature layout: ed25519_sig(64) || leafVk(32) || companion(32)
    val kesSig = sig ++ leafVk ++ companion
    (rootVk, kesSig)

  /** Build a CompactSumKES tree at depth 2 (4 periods) for a given period. */
  private def buildDepth2(period: Int, message: ByteVector): (ByteVector, ByteVector) =
    require(period >= 0 && period < 4)
    // 4 leaf keys
    val keys = (0 until 4).map(_ => genEd25519KeyPair())
    val vks  = keys.map { case (_, pk) => ByteVector.view(pk.getEncoded) }
    // Level 1 hashes (2 internal nodes)
    val h01 = blake2b256(vks(0) ++ vks(1))
    val h23 = blake2b256(vks(2) ++ vks(3))
    // Root
    val rootVk = blake2b256(h01 ++ h23)
    // Determine path
    val leafIdx = period
    val (sk, _) = keys(leafIdx)
    val leafVk  = vks(leafIdx)
    val sig     = ByteVector.view(ed25519Sign(sk, message.toArray))
    // Bottom companion (sibling at depth 2)
    val bottomCompanion = if period % 2 == 0 then vks(leafIdx + 1) else vks(leafIdx - 1)
    // Top companion (sibling at depth 1)
    val topCompanion = if period < 2 then h23 else h01
    // Signature: ed25519_sig(64) || leafVk(32) || bottomCompanion(32) || topCompanion(32)
    val kesSig = sig ++ leafVk ++ bottomCompanion ++ topCompanion
    (rootVk, kesSig)

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  test("signatureSize: depth 6 produces 288 bytes") {
    assertEquals(SumKES.signatureSize(6), 288)
  }

  test("signatureSize: depth 1 produces 128 bytes") {
    assertEquals(SumKES.signatureSize(1), 128)
  }

  test("signatureSize: depth 2 produces 160 bytes") {
    assertEquals(SumKES.signatureSize(2), 160)
  }

  test("depth 1, period 0: valid signature verifies") {
    val msg           = ByteVector.fromValidHex("deadbeef")
    val (rootVk, sig) = buildDepth1(0, msg)
    assert(SumKES.verify(rootVk, 0, msg, sig, depth = 1))
  }

  test("depth 1, period 1: valid signature verifies") {
    val msg           = ByteVector.fromValidHex("cafebabe")
    val (rootVk, sig) = buildDepth1(1, msg)
    assert(SumKES.verify(rootVk, 1, msg, sig, depth = 1))
  }

  test("depth 1: wrong period fails") {
    val msg           = ByteVector.fromValidHex("deadbeef")
    val (rootVk, sig) = buildDepth1(0, msg)
    assert(!SumKES.verify(rootVk, 1, msg, sig, depth = 1))
  }

  test("depth 1: wrong message fails") {
    val msg           = ByteVector.fromValidHex("deadbeef")
    val (rootVk, sig) = buildDepth1(0, msg)
    assert(!SumKES.verify(rootVk, 0, ByteVector.fromValidHex("baadf00d"), sig, depth = 1))
  }

  test("depth 1: wrong root VK fails") {
    val msg      = ByteVector.fromValidHex("deadbeef")
    val (_, sig) = buildDepth1(0, msg)
    val wrongVk  = ByteVector.fill(32)(0x42)
    assert(!SumKES.verify(wrongVk, 0, msg, sig, depth = 1))
  }

  test("depth 2, all periods: valid signatures verify") {
    val msg = ByteVector.fromValidHex("0011223344556677")
    for period <- 0 until 4 do
      val (rootVk, sig) = buildDepth2(period, msg)
      assert(SumKES.verify(rootVk, period, msg, sig, depth = 2), s"failed for period $period")
  }

  test("depth 2: wrong period fails") {
    val msg           = ByteVector.fromValidHex("0011223344556677")
    val (rootVk, sig) = buildDepth2(0, msg)
    assert(!SumKES.verify(rootVk, 2, msg, sig, depth = 2))
  }

  test("period out of range returns false") {
    val vk  = ByteVector.fill(32)(0)
    val sig = ByteVector.fill(128)(0) // depth 1 size
    assert(!SumKES.verify(vk, -1, ByteVector.empty, sig, depth = 1))
    assert(!SumKES.verify(vk, 2, ByteVector.empty, sig, depth = 1))
  }

  test("wrong signature size returns false") {
    val vk  = ByteVector.fill(32)(0)
    val sig = ByteVector.fill(100)(0) // wrong size for any depth
    assert(!SumKES.verify(vk, 0, ByteVector.empty, sig, depth = 1))
  }

  test("wrong VK size returns false") {
    val vk  = ByteVector.fill(16)(0) // too short
    val sig = ByteVector.fill(128)(0)
    assert(!SumKES.verify(vk, 0, ByteVector.empty, sig, depth = 1))
  }
