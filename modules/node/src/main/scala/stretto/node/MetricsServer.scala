package stretto.node

import cats.effect.{IO, Ref}
import com.comcast.ip4s.{Host, Port}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.lang.management.ManagementFactory

/**
 * Prometheus metrics endpoint for monitoring.
 *
 * Serves `/metrics` in Prometheus text exposition format.
 * Includes Cardano chain metrics and standard JVM metrics.
 */
object MetricsServer:

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.node.MetricsServer")

  /** Mutable metrics state, updated by the sync pipeline. */
  final case class Metrics(
      chainTipSlot: Long = 0L,
      chainTipBlock: Long = 0L,
      peerTipSlot: Long = 0L,
      peerTipBlock: Long = 0L,
      syncedBlocks: Long = 0L,
      syncedBytes: Long = 0L,
      rollbacks: Long = 0L,
      epoch: Long = 0L,
      n2nPeersConnected: Long = 0L,
      n2cClientsConnected: Long = 0L,
      keepAliveRttMs: Long = 0L
  )

  /**
   * Start the Prometheus metrics HTTP server.
   *
   * @param host       bind address
   * @param port       bind port
   * @param metricsRef shared metrics state (updated by sync pipeline)
   * @param networkName network name for labels
   * @return never-terminating IO
   */
  def serve(
      host: String,
      port: Int,
      metricsRef: Ref[IO, Metrics],
      networkName: String
  ): IO[Nothing] =
    val routes = HttpRoutes.of[IO] { case GET -> Root / "metrics" =>
      renderMetrics(metricsRef, networkName).flatMap(body =>
        Ok(body, Header.Raw(ci"Content-Type", "text/plain; version=0.0.4; charset=utf-8"))
      )
    }

    val app = routes.orNotFound

    (for
      h <- IO.fromOption(Host.fromString(host))(new IllegalArgumentException(s"Invalid host: $host"))
      p <- IO.fromOption(Port.fromInt(port))(new IllegalArgumentException(s"Invalid port: $port"))
      _ <- logger.info(s"Metrics server starting on $host:$port/metrics")
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(h)
        .withPort(p)
        .withHttpApp(app)
        .build
        .useForever
    yield ()).asInstanceOf[IO[Nothing]]

  private def renderMetrics(metricsRef: Ref[IO, Metrics], network: String): IO[String] =
    metricsRef.get.map { m =>
      val sb = new StringBuilder

      // --- Cardano chain metrics ---
      sb ++= gauge("stretto_chain_tip_slot", "Current chain tip slot number", m.chainTipSlot, network)
      sb ++= gauge("stretto_chain_tip_block", "Current chain tip block number", m.chainTipBlock, network)
      sb ++= gauge("stretto_peer_tip_slot", "Upstream peer tip slot number", m.peerTipSlot, network)
      sb ++= gauge("stretto_peer_tip_block", "Upstream peer tip block number", m.peerTipBlock, network)
      sb ++= counter("stretto_sync_blocks_total", "Total blocks synced", m.syncedBlocks, network)
      sb ++= counter("stretto_sync_bytes_total", "Total block bytes synced", m.syncedBytes, network)
      sb ++= counter("stretto_sync_rollbacks_total", "Total chain rollbacks", m.rollbacks, network)
      sb ++= gauge("stretto_epoch", "Current epoch number", m.epoch, network)

      // Chain density: blocks / slots over the synced range
      val density =
        if m.chainTipSlot > 0 then m.chainTipBlock.toDouble / m.chainTipSlot.toDouble
        else 0.0
      sb ++= s"# HELP stretto_chain_density Ratio of blocks to slots (expected ~0.05 on mainnet)\n"
      sb ++= s"# TYPE stretto_chain_density gauge\n"
      sb ++= s"""stretto_chain_density{network="$network"} ${"%.6f".format(density)}\n"""

      // Sync progress (0.0 to 1.0)
      val syncProgress =
        if m.peerTipSlot > 0 then math.min(1.0, m.chainTipSlot.toDouble / m.peerTipSlot.toDouble)
        else 0.0
      sb ++= s"# HELP stretto_sync_progress Sync progress (0.0 to 1.0)\n"
      sb ++= s"# TYPE stretto_sync_progress gauge\n"
      sb ++= s"""stretto_sync_progress{network="$network"} ${"%.6f".format(syncProgress)}\n"""

      // Peer connections
      sb ++= gauge("stretto_n2n_peers_connected", "Connected N2N downstream peers", m.n2nPeersConnected, network)
      sb ++= gauge("stretto_n2c_clients_connected", "Connected N2C local clients", m.n2cClientsConnected, network)
      sb ++= gauge("stretto_keepalive_rtt_ms", "Last upstream KeepAlive RTT in milliseconds", m.keepAliveRttMs, network)

      // --- JVM metrics ---
      sb ++= jvmMetrics()

      sb.toString
    }

  private def gauge(name: String, help: String, value: Long, network: String): String =
    s"# HELP $name $help\n# TYPE $name gauge\n$name{network=\"$network\"} $value\n"

  private def counter(name: String, help: String, value: Long, network: String): String =
    s"# HELP $name $help\n# TYPE $name counter\n$name{network=\"$network\"} $value\n"

  private def jvmMetrics(): String =
    val sb      = new StringBuilder
    val runtime = Runtime.getRuntime
    val mxBean  = ManagementFactory.getRuntimeMXBean
    val memBean = ManagementFactory.getMemoryMXBean
    val heap    = memBean.getHeapMemoryUsage
    val nonHeap = memBean.getNonHeapMemoryUsage
    val threads = ManagementFactory.getThreadMXBean
    val os      = ManagementFactory.getOperatingSystemMXBean
    val gcBeans = ManagementFactory.getGarbageCollectorMXBeans

    // Uptime
    sb ++= s"# HELP jvm_uptime_seconds JVM uptime in seconds\n"
    sb ++= s"# TYPE jvm_uptime_seconds gauge\n"
    sb ++= s"jvm_uptime_seconds ${mxBean.getUptime / 1000.0}\n"

    // Start time
    sb ++= s"# HELP jvm_start_time_seconds JVM start time (Unix epoch seconds)\n"
    sb ++= s"# TYPE jvm_start_time_seconds gauge\n"
    sb ++= s"jvm_start_time_seconds ${mxBean.getStartTime / 1000.0}\n"

    // Heap memory
    sb ++= s"# HELP jvm_memory_heap_used_bytes JVM heap memory used\n"
    sb ++= s"# TYPE jvm_memory_heap_used_bytes gauge\n"
    sb ++= s"jvm_memory_heap_used_bytes ${heap.getUsed}\n"
    sb ++= s"# HELP jvm_memory_heap_committed_bytes JVM heap memory committed\n"
    sb ++= s"# TYPE jvm_memory_heap_committed_bytes gauge\n"
    sb ++= s"jvm_memory_heap_committed_bytes ${heap.getCommitted}\n"
    sb ++= s"# HELP jvm_memory_heap_max_bytes JVM heap memory max\n"
    sb ++= s"# TYPE jvm_memory_heap_max_bytes gauge\n"
    sb ++= s"jvm_memory_heap_max_bytes ${heap.getMax}\n"

    // Non-heap memory
    sb ++= s"# HELP jvm_memory_nonheap_used_bytes JVM non-heap memory used\n"
    sb ++= s"# TYPE jvm_memory_nonheap_used_bytes gauge\n"
    sb ++= s"jvm_memory_nonheap_used_bytes ${nonHeap.getUsed}\n"

    // Threads
    sb ++= s"# HELP jvm_threads_current Current JVM thread count\n"
    sb ++= s"# TYPE jvm_threads_current gauge\n"
    sb ++= s"jvm_threads_current ${threads.getThreadCount}\n"
    sb ++= s"# HELP jvm_threads_daemon Daemon JVM thread count\n"
    sb ++= s"# TYPE jvm_threads_daemon gauge\n"
    sb ++= s"jvm_threads_daemon ${threads.getDaemonThreadCount}\n"

    // Available processors
    sb ++= s"# HELP jvm_available_processors Available CPU cores\n"
    sb ++= s"# TYPE jvm_available_processors gauge\n"
    sb ++= s"jvm_available_processors ${runtime.availableProcessors}\n"

    // System load average
    sb ++= s"# HELP jvm_system_load_average System load average (1 min)\n"
    sb ++= s"# TYPE jvm_system_load_average gauge\n"
    sb ++= s"jvm_system_load_average ${os.getSystemLoadAverage}\n"

    // GC
    import scala.jdk.CollectionConverters.*
    gcBeans.asScala.foreach { gc =>
      val name = gc.getName.replaceAll("[^a-zA-Z0-9_]", "_")
      sb ++= s"# HELP jvm_gc_collection_seconds_total GC collection time for $name\n"
      sb ++= s"# TYPE jvm_gc_collection_seconds_total counter\n"
      sb ++= s"""jvm_gc_collection_seconds_total{gc="$name"} ${gc.getCollectionTime / 1000.0}\n"""
      sb ++= s"# HELP jvm_gc_collection_count_total GC collection count for $name\n"
      sb ++= s"# TYPE jvm_gc_collection_count_total counter\n"
      sb ++= s"""jvm_gc_collection_count_total{gc="$name"} ${gc.getCollectionCount}\n"""
    }

    sb.toString
