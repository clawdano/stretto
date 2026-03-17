package stretto.consensus

import munit.FunSuite

class LeaderCheckSpec extends FunSuite:

  // Mainnet: f = 1/20
  private val mainnetF = (1L, 20L)

  test("zero stake is never elected") {
    val certNat = BigInt(0)
    assert(!LeaderCheck.isLeader(certNat, BigInt(0), BigInt(1000000), mainnetF))
  }

  test("negative stake is never elected") {
    val certNat = BigInt(0)
    assert(!LeaderCheck.isLeader(certNat, BigInt(-1), BigInt(1000000), mainnetF))
  }

  test("100% stake with certNat=0 is always elected") {
    assert(LeaderCheck.isLeader(BigInt(0), BigInt(1000000), BigInt(1000000), mainnetF))
  }

  test("100% stake with max certNat is still elected (threshold = 2^512)") {
    // When sigma=1, threshold = 2^512 * (1 - (1-f)^1) = 2^512 * f
    // Actually for 100% stake, isLeader returns certNat < 2^512, which is always true
    val maxCertNat = (BigInt(1) << 512) - 1
    assert(LeaderCheck.isLeader(maxCertNat, BigInt(1000000), BigInt(1000000), mainnetF))
  }

  test("small stake with certNat=0 is elected") {
    // certNat=0 < any positive threshold, so should be elected
    assert(LeaderCheck.isLeader(BigInt(0), BigInt(1), BigInt(1000000), mainnetF))
  }

  test("small stake with max certNat is not elected") {
    val maxCertNat = (BigInt(1) << 512) - 1
    // With 0.0001% stake and f=1/20, threshold is very small
    assert(!LeaderCheck.isLeader(maxCertNat, BigInt(1), BigInt(1000000), mainnetF))
  }

  test("threshold increases with stake") {
    val t1 = LeaderCheck.computeThreshold(BigInt(100), BigInt(1000), BigInt(19), BigInt(20))
    val t2 = LeaderCheck.computeThreshold(BigInt(500), BigInt(1000), BigInt(19), BigInt(20))
    assert(t2 > t1, "higher stake should produce higher threshold")
  }

  test("threshold is positive for positive stake") {
    val t = LeaderCheck.computeThreshold(BigInt(1), BigInt(1000000), BigInt(19), BigInt(20))
    assert(t > 0, "threshold should be positive for positive stake")
  }

  test("threshold with 50% stake is approximately correct") {
    // sigma = 0.5, f = 1/20 = 0.05
    // 1 - (1-0.05)^0.5 = 1 - 0.95^0.5 ≈ 1 - 0.97468 ≈ 0.02532
    // threshold ≈ 2^512 * 0.02532
    val t        = LeaderCheck.computeThreshold(BigInt(500), BigInt(1000), BigInt(19), BigInt(20))
    val twoTo512 = BigInt(1) << 512
    val ratio    = t.toDouble / twoTo512.toDouble
    // Should be approximately 0.02532
    assert(ratio > 0.025 && ratio < 0.026, s"ratio $ratio should be ~0.02532")
  }

  test("threshold with 100% stake equals f * 2^512") {
    // sigma = 1.0, f = 0.05
    // 1 - (1-0.05)^1 = 0.05
    val t        = LeaderCheck.computeThreshold(BigInt(1000), BigInt(1000), BigInt(19), BigInt(20))
    val twoTo512 = BigInt(1) << 512
    val expected = twoTo512 / 20 // f = 1/20
    // Should be close to 2^512 / 20
    val diff = (t - expected).abs
    // Allow 0.001% error
    assert(diff * 100000 < expected, s"threshold $t should be close to $expected")
  }

  test("zero denominator returns false") {
    assert(!LeaderCheck.isLeader(BigInt(0), BigInt(100), BigInt(0), mainnetF))
  }
