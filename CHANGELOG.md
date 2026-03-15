# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0-SNAPSHOT] - 2026-03-15

### Added
- **Block model & decoder** — ADTs for all eras (Byron EBB, Byron main, Shelley through Conway) with CBOR decoder supporting indefinite-length encoding, tx hash computation via blake2b256
- **Block decoder conformance tests** — 15 tests using Pallas (Rust) test fixtures, verified tx counts, tx hashes, and fees across all 7 eras
- **Block sync pipeline** — `sync-blocks` command downloads full blocks via BlockFetch alongside headers from ChainSync, storing both atomically in RocksDB
- **Storage: blocks column family** — new `blocks` CF (index 4) for full block bodies, with `putBlock`, `getBlock`, `putBatchWithBlocks` methods
- **BlockSyncer** — protocol-level adapter for combined header+block batch persistence with rollback support
- **BlockSyncPipeline** — end-to-end pipeline: ChainSync headers → batch 50 → BlockFetch range → atomic persist, with progress reporting (blocks stored, MB/s throughput, fetch errors)
- **CLI** — `stretto sync-blocks` command with `--network`, `--peer`, `--db`, `--max-blocks`, `--magic` options
- **CLI** — `stretto sync-headers` command with `--network`, `--peer`, `--db`, `--max-headers`, `--magic` options
- **Auto-reconnect** — automatic retry with backoff on connection loss (up to 10 retries)
- **Multi-network support** — mainnet, preprod, preview presets with default relay peers
- **BlockFetch N2N mini-protocol** — all 6 message types (MsgRequestRange, MsgClientDone, MsgStartBatch, MsgNoBlocks, MsgBlock, MsgBatchDone) with streaming client
- **ChainSync N2N mini-protocol** — all 8 message types with pipelined client (100 in-flight sliding window, ~6,600 headers/sec)
- **Ouroboros Handshake** — N2N protocol versions 11-14 with automatic version negotiation
- **Mux framing** — per-protocol ChannelBuffer with CBOR-aware segment reassembly
- **HeaderParser** — multi-era header parsing (Byron EBB, Byron main, Shelley through Conway)
- **RocksDB storage** — chain store with 3 column families (headers, meta, by_height), atomic batch writes
- **SyncPipeline** — end-to-end pipelined header sync with batched RocksDB persistence
- **Core types** — opaque types for SlotNo, BlockNo, Hash32, Hash28, BlockHeaderHash, TxHash, EpochNo
- **CBOR codecs** — low-level CBOR primitives and Cardano-specific codecs (scodec-based)
- **Structured logging** — log4cats with throughput stats and progress reporting
- **CI pipeline** — GitHub Actions with formatting checks and test suite

### Fixed
- **Mux write contention** — add `Semaphore` to serialize concurrent socket writes from ChainSync + BlockFetch, preventing frame interleaving
- **KeepAlive support** — respond to peer KeepAlive pings to prevent timeout disconnections during large block downloads
- **Byron header hash** — prepend `0x82 + sub_tag` before hashing to match cardano-ledger-byron's `wrapBoundaryBytes`/`wrapHeaderBytes`
- **Preview network** — extend handshake to propose version 14 (preview nodes require v14+)

### Testing
- 259 tests total (250 passing, 9 ignored integration tests)
- Conformance test vectors sourced from ouroboros-network CDDL specs and Pallas (Rust)
- Round-trip codec tests for all protocol messages (ChainSync, BlockFetch, Handshake, MuxFrame)
- Low-level CBOR primitive tests (uint, bstr, array, map, tag, bool)
- Cardano-specific codec tests (hash32, slotNo, blockNo, multiEraBlock)

### Verified
- **Full mainnet header sync** — 13,162,007 headers from genesis to tip @ 4,728 hdr/s in ~29 min, zero errors, all eras (Byron→Conway)
- **Full preprod header sync** — 4,511,000 headers from genesis to tip @ 9,742 hdr/s in ~8 min, zero errors
- **Preview header sync** — partial sync via public relay (relay disconnects; works with local node)
- **BlockFetch** — Byron blocks successfully fetched from mainnet
