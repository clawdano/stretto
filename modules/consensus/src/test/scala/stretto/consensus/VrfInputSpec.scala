package stretto.consensus

import munit.FunSuite
import scodec.bits.ByteVector
import stretto.core.Types.*

class VrfInputSpec extends FunSuite:

  test("praos input is 40 bytes (8 slot + 32 nonce)") {
    val slot  = SlotNo(12345L)
    val nonce = ByteVector.fill(32)(0xab)
    val input = VrfInput.praos(slot, nonce)
    assertEquals(input.size, 40L)
  }

  test("praos input starts with big-endian slot number") {
    val slot  = SlotNo(256L)
    val nonce = ByteVector.fill(32)(0)
    val input = VrfInput.praos(slot, nonce)
    // 256 in big-endian 8 bytes = 0x0000000000000100
    assertEquals(input.take(8), ByteVector.fromValidHex("0000000000000100"))
  }

  test("praos input ends with epoch nonce") {
    val slot  = SlotNo(0L)
    val nonce = ByteVector.fill(32)(0xff)
    val input = VrfInput.praos(slot, nonce)
    assertEquals(input.drop(8), nonce)
  }

  test("tpraos nonce and leader inputs have same structure") {
    val slot        = SlotNo(42L)
    val nonce       = ByteVector.fill(32)(0x01)
    val nonceInput  = VrfInput.tpraosNonce(slot, nonce)
    val leaderInput = VrfInput.tpraosLeader(slot, nonce)
    assertEquals(nonceInput.size, 40L)
    assertEquals(leaderInput.size, 40L)
  }

  test("certNatFromOutput produces positive BigInt") {
    val output  = ByteVector.fill(64)(0xff)
    val certNat = VrfInput.certNatFromOutput(output)
    assert(certNat > 0)
    // All 0xff bytes = 2^512 - 1
    assertEquals(certNat, (BigInt(1) << 512) - 1)
  }

  test("certNatFromOutput of all zeros is zero") {
    val output  = ByteVector.fill(64)(0)
    val certNat = VrfInput.certNatFromOutput(output)
    assertEquals(certNat, BigInt(0))
  }
