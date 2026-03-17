package stretto.consensus

/**
 * Leader election check for Ouroboros Praos.
 *
 * A pool is elected leader for a slot if:
 *   certNat / 2^512 < 1 - (1-f)^sigma
 *
 * where:
 *   - certNat = VRF output interpreted as big-endian unsigned integer
 *   - f = active slot coefficient (1/20 on mainnet)
 *   - sigma = pool's relative active stake (delegated_stake / total_active_stake)
 *
 * To avoid floating point, we rearrange to:
 *   certNat < 2^512 * (1 - (1-f)^sigma)
 *
 * We compute (1-f)^sigma using a Taylor series approximation of the natural logarithm:
 *   (1-f)^sigma = exp(sigma * ln(1-f))
 *
 * For high precision with rational arithmetic, we use:
 *   q = 1-f  (e.g., 19/20 on mainnet)
 *   threshold = 2^512 * (1 - q^sigma)
 *
 * Since sigma is rational (a/b where a = pool stake, b = total stake),
 * we approximate using a Taylor series for ln(q) and exp().
 */
object LeaderCheck:

  /** 2^512 as BigInt — the denominator for normalizing VRF output. */
  private val TwoTo512: BigInt = BigInt(1) << 512

  /** Number of Taylor series terms for ln(1-f) approximation. */
  private val TaylorTerms: Int = 20

  /**
   * Check if a pool is elected leader for this slot.
   *
   * @param certNat    VRF output as big-endian unsigned integer (0 to 2^512 - 1)
   * @param sigmaNum   Pool's delegated stake (numerator)
   * @param sigmaDen   Total active stake (denominator)
   * @param activeSlotCoeffF Rational active slot coefficient as (numerator, denominator), e.g. (1, 20) for mainnet
   * @return true if the pool is elected leader
   */
  def isLeader(
      certNat: BigInt,
      sigmaNum: BigInt,
      sigmaDen: BigInt,
      activeSlotCoeffF: (Long, Long) = (1L, 20L)
  ): Boolean =
    if sigmaDen <= 0 || sigmaNum < 0 then return false
    if sigmaNum == 0 then return false
    if sigmaNum >= sigmaDen then
      // 100% stake: always elected (threshold = 2^512 * f / 1 ≈ 2^512)
      return certNat < TwoTo512

    val (fNum, fDen) = activeSlotCoeffF
    // q = 1 - f = (fDen - fNum) / fDen
    val qNum = fDen - fNum
    val qDen = fDen

    // We need: certNat < 2^512 * (1 - q^sigma)
    // Equivalently: certNat < 2^512 - 2^512 * q^sigma
    // So we need: 2^512 * q^sigma < 2^512 - certNat
    //
    // Compute q^sigma via Taylor series of exp(sigma * ln(q)):
    //   ln(q) = ln(1 - f) = -sum_{k=1}^{N} f^k / k   (Taylor series)
    //   q^sigma = exp(sigma * ln(q))
    //
    // Instead, we use a direct rational approximation:
    // threshold = floor(2^512 * (1 - q^sigma))
    // where q^sigma is approximated using the identity:
    //   1 - (1-f)^sigma = 1 - exp(sigma * ln(1-f))
    //
    // Taylor series for -ln(1-x) = x + x^2/2 + x^3/3 + ...
    // So ln(q) = -f - f^2/2 - f^3/3 - ... (negative)
    // sigma * ln(q) = -sigma * (f + f^2/2 + f^3/3 + ...)
    //
    // exp(-y) ≈ 1 - y + y^2/2! - y^3/3! + ...  where y = sigma * |ln(q)|
    //
    // 1 - exp(-y) ≈ y - y^2/2! + y^3/3! - ...
    //
    // For precision, compute everything scaled by 2^512.

    val threshold = computeThreshold(sigmaNum, sigmaDen, qNum, qDen)
    certNat < threshold

  /**
   * Compute threshold = floor(2^512 * (1 - q^sigma)).
   * Uses Taylor series approximation with high-precision rational arithmetic.
   *
   * We compute: 1 - (1-f)^sigma using the identity
   *   1 - (1-f)^sigma = 1 - exp(sigma * ln(1-f))
   *
   * Taylor expansion of 1 - exp(-y) where y = sigma * (-ln(1-f)):
   *   = y - y^2/2! + y^3/3! - y^4/4! + ...
   *
   * And y itself via Taylor expansion of -ln(1-f) = f + f^2/2 + f^3/3 + ...
   */
  private[consensus] def computeThreshold(
      sigmaNum: BigInt,
      sigmaDen: BigInt,
      qNum: BigInt,
      qDen: BigInt
  ): BigInt =
    // f = 1 - q/qDen... actually fNum = qDen - qNum, fDen = qDen
    val fNum = qDen - qNum
    val fDen = qDen

    // Compute y = sigma * (-ln(1-f)) using Taylor series:
    // -ln(1-f) = f + f^2/2 + f^3/3 + ... = sum_{k=1}^N f^k/k
    // y = sigma * sum_{k=1}^N f^k/k
    //
    // We represent y as yNum/yDen (rational).
    // y = (sigmaNum/sigmaDen) * sum_{k=1}^N (fNum^k / (k * fDen^k))
    //
    // For numerical stability, compute sum as a single fraction first.
    // sum = sum_{k=1}^N fNum^k / (k * fDen^k)
    // Common denominator for sum: lcm of all k * fDen^k, but simpler to accumulate.

    // Instead, compute the threshold directly using the Shelley spec's approach:
    // c = ln(1-f)
    // threshold = 1 - exp(sigma * c) = 1 - exp(-(sigma * |c|))
    //
    // We'll compute exp(sigma * c) as a Taylor series and subtract from 1.
    // sigma * c = -(sigmaNum/sigmaDen) * (f/1 + f^2/2 + f^3/3 + ...)

    // Approach: compute exp(sigma * ln(q/qDen)) directly.
    // ln(q) where q = qNum/qDen, and q < 1.
    // Let t = 1 - q/qDen = f/fDen... this gets circular.

    // Simplest correct approach: compute via repeated squaring-like Taylor.
    // threshold_scaled = 2^512 * (1 - (qNum/qDen)^(sigmaNum/sigmaDen))
    //
    // We use the identity: for rational exponents,
    // (1-f)^(a/b) can be computed via Taylor series of exp((a/b)*ln(1-f)).
    //
    // Let's just compute the Taylor series of 1 - exp(-y) where y = sigma * sum_{k=1}^N f^k/k,
    // all in scaled integer arithmetic (multiply by 2^512 at the end).

    // Precision: use 2^(512 + extraBits) for intermediate computation
    val extraBits = 128
    val scale     = BigInt(1) << (512 + extraBits)

    // Compute y * scale = scale * sigmaNum/sigmaDen * sum_{k=1}^N fNum^k / (k * fDen^k)
    var yScaled   = BigInt(0)
    var fPowerNum = fNum // fNum^k
    var fPowerDen = fDen // fDen^k
    for k <- 1 to TaylorTerms do
      // term = scale * sigmaNum * fPowerNum / (sigmaDen * k * fPowerDen)
      val termNum = scale * sigmaNum * fPowerNum
      val termDen = sigmaDen * k * fPowerDen
      yScaled += termNum / termDen
      fPowerNum *= fNum
      fPowerDen *= fDen

    // Now compute 1 - exp(-y) via Taylor series of exp(-y):
    // exp(-y) = 1 - y + y^2/2! - y^3/3! + ...
    // 1 - exp(-y) = y - y^2/2! + y^3/3! - ...
    //
    // In scaled form: result_scaled = y_scaled - y_scaled^2/(2! * scale) + y_scaled^3/(3! * scale^2) - ...
    var result    = yScaled // first term: y
    var yPower    = yScaled // y^k (scaled by scale^(k-1) at step k)
    var factorial = BigInt(1)
    for k <- 2 to TaylorTerms do
      yPower = yPower * yScaled / scale
      factorial *= k
      val term = yPower / factorial
      if k % 2 == 0 then result -= term
      else result += term

    // result is scaled by 2^(512+extraBits), shift right by extraBits to get 2^512 scale
    result >> extraBits
