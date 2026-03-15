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
        val chainSync = new ChainSyncClient(conn.mux)
        val protoId   = MiniProtocolId.BlockFetch.id

        for
          // Step 1: Find intersection from genesis
          intersect <- chainSync.findIntersect(List(Point.Origin))
          _         <- IO.println(s"Intersection result: $intersect")

          // Step 2: First response after origin intersection is RollBackward
          r0 <- chainSync.requestNext
          _  <- IO.println(s"Initial response: $r0")

          // Step 3: Collect 7 headers
          headers <- collectHeaders(chainSync, 7)
          _       <- IO.println(s"\nCollected ${headers.size} headers")

          // Use headers 2-6 (skip EBB at index 0, use regular Byron blocks)
          selected = headers.drop(1).take(5)
          _ <- IO.println(s"Selected ${selected.size} headers for BlockFetch")
          _ <- selected.zipWithIndex.traverse_ { case ((_, bp), i) =>
            IO.println(s"  Point $i: slot=${bp.slotNo.value}, hash=${bp.blockHash.toHash32.hash32Hex}")
          }

          // Step 4: Send MsgRequestRange manually and debug the raw response
          fromPoint  = selected.head._2
          toPoint    = selected.last._2
          encodedMsg = BlockFetchMessage.encode(BlockFetchMessage.MsgRequestRange(fromPoint, toPoint))
          _ <- IO.println(s"\nEncoded MsgRequestRange (${encodedMsg.size} bytes): ${encodedMsg.toHex}")
          _ <- conn.mux.send(protoId, encodedMsg)

          // Read raw response
          rawResponse <- conn.mux.recvProtocol(protoId)
          _           <- IO.println(s"Raw response (${rawResponse.size} bytes): ${rawResponse.take(64).toHex}...")
          decoded = BlockFetchMessage.decode(rawResponse)
          _ <- IO.println(s"Decoded response: $decoded")

          // If StartBatch, read blocks
          result <- decoded match
            case Right(BlockFetchMessage.MsgStartBatch) =>
              IO.println("Got MsgStartBatch, reading blocks...") *>
                readBlocksManually(conn.mux, protoId, 0, List.empty)
            case Right(BlockFetchMessage.MsgNoBlocks) =>
              IO.println("Got MsgNoBlocks - server doesn't have these blocks at these points") *>
                IO.pure(List.empty[ByteVector])
            case Right(other) =>
              IO.raiseError(new RuntimeException(s"Unexpected: $other"))
            case Left(err) =>
              IO.raiseError(new RuntimeException(s"Decode error: $err"))

          _ <- IO.println(s"\nReceived ${result.size} blocks")
          _ <- result.zipWithIndex.traverse_ { case (blockData, i) =>
            val eraInfo = parseEraTag(blockData)
            IO.println(s"  Block $i: size=${blockData.size} bytes, $eraInfo")
          }

          _ <- IO {
            assert(result.nonEmpty, "Expected non-empty block list")
            assert(result.size == 5, s"Expected 5 blocks, got ${result.size}")
            result.foreach { blockData =>
              assert(blockData.nonEmpty, "Block data should not be empty")
            }
          }

          _ <- IO.println("\nAll assertions passed!")
          _ <- chainSync.done
          _ <- conn.mux.send(protoId, BlockFetchMessage.encode(BlockFetchMessage.MsgClientDone))
        yield ()
      }
  }

  private def readBlocksManually(
      mux: MuxDemuxer,
      protoId: Int,
      count: Int,
      acc: List[ByteVector]
  ): IO[List[ByteVector]] =
    mux.recvProtocol(protoId).flatMap { raw =>
      IO.println(s"  Block msg raw (${raw.size} bytes): ${raw.take(20).toHex}...") *> {
        BlockFetchMessage.decode(raw) match
          case Right(BlockFetchMessage.MsgBlock(data)) =>
            IO.println(s"  Got block ${count}: ${data.size} bytes") *>
              readBlocksManually(mux, protoId, count + 1, acc :+ data)
          case Right(BlockFetchMessage.MsgBatchDone) =>
            IO.println(s"  Got MsgBatchDone after $count blocks") *>
              IO.pure(acc)
          case Right(other) =>
            IO.raiseError(new RuntimeException(s"Unexpected in stream: $other"))
          case Left(err) =>
            IO.raiseError(new RuntimeException(s"Decode error in stream: $err"))
      }
    }

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
