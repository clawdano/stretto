package stretto.consensus

import munit.FunSuite
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.{
  Ed25519KeyGenerationParameters,
  Ed25519PrivateKeyParameters,
  Ed25519PublicKeyParameters
}
import org.bouncycastle.crypto.signers.Ed25519Signer
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

import java.security.SecureRandom

class HeaderValidationSpec extends FunSuite:

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

  test("validate rejects unregistered pool") {
    val header = makeMinimalHeader()
    val result = HeaderValidation.validate(
      header = header,
      era = Era.Babbage,
      epochNonce = ByteVector.fill(32)(0),
      lookupPool = _ => None
    )
    assert(result.isLeft)
    result.left.foreach {
      case HeaderValidationError.PoolNotRegistered(_) => ()
      case other                                      => fail(s"expected PoolNotRegistered, got $other")
    }
  }

  test("validate rejects KES period out of range") {
    val (coldSk, coldPk) = genKeyPair()
    val coldVk           = ByteVector.view(coldPk.getEncoded)

    // Set startKesPeriod = 100, but slot implies current period = 0
    val header = makeMinimalHeader(
      issuerVkey = coldVk,
      slotNo = SlotNo(0L),
      ocertStartKesPeriod = 100L
    )

    val issuerHash = Crypto.blake2b256(coldVk)
    val pool = PoolInfo(
      vrfKeyHash = Hash32.unsafeFrom(ByteVector.fill(32)(0)),
      relativeStakeNum = 1L,
      relativeStakeDen = 100L
    )

    val result = HeaderValidation.validate(
      header = header,
      era = Era.Babbage,
      epochNonce = ByteVector.fill(32)(0),
      lookupPool = h => if h == issuerHash then Some(pool) else None
    )
    assert(result.isLeft)
    result.left.foreach {
      case HeaderValidationError.KesPeriodOutOfRange(_, _, _) => ()
      case other                                              => fail(s"expected KesPeriodOutOfRange, got $other")
    }
  }

  test("validate rejects invalid OCert signature") {
    val (coldSk, coldPk) = genKeyPair()
    val coldVk           = ByteVector.view(coldPk.getEncoded)

    val header = makeMinimalHeader(
      issuerVkey = coldVk,
      slotNo = SlotNo(0L),
      ocertColdSignature = ByteVector.fill(64)(0xaa) // wrong signature
    )

    val issuerHash = Crypto.blake2b256(coldVk)
    val pool = PoolInfo(
      vrfKeyHash = Hash32.unsafeFrom(ByteVector.fill(32)(0)),
      relativeStakeNum = 1L,
      relativeStakeDen = 100L
    )

    val result = HeaderValidation.validate(
      header = header,
      era = Era.Babbage,
      epochNonce = ByteVector.fill(32)(0),
      lookupPool = h => if h == issuerHash then Some(pool) else None
    )
    assert(result.isLeft)
    result.left.foreach {
      case HeaderValidationError.OcertSignatureInvalid => ()
      case other                                       => fail(s"expected OcertSignatureInvalid, got $other")
    }
  }

  test("validate with valid OCert but invalid KES fails at KES step") {
    val (coldSk, coldPk) = genKeyPair()
    val coldVk           = ByteVector.view(coldPk.getEncoded)
    val hotVk            = ByteVector.fill(32)(0x01) // fake KES key
    val counter          = 0L
    val startKesPeriod   = 0L

    // Sign the OCert correctly
    val ocertMsg = hotVk ++ ByteVector.fromLong(counter) ++ ByteVector.fromLong(startKesPeriod)
    val coldSig  = ByteVector.view(sign(coldSk, ocertMsg.toArray))

    val header = makeMinimalHeader(
      issuerVkey = coldVk,
      slotNo = SlotNo(0L),
      ocertHotVkey = hotVk,
      ocertCounter = counter,
      ocertStartKesPeriod = startKesPeriod,
      ocertColdSignature = coldSig,
      kesSignature = ByteVector.fill(288)(0) // wrong KES sig
    )

    val issuerHash = Crypto.blake2b256(coldVk)
    val pool = PoolInfo(
      vrfKeyHash = Hash32.unsafeFrom(ByteVector.fill(32)(0)),
      relativeStakeNum = 1L,
      relativeStakeDen = 100L
    )

    val result = HeaderValidation.validate(
      header = header,
      era = Era.Babbage,
      epochNonce = ByteVector.fill(32)(0),
      lookupPool = h => if h == issuerHash then Some(pool) else None
    )
    assert(result.isLeft)
    result.left.foreach {
      case HeaderValidationError.KesSignatureInvalid => ()
      case other                                     => fail(s"expected KesSignatureInvalid, got $other")
    }
  }

  private def makeMinimalHeader(
      issuerVkey: ByteVector = ByteVector.fill(32)(0),
      vrfVkey: ByteVector = ByteVector.fill(32)(0),
      slotNo: SlotNo = SlotNo(0L),
      ocertHotVkey: ByteVector = ByteVector.fill(32)(0),
      ocertCounter: Long = 0L,
      ocertStartKesPeriod: Long = 0L,
      ocertColdSignature: ByteVector = ByteVector.fill(64)(0),
      kesSignature: ByteVector = ByteVector.fill(288)(0)
  ): ShelleyHeader =
    ShelleyHeader(
      blockNo = BlockNo(1L),
      slotNo = slotNo,
      prevHash = Hash32.unsafeFrom(ByteVector.fill(32)(0)),
      issuerVkey = issuerVkey,
      vrfVkey = vrfVkey,
      vrfResult = VrfResult.Praos(VrfCert(ByteVector.fill(80)(0), ByteVector.fill(64)(0))),
      blockBodySize = 0L,
      blockBodyHash = Hash32.unsafeFrom(ByteVector.fill(32)(0)),
      ocert = OperationalCert(ocertHotVkey, ocertCounter, ocertStartKesPeriod, ocertColdSignature),
      protocolVersion = (9, 0),
      kesSignature = kesSignature,
      rawHeaderBody = ByteVector.empty
    )
