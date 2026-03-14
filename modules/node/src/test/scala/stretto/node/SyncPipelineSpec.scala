package stretto.node

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

class SyncPipelineSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 5.minutes

  private def tmpDir: Path =
    Files.createTempDirectory("stretto-sync-test")

  // Preprod node at panic-station
  private val host         = "192.168.1.37"
  private val port         = 30010
  private val preprodMagic = 1L

  test("sync headers from preprod to RocksDB".ignore) {
    val dir       = tmpDir
    val startTime = System.currentTimeMillis()

    SyncPipeline
      .sync(
        host = host,
        port = port,
        networkMagic = preprodMagic,
        dbPath = dir,
        maxHeaders = 100,
        progressInterval = 10,
        onProgress = progress =>
          IO.println(
            f"[sync] ${progress.headersStored}%,d headers | " +
              f"slot ${progress.currentSlot.value}%,d | " +
              f"peer tip: block ${progress.peerTipBlock.blockNoValue}%,d slot ${progress.peerTipSlot.value}%,d | " +
              f"errors: ${progress.parseErrors}"
          )
      )
      .timeout(5.minutes)
      .flatMap { result =>
        val elapsed = System.currentTimeMillis() - startTime
        IO.println(
          f"\n=== Sync Complete ===" +
            f"\nHeaders stored: ${result.headersStored}%,d" +
            f"\nRollbacks:      ${result.rollbacks}" +
            f"\nParse errors:   ${result.parseErrors}" +
            f"\nCurrent slot:   ${result.currentSlot.value}%,d" +
            f"\nPeer tip:       block ${result.peerTipBlock.blockNoValue}%,d / slot ${result.peerTipSlot.value}%,d" +
            f"\nElapsed:        ${elapsed}ms" +
            f"\nThroughput:     ${result.headersStored * 1000 / math.max(elapsed, 1)} headers/sec"
        ) *> IO {
          assert(result.headersStored > 0L)
          assert(result.parseErrors == 0L)
        }
      }
      .guarantee(IO {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      })
  }
