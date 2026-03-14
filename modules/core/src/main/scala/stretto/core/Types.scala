package stretto.core

import scala.annotation.targetName
import scodec.bits.ByteVector

object Types:

  opaque type SlotNo = Long
  object SlotNo:
    def apply(value: Long): SlotNo = value
    given Ordering[SlotNo] = Ordering.Long
  extension (s: SlotNo)
    def value: Long = s
    def +(other: SlotNo): SlotNo = s + other
    def -(other: SlotNo): SlotNo = s - other

  opaque type BlockNo = Long
  object BlockNo:
    def apply(value: Long): BlockNo = value
    given Ordering[BlockNo] = Ordering.Long
  extension (b: BlockNo)
    def blockNoValue: Long = b

  opaque type EpochNo = Long
  object EpochNo:
    def apply(value: Long): EpochNo = value
    given Ordering[EpochNo] = Ordering.Long
  extension (e: EpochNo)
    def epochNoValue: Long = e

  opaque type TxIndex = Int
  object TxIndex:
    def apply(value: Int): TxIndex = value
    given Ordering[TxIndex] = Ordering.Int
  extension (t: TxIndex)
    def txIndexValue: Int = t

  opaque type Lovelace = Long
  object Lovelace:
    def apply(value: Long): Lovelace = value
    val zero: Lovelace = 0L
    given Ordering[Lovelace] = Ordering.Long
  extension (l: Lovelace)
    def lovelaceValue: Long = l
    @targetName("addLovelace")
    def +(other: Lovelace): Lovelace = l + other
    @targetName("subLovelace")
    def -(other: Lovelace): Lovelace = l - other
    def *(factor: Long): Lovelace = l * factor

  opaque type Hash32 = ByteVector
  object Hash32:
    def apply(bytes: ByteVector): Either[String, Hash32] =
      if bytes.size == 32 then Right(bytes)
      else Left(s"Hash32 requires 32 bytes, got ${bytes.size}")
    def unsafeFrom(bytes: ByteVector): Hash32 =
      require(bytes.size == 32, s"Hash32 requires 32 bytes, got ${bytes.size}")
      bytes
    given ordering: Ordering[Hash32] =
      Ordering.by((h: Hash32) => h.toHex)
  extension (h: Hash32)
    def hash32Bytes: ByteVector = h
    def hash32Hex: String = h.toHex

  opaque type Hash28 = ByteVector
  object Hash28:
    def apply(bytes: ByteVector): Either[String, Hash28] =
      if bytes.size == 28 then Right(bytes)
      else Left(s"Hash28 requires 28 bytes, got ${bytes.size}")
    def unsafeFrom(bytes: ByteVector): Hash28 =
      require(bytes.size == 28, s"Hash28 requires 28 bytes, got ${bytes.size}")
      bytes
    given ordering: Ordering[Hash28] =
      Ordering.by((h: Hash28) => h.toHex)
  extension (h: Hash28)
    def hash28Bytes: ByteVector = h
    def hash28Hex: String = h.toHex

  opaque type BlockHeaderHash = Hash32
  object BlockHeaderHash:
    def apply(hash: Hash32): BlockHeaderHash = hash
    given Ordering[BlockHeaderHash] = Hash32.ordering
  extension (b: BlockHeaderHash)
    def toHash32: Hash32 = b

  opaque type TxHash = Hash32
  object TxHash:
    def apply(hash: Hash32): TxHash = hash
    given Ordering[TxHash] = Hash32.ordering
  extension (t: TxHash)
    def txHashToHash32: Hash32 = t

  opaque type ScriptHash = Hash28
  object ScriptHash:
    def apply(hash: Hash28): ScriptHash = hash
    given Ordering[ScriptHash] = Hash28.ordering
  extension (s: ScriptHash)
    def scriptHashToHash28: Hash28 = s

  opaque type PolicyId = Hash28
  object PolicyId:
    def apply(hash: Hash28): PolicyId = hash
    given Ordering[PolicyId] = Hash28.ordering
  extension (p: PolicyId)
    def policyIdToHash28: Hash28 = p

  opaque type PoolId = Hash28
  object PoolId:
    def apply(hash: Hash28): PoolId = hash
    given Ordering[PoolId] = Hash28.ordering
  extension (p: PoolId)
    def poolIdToHash28: Hash28 = p

  opaque type VrfKeyHash = Hash32
  object VrfKeyHash:
    def apply(hash: Hash32): VrfKeyHash = hash
    given Ordering[VrfKeyHash] = Hash32.ordering
  extension (v: VrfKeyHash)
    def vrfKeyHashToHash32: Hash32 = v

  opaque type KesKeyHash = Hash32
  object KesKeyHash:
    def apply(hash: Hash32): KesKeyHash = hash
    given Ordering[KesKeyHash] = Hash32.ordering
  extension (k: KesKeyHash)
    def kesKeyHashToHash32: Hash32 = k
