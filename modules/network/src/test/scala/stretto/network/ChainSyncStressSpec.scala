package stretto.network

import cats.effect.IO
import munit.CatsEffectSuite
import stretto.core.Point

import scala.concurrent.duration.*

class ChainSyncStressSpec extends CatsEffectSuite:

  // Preprod node at panic-station
  private val host         = "192.168.1.37"
  private val port         = 30010
  private val preprodMagic = 1L

  private val targetHeaders = 1000
  private val logInterval   = 100

  test("sync 1000 headers from preprod node".ignore) {
    MuxConnection
      .connect(host, port, preprodMagic)
      .use { conn =>
        val client = new ChainSyncClient(conn.mux)
        for
          // Find intersection from Origin
          intersect <- client.findIntersect(List(Point.Origin))
          _ <- intersect match
            case Right((point, tip)) =>
              IO.println(s"[ChainSyncStress] Intersection at: $point, peer tip: $tip")
            case Left(tip) =>
              IO.println(s"[ChainSyncStress] No intersection, peer tip: $tip")

          startTime <- IO.monotonic

          // Stream headers in a loop, counting results
          result <- (1 to targetHeaders)
            .foldLeft(IO.pure((0, 0, 0))) { case (accIO, i) =>
              accIO.flatMap { case (forwards, backwards, errors) =>
                client.requestNext
                  .flatMap { response =>
                    val (fw, bw) = response match
                      case ChainSyncResponse.RollForward(header, tip) =>
                        (forwards + 1, backwards)
                      case ChainSyncResponse.RollBackward(point, tip) =>
                        (forwards, backwards + 1)

                    val logIO =
                      if i % logInterval == 0 then
                        val tipInfo = response match
                          case ChainSyncResponse.RollForward(header, tip) => s"tip=$tip, headerSize=${header.size}bytes"
                          case ChainSyncResponse.RollBackward(point, tip) => s"tip=$tip, rollbackPoint=$point"
                        IO.println(
                          s"[ChainSyncStress] Header #$i — rollForwards=$fw, rollBackwards=$bw, $tipInfo"
                        )
                      else IO.unit

                    logIO.as((fw, bw, errors))
                  }
                  .handleErrorWith { e =>
                    IO.println(
                      s"[ChainSyncStress] ERROR at header #$i: ${e.getMessage}"
                    ) *> IO.pure((forwards, backwards, errors + 1))
                  }
              }
            }

          (totalForwards, totalBackwards, totalErrors) = result

          endTime <- IO.monotonic
          elapsed = endTime - startTime

          _ <- IO.println(s"""
            |=== ChainSync Stress Test Summary ===
            |Total headers requested: $targetHeaders
            |RollForward responses:   $totalForwards
            |RollBackward responses:  $totalBackwards
            |Decode errors:           $totalErrors
            |Time elapsed:            ${elapsed.toMillis}ms (${elapsed.toSeconds}s)
            |Throughput:              ${
                              if elapsed.toSeconds > 0 then targetHeaders / elapsed.toSeconds else "N/A"
                            } headers/sec
            |=====================================""".stripMargin)

          _ <- client.done
        yield
          assert(
            totalForwards + totalBackwards == targetHeaders,
            s"Expected $targetHeaders total responses, got ${totalForwards + totalBackwards}"
          )
          assertEquals(totalErrors, 0, s"Expected 0 errors but got $totalErrors")
          assert(totalForwards > 0, "Expected at least one RollForward response")
      }
      .timeout(2.minutes)
  }
