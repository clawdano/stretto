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
      n2nListenPort = 3001,
      n2cListenPort = 3002,
      dbPath = Paths.get("/tmp/test-relay"),
      maxN2NPeers = 32,
      maxN2CClients = 64
    )
    assertEquals(config.upstreamHost, "panic-station")
    assertEquals(config.upstreamPort, 30010)
    assertEquals(config.networkMagic, 1L)
    assertEquals(config.listenHost, "127.0.0.1")
    assertEquals(config.n2nListenPort, 3001)
    assertEquals(config.n2cListenPort, 3002)
    assertEquals(config.maxN2NPeers, 32)
    assertEquals(config.maxN2CClients, 64)
  }

  test("RelayNode.Config defaults: N2N on 3001, N2C disabled") {
    val config = RelayNode.Config(
      upstreamHost = "localhost",
      upstreamPort = 3001,
      networkMagic = 1L,
      networkName = "preprod",
      listenHost = "127.0.0.1",
      dbPath = Paths.get("/tmp/test")
    )
    assertEquals(config.n2nListenPort, 3001)
    assertEquals(config.n2cListenPort, 0)
    assertEquals(config.maxN2NPeers, 16)
    assertEquals(config.maxN2CClients, 32)
  }
