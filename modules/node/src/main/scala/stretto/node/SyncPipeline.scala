package stretto.node

import cats.effect.IO
import stretto.core.Point
import stretto.core.Types.*
import stretto.network.{ChainSyncClient, ChainSyncResponse, HeaderParser, KeepAliveClient, MuxConnection}
import stretto.storage.{HeaderSyncer, RocksDbStore}

import java.nio.file.Path

/**
 * End-to-end sync pipeline: connects to a Cardano node, streams headers
 * via pipelined ChainSync N2N, parses them, and persists to RocksDB
 * in batches for maximum throughput.
 */
object SyncPipeline:

  final case class SyncProgress(
      headersStored: Long,
      rollbacks: Long,
      parseErrors: Long,
      currentSlot: SlotNo,
      peerTipSlot: SlotNo,
      peerTipBlock: BlockNo
  )

  /**
   * Run the sync pipeline.
   *
   * @param host             peer hostname
   * @param port             peer N2N port
   * @param networkMagic     network magic (1 = preprod)
   * @param dbPath           RocksDB directory
   * @param maxHeaders       max headers to sync (0 = unlimited)
   * @param onProgress       callback every `progressInterval` headers
   * @param progressInterval how often to call onProgress
   * @param pipelineWindow   number of in-flight ChainSync requests
   * @param batchSize        number of headers per RocksDB WriteBatch
   */
  def sync(
      host: String,
      port: Int,
      networkMagic: Long,
      dbPath: Path,
      maxHeaders: Long,
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int = 500,
      pipelineWindow: Int = 100,
      batchSize: Int = 50
  ): IO[SyncProgress] =
    RocksDbStore.open(dbPath).use { store =>
      val syncer = new HeaderSyncer(store)
      MuxConnection.connect(host, port, networkMagic).use { conn =>
        val client    = new ChainSyncClient(conn.mux)
        val keepAlive = new KeepAliveClient(conn.mux)
        for
          _        <- keepAlive.respondLoop.start
          knownPts <- syncer.knownPoints
          result <- pipelinedSyncLoop(
            client,
            syncer,
            knownPts,
            maxHeaders,
            onProgress,
            progressInterval,
            pipelineWindow,
            batchSize
          )
        yield result
      }
    }

  private def pipelinedSyncLoop(
      client: ChainSyncClient,
      syncer: HeaderSyncer,
      knownPoints: List[Point],
      maxHeaders: Long,
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int,
      pipelineWindow: Int,
      batchSize: Int
  ): IO[SyncProgress] =
    val headerStream = client.pipelinedHeaderStream(knownPoints, pipelineWindow)

    // Process headers in chunks for batched RocksDB writes
    headerStream.zipWithIndex
      .takeWhile { case (_, idx) => maxHeaders <= 0 || idx < maxHeaders }
      .groupWithin(batchSize, scala.concurrent.duration.FiniteDuration(100, "ms"))
      .evalScan(SyncProgress(0L, 0L, 0L, SlotNo(0L), SlotNo(0L), BlockNo(0L))) { case (progress, chunk) =>
        processBatch(syncer, progress, chunk.toList, onProgress, progressInterval)
      }
      .compile
      .last
      .map(_.getOrElse(SyncProgress(0L, 0L, 0L, SlotNo(0L), SlotNo(0L), BlockNo(0L))))
      .flatTap(_ => client.done)

  private def processBatch(
      syncer: HeaderSyncer,
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

    // Parse all forward headers
    val (parsed, errors) = forwards.foldLeft(
      (List.empty[(Point.BlockPoint, scodec.bits.ByteVector, BlockNo, stretto.core.Tip)], 0L)
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

    // Batch write all parsed headers
    val writeIO = entries match
      case Nil => IO.unit
      case _ =>
        val tip = entries.last._4
        syncer.rollForwardBatch(
          entries.map { case (pt, hdr, bn, _) => (pt, hdr, bn) },
          tip
        )

    // Handle rollbacks (just update tip for now)
    val rollbackIO = batch
      .collect { case (ChainSyncResponse.RollBackward(_, tip), _) =>
        tip
      }
      .lastOption
      .fold(IO.unit)(syncer.rollBackward)

    val newProgress = SyncProgress(
      headersStored = progress.headersStored + entries.size,
      rollbacks = progress.rollbacks + rollbackCount,
      parseErrors = progress.parseErrors + errors,
      currentSlot = entries.lastOption.map(_._1.slotNo).getOrElse(progress.currentSlot),
      peerTipSlot = effectiveTip
        .map(_.point)
        .collect { case bp: Point.BlockPoint =>
          bp.slotNo
        }
        .getOrElse(progress.peerTipSlot),
      peerTipBlock = effectiveTip.map(_.blockNo).getOrElse(progress.peerTipBlock)
    )

    val reportIO =
      if newProgress.headersStored / progressInterval > progress.headersStored / progressInterval then
        onProgress(newProgress)
      else IO.unit

    writeIO *> rollbackIO *> reportIO.as(newProgress)
