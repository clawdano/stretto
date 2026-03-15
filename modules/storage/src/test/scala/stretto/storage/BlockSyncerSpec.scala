package stretto.storage

import cats.effect.IO
import munit.CatsEffectSuite
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*

import java.nio.file.{Files, Path}

class BlockSyncerSpec extends CatsEffectSuite:

  private def tmpDir: Path =
    Files.createTempDirectory("stretto-block-syncer-test")

  private def withSyncer(f: BlockSyncer => IO[Unit]): IO[Unit] =
    val dir = tmpDir
    RocksDbStore
      .open(dir)
      .use(store => f(new BlockSyncer(store)))
      .guarantee(IO {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      })

  private val hash1                    = Hash32.unsafeFrom(ByteVector.fill(32)(0x01))
  private val hash2                    = Hash32.unsafeFrom(ByteVector.fill(32)(0x02))
  private val point1: Point.BlockPoint = Point.BlockPoint(SlotNo(100L), BlockHeaderHash(hash1))
  private val point2: Point.BlockPoint = Point.BlockPoint(SlotNo(200L), BlockHeaderHash(hash2))

  test("rollForwardBatchWithBlocks persists headers and blocks") {
    withSyncer { syncer =>
      val tip = Tip(point2, BlockNo(2L))
      val entries = List(
        (point1, ByteVector.fromValidHex("aa"), BlockNo(1L), ByteVector.fromValidHex("bb")),
        (point2, ByteVector.fromValidHex("cc"), BlockNo(2L), ByteVector.fromValidHex("dd"))
      )
      for
        _ <- syncer.rollForwardBatchWithBlocks(entries, tip)
        t <- syncer.currentTip
      yield assertEquals(t, Some(tip))
    }
  }

  test("empty batch is a no-op") {
    withSyncer { syncer =>
      for
        _ <- syncer.rollForwardBatchWithBlocks(Nil, Tip.origin)
        t <- syncer.currentTip
      yield assertEquals(t, None) // tip should not be written
    }
  }

  test("rollBackward updates tip") {
    withSyncer { syncer =>
      val tip1 = Tip(point2, BlockNo(2L))
      val tip2 = Tip(point1, BlockNo(1L))
      val entries = List(
        (point1, ByteVector.fromValidHex("aa"), BlockNo(1L), ByteVector.fromValidHex("bb")),
        (point2, ByteVector.fromValidHex("cc"), BlockNo(2L), ByteVector.fromValidHex("dd"))
      )
      for
        _ <- syncer.rollForwardBatchWithBlocks(entries, tip1)
        _ <- syncer.rollBackward(tip2)
        t <- syncer.currentTip
      yield assertEquals(t, Some(tip2))
    }
  }

  test("knownPoints returns Origin when empty") {
    withSyncer { syncer =>
      syncer.knownPoints.map(pts => assertEquals(pts, List(Point.Origin)))
    }
  }

  test("knownPoints includes stored points and Origin") {
    withSyncer { syncer =>
      val tip = Tip(point2, BlockNo(2L))
      val entries = List(
        (point1, ByteVector.fromValidHex("aa"), BlockNo(1L), ByteVector.fromValidHex("bb")),
        (point2, ByteVector.fromValidHex("cc"), BlockNo(2L), ByteVector.fromValidHex("dd"))
      )
      for
        _   <- syncer.rollForwardBatchWithBlocks(entries, tip)
        pts <- syncer.knownPoints
      yield
        assert(pts.nonEmpty)
        assertEquals(pts.last, Point.Origin)
        assert(pts.contains(point2))
    }
  }
