package stretto.network

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector
import stretto.core.Point
import stretto.core.Types.*

import scala.concurrent.duration.*

class BlockFetchIntegrationSpec extends CatsEffectSuite:

  // Mainnet node at panic-station
  private val host         = "panic-station"
  private val port         = 30000
  private val mainnetMagic = 764824073L

  override def munitIOTimeout: Duration = 60.seconds

  // Requires local network access to panic-station — skipped in CI
  test("fetch block range from mainnet node using ChainSync headers".ignore) {
    MuxConnection
      .connect(host, port, mainnetMagic)
      .use { conn =>
        val chainSync  = new ChainSyncClient(conn.mux)
        val blockFetch = new BlockFetchClient(conn.mux)

        for
          // Step 1: Find intersection from genesis
          intersect <- chainSync.findIntersect(List(Point.Origin))
          _         <- IO.println(s"Intersection result: $intersect")

          // Step 2: First response after origin intersection is RollBackward
          r0 <- chainSync.requestNext
          _  <- IO.println(s"Initial response: $r0")

          // Step 3: Collect 7 headers (EBB + 6 regular Byron blocks)
          headers <- collectHeaders(chainSync, 7)
          _       <- IO.println(s"\nCollected ${headers.size} headers")

          // Skip the EBB (index 0), use the next 5 regular Byron blocks
          selected = headers.drop(1).take(5)
          _ <- IO.println(s"Selected ${selected.size} headers for BlockFetch")
          _ <- selected.zipWithIndex.traverse_ { case ((_, bp), i) =>
            IO.println(s"  Point $i: slot=${bp.slotNo.value}, hash=${bp.blockHash.toHash32.hash32Hex}")
          }

          _ <- IO {
            assert(selected.size == 5, s"Expected 5 selected headers, got ${selected.size}")
          }

          // Step 4: Fetch blocks from first to last selected header
          fromPoint = selected.head._2
          toPoint   = selected.last._2
          _ <- IO.println(s"\nFetching blocks from slot ${fromPoint.slotNo.value} to slot ${toPoint.slotNo.value}")

          blocks <- blockFetch.fetchRange(fromPoint, toPoint).compile.toList

          // Step 5: Verify blocks received and non-empty
          _ <- IO.println(s"\nReceived ${blocks.size} blocks")
          _ <- blocks.zipWithIndex.traverse_ { case (blockData, i) =>
            val eraInfo = parseEraTag(blockData)
            IO.println(s"  Block $i: size=${blockData.size} bytes, $eraInfo")
          }

          // Step 6: Assertions
          _ <- IO {
            assert(blocks.nonEmpty, "Expected non-empty block list")
            assert(blocks.size == 5, s"Expected 5 blocks, got ${blocks.size}")
            blocks.foreach { blockData =>
              assert(blockData.nonEmpty, "Block data should not be empty")
            }
          }

          _ <- IO.println("\nAll assertions passed!")

          // Clean up protocols
          _ <- chainSync.done
          _ <- blockFetch.done
        yield ()
      }
  }

  /** Collect N headers via ChainSync, parsing each to extract the block point. */
  private def collectHeaders(
      chainSync: ChainSyncClient,
      count: Int
  ): IO[List[(ByteVector, Point.BlockPoint)]] =
    (1 to count).toList.foldLeft(IO.pure(List.empty[(ByteVector, Point.BlockPoint)])) { (accIO, i) =>
      accIO.flatMap { acc =>
        chainSync.requestNext.flatMap {
          case ChainSyncResponse.RollForward(header, _) =>
            HeaderParser.parse(header) match
              case Right(meta) =>
                val blockPoint: Point.BlockPoint = Point.BlockPoint(meta.slotNo, meta.blockHash)
                IO.println(
                  s"Header $i: era=${meta.era}, slot=${meta.slotNo.value}, hash=${meta.blockHash.toHash32.hash32Hex.take(16)}..."
                ) *> IO.pure(acc :+ (header, blockPoint))
              case Left(err) =>
                IO.raiseError(new RuntimeException(s"Failed to parse header $i: $err"))
          case ChainSyncResponse.RollBackward(point, _) =>
            IO.println(s"Got rollback to $point, continuing") *> IO.pure(acc)
        }
      }
    }

  /** Parse the era tag from era-wrapped block data for debug output. */
  private def parseEraTag(blockData: ByteVector): String =
    if blockData.isEmpty then "empty"
    else
      val b     = blockData(0) & 0xff
      val major = b >> 5
      if major == 4 then
        val ai       = b & 0x1f
        val afterArr = if ai < 24 then 1 else 2
        if afterArr < blockData.size.toInt then
          val eraB     = blockData(afterArr.toLong) & 0xff
          val eraMajor = eraB >> 5
          if eraMajor == 0 then s"era_tag=${eraB & 0x1f}"
          else s"era_byte=0x${eraB.toHexString}"
        else "too short"
      else s"major=$major"
