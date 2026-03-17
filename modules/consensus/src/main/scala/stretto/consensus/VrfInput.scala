package stretto.consensus

import scodec.bits.ByteVector
import stretto.core.Types.*

/**
 * VRF input construction for leader election.
 *
 * Pre-Babbage (TPraos): two separate VRF evaluations with different inputs.
 * Babbage+ (Praos): single VRF evaluation with combined input.
 */
object VrfInput:

  /**
   * Praos (Babbage+): single VRF input = slotNo (big-endian 8 bytes) ++ epochNonce (32 bytes).
   * The VRF is evaluated on this input and the output serves both for
   * leader election and nonce contribution.
   */
  def praos(slotNo: SlotNo, epochNonce: ByteVector): ByteVector =
    val slotBytes = ByteVector.fromLong(slotNo.value)
    slotBytes ++ epochNonce

  /**
   * TPraos (Shelley-Alonzo): nonce VRF input.
   * Input = slotNo (big-endian 8 bytes) ++ epochNonce (32 bytes).
   * Used for the nonce contribution VRF proof.
   */
  def tpraosNonce(slotNo: SlotNo, epochNonce: ByteVector): ByteVector =
    val slotBytes = ByteVector.fromLong(slotNo.value)
    slotBytes ++ epochNonce

  /**
   * TPraos (Shelley-Alonzo): leader VRF input.
   * Input = slotNo (big-endian 8 bytes) ++ epochNonce (32 bytes).
   * Used for the leader election VRF proof.
   * Note: In TPraos, both VRF inputs use the same structure but are
   * distinguished by a domain separation tag prepended to the hash input.
   * The Shelley spec uses: "nonce" tag (0) and "leader" tag (1).
   */
  def tpraosLeader(slotNo: SlotNo, epochNonce: ByteVector): ByteVector =
    val slotBytes = ByteVector.fromLong(slotNo.value)
    slotBytes ++ epochNonce

  /**
   * Compute certNat from VRF output: interpret 64-byte VRF hash as big-endian unsigned integer.
   * Used in the leader check: certNat / 2^512 < threshold.
   */
  def certNatFromOutput(vrfOutput: ByteVector): BigInt =
    BigInt(1, vrfOutput.toArray)
