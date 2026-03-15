package stretto.network

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector
import stretto.core.{Crypto, Point}
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

          // Step 2: First response is RollBackward
          r0 <- chainSync.requestNext
          _  <- IO.println(s"Initial response: $r0")

          // Step 3: Get first header (EBB) and debug hash computation
          r1 <- chainSync.requestNext
          _ <- r1 match
            case ChainSyncResponse.RollForward(header, _) =>
              debugHeaderHash(header, "EBB (header 1)")
            case _ => IO.println(s"Unexpected: $r1")

          // Get second header (first regular Byron block) and debug
          r2 <- chainSync.requestNext
          _ <- r2 match
            case ChainSyncResponse.RollForward(header, _) =>
              debugHeaderHash(header, "Byron block 1 (header 2)")
            case _ => IO.println(s"Unexpected: $r2")

          // Now collect a few more headers
          headers <- collectHeaders(chainSync, 4)

          // Combine headers 2-6 for BlockFetch
          allHeaders = (r2 match
            case ChainSyncResponse.RollForward(header, _) =>
              HeaderParser.parse(header).toOption.map { meta =>
                val bp: Point.BlockPoint = Point.BlockPoint(meta.slotNo, meta.blockHash)
                (header, bp)
              }
            case _ => None
          ).toList ++ headers

          selected = allHeaders.take(5)
          _ <- IO.println(s"\nSelected ${selected.size} points for BlockFetch:")
          _ <- selected.zipWithIndex.traverse_ { case ((_, bp), i) =>
            IO.println(s"  Point $i: slot=${bp.slotNo.value}, hash=${bp.blockHash.toHash32.hash32Hex}")
          }

          fromPoint = selected.head._2
          toPoint   = selected.last._2
          _ <- IO.println(s"\nFetching blocks from slot ${fromPoint.slotNo.value} to slot ${toPoint.slotNo.value}")

          encodedMsg = BlockFetchMessage.encode(BlockFetchMessage.MsgRequestRange(fromPoint, toPoint))
          _ <- IO.println(s"Encoded MsgRequestRange: ${encodedMsg.toHex}")
          _ <- conn.mux.send(protoId, encodedMsg)

          rawResponse <- conn.mux.recvProtocol(protoId)
          _           <- IO.println(s"Raw response: ${rawResponse.toHex}")
          decoded = BlockFetchMessage.decode(rawResponse)
          _ <- IO.println(s"Decoded: $decoded")

          result <- decoded match
            case Right(BlockFetchMessage.MsgStartBatch) =>
              IO.println("MsgStartBatch!") *>
                readBlocksManually(conn.mux, protoId, 0, List.empty)
            case Right(BlockFetchMessage.MsgNoBlocks) =>
              IO.println("MsgNoBlocks - hash mismatch suspected") *>
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

  /** Debug hash computation by trying multiple approaches */
  private def debugHeaderHash(wrappedHeader: ByteVector, label: String): IO[Unit] =
    IO {
      println(s"\n=== DEBUG: $label ===")
      println(s"  Wrapped header size: ${wrappedHeader.size}")
      println(s"  First 40 bytes: ${wrappedHeader.take(40).toHex}")

      // Our computed hash via HeaderParser
      val parsed = HeaderParser.parse(wrappedHeader)
      println(s"  HeaderParser result: $parsed")

      // Manual extraction: find tag24 content
      // Byron header: [0, [[sub_tag, param], tag24(headerBytes)]]
      // Let's scan for d818 (tag24)
      var idx = 0
      while idx < wrappedHeader.size.toInt - 1 do
        val b0 = wrappedHeader(idx.toLong) & 0xff
        val b1 = wrappedHeader((idx + 1).toLong) & 0xff
        if b0 == 0xd8 && b1 == 0x18 then
          println(s"  tag24 found at offset $idx")
          val bstrOff = idx + 2
          val bstrB   = wrappedHeader(bstrOff.toLong) & 0xff
          val bstrAi  = bstrB & 0x1f
          val bstrMaj = bstrB >> 5
          if bstrMaj == 2 then
            val (len, dataOff) =
              if bstrAi < 24 then (bstrAi.toLong, bstrOff + 1)
              else if bstrAi == 24 then ((wrappedHeader((bstrOff + 1).toLong) & 0xff).toLong, bstrOff + 2)
              else if bstrAi == 25 then
                val v =
                  ((wrappedHeader((bstrOff + 1).toLong) & 0xff) << 8) | (wrappedHeader((bstrOff + 2).toLong) & 0xff)
                (v.toLong, bstrOff + 3)
              else (0L, bstrOff + 1)
            val content = wrappedHeader.slice(dataOff.toLong, dataOff.toLong + len)
            println(s"  tag24 bstr len: $len, content first 20: ${content.take(20).toHex}")

            // Hash approaches
            println(s"  H1 blake2b256(content)       = ${Crypto.blake2b256(content).toHex}")
            println(
              s"  H2 blake2b256(tag24+content)  = ${Crypto.blake2b256(wrappedHeader.slice(idx.toLong, dataOff.toLong + len)).toHex}"
            )
            println(
              s"  H3 blake2b256(bstr+content)   = ${Crypto.blake2b256(wrappedHeader.slice(bstrOff.toLong, dataOff.toLong + len)).toHex}"
            )
            println(s"  H4 blake2b256(full_wrapped)   = ${Crypto.blake2b256(wrappedHeader).toHex}")
          end if
        end if
        idx += 1
      end while
    }

  private def readBlocksManually(
      mux: MuxDemuxer,
      protoId: Int,
      count: Int,
      acc: List[ByteVector]
  ): IO[List[ByteVector]] =
    mux.recvProtocol(protoId).flatMap { raw =>
      BlockFetchMessage.decode(raw) match
        case Right(BlockFetchMessage.MsgBlock(data)) =>
          IO.println(s"  Got block $count: ${data.size} bytes") *>
            readBlocksManually(mux, protoId, count + 1, acc :+ data)
        case Right(BlockFetchMessage.MsgBatchDone) =>
          IO.println(s"  MsgBatchDone after $count blocks") *>
            IO.pure(acc)
        case Right(other) =>
          IO.raiseError(new RuntimeException(s"Unexpected in stream: $other"))
        case Left(err) =>
          IO.raiseError(new RuntimeException(s"Decode error in stream: $err"))
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
