package stretto.node

import cats.effect.IO
import stretto.core.Point
import stretto.core.Types.*
import stretto.network.{ChainSyncClient, ChainSyncResponse, HeaderParser, MuxConnection}
import stretto.storage.{HeaderSyncer, RocksDbStore}

import java.nio.file.Path

/**
 * End-to-end sync pipeline: connects to a Cardano node, streams headers
 * via ChainSync N2N, parses them, and persists to RocksDB.
 *
 * Reports progress via a callback.
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
   * Run the sync pipeline for a given number of headers.
   *
   * @param host         peer hostname
   * @param port         peer N2N port
   * @param networkMagic network magic (1 = preprod)
   * @param dbPath       RocksDB directory
   * @param maxHeaders   max headers to sync (0 = unlimited)
   * @param onProgress   callback every `progressInterval` headers
   * @param progressInterval how often to call onProgress
   */
  def sync(
      host: String,
      port: Int,
      networkMagic: Long,
      dbPath: Path,
      maxHeaders: Long,
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int = 500
  ): IO[SyncProgress] =
    RocksDbStore.open(dbPath).use { store =>
      val syncer = new HeaderSyncer(store)
      MuxConnection.connect(host, port, networkMagic).use { conn =>
        val client = new ChainSyncClient(conn.mux)
        for
          // Resume from stored tip or start from origin
          knownPts <- syncer.knownPoints
          _        <- client.findIntersect(knownPts)

          // Track progress
          result <- syncLoop(
            client,
            syncer,
            maxHeaders,
            onProgress,
            progressInterval
          )
        yield result
      }
    }

  private def syncLoop(
      client: ChainSyncClient,
      syncer: HeaderSyncer,
      maxHeaders: Long,
      onProgress: SyncProgress => IO[Unit],
      progressInterval: Int
  ): IO[SyncProgress] =
    def go(
        stored: Long,
        rollbacks: Long,
        errors: Long,
        currentSlot: SlotNo,
        peerTipSlot: SlotNo,
        peerTipBlock: BlockNo,
        blockNo: Long
    ): IO[SyncProgress] =
      if maxHeaders > 0 && stored >= maxHeaders then
        val progress = SyncProgress(stored, rollbacks, errors, currentSlot, peerTipSlot, peerTipBlock)
        client.done *> IO.pure(progress)
      else
        client.requestNext.flatMap {
          case ChainSyncResponse.RollForward(header, tip) =>
            HeaderParser.parse(header) match
              case Right(meta) =>
                val point: Point.BlockPoint = Point.BlockPoint(meta.slotNo, meta.blockHash)
                val bn                      = BlockNo(blockNo)
                syncer.rollForward(point, header, bn, tip) *> {
                  val newStored = stored + 1
                  val tipSlot = tip.point match
                    case Point.Origin         => SlotNo(0L)
                    case bp: Point.BlockPoint => bp.slotNo
                  val report =
                    if newStored % progressInterval == 0 then
                      onProgress(
                        SyncProgress(newStored, rollbacks, errors, meta.slotNo, tipSlot, tip.blockNo)
                      )
                    else IO.unit
                  report *> go(newStored, rollbacks, errors, meta.slotNo, tipSlot, tip.blockNo, blockNo + 1)
                }
              case Left(_) =>
                // Parse error — skip this header but keep going
                go(stored, rollbacks, errors + 1, currentSlot, peerTipSlot, peerTipBlock, blockNo + 1)

          case ChainSyncResponse.RollBackward(_, tip) =>
            val tipSlot = tip.point match
              case Point.Origin         => SlotNo(0L)
              case bp: Point.BlockPoint => bp.slotNo
            syncer.rollBackward(tip) *>
              go(stored, rollbacks + 1, errors, currentSlot, tipSlot, tip.blockNo, blockNo)
        }

    go(0L, 0L, 0L, SlotNo(0L), SlotNo(0L), BlockNo(0L), 1L)
