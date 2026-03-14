package stretto.core

import scodec.bits.ByteVector
import stretto.core.Types.*
import stretto.core.Value.AssetName

class ValueSpec extends munit.FunSuite:

  private val policy1 = PolicyId(Hash28.unsafeFrom(ByteVector.fill(28)(0x01)))
  private val policy2 = PolicyId(Hash28.unsafeFrom(ByteVector.fill(28)(0x02)))

  private val asset1 = AssetName.unsafeFrom(ByteVector.fromValidHex("cafe"))
  private val asset2 = AssetName.unsafeFrom(ByteVector.fromValidHex("beef"))

  // ---------------------------------------------------------------------------
  // Lovelace-only values
  // ---------------------------------------------------------------------------

  test("lovelaceOnly creates value with empty multi-asset") {
    val v = ValueInstances.lovelaceOnly(Lovelace(5_000_000L))
    assertEquals(v.coin.lovelaceValue, 5_000_000L)
    assert(v.multiAsset.isEmpty)
  }

  test("zero value has zero lovelace and no assets") {
    val v = ValueInstances.zero
    assertEquals(v.coin.lovelaceValue, 0L)
    assert(v.multiAsset.isEmpty)
  }

  // ---------------------------------------------------------------------------
  // Addition
  // ---------------------------------------------------------------------------

  test("adding two lovelace-only values sums coin") {
    val a = ValueInstances.lovelaceOnly(Lovelace(10L))
    val b = ValueInstances.lovelaceOnly(Lovelace(20L))
    assertEquals((a + b).coin.lovelaceValue, 30L)
  }

  test("adding multi-asset values merges quantities") {
    val a      = Value(Lovelace(0L), Map(policy1 -> Map(asset1 -> 100L)))
    val b      = Value(Lovelace(0L), Map(policy1 -> Map(asset1 -> 50L)))
    val result = a + b
    assertEquals(result.multiAsset(policy1)(asset1), 150L)
  }

  test("adding values with different policies preserves both") {
    val a      = Value(Lovelace(1L), Map(policy1 -> Map(asset1 -> 10L)))
    val b      = Value(Lovelace(2L), Map(policy2 -> Map(asset2 -> 20L)))
    val result = a + b
    assertEquals(result.coin.lovelaceValue, 3L)
    assertEquals(result.multiAsset(policy1)(asset1), 10L)
    assertEquals(result.multiAsset(policy2)(asset2), 20L)
  }

  test("adding zero value is identity") {
    val v      = Value(Lovelace(42L), Map(policy1 -> Map(asset1 -> 7L)))
    val result = v + ValueInstances.zero
    assertEquals(result.coin.lovelaceValue, 42L)
    assertEquals(result.multiAsset(policy1)(asset1), 7L)
  }

  // ---------------------------------------------------------------------------
  // Subtraction
  // ---------------------------------------------------------------------------

  test("subtracting lovelace-only values") {
    val a = ValueInstances.lovelaceOnly(Lovelace(50L))
    val b = ValueInstances.lovelaceOnly(Lovelace(20L))
    assertEquals((a - b).coin.lovelaceValue, 30L)
  }

  test("subtracting multi-asset values") {
    val a      = Value(Lovelace(100L), Map(policy1 -> Map(asset1 -> 80L)))
    val b      = Value(Lovelace(30L), Map(policy1 -> Map(asset1 -> 30L)))
    val result = a - b
    assertEquals(result.coin.lovelaceValue, 70L)
    assertEquals(result.multiAsset(policy1)(asset1), 50L)
  }

  test("subtracting zero value is identity") {
    val v      = Value(Lovelace(99L), Map(policy1 -> Map(asset1 -> 5L)))
    val result = v - ValueInstances.zero
    assertEquals(result.coin.lovelaceValue, 99L)
    assertEquals(result.multiAsset(policy1)(asset1), 5L)
  }

  // ---------------------------------------------------------------------------
  // AssetName validation
  // ---------------------------------------------------------------------------

  test("AssetName: empty is valid") {
    assert(AssetName(ByteVector.empty).isRight)
  }

  test("AssetName: up to 32 bytes is valid") {
    assert(AssetName(ByteVector.fill(32)(0x41)).isRight)
  }

  test("AssetName: more than 32 bytes is rejected") {
    assert(AssetName(ByteVector.fill(33)(0x41)).isLeft)
  }

  test("AssetName.empty has zero length") {
    assertEquals(AssetName.empty.assetNameBytes.size, 0L)
  }

  test("AssetName: unsafeFrom throws on oversized input") {
    intercept[IllegalArgumentException] {
      AssetName.unsafeFrom(ByteVector.fill(33)(0))
    }
  }
