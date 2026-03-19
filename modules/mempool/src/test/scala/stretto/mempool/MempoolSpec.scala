package stretto.mempool

import cats.effect.IO
import munit.CatsEffectSuite
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

class MempoolSpec extends CatsEffectSuite:

  private val txHash1 = TxHash(Hash32.unsafeFrom(ByteVector.fill(32)(0x01)))

  private val emptyLookup: TxInput => IO[Option[TxOutput]] = _ => IO.pure(None)

  test("empty mempool has size 0") {
    for
      mp   <- Mempool.create(utxoLookup = emptyLookup)
      size <- mp.size
    yield assertEquals(size, 0)
  }

  test("removeTxs on empty mempool is no-op") {
    for
      mp <- Mempool.create(utxoLookup = emptyLookup)
      _  <- mp.removeTxs(Set(txHash1))
      s  <- mp.size
    yield assertEquals(s, 0)
  }

  test("getTxs returns empty for empty mempool") {
    for
      mp  <- Mempool.create(utxoLookup = emptyLookup)
      txs <- mp.getTxs(1000000L)
    yield assert(txs.isEmpty)
  }

  test("snapshot returns empty state for new mempool") {
    for
      mp    <- Mempool.create(utxoLookup = emptyLookup)
      state <- mp.snapshot
    yield
      assert(state.txs.isEmpty)
      assert(state.consumedInputs.isEmpty)
      assert(state.producedOutputs.isEmpty)
      assertEquals(state.totalBytes, 0L)
  }
