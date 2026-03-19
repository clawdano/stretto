package stretto.node

import cats.effect.IO
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector
import stretto.core.*
import stretto.core.Types.*
import stretto.ledger.{BlockApplicator, ProtocolParameters, UtxoState}
import stretto.serialization.BlockDecoder
import stretto.storage.RocksDbStore

/**
 * IO-based ledger state that applies decoded blocks and writes UTxO deltas
 * to RocksDB. Bridges the sync pipeline (raw block bytes) and the pure
 * BlockApplicator.
 *
 * Runs in permissive mode: logs errors but never rejects blocks.
 *
 * Reference: Shelley formal spec §9 (UTxO transition)
 */
final class LedgerState(store: RocksDbStore):

  private val logger = Slf4jLogger.getLoggerFromName[IO]("stretto.node.LedgerState")

  /**
   * Decode a raw block, compute its UTxO delta, and persist to RocksDB atomically.
   *
   * @param blockData raw era-wrapped block CBOR
   * @param blockNo   the block number (for height tracking and logging)
   */
  def applyBlock(blockData: ByteVector, blockNo: BlockNo): IO[Unit] =
    IO(BlockDecoder.decode(blockData)).flatMap {
      case Left(_) =>
        // Skip blocks that can't be decoded (e.g., malformed CBOR)
        IO.unit
      case Right(block) =>
        applyDecodedBlock(block, blockNo)
    }

  private def applyDecodedBlock(block: Block, blockNo: BlockNo): IO[Unit] =
    block match
      case Block.ByronEbBlock(_, _) =>
        // EBBs have no transactions — just update height
        store.putLedgerHeight(blockNo)

      case Block.ByronBlock(_, body, _) =>
        if body.txPayload.isEmpty then store.putLedgerHeight(blockNo)
        else
          val allInputs = body.txPayload.flatMap(tx => tx.inputs.map(i => TxInput(i.txId, i.index)))
          lookupUtxos(allInputs).flatMap { utxoMap =>
            val state    = UtxoState(utxoMap)
            val result   = BlockApplicator.apply(state, block)
            val consumed = allInputs.filter(utxoMap.contains)
            val produced = body.txPayload.flatMap { tx =>
              tx.outputs.zipWithIndex.map { case (out, idx) =>
                (TxInput(tx.txId, idx.toLong), TxOutput(out.address, OutputValue.PureAda(out.amount)))
              }
            }
            logErrors(blockNo, result) *>
              store.applyUtxoDeltaWithHeight(consumed, produced, blockNo)
          }

      case Block.ShelleyBlock(era, header, txBodies, _, _, _) =>
        if txBodies.isEmpty then store.putLedgerHeight(blockNo)
        else
          val allInputs = txBodies.flatMap(_.inputs)
          lookupUtxos(allInputs).flatMap { utxoMap =>
            val state    = UtxoState(utxoMap)
            val result   = BlockApplicator.apply(state, block, header.slotNo, Some(ProtocolParameters.forEra(era)))
            val consumed = allInputs.filter(utxoMap.contains)
            val produced = txBodies.flatMap { tx =>
              val txHash = TxHash(Hash32.unsafeFrom(Crypto.blake2b256(tx.rawCbor)))
              tx.outputs.zipWithIndex.map { case (output, idx) =>
                (TxInput(txHash, idx.toLong), output)
              }
            }
            logErrors(blockNo, result) *>
              store.applyUtxoDeltaWithHeight(consumed, produced, blockNo)
          }

  /** Look up multiple UTxOs from storage, returning a map of found entries. */
  private def lookupUtxos(inputs: Vector[TxInput]): IO[Map[TxInput, TxOutput]] =
    // Deduplicate inputs for efficiency
    val unique = inputs.distinct
    unique.toList
      .traverse(input => store.getUtxo(input).map(opt => input -> opt))
      .map(_.collect { case (input, Some(output)) => input -> output }.toMap)

  private def logErrors(blockNo: BlockNo, result: BlockApplicator.ApplyResult): IO[Unit] =
    if result.errors.nonEmpty then
      // Sample logging: only log every 10000th block during bulk sync to avoid noise
      if blockNo.blockNoValue % 10000 == 0 then
        logger.debug(
          s"Block ${blockNo.blockNoValue}: ${result.txsProcessed} txs, " +
            s"${result.errors.size} validation errors (permissive)"
        )
      else IO.unit
    else IO.unit
