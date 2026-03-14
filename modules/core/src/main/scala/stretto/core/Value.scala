package stretto.core

import scodec.bits.ByteVector
import stretto.core.Types.*

object Value:

  opaque type AssetName = ByteVector
  object AssetName:
    def apply(bytes: ByteVector): Either[String, AssetName] =
      if bytes.size <= 32 then Right(bytes)
      else Left(s"AssetName max 32 bytes, got ${bytes.size}")
    def unsafeFrom(bytes: ByteVector): AssetName =
      require(bytes.size <= 32, s"AssetName max 32 bytes, got ${bytes.size}")
      bytes
    val empty: AssetName = ByteVector.empty
    given Ordering[AssetName] =
      Ordering.by((a: AssetName) => a.toHex)
  extension (a: AssetName)
    def assetNameBytes: ByteVector = a

final case class Value(coin: Lovelace, multiAsset: Map[PolicyId, Map[Value.AssetName, Long]]):
  def +(other: Value): Value =
    val mergedAssets = other.multiAsset.foldLeft(multiAsset):
      case (acc, (policy, assets)) =>
        val existing = acc.getOrElse(policy, Map.empty)
        val merged = assets.foldLeft(existing):
          case (inner, (name, qty)) =>
            val current = inner.getOrElse(name, 0L)
            inner.updated(name, current + qty)
        acc.updated(policy, merged)
    Value(coin + other.coin, mergedAssets)

  def -(other: Value): Value =
    val mergedAssets = other.multiAsset.foldLeft(multiAsset):
      case (acc, (policy, assets)) =>
        val existing = acc.getOrElse(policy, Map.empty)
        val merged = assets.foldLeft(existing):
          case (inner, (name, qty)) =>
            val current = inner.getOrElse(name, 0L)
            inner.updated(name, current - qty)
        acc.updated(policy, merged)
    Value(coin - other.coin, mergedAssets)

object ValueInstances:
  val zero: Value = Value(Lovelace(0L), Map.empty)
  def lovelaceOnly(coin: Lovelace): Value = Value(coin, Map.empty)
