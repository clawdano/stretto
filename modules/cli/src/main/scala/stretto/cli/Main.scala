package stretto.cli

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jLogger
import stretto.node.{BlockSyncPipeline, NetworkPreset, RelayNode, SyncPipeline}

import java.nio.file.{Path, Paths}

/** Stretto CLI — command-line interface for the Cardano node. */
object Main extends IOApp:

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.cli")

  override def run(args: List[String]): IO[ExitCode] =
    args match
      case "sync-headers" :: rest => runSyncHeaders(rest)
      case "sync-blocks" :: rest  => runSyncBlocks(rest)
      case "relay" :: rest        => runRelay(rest)
      case "version" :: _         => IO.println("stretto 0.1.0-SNAPSHOT") *> IO.pure(ExitCode.Success)
      case "help" :: _ | Nil      => printUsage *> IO.pure(ExitCode.Success)
      case unknown :: _ =>
        IO.println(s"Unknown command: $unknown. Run 'stretto help' for usage.") *> IO.pure(ExitCode(2))

  private def runSyncHeaders(args: List[String]): IO[ExitCode] =
    parseArgs(args, SyncHeadersConfig()) match
      case Left(err) =>
        IO.println(s"Error: $err") *> IO.println("") *> printSyncHeadersUsage *> IO.pure(ExitCode(2))
      case Right(config) =>
        resolveConfig(config).flatMap {
          case Left(err) =>
            IO.println(s"Error: $err") *> IO.pure(ExitCode(2))
          case Right(resolved) =>
            executeSyncHeaders(resolved)
        }

  private def runSyncBlocks(args: List[String]): IO[ExitCode] =
    parseSyncBlocksArgs(args, SyncBlocksConfig()) match
      case Left(err) =>
        IO.println(s"Error: $err") *> IO.println("") *> printSyncBlocksUsage *> IO.pure(ExitCode(2))
      case Right(config) =>
        resolveSyncBlocksConfig(config).flatMap {
          case Left(err) =>
            IO.println(s"Error: $err") *> IO.pure(ExitCode(2))
          case Right(resolved) =>
            executeSyncBlocks(resolved)
        }

  private def runRelay(args: List[String]): IO[ExitCode] =
    parseRelayArgs(args, RelayConfig()) match
      case Left(err) =>
        IO.println(s"Error: $err") *> IO.println("") *> printRelayUsage *> IO.pure(ExitCode(2))
      case Right(config) =>
        resolveRelayConfig(config).flatMap {
          case Left(err) =>
            IO.println(s"Error: $err") *> IO.pure(ExitCode(2))
          case Right(resolved) =>
            RelayNode
              .run(resolved)
              .as(ExitCode.Success)
              .handleErrorWith { err =>
                logger.error(err)(s"Relay node terminated: ${err.getMessage}") *>
                  IO.pure(ExitCode(1))
              }
        }

  private val MaxRetries    = 10
  private val RetryDelaySec = 5

  private def executeSyncBlocks(config: ResolvedBlockConfig): IO[ExitCode] =
    for
      _         <- logger.info(s"Stretto — Cardano block sync")
      _         <- logger.info(s"Network: ${config.networkName} (magic ${config.networkMagic})")
      _         <- logger.info(s"Peer: ${config.host}:${config.port}")
      _         <- logger.info(s"Database: ${config.dbPath}")
      _         <- if config.maxBlocks > 0 then logger.info(s"Max blocks: ${config.maxBlocks}") else IO.unit
      startTime <- IO.monotonic
      result    <- syncBlocksWithRetry(config, startTime, 0)
      elapsed   <- IO.monotonic.map(now => (now - startTime).toSeconds)
      _ <- logger.info(
        s"Sync complete: ${result.blocksStored} blocks (${formatBytes(result.blockBytes)}) in ${elapsed}s" +
          s" (${result.rollbacks} rollbacks, ${result.parseErrors} parse errors, ${result.fetchErrors} fetch errors)"
      )
    yield if result.parseErrors > 0 || result.fetchErrors > 0 then ExitCode(1) else ExitCode.Success

  private def syncBlocksWithRetry(
      config: ResolvedBlockConfig,
      startTime: scala.concurrent.duration.FiniteDuration,
      attempt: Int
  ): IO[BlockSyncPipeline.SyncProgress] =
    runBlockSyncOnce(config, startTime).handleErrorWith { err =>
      if attempt >= MaxRetries then
        logger.error(err)(s"Sync failed after ${attempt + 1} attempts: ${err.getMessage}") *>
          IO.pure(BlockSyncPipeline.emptyProgress)
      else
        logger.warn(
          s"Connection lost: ${err.getMessage}. Reconnecting in ${RetryDelaySec}s (attempt ${attempt + 1}/$MaxRetries)..."
        ) *>
          IO.sleep(scala.concurrent.duration.FiniteDuration(RetryDelaySec.toLong, "s")) *>
          syncBlocksWithRetry(config, startTime, attempt + 1)
    }

  private def runBlockSyncOnce(
      config: ResolvedBlockConfig,
      startTime: scala.concurrent.duration.FiniteDuration
  ): IO[BlockSyncPipeline.SyncProgress] =
    BlockSyncPipeline.sync(
      host = config.host,
      port = config.port,
      networkMagic = config.networkMagic,
      dbPath = config.dbPath,
      maxBlocks = config.maxBlocks,
      onProgress = progress =>
        for
          elapsed <- IO.monotonic.map(now => (now - startTime).toSeconds.max(1))
          bytesPerSec = progress.blockBytes.toDouble / elapsed.toDouble
          pct =
            if progress.peerTipBlock.blockNoValue > 0 then
              f"${progress.blocksStored.toDouble / progress.peerTipBlock.blockNoValue * 100}%.1f%%"
            else "N/A"
          _ <- logger.info(
            s"Progress: ${progress.blocksStored} blocks (${formatBytes(progress.blockBytes)}) " +
              s"(slot ${progress.currentSlot.value}, $pct) " +
              f"@ ${formatBytes(bytesPerSec.toLong)}/s" +
              s" | tip: slot ${progress.peerTipSlot.value} block ${progress.peerTipBlock.blockNoValue}" +
              (if progress.rollbacks > 0 then s" | rollbacks: ${progress.rollbacks}" else "") +
              (if progress.fetchErrors > 0 then s" | fetch errors: ${progress.fetchErrors}" else "")
          )
        yield ()
    )

  private def formatBytes(bytes: Long): String =
    if bytes < 1024 then s"${bytes}B"
    else if bytes < 1024 * 1024 then f"${bytes / 1024.0}%.1fKB"
    else if bytes < 1024L * 1024 * 1024 then f"${bytes / (1024.0 * 1024)}%.1fMB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.2fGB"

  private def executeSyncHeaders(config: ResolvedConfig): IO[ExitCode] =
    for
      _         <- logger.info(s"Stretto — Cardano header sync")
      _         <- logger.info(s"Network: ${config.networkName} (magic ${config.networkMagic})")
      _         <- logger.info(s"Peer: ${config.host}:${config.port}")
      _         <- logger.info(s"Database: ${config.dbPath}")
      _         <- if config.maxHeaders > 0 then logger.info(s"Max headers: ${config.maxHeaders}") else IO.unit
      startTime <- IO.monotonic
      result    <- syncWithRetry(config, startTime, 0)
      elapsed   <- IO.monotonic.map(now => (now - startTime).toSeconds)
      _ <- logger.info(
        s"Sync complete: ${result.headersStored} headers in ${elapsed}s" +
          s" (${result.rollbacks} rollbacks, ${result.parseErrors} errors)"
      )
    yield if result.parseErrors > 0 then ExitCode(1) else ExitCode.Success

  private def syncWithRetry(
      config: ResolvedConfig,
      startTime: scala.concurrent.duration.FiniteDuration,
      attempt: Int
  ): IO[SyncPipeline.SyncProgress] =
    runSyncOnce(config, startTime).handleErrorWith { err =>
      if attempt >= MaxRetries then
        logger.error(err)(s"Sync failed after ${attempt + 1} attempts: ${err.getMessage}") *>
          IO.pure(
            SyncPipeline.SyncProgress(
              0L,
              0L,
              0L,
              stretto.core.Types.SlotNo(0L),
              stretto.core.Types.SlotNo(0L),
              stretto.core.Types.BlockNo(0L)
            )
          )
      else
        logger.warn(
          s"Connection lost: ${err.getMessage}. Reconnecting in ${RetryDelaySec}s (attempt ${attempt + 1}/$MaxRetries)..."
        ) *>
          IO.sleep(scala.concurrent.duration.FiniteDuration(RetryDelaySec.toLong, "s")) *>
          syncWithRetry(config, startTime, attempt + 1)
    }

  private def runSyncOnce(
      config: ResolvedConfig,
      startTime: scala.concurrent.duration.FiniteDuration
  ): IO[SyncPipeline.SyncProgress] =
    SyncPipeline.sync(
      host = config.host,
      port = config.port,
      networkMagic = config.networkMagic,
      dbPath = config.dbPath,
      maxHeaders = config.maxHeaders,
      onProgress = progress =>
        for
          elapsed <- IO.monotonic.map(now => (now - startTime).toSeconds.max(1))
          rate = progress.headersStored.toDouble / elapsed.toDouble
          pct =
            if progress.peerTipBlock.blockNoValue > 0 then
              f"${progress.headersStored.toDouble / progress.peerTipBlock.blockNoValue * 100}%.1f%%"
            else "N/A"
          _ <- logger.info(
            s"Progress: ${progress.headersStored} headers " +
              s"(slot ${progress.currentSlot.value}, $pct) " +
              f"@ $rate%.0f hdr/s" +
              s" | tip: slot ${progress.peerTipSlot.value} block ${progress.peerTipBlock.blockNoValue}" +
              (if progress.rollbacks > 0 then s" | rollbacks: ${progress.rollbacks}" else "") +
              (if progress.parseErrors > 0 then s" | errors: ${progress.parseErrors}" else "")
          )
        yield ()
    )

  // --- Config types ---

  private case class SyncHeadersConfig(
      network: Option[String] = None,
      host: Option[String] = None,
      port: Option[Int] = None,
      dbPath: Option[String] = None,
      maxHeaders: Long = 0L,
      networkMagic: Option[Long] = None
  )

  private case class ResolvedConfig(
      networkName: String,
      networkMagic: Long,
      host: String,
      port: Int,
      dbPath: Path,
      maxHeaders: Long
  )

  private case class SyncBlocksConfig(
      network: Option[String] = None,
      host: Option[String] = None,
      port: Option[Int] = None,
      dbPath: Option[String] = None,
      maxBlocks: Long = 0L,
      networkMagic: Option[Long] = None
  )

  private case class ResolvedBlockConfig(
      networkName: String,
      networkMagic: Long,
      host: String,
      port: Int,
      dbPath: Path,
      maxBlocks: Long
  )

  private case class RelayConfig(
      network: Option[String] = None,
      peerHost: Option[String] = None,
      peerPort: Option[Int] = None,
      listenHost: String = "127.0.0.1",
      listenPort: Option[Int] = None,
      dbPath: Option[String] = None,
      maxClients: Int = 32,
      networkMagic: Option[Long] = None
  )

  // --- Arg parsing ---

  private def parseRelayArgs(args: List[String], config: RelayConfig): Either[String, RelayConfig] =
    args match
      case Nil => Right(config)
      case ("--network" | "-n") :: value :: rest =>
        parseRelayArgs(rest, config.copy(network = Some(value)))
      case ("--peer" | "-p") :: value :: rest =>
        parsePeer(value).flatMap { case (h, p) =>
          parseRelayArgs(rest, config.copy(peerHost = Some(h), peerPort = Some(p)))
        }
      case ("--listen" | "-l") :: value :: rest =>
        parsePeer(value).flatMap { case (h, p) =>
          parseRelayArgs(rest, config.copy(listenHost = h, listenPort = Some(p)))
        }
      case ("--db" | "-d") :: value :: rest =>
        parseRelayArgs(rest, config.copy(dbPath = Some(value)))
      case "--max-clients" :: value :: rest =>
        value.toIntOption match
          case Some(n) if n > 0 => parseRelayArgs(rest, config.copy(maxClients = n))
          case _                => Left(s"Invalid max-clients: $value")
      case "--magic" :: value :: rest =>
        value.toLongOption match
          case Some(n) => parseRelayArgs(rest, config.copy(networkMagic = Some(n)))
          case None    => Left(s"Invalid magic: $value")
      case ("--help" | "-h") :: _ =>
        Left("")
      case unknown :: _ =>
        Left(s"Unknown option: $unknown")

  private def resolveRelayConfig(config: RelayConfig): IO[Either[String, RelayNode.Config]] = IO {
    val preset      = config.network.flatMap(NetworkPreset.fromName)
    val networkName = config.network.getOrElse(preset.map(_.name).getOrElse("custom"))

    val magic = config.networkMagic
      .orElse(preset.map(_.networkMagic))
      .toRight("No network specified. Use --network <mainnet|preprod|preview> or --magic <number>")

    magic.flatMap { m =>
      val (peerHost, peerPort) = (config.peerHost, config.peerPort) match
        case (Some(h), Some(p)) => (h, p)
        case _ =>
          preset.flatMap(_.defaultPeers.headOption).getOrElse(("", 0))

      if peerHost.isEmpty then Left("No upstream peer specified. Use --peer host:port")
      else
        val listenPort = config.listenPort.getOrElse(3001)
        val db = config.dbPath
          .map(Paths.get(_))
          .getOrElse(Paths.get(".", "data", s"$networkName-relay"))

        Right(
          RelayNode.Config(
            upstreamHost = peerHost,
            upstreamPort = peerPort,
            networkMagic = m,
            networkName = networkName,
            listenHost = config.listenHost,
            listenPort = listenPort,
            dbPath = db,
            maxClients = config.maxClients
          )
        )
    }
  }

  private def parseSyncBlocksArgs(args: List[String], config: SyncBlocksConfig): Either[String, SyncBlocksConfig] =
    args match
      case Nil => Right(config)
      case ("--network" | "-n") :: value :: rest =>
        parseSyncBlocksArgs(rest, config.copy(network = Some(value)))
      case ("--peer" | "-p") :: value :: rest =>
        parsePeer(value).flatMap { case (h, p) =>
          parseSyncBlocksArgs(rest, config.copy(host = Some(h), port = Some(p)))
        }
      case ("--db" | "-d") :: value :: rest =>
        parseSyncBlocksArgs(rest, config.copy(dbPath = Some(value)))
      case ("--max-blocks" | "-m") :: value :: rest =>
        value.toLongOption match
          case Some(n) => parseSyncBlocksArgs(rest, config.copy(maxBlocks = n))
          case None    => Left(s"Invalid number: $value")
      case "--magic" :: value :: rest =>
        value.toLongOption match
          case Some(n) => parseSyncBlocksArgs(rest, config.copy(networkMagic = Some(n)))
          case None    => Left(s"Invalid magic: $value")
      case ("--help" | "-h") :: _ =>
        Left("")
      case unknown :: _ =>
        Left(s"Unknown option: $unknown")

  private def resolveSyncBlocksConfig(config: SyncBlocksConfig): IO[Either[String, ResolvedBlockConfig]] = IO {
    val preset      = config.network.flatMap(NetworkPreset.fromName)
    val networkName = config.network.getOrElse(preset.map(_.name).getOrElse("custom"))

    val magic = config.networkMagic
      .orElse(preset.map(_.networkMagic))
      .toRight("No network specified. Use --network <mainnet|preprod|preview> or --magic <number>")

    magic.flatMap { m =>
      val (host, port) = (config.host, config.port) match
        case (Some(h), Some(p)) => (h, p)
        case _ =>
          preset.flatMap(_.defaultPeers.headOption).getOrElse(("", 0))

      if host.isEmpty then Left(s"No peer specified. Use --peer host:port or a known --network with default peers")
      else
        val db = config.dbPath
          .map(Paths.get(_))
          .getOrElse(
            Paths.get(".", "data", networkName)
          )
        Right(ResolvedBlockConfig(networkName, m, host, port, db, config.maxBlocks))
    }
  }

  private def parseArgs(args: List[String], config: SyncHeadersConfig): Either[String, SyncHeadersConfig] =
    args match
      case Nil => Right(config)
      case ("--network" | "-n") :: value :: rest =>
        parseArgs(rest, config.copy(network = Some(value)))
      case ("--peer" | "-p") :: value :: rest =>
        parsePeer(value).flatMap { case (h, p) =>
          parseArgs(rest, config.copy(host = Some(h), port = Some(p)))
        }
      case ("--db" | "-d") :: value :: rest =>
        parseArgs(rest, config.copy(dbPath = Some(value)))
      case ("--max-headers" | "-m") :: value :: rest =>
        value.toLongOption match
          case Some(n) => parseArgs(rest, config.copy(maxHeaders = n))
          case None    => Left(s"Invalid number: $value")
      case "--magic" :: value :: rest =>
        value.toLongOption match
          case Some(n) => parseArgs(rest, config.copy(networkMagic = Some(n)))
          case None    => Left(s"Invalid magic: $value")
      case ("--help" | "-h") :: _ =>
        Left("") // triggers usage
      case unknown :: _ =>
        Left(s"Unknown option: $unknown")

  private def parsePeer(s: String): Either[String, (String, Int)] =
    s.lastIndexOf(':') match
      case -1 => Left(s"Invalid peer format '$s'. Expected host:port")
      case idx =>
        val host    = s.substring(0, idx)
        val portStr = s.substring(idx + 1)
        portStr.toIntOption match
          case Some(p) if p > 0 && p < 65536 => Right((host, p))
          case _                             => Left(s"Invalid port in '$s'")

  private def resolveConfig(config: SyncHeadersConfig): IO[Either[String, ResolvedConfig]] = IO {
    val preset      = config.network.flatMap(NetworkPreset.fromName)
    val networkName = config.network.getOrElse(preset.map(_.name).getOrElse("custom"))

    val magic = config.networkMagic
      .orElse(preset.map(_.networkMagic))
      .toRight("No network specified. Use --network <mainnet|preprod|preview> or --magic <number>")

    magic.flatMap { m =>
      val (host, port) = (config.host, config.port) match
        case (Some(h), Some(p)) => (h, p)
        case _ =>
          preset.flatMap(_.defaultPeers.headOption).getOrElse(("", 0))

      if host.isEmpty then Left(s"No peer specified. Use --peer host:port or a known --network with default peers")
      else
        val db = config.dbPath
          .map(Paths.get(_))
          .getOrElse(
            Paths.get(".", "data", networkName)
          )
        Right(ResolvedConfig(networkName, m, host, port, db, config.maxHeaders))
    }
  }

  // --- Usage ---

  private def printUsage: IO[Unit] = IO.println(
    """Usage: stretto <command> [options]
      |
      |Commands:
      |  sync-headers   Sync block headers from a Cardano node
      |  sync-blocks    Sync full blocks (headers + bodies) from a Cardano node
      |  relay          Run a lightweight N2C relay node
      |  version        Print version
      |  help           Show this help
      |""".stripMargin
  )

  private def printSyncHeadersUsage: IO[Unit] = IO.println(
    """Usage: stretto sync-headers [options]
      |
      |Options:
      |  -n, --network <name>       Network: mainnet, preprod, preview
      |  -p, --peer <host:port>     Peer address (overrides network default)
      |  -d, --db <path>            Database directory (default: ./data/<network>)
      |  -m, --max-headers <n>      Max headers to sync (0 = unlimited)
      |      --magic <number>       Custom network magic (overrides --network)
      |  -h, --help                 Show this help
      |
      |Examples:
      |  stretto sync-headers --network preprod
      |  stretto sync-headers --network mainnet --peer relay:3001
      |  stretto sync-headers --peer custom-node:3001 --magic 42 --db ./mydata
      |""".stripMargin
  )

  private def printSyncBlocksUsage: IO[Unit] = IO.println(
    """Usage: stretto sync-blocks [options]
      |
      |Syncs full blocks (headers + bodies) from a Cardano node using
      |ChainSync + BlockFetch over a single multiplexed connection.
      |
      |Options:
      |  -n, --network <name>       Network: mainnet, preprod, preview
      |  -p, --peer <host:port>     Peer address (overrides network default)
      |  -d, --db <path>            Database directory (default: ./data/<network>)
      |  -m, --max-blocks <n>       Max blocks to sync (0 = unlimited)
      |      --magic <number>       Custom network magic (overrides --network)
      |  -h, --help                 Show this help
      |
      |Examples:
      |  stretto sync-blocks --network preprod --peer panic-station:30010
      |  stretto sync-blocks --network preprod --peer panic-station:30010 --max-blocks 100
      |  stretto sync-blocks --peer custom-node:3001 --magic 42 --db ./mydata
      |""".stripMargin
  )

  private def printRelayUsage: IO[Unit] = IO.println(
    """Usage: stretto relay [options]
      |
      |Run a lightweight N2C relay node. Syncs blocks from an upstream N2N peer
      |and serves them to local N2C clients via ChainSync.
      |
      |Options:
      |  -n, --network <name>       Network: mainnet, preprod, preview
      |  -p, --peer <host:port>     Upstream N2N peer address
      |  -l, --listen <host:port>   N2C listen address (default: 127.0.0.1:3001)
      |  -d, --db <path>            Database directory (default: ./data/<network>-relay)
      |      --max-clients <n>      Max concurrent N2C clients (default: 32)
      |      --magic <number>       Custom network magic (overrides --network)
      |  -h, --help                 Show this help
      |
      |Examples:
      |  stretto relay --network preprod --peer panic-station:30010
      |  stretto relay --network preprod --peer panic-station:30010 --listen 127.0.0.1:3001
      |  stretto relay --network mainnet --peer relay:3001 --listen 0.0.0.0:3001 --max-clients 64
      |""".stripMargin
  )
