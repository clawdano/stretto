package stretto.core

import munit.FunSuite
import scodec.bits.ByteVector
import stretto.core.Types.*

class AddressSpec extends FunSuite:

  test("extract PubKeyCredential from type-0 base address") {
    // Type 0 = base address (key hash, key hash), network 0
    val keyHash = Hash28.unsafeFrom(ByteVector.fill(28)(0x42))
    val address = ByteVector(0x00.toByte) ++ keyHash.hash28Bytes ++ ByteVector.fill(28)(0xaa)
    val result  = Address.extractPaymentCredential(address)
    assert(result.isDefined)
    result.foreach {
      case PaymentCredential.PubKeyCredential(h) =>
        assertEquals(h.hash28Bytes, keyHash.hash28Bytes)
      case _ => fail("expected PubKeyCredential")
    }
  }

  test("extract ScriptCredential from type-1 base address") {
    // Type 1 = base address (script hash, key hash), network 0
    val scriptHash = Hash28.unsafeFrom(ByteVector.fill(28)(0x42))
    val address    = ByteVector(0x10.toByte) ++ scriptHash.hash28Bytes ++ ByteVector.fill(28)(0xaa)
    val result     = Address.extractPaymentCredential(address)
    assert(result.isDefined)
    result.foreach {
      case PaymentCredential.ScriptCredential(h) =>
        assertEquals(h.hash28Bytes, scriptHash.hash28Bytes)
      case _ => fail("expected ScriptCredential")
    }
  }

  test("extract PubKeyCredential from type-6 enterprise address") {
    // Type 6 = enterprise address (key hash), network 1
    val keyHash = Hash28.unsafeFrom(ByteVector.fill(28)(0x42))
    val address = ByteVector(0x61.toByte) ++ keyHash.hash28Bytes
    val result  = Address.extractPaymentCredential(address)
    assert(result.isDefined)
    result.foreach {
      case PaymentCredential.PubKeyCredential(h) =>
        assertEquals(h.hash28Bytes, keyHash.hash28Bytes)
      case _ => fail("expected PubKeyCredential")
    }
  }

  test("Byron address returns None") {
    // Type 8 = Byron address
    val address = ByteVector(0x82.toByte) ++ ByteVector.fill(64)(0x00)
    val result  = Address.extractPaymentCredential(address)
    assert(result.isEmpty)
  }

  test("isByronAddress detects Byron addresses") {
    val byronAddr   = ByteVector(0x82.toByte) ++ ByteVector.fill(32)(0x00)
    val shelleyAddr = ByteVector(0x00.toByte) ++ ByteVector.fill(56)(0x00)
    assert(Address.isByronAddress(byronAddr))
    assert(!Address.isByronAddress(shelleyAddr))
  }

  test("too-short address returns None") {
    val address = ByteVector.fill(10)(0x00)
    val result  = Address.extractPaymentCredential(address)
    assert(result.isEmpty)
  }
