package stretto.node

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

class BlockSyncPipelineSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.minutes

  private def tmpDir: Path =
    Files.createTempDirectory("stretto-block-sync-test")

  // Preprod node at panic-station
  private val host         = "192.168.1.37"
  private val port         = 30010
  private val preprodMagic = 1L

  test("sync 100 blocks from preprod with BlockFetch".ignore) {
    val dir       = tmpDir
    val startTime = System.currentTimeMillis()

    BlockSyncPipeline
      .sync(
        host = host,
        port = port,
        networkMagic = preprodMagic,
        dbPath = dir,
        maxBlocks = 100,
        progressInterval = 50,
        onProgress = progress =>
          IO.println(
            f"[block-sync] ${progress.blocksStored}%,d blocks (${progress.blockBytes}%,d bytes) | " +
              f"slot ${progress.currentSlot.value}%,d | " +
              f"peer tip: block ${progress.peerTipBlock.blockNoValue}%,d slot ${progress.peerTipSlot.value}%,d | " +
              f"fetch errors: ${progress.fetchErrors}"
          )
      )
      .timeout(120.minutes)
      .flatMap { result =>
        val elapsed = System.currentTimeMillis() - startTime
        IO.println(
          f"\n=== Block Sync Complete ===" +
            f"\nBlocks stored:  ${result.blocksStored}%,d" +
            f"\nBlock bytes:    ${result.blockBytes}%,d" +
            f"\nHeaders stored: ${result.headersStored}%,d" +
            f"\nRollbacks:      ${result.rollbacks}" +
            f"\nParse errors:   ${result.parseErrors}" +
            f"\nFetch errors:   ${result.fetchErrors}" +
            f"\nCurrent slot:   ${result.currentSlot.value}%,d" +
            f"\nPeer tip:       block ${result.peerTipBlock.blockNoValue}%,d / slot ${result.peerTipSlot.value}%,d" +
            f"\nElapsed:        ${elapsed}ms"
        ) *> IO {
          assert(result.blocksStored > 0L)
          assert(result.blockBytes > 0L)
          assert(result.parseErrors == 0L)
          assert(result.fetchErrors == 0L)
        }
      }
      .guarantee(IO {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      })
  }
