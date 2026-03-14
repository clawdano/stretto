package stretto.network

import cats.effect.IO
import munit.CatsEffectSuite

class HandshakeIntegrationSpec extends CatsEffectSuite:

  // Preprod node at panic-station (192.168.1.37)
  private val host         = "192.168.1.37"
  private val port         = 30010
  private val preprodMagic = 1L

  // Enable when running on a machine with direct access to panic-station
  test("handshake with preprod node".ignore) {
    MuxConnection
      .connect(host, port, preprodMagic)
      .use { conn =>
        IO.println(s"Connected! Accepted version: ${conn.acceptedVersion}") *>
          IO(assert(conn.acceptedVersion > 0, "should negotiate a version"))
      }
  }
