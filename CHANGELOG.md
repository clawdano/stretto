# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0-SNAPSHOT] - 2026-03-16

### Added
- **N2C LocalStateQuery server** — protocol ID 7, supports Acquire/Release/Query lifecycle with GetSystemStart, GetChainBlockNo, GetChainPoint, GetCurrentEra, GetEpochNo, GetLedgerTip, GetCurrentPParams, GetEraSummaries; hardcoded Conway protocol parameters; per-network GenesisConfig (mainnet/preprod/preview)
- **N2C ChainSync relay node** — lightweight relay: syncs blocks from upstream N2N peer, serves them to local N2C clients via ChainSync (protocol ID 5)
- **N2C Handshake responder** — server-side N2C handshake with versions V16-V19 (bit-15 offset: 32784-32787), MsgProposeVersions decoder, version negotiation
- **ChainSyncServer** — per-client N2C server with cursor tracking, FindIntersect/RequestNext handling, MsgRollForward with era-wrapped blocks (no tag24), tip-following via fs2 Topic subscription
- **ChainEvent + Topic** — `ChainEvent.BlockAdded`/`RolledBack` enum, fs2 `Topic[IO, ChainEvent]` for zero-overhead tip broadcast to N2C subscribers
- **N2CListener** — TCP server accepting N2C connections, `Semaphore[IO]` gating max 32 clients (configurable), per-client handler fibers
- **N2CConnectionHandler** — per-client lifecycle: MuxDemuxer creation, N2C handshake (30s timeout), ChainSync server loop
- **RelayNode** — orchestrator: RocksDB + Topic + upstream N2N sync + N2C listener running concurrently, infinite upstream auto-reconnect
- **BlockSyncPipeline.syncWithTopic** — variant that publishes ChainEvents to Topic after each batch write, used by relay node
- **RocksDbStore** — `getPointByHeight`, `getMaxHeight` methods for serving blocks by chain height
- **CLI** — `stretto relay` command with `--network`, `--peer`, `--listen`, `--db`, `--max-clients`, `--magic` options; binds 127.0.0.1 by default
- **Transaction validation** — `TransactionValidation` with 6 rules: value preservation (Shelley §9.1), fee adequacy (§10.2), TTL expiry (§8.1), min UTxO (§9.2), max tx size, input existence
- **Protocol parameters** — `ProtocolParameters` per era (Byron through Conway) with minFeeA/B, minUtxoValue, coinsPerUtxoByte, maxTxSize
- **BlockApplicator** — now runs validation during block application (permissive mode: logs errors, continues processing)
- **UTxO state & block applicator** — `UtxoState` (unspent output map), `BlockApplicator` applies blocks to UTxO (removes spent inputs, adds new outputs), works across all eras, 13 tests
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
- **KeepAlive client (initiator)** — TCP connection initiator now correctly sends periodic MsgKeepAlive pings (default 10s, configurable via `--keep-alive-interval`), preventing peer timeout disconnections. Previous implementation was a responder waiting for pings that never arrived.
- **Byron header hash** — prepend `0x82 + sub_tag` before hashing to match cardano-ledger-byron's `wrapBoundaryBytes`/`wrapHeaderBytes`
- **Preview network** — extend handshake to propose version 14 (preview nodes require v14+)

### Testing
- 322 tests total (313 passing, 9 ignored integration tests)
- LocalStateQuery: 17 tests (GenesisConfig epoch calculation, CBOR message encoding, protocol IDs)
- N2C handshake: 11 tests (version constants, encoding, decode, negotiation)
- ChainEvent topic: 3 tests (publish/subscribe, multi-subscriber broadcast)
- ChainSync server: 8 tests (CBOR encoding, protocol IDs, N2C format verification)
- RelayNode config: 2 tests
- Conformance test vectors sourced from ouroboros-network CDDL specs and Pallas (Rust)
- Round-trip codec tests for all protocol messages (ChainSync, BlockFetch, Handshake, MuxFrame)
- Low-level CBOR primitive tests (uint, bstr, array, map, tag, bool)
- Cardano-specific codec tests (hash32, slotNo, blockNo, multiEraBlock)

### Verified
- **Full mainnet header sync** — 13,162,007 headers from genesis to tip @ 4,728 hdr/s in ~29 min, zero errors, all eras (Byron→Conway)
- **Full preprod header sync** — 4,511,000 headers from genesis to tip @ 9,742 hdr/s in ~8 min, zero errors
- **Preview header sync** — partial sync via public relay (relay disconnects; works with local node)
- **BlockFetch** — Byron blocks successfully fetched from mainnet
