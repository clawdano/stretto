# Stretto — Roadmap

> Detailed progress tracking for the Stretto Cardano node implementation.
> Last updated: 2026-03-14

## Module Status

### Core (`modules/core/`) — :white_check_mark: Complete

Domain types and primitives that all other modules depend on.

- [x] Opaque types: `SlotNo`, `BlockNo`, `EpochNo`, `TxIndex`, `Lovelace`
- [x] Hash types: `Hash32`, `Hash28` with size validation
- [x] Derived hashes: `BlockHeaderHash`, `TxHash`, `ScriptHash`, `PolicyId`, `PoolId`, `VrfKeyHash`, `KesKeyHash`
- [x] `Point` ADT: `Origin | BlockPoint(slot, hash)` with ordering
- [x] `Tip`: chain tip type (point + block number)
- [x] `Era` enum: Byron through Conway
- [x] `Address` types: Shelley (payment + stake) and Byron
- [x] `Value`: multi-asset value with arithmetic
- [x] `NetworkId`: Mainnet / Testnet
- [x] 79 unit tests (Types, Value, Point)

### Serialization (`modules/serialization/`) — :white_check_mark: Complete

CBOR encoding/decoding built on scodec.

- [x] Low-level CBOR primitives: uint, negint, byte string, text, array, map, tag, bool, null
- [x] Cardano codecs: hash28, hash32, slotNo, blockNo, epochNo, coin
- [x] Multi-era block codec: `[era_tag, block_bytes]`
- [x] Mux frame codec: 8-byte header + payload
- [ ] Full block header CBOR decoders (per era)
- [ ] Transaction body CBOR decoders (per era)
- [ ] Unit tests for serialization codecs

### Network (`modules/network/`) — :yellow_circle: In Progress

Ouroboros miniprotocol implementation (pure Scala, no external deps).

- [x] Mux framing: `MuxFrame` encode/decode, `MuxDemuxer` with background fiber
- [x] Mini-protocol IDs: all N2N and N2C protocols defined
- [x] Handshake: `MsgProposeVersions` / `MsgAcceptVersion` / `MsgRefuse`
- [x] N2N version negotiation (versions 11-13, Conway era)
- [x] `MuxConnection`: TCP connect → handshake → multiplexed connection
- [x] ChainSync N2N: all 8 message types with CBOR encode/decode
- [x] `ChainSyncClient`: state machine (findIntersect, requestNext, headerStream)
- [x] Integration tests: handshake + ChainSync verified against live preprod node
- [x] Stress test: 1000 headers synced, 0 decode errors, ~200 headers/sec
- [ ] BlockFetch mini-protocol
- [ ] TxSubmission mini-protocol
- [ ] KeepAlive mini-protocol
- [ ] PeerSharing mini-protocol
- [ ] N2C (node-to-client) protocols
- [ ] Connection manager (reconnect, peer rotation)

### Storage (`modules/storage/`) — :construction: Next Up

Persistent chain database.

- [ ] RocksDB integration (rocksdbjni)
- [ ] Block header store (keyed by slot + hash)
- [ ] Full block store
- [ ] UTxO set persistence
- [ ] Chain index (tip tracking, fork management)
- [ ] Crash recovery and consistency checks
- [ ] Snapshot and restore

### Ledger (`modules/ledger/`) — :x: Not Started

Transaction validation and UTxO rules for all eras.

- [ ] Transaction types (inputs, outputs, fees, metadata)
- [ ] UTxO state model
- [ ] Byron-era validation rules
- [ ] Shelley validation rules (formal spec §13)
- [ ] Allegra/Mary extensions (timelocks, multi-asset)
- [ ] Alonzo extensions (Plutus scripts, datums, redeemers)
- [ ] Babbage extensions (reference inputs, inline datums)
- [ ] Conway extensions (governance actions, DReps)
- [ ] Plutus script evaluation via scalus
- [ ] Stake pool operations
- [ ] Conformance tests against Haskell node

### Consensus (`modules/consensus/`) — :x: Not Started

Ouroboros Praos consensus and chain selection.

- [ ] VRF proof verification
- [ ] KES signature verification
- [ ] Slot leader eligibility check
- [ ] Chain selection (prefer longer chain, density tie-breaking)
- [ ] Epoch transitions and stake snapshots
- [ ] Operational certificate validation

### Mempool (`modules/mempool/`) — :x: Not Started

Transaction mempool management.

- [ ] Mempool data structure (bounded, ordered)
- [ ] Transaction submission and validation
- [ ] Fee-based prioritization
- [ ] Size-based eviction policy
- [ ] Double-spend / conflict detection
- [ ] Batch selection for block production

### Node (`modules/node/`) — :x: Not Started

Main node assembly and orchestration.

- [ ] Configuration (YAML/TOML)
- [ ] Main entry point and startup sequence
- [ ] Module wiring (network + storage + ledger + consensus)
- [ ] Sync state machine (initial sync → steady state)
- [ ] Metrics endpoint (http4s + Prometheus)
- [ ] Logging (log4cats + logback)
- [ ] Graceful shutdown

### CLI (`modules/cli/`) — :x: Not Started

Command-line interface.

- [ ] Argument parsing
- [ ] `start` command (launch node)
- [ ] `query` command (tip, UTxO, protocol params)
- [ ] `submit` command (submit transaction)
- [ ] Output formatting (JSON, text)

---

## Critical Path to Syncing Node

```
core ──► serialization ──► network (Handshake + ChainSync) ──► storage (RocksDB)
                                        │                          │
                                        ▼                          ▼
                                   BlockFetch ──────────────► ledger (validate)
                                                                   │
                                                                   ▼
                                                            consensus (select)
                                                                   │
                                                                   ▼
                                                              node (assemble)
```

**Current position:** Core + serialization + ChainSync done. Next: storage (RocksDB) → BlockFetch → ledger.
