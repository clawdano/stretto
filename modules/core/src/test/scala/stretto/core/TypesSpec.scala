package stretto.core

import scodec.bits.ByteVector
import stretto.core.Types.*

class TypesSpec extends munit.FunSuite:

  // ---------------------------------------------------------------------------
  // SlotNo
  // ---------------------------------------------------------------------------

  test("SlotNo: creation preserves value") {
    assertEquals(SlotNo(42L).value, 42L)
  }

  test("SlotNo: addition") {
    assertEquals((SlotNo(10L) + SlotNo(5L)).value, 15L)
  }

  test("SlotNo: subtraction") {
    assertEquals((SlotNo(10L) - SlotNo(3L)).value, 7L)
  }

  test("SlotNo: ordering") {
    val ord = summon[Ordering[SlotNo]]
    assert(ord.lt(SlotNo(1L), SlotNo(2L)))
    assert(ord.gt(SlotNo(5L), SlotNo(3L)))
    assert(ord.equiv(SlotNo(7L), SlotNo(7L)))
  }

  // ---------------------------------------------------------------------------
  // BlockNo
  // ---------------------------------------------------------------------------

  test("BlockNo: creation preserves value") {
    assertEquals(BlockNo(100L).blockNoValue, 100L)
  }

  test("BlockNo: ordering") {
    val ord = summon[Ordering[BlockNo]]
    assert(ord.lt(BlockNo(0L), BlockNo(1L)))
    assert(ord.equiv(BlockNo(5L), BlockNo(5L)))
  }

  // ---------------------------------------------------------------------------
  // Lovelace
  // ---------------------------------------------------------------------------

  test("Lovelace: creation preserves value") {
    assertEquals(Lovelace(1_000_000L).lovelaceValue, 1_000_000L)
  }

  test("Lovelace: zero") {
    assertEquals(Lovelace.zero.lovelaceValue, 0L)
  }

  test("Lovelace: addition") {
    assertEquals((Lovelace(10L) + Lovelace(20L)).lovelaceValue, 30L)
  }

  test("Lovelace: subtraction") {
    assertEquals((Lovelace(50L) - Lovelace(20L)).lovelaceValue, 30L)
  }

  test("Lovelace: multiplication") {
    assertEquals((Lovelace(7L) * 3L).lovelaceValue, 21L)
  }

  test("Lovelace: ordering") {
    val ord = summon[Ordering[Lovelace]]
    assert(ord.lt(Lovelace(1L), Lovelace(2L)))
    assert(ord.gt(Lovelace(100L), Lovelace(99L)))
    assert(ord.equiv(Lovelace.zero, Lovelace(0L)))
  }

  // ---------------------------------------------------------------------------
  // Hash32
  // ---------------------------------------------------------------------------

  private val valid32 = ByteVector.fill(32)(0xab)
  private val valid28 = ByteVector.fill(28)(0xcd)

  test("Hash32: valid 32-byte creation succeeds") {
    assert(Hash32(valid32).isRight)
  }

  test("Hash32: rejects fewer than 32 bytes") {
    assert(Hash32(ByteVector.fill(31)(0)).isLeft)
  }

  test("Hash32: rejects more than 32 bytes") {
    assert(Hash32(ByteVector.fill(33)(0)).isLeft)
  }

  test("Hash32: rejects empty bytes") {
    assert(Hash32(ByteVector.empty).isLeft)
  }

  test("Hash32: unsafeFrom throws on wrong size") {
    intercept[IllegalArgumentException] {
      Hash32.unsafeFrom(ByteVector.fill(10)(0))
    }
  }

  test("Hash32: hash32Bytes round-trips") {
    val h = Hash32.unsafeFrom(valid32)
    assertEquals(h.hash32Bytes, valid32)
  }

  test("Hash32: hash32Hex returns hex string") {
    val h = Hash32.unsafeFrom(valid32)
    assertEquals(h.hash32Hex, valid32.toHex)
  }

  // ---------------------------------------------------------------------------
  // Hash28
  // ---------------------------------------------------------------------------

  test("Hash28: valid 28-byte creation succeeds") {
    assert(Hash28(valid28).isRight)
  }

  test("Hash28: rejects fewer than 28 bytes") {
    assert(Hash28(ByteVector.fill(27)(0)).isLeft)
  }

  test("Hash28: rejects more than 28 bytes") {
    assert(Hash28(ByteVector.fill(29)(0)).isLeft)
  }

  test("Hash28: rejects empty bytes") {
    assert(Hash28(ByteVector.empty).isLeft)
  }

  test("Hash28: unsafeFrom throws on wrong size") {
    intercept[IllegalArgumentException] {
      Hash28.unsafeFrom(ByteVector.fill(5)(0))
    }
  }

  test("Hash28: hash28Bytes round-trips") {
    val h = Hash28.unsafeFrom(valid28)
    assertEquals(h.hash28Bytes, valid28)
  }

  // ---------------------------------------------------------------------------
  // Derived Hash32 types
  // ---------------------------------------------------------------------------

  test("BlockHeaderHash: wrap and unwrap") {
    val h   = Hash32.unsafeFrom(valid32)
    val bhh = BlockHeaderHash(h)
    assertEquals(bhh.toHash32, h)
  }

  test("TxHash: wrap and unwrap") {
    val h  = Hash32.unsafeFrom(valid32)
    val tx = TxHash(h)
    assertEquals(tx.txHashToHash32, h)
  }

  test("VrfKeyHash: wrap and unwrap") {
    val h   = Hash32.unsafeFrom(valid32)
    val vrf = VrfKeyHash(h)
    assertEquals(vrf.vrfKeyHashToHash32, h)
  }

  test("KesKeyHash: wrap and unwrap") {
    val h   = Hash32.unsafeFrom(valid32)
    val kes = KesKeyHash(h)
    assertEquals(kes.kesKeyHashToHash32, h)
  }

  // ---------------------------------------------------------------------------
  // Derived Hash28 types
  // ---------------------------------------------------------------------------

  test("ScriptHash: wrap and unwrap") {
    val h  = Hash28.unsafeFrom(valid28)
    val sh = ScriptHash(h)
    assertEquals(sh.scriptHashToHash28, h)
  }

  test("PolicyId: wrap and unwrap") {
    val h   = Hash28.unsafeFrom(valid28)
    val pid = PolicyId(h)
    assertEquals(pid.policyIdToHash28, h)
  }

  test("PoolId: wrap and unwrap") {
    val h    = Hash28.unsafeFrom(valid28)
    val pool = PoolId(h)
    assertEquals(pool.poolIdToHash28, h)
  }
