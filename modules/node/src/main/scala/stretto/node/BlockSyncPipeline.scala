package stretto.node

import cats.effect.IO
import cats.syntax.all.*
import fs2.concurrent.Topic
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector
import stretto.core.{Point, Tip}
import stretto.core.Types.*
import stretto.network.{
  BlockFetchClient,
  ChainSyncClient,
  ChainSyncResponse,
  HeaderParser,
  KeepAliveClient,
  MuxConnection
}
import stretto.storage.{BlockSyncer, RocksDbStore}

import java.nio.file.Path

/**
 * End-to-end block sync pipeline: connects to a Cardano node, streams headers
 * via pipelined ChainSync N2N, fetches full blocks via BlockFetch, and persists
 * both headers and blocks to RocksDB in atomic batches.
 */
object BlockSyncPipeline:

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.node.BlockSyncPipeline")

  final case class SyncProgress(
      headersStored: Long,
      blocksStored: Long,
      blockBytes: Long,
      rollbacks: Long,
      parseErrors: Long,
      fetchErrors: Long,
      currentSlot: SlotNo,
      peerTipSlot: SlotNo,
      peerTipBlock: BlockNo
  )

  val emptyProgress: SyncProgress =
    SyncProgress(0L, 0L, 0L, 0L, 0L, 0L, SlotNo(0L), SlotNo(0L), BlockNo(0L))

  /**
   * Run the block sync pipeline.
   *
   * @param host             peer hostname
   * @param port             peer N2N port
   * @param networkMagic     network magic (1 = preprod)
   * @param dbPath           RocksDB directory
   * @param maxBlocks        max blocks to sync (0 = unlimited)
   * @param onProgress       callback every `progressInterval` blocks
   * @param progressInterval how often to call onProgress
   * @param pipelineWindow   number of in-flight ChainSync requests
   * @param batchSize        number of headers per batch before BlockFetch
   */
  def sync(
      host: String,
      port: Int,
      networkMagic: Long,
      dbPath: Path,
      maxBlocks: Long,
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int = 500,
      pipelineWindow: Int = 100,
      batchSize: Int = 50
  ): IO[SyncProgress] =
    RocksDbStore.open(dbPath).use { store =>
      val syncer = new BlockSyncer(store)
      MuxConnection.connect(host, port, networkMagic).use { conn =>
        val chainSyncClient  = new ChainSyncClient(conn.mux)
        val blockFetchClient = new BlockFetchClient(conn.mux)
        val keepAlive        = new KeepAliveClient(conn.mux)
        for
          _        <- keepAlive.respondLoop.start
          knownPts <- syncer.knownPoints
          result <- pipelinedSyncLoop(
            chainSyncClient,
            blockFetchClient,
            syncer,
            knownPts,
            maxBlocks,
            onProgress,
            progressInterval,
            pipelineWindow,
            batchSize
          )
          _ <- blockFetchClient.done
        yield result
      }
    }

  /**
   * Run the block sync pipeline with an externally-managed store and topic.
   *
   * Used by RelayNode to publish ChainEvents to N2C subscribers.
   */
  def syncWithTopic(
      host: String,
      port: Int,
      networkMagic: Long,
      store: RocksDbStore,
      maxBlocks: Long,
      tipTopic: Topic[IO, ChainEvent],
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int = 500,
      pipelineWindow: Int = 100,
      batchSize: Int = 50
  ): IO[SyncProgress] =
    val syncer = new BlockSyncer(store)
    MuxConnection.connect(host, port, networkMagic).use { conn =>
      val chainSyncClient  = new ChainSyncClient(conn.mux)
      val blockFetchClient = new BlockFetchClient(conn.mux)
      val keepAlive        = new KeepAliveClient(conn.mux)
      for
        _        <- keepAlive.respondLoop.start
        knownPts <- syncer.knownPoints
        result <- pipelinedSyncLoopWithTopic(
          chainSyncClient,
          blockFetchClient,
          syncer,
          knownPts,
          maxBlocks,
          tipTopic,
          onProgress,
          progressInterval,
          pipelineWindow,
          batchSize
        )
        _ <- blockFetchClient.done
      yield result
    }

  private def pipelinedSyncLoopWithTopic(
      chainSyncClient: ChainSyncClient,
      blockFetchClient: BlockFetchClient,
      syncer: BlockSyncer,
      knownPoints: List[Point],
      maxBlocks: Long,
      tipTopic: Topic[IO, ChainEvent],
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int,
      pipelineWindow: Int,
      batchSize: Int
  ): IO[SyncProgress] =
    val headerStream = chainSyncClient.pipelinedHeaderStream(knownPoints, pipelineWindow)

    headerStream.zipWithIndex
      .takeWhile { case (_, idx) => maxBlocks <= 0 || idx < maxBlocks }
      .groupWithin(batchSize, scala.concurrent.duration.FiniteDuration(100, "ms"))
      .evalScan(emptyProgress) { case (progress, chunk) =>
        processBatchWithTopic(
          blockFetchClient,
          syncer,
          progress,
          chunk.toList,
          tipTopic,
          onProgress,
          progressInterval
        )
      }
      .compile
      .last
      .map(_.getOrElse(emptyProgress))
      .flatTap(_ => chainSyncClient.done)

  private def processBatchWithTopic(
      blockFetchClient: BlockFetchClient,
      syncer: BlockSyncer,
      progress: SyncProgress,
      batch: List[(ChainSyncResponse, Long)],
      tipTopic: Topic[IO, ChainEvent],
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int
  ): IO[SyncProgress] =
    // Delegate to the regular processBatch logic, then publish events
    processBatch(blockFetchClient, syncer, progress, batch, onProgress, progressInterval).flatTap { newProgress =>
      // Publish chain events for the batch
      val forwards = batch.collect { case (ChainSyncResponse.RollForward(header, tip), _) =>
        (header, tip)
      }
      val lastForward = forwards.lastOption
      val rollbacks = batch.collect { case (ChainSyncResponse.RollBackward(point, tip), _) =>
        (point, tip)
      }

      // Publish rollback events
      val rollbackPublish = rollbacks.traverse_ { case (point, tip) =>
        tipTopic.publish1(ChainEvent.RolledBack(point, tip))
      }

      // Publish block added for the last block in this batch
      val forwardPublish = lastForward match
        case Some((header, tip)) =>
          HeaderParser.parse(header) match
            case Right(meta) =>
              val bp: Point.BlockPoint = Point.BlockPoint(meta.slotNo, meta.blockHash)
              tipTopic.publish1(ChainEvent.BlockAdded(bp, tip)).void
            case Left(_) => IO.unit
        case None => IO.unit

      rollbackPublish *> forwardPublish
    }

  private def pipelinedSyncLoop(
      chainSyncClient: ChainSyncClient,
      blockFetchClient: BlockFetchClient,
      syncer: BlockSyncer,
      knownPoints: List[Point],
      maxBlocks: Long,
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int,
      pipelineWindow: Int,
      batchSize: Int
  ): IO[SyncProgress] =
    val headerStream = chainSyncClient.pipelinedHeaderStream(knownPoints, pipelineWindow)

    headerStream.zipWithIndex
      .takeWhile { case (_, idx) => maxBlocks <= 0 || idx < maxBlocks }
      .groupWithin(batchSize, scala.concurrent.duration.FiniteDuration(100, "ms"))
      .evalScan(emptyProgress) { case (progress, chunk) =>
        processBatch(blockFetchClient, syncer, progress, chunk.toList, onProgress, progressInterval)
      }
      .compile
      .last
      .map(_.getOrElse(emptyProgress))
      .flatTap(_ => chainSyncClient.done)

  private def processBatch(
      blockFetchClient: BlockFetchClient,
      syncer: BlockSyncer,
      progress: SyncProgress,
      batch: List[(ChainSyncResponse, Long)],
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int
  ): IO[SyncProgress] =
    // Separate forwards and backwards
    val forwards = batch.collect { case (ChainSyncResponse.RollForward(header, tip), _) =>
      (header, tip)
    }
    val rollbackCount = batch.count(_._1.isInstanceOf[ChainSyncResponse.RollBackward])

    // Parse all forward headers to extract points
    val (parsed, errors) = forwards.foldLeft(
      (List.empty[(Point.BlockPoint, ByteVector, BlockNo, stretto.core.Tip)], 0L)
    ) { case ((acc, errs), (header, tip)) =>
      HeaderParser.parse(header) match
        case Right(meta) =>
          val point: Point.BlockPoint = Point.BlockPoint(meta.slotNo, meta.blockHash)
          val bn                      = BlockNo(progress.headersStored + acc.size + 1)
          ((point, header, bn, tip) :: acc, errs)
        case Left(_) =>
          (acc, errs + 1)
    }

    val entries      = parsed.reverse
    val lastTip      = entries.lastOption.map(_._4)
    val lastRbTip    = batch.reverse.collectFirst { case (ChainSyncResponse.RollBackward(_, tip), _) => tip }
    val effectiveTip = lastTip.orElse(lastRbTip)

    // Fetch blocks for all parsed headers in one range request
    val fetchAndWriteIO = entries match
      case Nil => IO.pure((0L, 0L, 0L))
      case _ =>
        val firstPoint = entries.head._1
        val lastPoint  = entries.last._1
        val tip        = entries.last._4

        blockFetchClient
          .fetchRange(firstPoint, lastPoint)
          .compile
          .toList
          .flatMap { blocks =>
            if blocks.size == entries.size then
              // Match blocks to headers by position
              val combined = entries.zip(blocks).map { case ((point, header, blockNo, _), blockData) =>
                (point, header, blockNo, blockData)
              }
              syncer
                .rollForwardBatchWithBlocks(combined, tip)
                .as(
                  (combined.size.toLong, combined.map(_._4.size).sum, 0L)
                )
            else
              // Size mismatch — store headers only, log warning
              logger.warn(
                s"BlockFetch returned ${blocks.size} blocks for ${entries.size} headers, storing available blocks"
              ) *> {
                // Store what we can match positionally
                val matchCount = math.min(blocks.size, entries.size)
                val combined = entries.take(matchCount).zip(blocks.take(matchCount)).map {
                  case ((point, header, blockNo, _), blockData) =>
                    (point, header, blockNo, blockData)
                }
                if combined.nonEmpty then
                  syncer
                    .rollForwardBatchWithBlocks(combined, tip)
                    .as(
                      (combined.size.toLong, combined.map(_._4.size).sum, (entries.size - matchCount).toLong)
                    )
                else IO.pure((0L, 0L, entries.size.toLong))
              }
          }
          .handleErrorWith { err =>
            logger.warn(s"BlockFetch failed: ${err.getMessage}, skipping batch") *>
              IO.pure((0L, 0L, entries.size.toLong))
          }

    // Handle rollbacks
    val rollbackIO = batch
      .collect { case (ChainSyncResponse.RollBackward(_, tip), _) => tip }
      .lastOption
      .fold(IO.unit)(syncer.rollBackward)

    for
      (blocksWritten, bytesWritten, fetchErrs) <- fetchAndWriteIO
      _                                        <- rollbackIO
      newProgress = SyncProgress(
        headersStored = progress.headersStored + entries.size,
        blocksStored = progress.blocksStored + blocksWritten,
        blockBytes = progress.blockBytes + bytesWritten,
        rollbacks = progress.rollbacks + rollbackCount,
        parseErrors = progress.parseErrors + errors,
        fetchErrors = progress.fetchErrors + fetchErrs,
        currentSlot = entries.lastOption.map(_._1.slotNo).getOrElse(progress.currentSlot),
        peerTipSlot = effectiveTip
          .map(_.point)
          .collect { case bp: Point.BlockPoint => bp.slotNo }
          .getOrElse(progress.peerTipSlot),
        peerTipBlock = effectiveTip.map(_.blockNo).getOrElse(progress.peerTipBlock)
      )
      _ <-
        if newProgress.blocksStored / progressInterval > progress.blocksStored / progressInterval then
          onProgress(newProgress)
        else IO.unit
    yield newProgress
