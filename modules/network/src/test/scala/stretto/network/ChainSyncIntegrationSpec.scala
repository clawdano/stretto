package stretto.network

import cats.effect.IO
import munit.CatsEffectSuite
import stretto.core.Point

class ChainSyncIntegrationSpec extends CatsEffectSuite:

  // Preprod node at panic-station
  private val host         = "192.168.1.37"
  private val port         = 30010
  private val preprodMagic = 1L

  test("find intersection and receive headers from preprod node".ignore) {
    MuxConnection
      .connect(host, port, preprodMagic)
      .use { conn =>
        val client = new ChainSyncClient(conn.mux)
        for
          // Find intersection starting from origin
          intersect <- client.findIntersect(List(Point.Origin))
          _ <- intersect match
            case Right((point, tip)) =>
              IO.println(s"Intersection at: $point, peer tip: $tip")
            case Left(tip) =>
              IO.println(s"No intersection, peer tip: $tip")

          // First requestNext after origin intersection yields RollBackward to origin
          r1 <- client.requestNext
          _  <- IO.println(s"Response 1: $r1")

          // Subsequent requests yield RollForward with headers
          r2 <- client.requestNext
          _  <- IO.println(s"Response 2: $r2")
          r3 <- client.requestNext
          _  <- IO.println(s"Response 3: $r3")
          r4 <- client.requestNext
          _  <- IO.println(s"Response 4: $r4")

          _ <- client.done
        yield
          // First response is RollBackward to confirm the intersection
          assert(r1.isInstanceOf[ChainSyncResponse.RollBackward])
          // Following responses are RollForward with block headers
          assert(r2.isInstanceOf[ChainSyncResponse.RollForward])
          assert(r3.isInstanceOf[ChainSyncResponse.RollForward])
          assert(r4.isInstanceOf[ChainSyncResponse.RollForward])
      }
  }
