package stretto.node

import munit.FunSuite
import java.nio.file.Paths

class RelayNodeSpec extends FunSuite:

  test("RelayNode.Config holds all parameters") {
    val config = RelayNode.Config(
      upstreamHost = "panic-station",
      upstreamPort = 30010,
      networkMagic = 1L,
      networkName = "preprod",
      listenHost = "127.0.0.1",
      listenPort = 3001,
      dbPath = Paths.get("/tmp/test-relay"),
      maxClients = 64
    )
    assertEquals(config.upstreamHost, "panic-station")
    assertEquals(config.upstreamPort, 30010)
    assertEquals(config.networkMagic, 1L)
    assertEquals(config.listenHost, "127.0.0.1")
    assertEquals(config.listenPort, 3001)
    assertEquals(config.maxClients, 64)
  }

  test("RelayNode.Config default maxClients is 32") {
    val config = RelayNode.Config(
      upstreamHost = "localhost",
      upstreamPort = 3001,
      networkMagic = 1L,
      networkName = "preprod",
      listenHost = "127.0.0.1",
      listenPort = 3001,
      dbPath = Paths.get("/tmp/test")
    )
    assertEquals(config.maxClients, 32)
  }
