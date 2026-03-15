package stretto.storage

import cats.effect.IO
import munit.CatsEffectSuite
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*

import java.nio.file.{Files, Path}

class RocksDbStoreSpec extends CatsEffectSuite:

  private def tmpDir: Path =
    Files.createTempDirectory("stretto-rocks-test")

  private def withStore(f: RocksDbStore => IO[Unit]): IO[Unit] =
    val dir = tmpDir
    RocksDbStore
      .open(dir)
      .use(f)
      .guarantee(IO {
        // cleanup temp dir
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      })

  private val hash1                    = Hash32.unsafeFrom(ByteVector.fill(32)(0x01))
  private val hash2                    = Hash32.unsafeFrom(ByteVector.fill(32)(0x02))
  private val point1: Point.BlockPoint = Point.BlockPoint(SlotNo(100L), BlockHeaderHash(hash1))
  private val point2: Point.BlockPoint = Point.BlockPoint(SlotNo(200L), BlockHeaderHash(hash2))
  private val header1                  = ByteVector.fromValidHex("deadbeef01")
  private val header2                  = ByteVector.fromValidHex("deadbeef02")

  test("put and get a header") {
    withStore { store =>
      for
        _      <- store.putHeader(point1, header1)
        result <- store.getHeader(point1)
      yield assertEquals(result, Some(header1))
    }
  }

  test("get missing header returns None") {
    withStore { store =>
      store.getHeader(point1).map(r => assertEquals(r, None))
    }
  }

  test("put and get tip") {
    withStore { store =>
      val tip = Tip(point1, BlockNo(42L))
      for
        _      <- store.putTip(tip)
        result <- store.getTip
      yield assertEquals(result, Some(tip))
    }
  }

  test("tip is None when not set") {
    withStore { store =>
      store.getTip.map(r => assertEquals(r, None))
    }
  }

  test("putHeaderWithMeta stores header, height index, and tip atomically") {
    withStore { store =>
      val tip = Tip(point2, BlockNo(2L))
      for
        _       <- store.putHeaderWithMeta(point1, header1, BlockNo(1L), tip)
        _       <- store.putHeaderWithMeta(point2, header2, BlockNo(2L), tip)
        h1      <- store.getHeader(point1)
        h2      <- store.getHeader(point2)
        tipRead <- store.getTip
        recent  <- store.recentPoints(10)
      yield
        assertEquals(h1, Some(header1))
        assertEquals(h2, Some(header2))
        assertEquals(tipRead, Some(tip))
        // Recent points should be newest first (blockNo 2, then 1)
        assertEquals(recent.size, 2)
        assertEquals(recent.head, point2)
        assertEquals(recent.last, point1)
    }
  }

  test("recentPoints returns empty when no headers stored") {
    withStore { store =>
      store.recentPoints(10).map(r => assertEquals(r, Nil))
    }
  }

  test("recentPoints respects count limit") {
    withStore { store =>
      val tip = Tip(point2, BlockNo(2L))
      for
        _      <- store.putHeaderWithMeta(point1, header1, BlockNo(1L), tip)
        _      <- store.putHeaderWithMeta(point2, header2, BlockNo(2L), tip)
        recent <- store.recentPoints(1)
      yield
        assertEquals(recent.size, 1)
        assertEquals(recent.head, point2)
    }
  }

  test("origin tip round-trips correctly") {
    withStore { store =>
      for
        _      <- store.putTip(Tip.origin)
        result <- store.getTip
      yield assertEquals(result, Some(Tip.origin))
    }
  }

  test("putBlock and getBlock round-trip") {
    withStore { store =>
      val blockData = ByteVector.fromValidHex("cafebabe0102030405")
      for
        _      <- store.putBlock(point1, blockData)
        result <- store.getBlock(point1)
      yield assertEquals(result, Some(blockData))
    }
  }

  test("getBlock returns None for missing block") {
    withStore { store =>
      store.getBlock(point1).map(r => assertEquals(r, None))
    }
  }

  test("putBatchWithBlocks stores headers, blocks, height, and tip atomically") {
    withStore { store =>
      val block1 = ByteVector.fromValidHex("b10c0001")
      val block2 = ByteVector.fromValidHex("b10c0002")
      val tip    = Tip(point2, BlockNo(2L))
      val entries = List(
        (point1, header1, BlockNo(1L), block1),
        (point2, header2, BlockNo(2L), block2)
      )
      for
        _       <- store.putBatchWithBlocks(entries, tip)
        h1      <- store.getHeader(point1)
        h2      <- store.getHeader(point2)
        bl1     <- store.getBlock(point1)
        bl2     <- store.getBlock(point2)
        tipRead <- store.getTip
        recent  <- store.recentPoints(10)
      yield
        assertEquals(h1, Some(header1))
        assertEquals(h2, Some(header2))
        assertEquals(bl1, Some(block1))
        assertEquals(bl2, Some(block2))
        assertEquals(tipRead, Some(tip))
        assertEquals(recent.size, 2)
        assertEquals(recent.head, point2)
        assertEquals(recent.last, point1)
    }
  }

  test("putBatch (header-only) still works after blocks CF added") {
    withStore { store =>
      val tip = Tip(point1, BlockNo(1L))
      for
        _       <- store.putBatch(List((point1, header1, BlockNo(1L))), tip)
        h1      <- store.getHeader(point1)
        bl1     <- store.getBlock(point1) // should be None — no block stored
        tipRead <- store.getTip
      yield
        assertEquals(h1, Some(header1))
        assertEquals(bl1, None)
        assertEquals(tipRead, Some(tip))
    }
  }
