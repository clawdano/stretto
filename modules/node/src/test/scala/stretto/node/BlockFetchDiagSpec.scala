package stretto.node

import cats.effect.IO
import munit.CatsEffectSuite
import stretto.core.Point
import stretto.core.Types.*
import stretto.network.{BlockFetchClient, ChainSyncClient, ChainSyncResponse, HeaderParser, MuxConnection}

import cats.syntax.all.*
import scala.concurrent.duration.*

class BlockFetchDiagSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 5.minutes

  private val host         = "192.168.1.37"
  private val port         = 30000
  private val mainnetMagic = 764824073L

  test("diagnose mainnet BlockFetch at epoch boundary".ignore) {
    MuxConnection.connect(host, port, mainnetMagic).use { conn =>
      val chainSync  = new ChainSyncClient(conn.mux)
      val blockFetch = new BlockFetchClient(conn.mux)

      for
        // Sync headers from genesis, collect points near epoch boundary
        _ <- IO.println("Starting ChainSync from genesis...")
        headers <- chainSync
          .pipelinedHeaderStream(List(Point.Origin), 50)
          .zipWithIndex
          .takeWhile(_._2 < 21650) // Go past first epoch boundary (21600)
          .compile
          .toList
        _ <- chainSync.done
        _ <- IO.println(s"Got ${headers.size} headers")

        // Parse all headers to get points
        points = headers
          .collect { case (ChainSyncResponse.RollForward(hdr, _), idx) =>
            HeaderParser.parse(hdr).map { meta =>
              val point: Point.BlockPoint = Point.BlockPoint(meta.slotNo, meta.blockHash)
              (idx, meta.era, meta.slotNo, point, hdr)
            }
          }
          .collect { case Right(v) => v }

        // Log blocks around epoch boundary
        _ <- IO.println("\n=== Blocks near epoch boundary (21580-21620) ===")
        _ <- points.filter(p => p._3.value >= 21580 && p._3.value <= 21620).traverse_ {
          case (idx, era, slot, point, _) =>
            IO.println(
              f"  block $idx%5d | era=$era | slot=${slot.value}%6d | hash=${point.blockHash.toHash32.hash32Bytes.take(8).toHex}..."
            )
        }

        // Try fetching blocks one by one near the boundary
        _ <- IO.println("\n=== Fetching blocks individually near boundary ===")
        nearBoundary = points.filter(p => p._3.value >= 21590 && p._3.value <= 21610)
        _ <- nearBoundary.traverse_ { case (idx, era, slot, point, _) =>
          blockFetch
            .fetchRange(point, point)
            .compile
            .toList
            .flatMap { blocks =>
              IO.println(
                f"  block $idx%5d | slot=${slot.value}%6d | era=$era | fetched=${blocks.size} | size=${blocks.headOption.map(_.size).getOrElse(0L)}B"
              )
            }
            .handleErrorWith { err =>
              IO.println(f"  block $idx%5d | slot=${slot.value}%6d | era=$era | FAILED: ${err.getMessage}")
            }
        }
        _ <- blockFetch.done
      yield ()
    }
  }
