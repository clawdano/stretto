package stretto.network

import cats.effect.IO
import munit.CatsEffectSuite
import scodec.bits.ByteVector
import stretto.core.Point

import scala.concurrent.duration.*

class HeaderParserDebugSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 2.minutes

  private val host         = "192.168.1.37"
  private val port         = 30010
  private val preprodMagic = 1L

  test("diagnose HeaderParser on first 20 Byron-era headers from preprod".ignore) {
    MuxConnection
      .connect(host, port, preprodMagic)
      .use { conn =>
        val client = new ChainSyncClient(conn.mux)
        for
          // Find intersection from origin
          intersect <- client.findIntersect(List(Point.Origin))
          _ <- intersect match
            case Right((point, tip)) =>
              IO.println(s"[intersect] found at: $point, tip: $tip")
            case Left(tip) =>
              IO.println(s"[intersect] not found, tip: $tip")

          // Collect up to 20 RollForward headers (skip RollBackward)
          headers <- collectHeaders(client, 20, Nil)
          _       <- IO.println(s"\n=== Collected ${headers.size} headers ===\n")

          // Diagnose each header
          _ <- headers.zipWithIndex.traverse_ { case (raw, idx) =>
            val hexPrefix   = raw.take(50).toHex
            val eraTagInfo  = inspectEraTag(raw)
            val parseResult = HeaderParser.parse(raw)
            val statusLine = parseResult match
              case Right(meta) =>
                s"  OK: era=${meta.era}, slot=${meta.slotNo}, hash=${meta.blockHash}"
              case Left(err) =>
                s"  FAIL: $err"

            IO.println(
              s"--- Header #$idx ---\n" +
                s"  raw size: ${raw.size} bytes\n" +
                s"  first 50 hex bytes: $hexPrefix\n" +
                s"  $eraTagInfo\n" +
                statusLine
            )
          }
        yield ()
      }
  }

  /** Recursively collect up to `n` RollForward header bytes, skipping RollBackward. */
  private def collectHeaders(
      client: ChainSyncClient,
      remaining: Int,
      acc: List[ByteVector]
  ): IO[List[ByteVector]] =
    if remaining <= 0 then IO.pure(acc.reverse)
    else
      client.requestNext.flatMap {
        case ChainSyncResponse.RollForward(header, tip) =>
          IO.println(s"  [recv] RollForward #${acc.size}, header ${header.size} bytes, tip=$tip") *>
            collectHeaders(client, remaining - 1, header :: acc)
        case ChainSyncResponse.RollBackward(point, tip) =>
          IO.println(s"  [recv] RollBackward to $point, tip=$tip") *>
            collectHeaders(client, remaining, acc)
      }

  /** Inspect the era tag from the outer CBOR array without full parsing. */
  private def inspectEraTag(raw: ByteVector): String =
    if raw.isEmpty then "era tag: <empty>"
    else
      val firstByte = raw(0) & 0xff
      val major     = firstByte >> 5
      val ai        = firstByte & 0x1f
      if major == 4 then
        // Array header — era tag is next item
        val eraOffset =
          if ai < 24 then 1
          else if ai == 24 then 2
          else if ai == 25 then 3
          else if ai == 26 then 5
          else 9
        if raw.size > eraOffset then
          val eraByte  = raw(eraOffset.toLong) & 0xff
          val eraMajor = eraByte >> 5
          val eraAi    = eraByte & 0x1f
          if eraMajor == 0 then s"era tag: $eraAi (outer array len=$ai, major=$major)"
          else s"era tag byte: 0x${eraByte.toHexString} (major=$eraMajor, ai=$eraAi) — not a uint!"
        else s"era tag: <truncated after array header>"
      else s"era tag: <first byte 0x${firstByte.toHexString}, major=$major — not an array!>"

  // cats-effect traverse for List
  extension [A](list: List[A])

    def traverse_(f: A => IO[Unit]): IO[Unit] =
      list.foldLeft(IO.unit)((acc, a) => acc *> f(a))
