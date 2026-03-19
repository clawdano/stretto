package stretto.storage

import cats.effect.{IO, Resource}
import org.rocksdb.*
import scodec.bits.ByteVector
import stretto.core.{OutputValue, Point, Tip, TxInput, TxOutput}
import stretto.core.Types.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * RocksDB-backed chain store.
 *
 * Uses column families to separate concerns:
 *   - "headers"  : point key → raw block header bytes
 *   - "meta"     : string keys → chain metadata (tip, etc.)
 *   - "by_height": blockNo (8-byte BE) → point key
 *
 * Point key format: slotNo (8-byte BE) ++ blockHash (32 bytes) = 40 bytes
 */
final class RocksDbStore private (
    db: RocksDB,
    cfHeaders: ColumnFamilyHandle,
    cfMeta: ColumnFamilyHandle,
    cfByHeight: ColumnFamilyHandle,
    cfBlocks: ColumnFamilyHandle,
    cfPointToHeight: ColumnFamilyHandle,
    cfUtxos: ColumnFamilyHandle,
    cfLedgerMeta: ColumnFamilyHandle
) extends ChainStore:

  // ---------------------------------------------------------------------------
  // Key encoding
  // ---------------------------------------------------------------------------

  private def pointKey(p: Point.BlockPoint): Array[Byte] =
    val buf  = new Array[Byte](40)
    val slot = p.slotNo.value
    buf(0) = (slot >>> 56).toByte
    buf(1) = (slot >>> 48).toByte
    buf(2) = (slot >>> 40).toByte
    buf(3) = (slot >>> 32).toByte
    buf(4) = (slot >>> 24).toByte
    buf(5) = (slot >>> 16).toByte
    buf(6) = (slot >>> 8).toByte
    buf(7) = slot.toByte
    System.arraycopy(p.blockHash.toHash32.hash32Bytes.toArray, 0, buf, 8, 32)
    buf

  private def heightKey(blockNo: BlockNo): Array[Byte] =
    val n = blockNo.blockNoValue
    Array(
      (n >>> 56).toByte,
      (n >>> 48).toByte,
      (n >>> 40).toByte,
      (n >>> 32).toByte,
      (n >>> 24).toByte,
      (n >>> 16).toByte,
      (n >>> 8).toByte,
      n.toByte
    )

  private def decodePointKey(key: Array[Byte]): Point.BlockPoint =
    val slot =
      ((key(0).toLong & 0xff) << 56) |
        ((key(1).toLong & 0xff) << 48) |
        ((key(2).toLong & 0xff) << 40) |
        ((key(3).toLong & 0xff) << 32) |
        ((key(4).toLong & 0xff) << 24) |
        ((key(5).toLong & 0xff) << 16) |
        ((key(6).toLong & 0xff) << 8) |
        (key(7).toLong & 0xff)
    val hash = ByteVector.view(key, 8, 32)
    Point.BlockPoint(SlotNo(slot), BlockHeaderHash(Hash32.unsafeFrom(hash)))

  // ---------------------------------------------------------------------------
  // Tip encoding: slotNo (8) ++ blockHash (32) ++ blockNo (8) = 48 bytes
  // ---------------------------------------------------------------------------

  private val tipKey = "tip".getBytes("UTF-8")

  private def encodeTip(tip: Tip): Array[Byte] = tip.point match
    case Point.Origin =>
      new Array[Byte](48) // all zeros = origin
    case bp: Point.BlockPoint =>
      val buf = new Array[Byte](48)
      System.arraycopy(pointKey(bp), 0, buf, 0, 40)
      val bn = tip.blockNo.blockNoValue
      buf(40) = (bn >>> 56).toByte
      buf(41) = (bn >>> 48).toByte
      buf(42) = (bn >>> 40).toByte
      buf(43) = (bn >>> 32).toByte
      buf(44) = (bn >>> 24).toByte
      buf(45) = (bn >>> 16).toByte
      buf(46) = (bn >>> 8).toByte
      buf(47) = bn.toByte
      buf

  private def decodeTip(bytes: Array[Byte]): Tip =
    val allZero = bytes.take(40).forall(_ == 0)
    if allZero then Tip.origin
    else
      val point = decodePointKey(bytes.take(40))
      val bn =
        ((bytes(40).toLong & 0xff) << 56) |
          ((bytes(41).toLong & 0xff) << 48) |
          ((bytes(42).toLong & 0xff) << 40) |
          ((bytes(43).toLong & 0xff) << 32) |
          ((bytes(44).toLong & 0xff) << 24) |
          ((bytes(45).toLong & 0xff) << 16) |
          ((bytes(46).toLong & 0xff) << 8) |
          (bytes(47).toLong & 0xff)
      Tip(point, BlockNo(bn))

  // ---------------------------------------------------------------------------
  // ChainStore implementation
  // ---------------------------------------------------------------------------

  def putHeader(point: Point.BlockPoint, header: ByteVector): IO[Unit] =
    IO {
      val key = pointKey(point)
      db.put(cfHeaders, key, header.toArray)
    }

  def getHeader(point: Point.BlockPoint): IO[Option[ByteVector]] =
    IO {
      Option(db.get(cfHeaders, pointKey(point))).map(ByteVector.view)
    }

  def putTip(tip: Tip): IO[Unit] =
    IO {
      db.put(cfMeta, tipKey, encodeTip(tip))
    }

  def getTip: IO[Option[Tip]] =
    IO {
      Option(db.get(cfMeta, tipKey)).map(decodeTip)
    }

  /** Store a header and update the height index and tip atomically. */
  def putHeaderWithMeta(
      point: Point.BlockPoint,
      header: ByteVector,
      blockNo: BlockNo,
      tip: Tip
  ): IO[Unit] =
    IO {
      val batch = new WriteBatch()
      try
        val pk = pointKey(point)
        batch.put(cfHeaders, pk, header.toArray)
        batch.put(cfByHeight, heightKey(blockNo), pk)
        batch.put(cfPointToHeight, pk, heightKey(blockNo))
        batch.put(cfMeta, tipKey, encodeTip(tip))
        db.write(new WriteOptions(), batch)
      finally batch.close()
    }

  /** Store multiple headers with metadata in a single atomic WriteBatch. */
  def putBatch(
      entries: List[(Point.BlockPoint, ByteVector, BlockNo)],
      tip: Tip
  ): IO[Unit] =
    IO {
      val batch = new WriteBatch()
      try
        entries.foreach { case (point, header, blockNo) =>
          val pk = pointKey(point)
          val hk = heightKey(blockNo)
          batch.put(cfHeaders, pk, header.toArray)
          batch.put(cfByHeight, hk, pk)
          batch.put(cfPointToHeight, pk, hk)
        }
        batch.put(cfMeta, tipKey, encodeTip(tip))
        db.write(new WriteOptions(), batch)
      finally batch.close()
    }

  def putBlock(point: Point.BlockPoint, blockData: ByteVector): IO[Unit] =
    IO {
      db.put(cfBlocks, pointKey(point), blockData.toArray)
    }

  def getBlock(point: Point.BlockPoint): IO[Option[ByteVector]] =
    IO {
      Option(db.get(cfBlocks, pointKey(point))).map(ByteVector.view)
    }

  def putBatchWithBlocks(
      entries: List[(Point.BlockPoint, ByteVector, BlockNo, ByteVector)],
      tip: Tip
  ): IO[Unit] =
    IO {
      val batch = new WriteBatch()
      try
        entries.foreach { case (point, header, blockNo, blockData) =>
          val pk = pointKey(point)
          val hk = heightKey(blockNo)
          batch.put(cfHeaders, pk, header.toArray)
          batch.put(cfBlocks, pk, blockData.toArray)
          batch.put(cfByHeight, hk, pk)
          batch.put(cfPointToHeight, pk, hk)
        }
        batch.put(cfMeta, tipKey, encodeTip(tip))
        db.write(new WriteOptions(), batch)
      finally batch.close()
    }

  /** Close all column family handles and the database. */
  private[storage] def close(): Unit =
    cfHeaders.close()
    cfMeta.close()
    cfByHeight.close()
    cfBlocks.close()
    cfPointToHeight.close()
    cfUtxos.close()
    cfLedgerMeta.close()
    db.close()

  /** Get the point stored at a given block height, if any. */
  def getPointByHeight(blockNo: BlockNo): IO[Option[Point.BlockPoint]] =
    IO {
      Option(db.get(cfByHeight, heightKey(blockNo))).map(decodePointKey)
    }

  /** Get the maximum stored block height, if any. */
  def getMaxHeight: IO[Option[BlockNo]] =
    IO {
      val it = db.newIterator(cfByHeight)
      try
        it.seekToLast()
        if it.isValid then
          val key = it.key()
          val bn =
            ((key(0).toLong & 0xff) << 56) |
              ((key(1).toLong & 0xff) << 48) |
              ((key(2).toLong & 0xff) << 40) |
              ((key(3).toLong & 0xff) << 32) |
              ((key(4).toLong & 0xff) << 24) |
              ((key(5).toLong & 0xff) << 16) |
              ((key(6).toLong & 0xff) << 8) |
              (key(7).toLong & 0xff)
          Some(BlockNo(bn))
        else None
      finally it.close()
    }

  /** O(1) reverse lookup: point → block height. */
  def getHeightByPoint(point: Point.BlockPoint): IO[Option[BlockNo]] =
    IO {
      Option(db.get(cfPointToHeight, pointKey(point))).map { bytes =>
        val bn =
          ((bytes(0).toLong & 0xff) << 56) |
            ((bytes(1).toLong & 0xff) << 48) |
            ((bytes(2).toLong & 0xff) << 40) |
            ((bytes(3).toLong & 0xff) << 32) |
            ((bytes(4).toLong & 0xff) << 24) |
            ((bytes(5).toLong & 0xff) << 16) |
            ((bytes(6).toLong & 0xff) << 8) |
            (bytes(7).toLong & 0xff)
        BlockNo(bn)
      }
    }

  def recentPoints(count: Int): IO[List[Point.BlockPoint]] =
    IO {
      val it = db.newIterator(cfByHeight)
      try
        it.seekToLast()
        val buf = List.newBuilder[Point.BlockPoint]
        var n   = 0
        while it.isValid && n < count do
          buf += decodePointKey(it.value())
          it.prev()
          n += 1
        buf.result()
      finally it.close()
    }

  // ---------------------------------------------------------------------------
  // UTxO storage: TxInput (txId 32 bytes + index 8 bytes = 40 bytes) → TxOutput CBOR
  // ---------------------------------------------------------------------------

  private def utxoKey(txId: TxHash, index: Long): Array[Byte] =
    val buf = new Array[Byte](40)
    System.arraycopy(txId.txHashToHash32.hash32Bytes.toArray, 0, buf, 0, 32)
    buf(32) = (index >>> 56).toByte
    buf(33) = (index >>> 48).toByte
    buf(34) = (index >>> 40).toByte
    buf(35) = (index >>> 32).toByte
    buf(36) = (index >>> 24).toByte
    buf(37) = (index >>> 16).toByte
    buf(38) = (index >>> 8).toByte
    buf(39) = index.toByte
    buf

  private def utxoKey(input: TxInput): Array[Byte] =
    utxoKey(input.txId, input.index)

  /** Encode a TxOutput as: address_len (4 bytes) + address + value_tag (1 byte) + coin (8 bytes) [+ assets_raw] */
  private def encodeTxOutput(output: TxOutput): Array[Byte] =
    val addrBytes = output.address.toArray
    val addrLen   = addrBytes.length
    output.value match
      case OutputValue.PureAda(coin) =>
        val buf = new Array[Byte](4 + addrLen + 1 + 8)
        buf(0) = (addrLen >>> 24).toByte
        buf(1) = (addrLen >>> 16).toByte
        buf(2) = (addrLen >>> 8).toByte
        buf(3) = addrLen.toByte
        System.arraycopy(addrBytes, 0, buf, 4, addrLen)
        buf(4 + addrLen) = 0 // PureAda tag
        val c = coin.lovelaceValue
        buf(4 + addrLen + 1) = (c >>> 56).toByte
        buf(4 + addrLen + 2) = (c >>> 48).toByte
        buf(4 + addrLen + 3) = (c >>> 40).toByte
        buf(4 + addrLen + 4) = (c >>> 32).toByte
        buf(4 + addrLen + 5) = (c >>> 24).toByte
        buf(4 + addrLen + 6) = (c >>> 16).toByte
        buf(4 + addrLen + 7) = (c >>> 8).toByte
        buf(4 + addrLen + 8) = c.toByte
        buf
      case OutputValue.MultiAsset(coin, assets) =>
        val assetsBytes = assets.toArray
        val buf         = new Array[Byte](4 + addrLen + 1 + 8 + assetsBytes.length)
        buf(0) = (addrLen >>> 24).toByte
        buf(1) = (addrLen >>> 16).toByte
        buf(2) = (addrLen >>> 8).toByte
        buf(3) = addrLen.toByte
        System.arraycopy(addrBytes, 0, buf, 4, addrLen)
        buf(4 + addrLen) = 1 // MultiAsset tag
        val c = coin.lovelaceValue
        buf(4 + addrLen + 1) = (c >>> 56).toByte
        buf(4 + addrLen + 2) = (c >>> 48).toByte
        buf(4 + addrLen + 3) = (c >>> 40).toByte
        buf(4 + addrLen + 4) = (c >>> 32).toByte
        buf(4 + addrLen + 5) = (c >>> 24).toByte
        buf(4 + addrLen + 6) = (c >>> 16).toByte
        buf(4 + addrLen + 7) = (c >>> 8).toByte
        buf(4 + addrLen + 8) = c.toByte
        System.arraycopy(assetsBytes, 0, buf, 4 + addrLen + 9, assetsBytes.length)
        buf

  private def decodeTxOutput(bytes: Array[Byte]): TxOutput =
    val addrLen =
      ((bytes(0) & 0xff) << 24) |
        ((bytes(1) & 0xff) << 16) |
        ((bytes(2) & 0xff) << 8) |
        (bytes(3) & 0xff)
    val address = ByteVector.view(bytes, 4, addrLen)
    val tag     = bytes(4 + addrLen) & 0xff
    val coinOffset = 4 + addrLen + 1
    val coin = Lovelace(
      ((bytes(coinOffset).toLong & 0xff) << 56) |
        ((bytes(coinOffset + 1).toLong & 0xff) << 48) |
        ((bytes(coinOffset + 2).toLong & 0xff) << 40) |
        ((bytes(coinOffset + 3).toLong & 0xff) << 32) |
        ((bytes(coinOffset + 4).toLong & 0xff) << 24) |
        ((bytes(coinOffset + 5).toLong & 0xff) << 16) |
        ((bytes(coinOffset + 6).toLong & 0xff) << 8) |
        (bytes(coinOffset + 7).toLong & 0xff)
    )
    if tag == 0 then TxOutput(address, OutputValue.PureAda(coin))
    else
      val assetsRaw = ByteVector.view(bytes, coinOffset + 8, bytes.length - coinOffset - 8)
      TxOutput(address, OutputValue.MultiAsset(coin, assetsRaw))

  /** Store a UTxO entry. */
  def putUtxo(txId: TxHash, index: Long, output: TxOutput): IO[Unit] =
    IO(db.put(cfUtxos, utxoKey(txId, index), encodeTxOutput(output)))

  /** Retrieve a UTxO entry. */
  def getUtxo(input: TxInput): IO[Option[TxOutput]] =
    IO(Option(db.get(cfUtxos, utxoKey(input))).map(decodeTxOutput))

  /** Delete a UTxO entry. */
  def deleteUtxo(input: TxInput): IO[Unit] =
    IO(db.delete(cfUtxos, utxoKey(input)))

  /**
   * Apply a UTxO delta atomically: delete consumed inputs, add produced outputs.
   * Uses WriteBatch for atomicity.
   */
  def applyUtxoDelta(
      consumed: Iterable[TxInput],
      produced: Iterable[(TxInput, TxOutput)]
  ): IO[Unit] =
    IO {
      val batch = new WriteBatch()
      try
        consumed.foreach(input => batch.delete(cfUtxos, utxoKey(input)))
        produced.foreach { case (input, output) =>
          batch.put(cfUtxos, utxoKey(input), encodeTxOutput(output))
        }
        db.write(new WriteOptions(), batch)
      finally batch.close()
    }

  /** Get the last applied ledger block height. */
  def getLedgerHeight: IO[Option[BlockNo]] =
    IO {
      val key = "ledger_height".getBytes("UTF-8")
      Option(db.get(cfLedgerMeta, key)).map { bytes =>
        val bn =
          ((bytes(0).toLong & 0xff) << 56) |
            ((bytes(1).toLong & 0xff) << 48) |
            ((bytes(2).toLong & 0xff) << 40) |
            ((bytes(3).toLong & 0xff) << 32) |
            ((bytes(4).toLong & 0xff) << 24) |
            ((bytes(5).toLong & 0xff) << 16) |
            ((bytes(6).toLong & 0xff) << 8) |
            (bytes(7).toLong & 0xff)
        BlockNo(bn)
      }
    }

  /** Set the last applied ledger block height. */
  def putLedgerHeight(blockNo: BlockNo): IO[Unit] =
    IO {
      val key = "ledger_height".getBytes("UTF-8")
      db.put(cfLedgerMeta, key, heightKey(blockNo))
    }

  /** Apply UTxO delta and update ledger height atomically. */
  def applyUtxoDeltaWithHeight(
      consumed: Iterable[TxInput],
      produced: Iterable[(TxInput, TxOutput)],
      blockNo: BlockNo
  ): IO[Unit] =
    IO {
      val batch = new WriteBatch()
      try
        consumed.foreach(input => batch.delete(cfUtxos, utxoKey(input)))
        produced.foreach { case (input, output) =>
          batch.put(cfUtxos, utxoKey(input), encodeTxOutput(output))
        }
        val key = "ledger_height".getBytes("UTF-8")
        batch.put(cfLedgerMeta, key, heightKey(blockNo))
        db.write(new WriteOptions(), batch)
      finally batch.close()
    }

  /** Get the count of UTxO entries (approximate, uses RocksDB estimate). */
  def getUtxoCount: IO[Long] =
    IO {
      val prop = db.getProperty(cfUtxos, "rocksdb.estimate-num-keys")
      if prop != null then prop.toLong else 0L
    }

object RocksDbStore:

  /** Load the RocksDB native library once. */
  RocksDB.loadLibrary()

  private val cfNames =
    List("default", "headers", "meta", "by_height", "blocks", "point_to_height", "utxos", "ledger_meta")

  /**
   * Open a RocksDB-backed chain store as a cats-effect Resource.
   * The database directory is created if it doesn't exist.
   */
  def open(path: Path): Resource[IO, RocksDbStore] =
    Resource.make(acquire(path))(release)

  private def acquire(path: Path): IO[RocksDbStore] = IO {
    Files.createDirectories(path)

    val dbOpts = new DBOptions()
      .setCreateIfMissing(true)
      .setCreateMissingColumnFamilies(true)

    val cfOpts = new ColumnFamilyOptions()
      .setCompressionType(CompressionType.LZ4_COMPRESSION)

    val cfDescriptors = cfNames
      .map(name => new ColumnFamilyDescriptor(name.getBytes("UTF-8"), cfOpts))
      .asJava

    val cfHandles = new java.util.ArrayList[ColumnFamilyHandle]()

    val db = RocksDB.open(dbOpts, path.toString, cfDescriptors, cfHandles)

    new RocksDbStore(
      db,
      cfHeaders = cfHandles.get(1),
      cfMeta = cfHandles.get(2),
      cfByHeight = cfHandles.get(3),
      cfBlocks = cfHandles.get(4),
      cfPointToHeight = cfHandles.get(5),
      cfUtxos = cfHandles.get(6),
      cfLedgerMeta = cfHandles.get(7)
    )
  }

  private def release(store: RocksDbStore): IO[Unit] =
    IO(store.close())
