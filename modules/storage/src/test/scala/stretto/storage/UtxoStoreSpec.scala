package stretto.storage

import cats.effect.IO
import munit.CatsEffectSuite
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*

import java.nio.file.Files

class UtxoStoreSpec extends CatsEffectSuite:

  private val txHash1 = TxHash(Hash32.unsafeFrom(ByteVector.fill(32)(0x01)))
  private val txHash2 = TxHash(Hash32.unsafeFrom(ByteVector.fill(32)(0x02)))
  private val addr1   = ByteVector.fromValidHex("00" * 29)

  private def withStore[A](f: RocksDbStore => IO[A]): IO[A] =
    val tmpDir = Files.createTempDirectory("stretto-utxo-test")
    RocksDbStore.open(tmpDir).use(f)

  test("putUtxo and getUtxo round-trip PureAda") {
    withStore { store =>
      val input  = TxInput(txHash1, 0L)
      val output = TxOutput(addr1, OutputValue.PureAda(Lovelace(5000000L)))
      for
        _      <- store.putUtxo(txHash1, 0L, output)
        result <- store.getUtxo(input)
      yield
        assert(result.isDefined)
        val got = result.get
        assertEquals(got.address, addr1)
        got.value match
          case OutputValue.PureAda(c) => assertEquals(c, Lovelace(5000000L))
          case _                      => fail("expected PureAda")
    }
  }

  test("putUtxo and getUtxo round-trip MultiAsset") {
    withStore { store =>
      val assets = ByteVector.fromValidHex("a1a2b3c4d5e6")
      val input  = TxInput(txHash1, 1L)
      val output = TxOutput(addr1, OutputValue.MultiAsset(Lovelace(2000000L), assets))
      for
        _      <- store.putUtxo(txHash1, 1L, output)
        result <- store.getUtxo(input)
      yield
        assert(result.isDefined)
        val got = result.get
        got.value match
          case OutputValue.MultiAsset(c, a) =>
            assertEquals(c, Lovelace(2000000L))
            assertEquals(a, assets)
          case _ => fail("expected MultiAsset")
    }
  }

  test("getUtxo returns None for missing entry") {
    withStore { store =>
      val input = TxInput(txHash1, 99L)
      store.getUtxo(input).map(result => assert(result.isEmpty))
    }
  }

  test("deleteUtxo removes entry") {
    withStore { store =>
      val input  = TxInput(txHash1, 0L)
      val output = TxOutput(addr1, OutputValue.PureAda(Lovelace(1000000L)))
      for
        _      <- store.putUtxo(txHash1, 0L, output)
        before <- store.getUtxo(input)
        _      <- store.deleteUtxo(input)
        after  <- store.getUtxo(input)
      yield
        assert(before.isDefined)
        assert(after.isEmpty)
    }
  }

  test("applyUtxoDelta atomically consumes and produces") {
    withStore { store =>
      val input1  = TxInput(txHash1, 0L)
      val output1 = TxOutput(addr1, OutputValue.PureAda(Lovelace(5000000L)))
      val input2  = TxInput(txHash2, 0L)
      val output2 = TxOutput(addr1, OutputValue.PureAda(Lovelace(3000000L)))
      for
        _         <- store.putUtxo(txHash1, 0L, output1)
        _         <- store.applyUtxoDelta(consumed = List(input1), produced = List((input2, output2)))
        oldResult <- store.getUtxo(input1)
        newResult <- store.getUtxo(input2)
      yield
        assert(oldResult.isEmpty, "consumed output should be gone")
        assert(newResult.isDefined, "produced output should exist")
    }
  }

  test("applyUtxoDeltaWithHeight updates ledger height") {
    withStore { store =>
      val input  = TxInput(txHash1, 0L)
      val output = TxOutput(addr1, OutputValue.PureAda(Lovelace(1000000L)))
      for
        _      <- store.applyUtxoDeltaWithHeight(consumed = List.empty, produced = List((input, output)), BlockNo(42L))
        height <- store.getLedgerHeight
        utxo   <- store.getUtxo(input)
      yield
        assertEquals(height, Some(BlockNo(42L)))
        assert(utxo.isDefined)
    }
  }

  test("getLedgerHeight returns None initially") {
    withStore { store =>
      store.getLedgerHeight.map(h => assert(h.isEmpty))
    }
  }
