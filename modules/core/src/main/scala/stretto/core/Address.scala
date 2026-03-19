package stretto.core

import scodec.bits.ByteVector
import stretto.core.Types.*

enum PaymentCredential:
  case PubKeyCredential(hash: Hash28)
  case ScriptCredential(hash: Hash28)

enum StakeCredential:
  case PubKeyCredential(hash: Hash28)
  case ScriptCredential(hash: Hash28)

final case class Pointer(slotNo: SlotNo, txIndex: TxIndex, certIndex: Int)

enum StakeReference:
  case StakeByValue(credential: StakeCredential)
  case StakeByPointer(pointer: Pointer)
  case NoStake

enum Address:
  case ShelleyAddress(network: NetworkId, payment: PaymentCredential, stake: StakeReference)
  case ByronAddress(root: ByteVector)

object Address:

  /**
   * Extract the payment credential from raw address bytes.
   *
   * Shelley address format (first byte):
   *   - bits 7-4: address type (0-7 for Shelley, 8 for Byron)
   *   - bits 3-0: network id
   *
   * Address types:
   *   0: base (keyhash, keyhash)      → payment = bytes 1..28
   *   1: base (scripthash, keyhash)   → payment = bytes 1..28
   *   2: base (keyhash, scripthash)   → payment = bytes 1..28
   *   3: base (scripthash, scripthash)→ payment = bytes 1..28
   *   4: pointer (keyhash)            → payment = bytes 1..28
   *   5: pointer (scripthash)         → payment = bytes 1..28
   *   6: enterprise (keyhash)         → payment = bytes 1..28
   *   7: enterprise (scripthash)      → payment = bytes 1..28
   *   8: Byron bootstrap              → no payment credential
   *
   * Reference: Shelley address spec §4
   */
  def extractPaymentCredential(rawAddress: ByteVector): Option[PaymentCredential] =
    if rawAddress.size < 29 then None
    else
      val headerByte = rawAddress(0) & 0xff
      val addrType   = (headerByte >> 4) & 0x0f
      if addrType >= 0 && addrType <= 7 then
        val paymentHash = rawAddress.slice(1, 29)
        Hash28(paymentHash).toOption.map { h28 =>
          if addrType % 2 == 0 then PaymentCredential.PubKeyCredential(h28)
          else PaymentCredential.ScriptCredential(h28)
        }
      else None // Byron or unknown

  /**
   * Check if address is a Byron bootstrap address.
   */
  def isByronAddress(rawAddress: ByteVector): Boolean =
    if rawAddress.isEmpty then false
    else
      val headerByte = rawAddress(0) & 0xff
      val addrType   = (headerByte >> 4) & 0x0f
      addrType == 8
