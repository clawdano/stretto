package stretto.node

import cats.effect.IO
import munit.CatsEffectSuite
import scodec.bits.ByteVector
import stretto.core.Point
import stretto.network.{ChainSyncClient, ChainSyncResponse, HeaderParser, MuxConnection}

import scala.concurrent.duration.*

class HeaderParserDiagSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 5.minutes

  private val host         = "panic-station"
  private val port         = 30010
  private val preprodMagic = 1L

  // Requires local network access to panic-station — skipped in CI
  test("diagnose HeaderParser on first 50 headers from genesis".ignore) {
    MuxConnection
      .connect(host, port, preprodMagic)
      .use { conn =>
        val client = new ChainSyncClient(conn.mux)
        for
          intersect <- client.findIntersect(List(Point.Origin))
          _ <- intersect match
            case Right((point, tip)) =>
              IO.println(s"[intersect] found at: $point, tip: $tip")
            case Left(tip) =>
              IO.println(s"[intersect] not found, tip: $tip")

          headers <- collectHeaders(client, 50, Nil)
          _       <- IO.println(s"\n=== Collected ${headers.size} headers ===\n")

          _ <- headers.zipWithIndex.foldLeft(IO.unit) { case (acc, (raw, idx)) =>
            acc *> diagnoseHeader(raw, idx)
          }
        yield ()
      }
  }

  private def diagnoseHeader(raw: ByteVector, idx: Int): IO[Unit] =
    val hexPrefix   = raw.take(100).toHex
    val parseResult = HeaderParser.parse(raw)
    val statusLine = parseResult match
      case Right(meta) =>
        s"  PARSE OK: era=${meta.era}, slot=${meta.slotNo}, hash=${meta.blockHash}"
      case Left(err) =>
        s"  PARSE FAIL: $err"

    val eraTagInfo = inspectEraTag(raw)
    val innerInfo  = inspectInnerStructure(raw)

    IO.println(
      s"--- Header #$idx ---\n" +
        s"  raw size: ${raw.size} bytes\n" +
        s"  first 100 hex bytes: $hexPrefix\n" +
        s"  $eraTagInfo\n" +
        s"  $innerInfo\n" +
        statusLine
    )

  private def collectHeaders(
      client: ChainSyncClient,
      remaining: Int,
      acc: List[ByteVector]
  ): IO[List[ByteVector]] =
    if remaining <= 0 then IO.pure(acc.reverse)
    else
      client.requestNext.flatMap {
        case ChainSyncResponse.RollForward(header, tip) =>
          IO.println(s"  [recv] RollForward #${acc.size}, header ${header.size} bytes") *>
            collectHeaders(client, remaining - 1, header :: acc)
        case ChainSyncResponse.RollBackward(point, tip) =>
          IO.println(s"  [recv] RollBackward to $point") *>
            collectHeaders(client, remaining, acc)
      }

  /** Inspect the era tag from the outer CBOR wrapper. */
  private def inspectEraTag(raw: ByteVector): String =
    if raw.isEmpty then return "era tag: <empty>"
    val firstByte = raw(0) & 0xff
    val major     = firstByte >> 5
    val ai        = firstByte & 0x1f
    if major == 4 then
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
        s"outer: array(len=$ai), era byte=0x${eraByte.toHexString} (major=$eraMajor, ai=$eraAi)"
      else s"era tag: <truncated after array header>"
    else s"outer: first byte=0x${firstByte.toHexString} (major=$major, ai=$ai) -- NOT an array"

  /** Walk through the inner structure and report the major type at each position. */
  private def inspectInnerStructure(raw: ByteVector): String =
    try
      if raw.isEmpty then return "inner: <empty>"
      val firstByte  = raw(0) & 0xff
      val outerMajor = firstByte >> 5
      if outerMajor != 4 then return s"inner: outer is not array, major=$outerMajor"

      // Get outer array length and offset after it
      val (outerLen, afterOuter) = readArgAndOffset(raw, 0)

      // Read the era tag (first element)
      val eraInfo = describeCborItem(raw, afterOuter)

      // Skip past era tag to get to second element
      val afterEra = skipOneItem(raw, afterOuter)
      if afterEra < 0 then return s"inner: could not skip era tag. outer_len=$outerLen, era: ${eraInfo}"

      // Describe second element
      val secondInfo = describeCborItem(raw, afterEra)

      // If second element is an array, describe its children
      val secondByte  = raw(afterEra.toLong) & 0xff
      val secondMajor = secondByte >> 5
      val childrenInfo = if secondMajor == 4 then
        val (innerLen, afterInnerArr) = readArgAndOffset(raw, afterEra)
        val childDescs                = scala.collection.mutable.ArrayBuffer[String]()
        var cursor                    = afterInnerArr
        var i                         = 0
        while i < innerLen && i < 10 && cursor >= 0 && cursor < raw.size do
          childDescs += s"[$i]=${describeCborItem(raw, cursor)}"
          cursor = skipOneItem(raw, cursor)
          i += 1
        s"  inner children (${innerLen} items): ${childDescs.mkString(", ")}"
      else ""

      s"inner: outer_len=$outerLen, [0]=$eraInfo, [1]=$secondInfo\n$childrenInfo"
    catch case e: Exception => s"inner: inspection error: ${e.getMessage}"

  /** Describe what CBOR item is at a given offset. */
  private def describeCborItem(bytes: ByteVector, offset: Int): String =
    if offset < 0 || offset >= bytes.size then return "<out of bounds>"
    val b     = bytes(offset.toLong) & 0xff
    val major = b >> 5
    val majorName = major match
      case 0 => "uint"
      case 1 => "nint"
      case 2 => "bstr"
      case 3 => "tstr"
      case 4 => "array"
      case 5 => "map"
      case 6 => "tag"
      case 7 => "simple/float"
      case _ => "unknown"
    val argStr =
      if major == 6 || major == 0 || major == 1 then
        val (v, _) = readArgAndOffset(bytes, offset)
        s"($v)"
      else if major == 4 then
        val (v, _) = readArgAndOffset(bytes, offset)
        s"(len=$v)"
      else if major == 2 || major == 3 then
        val (v, _) = readArgAndOffset(bytes, offset)
        s"(len=$v)"
      else ""
    s"$majorName$argStr @$offset [0x${b.toHexString}]"

  /** Read the argument value and return (value, offsetAfterHeader). */
  private def readArgAndOffset(bytes: ByteVector, offset: Int): (Long, Int) =
    val b  = bytes(offset.toLong) & 0xff
    val ai = b & 0x1f
    if ai < 24 then (ai.toLong, offset + 1)
    else if ai == 24 then ((bytes(offset.toLong + 1) & 0xff).toLong, offset + 2)
    else if ai == 25 then
      val v = ((bytes(offset.toLong + 1) & 0xff) << 8) | (bytes(offset.toLong + 2) & 0xff)
      (v.toLong, offset + 3)
    else if ai == 26 then
      val v =
        ((bytes(offset.toLong + 1) & 0xff).toLong << 24) |
          ((bytes(offset.toLong + 2) & 0xff).toLong << 16) |
          ((bytes(offset.toLong + 3) & 0xff).toLong << 8) |
          (bytes(offset.toLong + 4) & 0xff).toLong
      (v, offset + 5)
    else if ai == 27 then
      val v =
        ((bytes(offset.toLong + 1) & 0xff).toLong << 56) |
          ((bytes(offset.toLong + 2) & 0xff).toLong << 48) |
          ((bytes(offset.toLong + 3) & 0xff).toLong << 40) |
          ((bytes(offset.toLong + 4) & 0xff).toLong << 32) |
          ((bytes(offset.toLong + 5) & 0xff).toLong << 24) |
          ((bytes(offset.toLong + 6) & 0xff).toLong << 16) |
          ((bytes(offset.toLong + 7) & 0xff).toLong << 8) |
          (bytes(offset.toLong + 8) & 0xff).toLong
      (v, offset + 9)
    else (0L, offset + 1)

  /** Skip one CBOR item, return the offset after it (or -1 on error). */
  private def skipOneItem(bytes: ByteVector, offset: Int): Int =
    if offset < 0 || offset >= bytes.size then return -1
    val b                  = bytes(offset.toLong) & 0xff
    val major              = b >> 5
    val (arg, afterHeader) = readArgAndOffset(bytes, offset)
    major match
      case 0 | 1 | 7 => afterHeader
      case 2 | 3     => afterHeader + arg.toInt
      case 4 =>
        var cursor = afterHeader
        var i      = 0L
        while i < arg do
          cursor = skipOneItem(bytes, cursor)
          if cursor < 0 then return -1
          i += 1
        cursor
      case 5 =>
        var cursor = afterHeader
        var i      = 0L
        while i < arg * 2 do
          cursor = skipOneItem(bytes, cursor)
          if cursor < 0 then return -1
          i += 1
        cursor
      case 6 =>
        skipOneItem(bytes, afterHeader)
      case _ => -1
