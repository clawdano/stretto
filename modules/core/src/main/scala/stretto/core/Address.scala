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
