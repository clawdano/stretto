package stretto.core

import munit.FunSuite
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.{
  Ed25519KeyGenerationParameters,
  Ed25519PrivateKeyParameters,
  Ed25519PublicKeyParameters
}
import org.bouncycastle.crypto.signers.Ed25519Signer
import scodec.bits.ByteVector

import java.security.SecureRandom

class CryptoSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // Blake2b-256
  // ---------------------------------------------------------------------------

  test("blake2b256 of empty input") {
    val hash = Crypto.blake2b256(ByteVector.empty)
    assertEquals(hash.size, 32L)
    // Known Blake2b-256 of empty string (BouncyCastle verified)
    assertEquals(
      hash.toHex,
      "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8"
    )
  }

  test("blake2b256 produces 32 bytes") {
    val data = ByteVector.fromValidHex("deadbeef")
    val hash = Crypto.blake2b256(data)
    assertEquals(hash.size, 32L)
  }

  // ---------------------------------------------------------------------------
  // Ed25519
  // ---------------------------------------------------------------------------

  private def genKeyPair(): (Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters) =
    val gen = new Ed25519KeyPairGenerator()
    gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()))
    val kp = gen.generateKeyPair()
    (kp.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters], kp.getPublic.asInstanceOf[Ed25519PublicKeyParameters])

  private def sign(sk: Ed25519PrivateKeyParameters, msg: Array[Byte]): Array[Byte] =
    val signer = new Ed25519Signer()
    signer.init(true, sk)
    signer.update(msg, 0, msg.length)
    signer.generateSignature()

  test("Ed25519 verify — valid signature") {
    val (sk, pk) = genKeyPair()
    val msg      = "hello cardano".getBytes
    val sig      = sign(sk, msg)
    assert(
      Crypto.Ed25519.verify(
        ByteVector.view(pk.getEncoded),
        ByteVector.view(msg),
        ByteVector.view(sig)
      )
    )
  }

  test("Ed25519 verify — empty message") {
    val (sk, pk) = genKeyPair()
    val msg      = Array.empty[Byte]
    val sig      = sign(sk, msg)
    assert(
      Crypto.Ed25519.verify(
        ByteVector.view(pk.getEncoded),
        ByteVector.empty,
        ByteVector.view(sig)
      )
    )
  }

  test("Ed25519 verify — wrong message returns false") {
    val (sk, pk) = genKeyPair()
    val sig      = sign(sk, "hello".getBytes)
    assert(
      !Crypto.Ed25519.verify(
        ByteVector.view(pk.getEncoded),
        ByteVector.view("wrong".getBytes),
        ByteVector.view(sig)
      )
    )
  }

  test("Ed25519 verify — wrong signature returns false") {
    val pk = ByteVector.fromValidHex(
      "d75a980182b10ab7d54bfed3c964073a0ee172f3daa3f4a18446b7e8c7f28e89"
    )
    val sig = ByteVector.fill(64)(0x00)
    assert(!Crypto.Ed25519.verify(pk, ByteVector.empty, sig))
  }

  test("Ed25519 verify — wrong public key returns false") {
    val (sk, _) = genKeyPair()
    val sig     = ByteVector.view(sign(sk, "test".getBytes))
    val wrongPk = ByteVector.fill(32)(0x42)
    assert(!Crypto.Ed25519.verify(wrongPk, ByteVector.view("test".getBytes), sig))
  }

  test("Ed25519 verify — invalid key size returns false") {
    val pk  = ByteVector.fill(16)(0x00)
    val sig = ByteVector.fill(64)(0x00)
    assert(!Crypto.Ed25519.verify(pk, ByteVector.empty, sig))
  }

  test("Ed25519 verify — invalid signature size returns false") {
    val pk  = ByteVector.fill(32)(0x00)
    val sig = ByteVector.fill(32)(0x00)
    assert(!Crypto.Ed25519.verify(pk, ByteVector.empty, sig))
  }

  // ---------------------------------------------------------------------------
  // VRF (only test availability; actual verification needs libsodium)
  // ---------------------------------------------------------------------------

  test("VRF.isAvailable reports libsodium status") {
    // This test just ensures the availability check doesn't crash
    val _ = Crypto.VRF.isAvailable
  }
