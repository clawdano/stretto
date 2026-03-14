package stretto.network

/** Ouroboros mini-protocol identifiers as defined in the network spec. */
enum MiniProtocolId(val id: Int):
  case Handshake         extends MiniProtocolId(0)
  case ChainSyncN2N      extends MiniProtocolId(2)
  case BlockFetch        extends MiniProtocolId(3)
  case ChainSyncN2C      extends MiniProtocolId(5)
  case TxSubmission2     extends MiniProtocolId(6)
  case LocalStateQuery   extends MiniProtocolId(7)
  case LocalTxSubmission extends MiniProtocolId(8)
  case KeepAlive         extends MiniProtocolId(8)
  case LocalTxMonitor    extends MiniProtocolId(9)
  case PeerSharing       extends MiniProtocolId(10)

object MiniProtocolId:

  def fromId(id: Int): Option[MiniProtocolId] =
    MiniProtocolId.values.find(_.id == id)

/** Which peer has agency (the right to send the next message). */
enum Agency:
  case Client
  case Server
  case Nobody

/** Direction of a message within the mux layer. */
enum Direction:
  case InitiatorToResponder
  case ResponderToInitiator
