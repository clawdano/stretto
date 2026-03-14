# Cardano Node Landscape Analysis

> Research document for the Cardano Scala Node project.
> Compiled: 2026-03-14

## Table of Contents
1. [Alternative Node Implementations](#alternative-node-implementations)
2. [JVM Ecosystem Libraries](#jvm-ecosystem-libraries)
3. [Official Specifications & Documentation](#official-specifications--documentation)
4. [Conformance Testing](#conformance-testing)
5. [Gap Analysis: What We Must Build](#gap-analysis-what-we-must-build)

---

## 1. Alternative Node Implementations

### 1.1 Haskell Node (Reference Implementation)
- **Repo:** https://github.com/IntersectMBO/cardano-node
- **Language:** Haskell
- **Status:** Production, runs mainnet
- **Architecture:** Three major subsystems in separate repos:
  - `cardano-ledger` — ledger rules, formal specs, CDDL schemas
  - `ouroboros-consensus` — consensus layer, chain selection, mempool, hard fork combinator
  - `ouroboros-network` — typed-protocols framework, all miniprotocols, multiplexer
- **Key insight:** The formal specs (LaTeX + Agda) are the authoritative source for ledger rules, not the Haskell code itself

### 1.2 Amaru (Rust — PRAGMA)
- **Repo:** https://github.com/pragma-org/amaru
- **Language:** Rust
- **Organization:** PRAGMA (pragma-org), led by KtorZ (Matthias Benkort)
- **Status:** Exploratory/alpha. Can function as a relay node but cannot yet produce blocks.
- **Architecture:** Built on the Pallas crate ecosystem (CBOR, networking, crypto)
- **Era support:** Focused on Babbage/Conway (current mainnet eras)
- **2026 plans:** Full block-producing node, Ouroboros Leios compatibility
- **Funding:** Cardano treasury proposal
- **Docs:** https://amaru.global/about/
- **Note:** Being used as test subject for the Antithesis testing platform

### 1.3 Dingo (Go — Blink Labs)
- **Repo:** https://github.com/blinklabs-io/dingo
- **Language:** Go
- **Organization:** Blink Labs (blinklabs-io)
- **Status:** Active development, funded through Catalyst and treasury proposals
- **Architecture:** Built on gouroboros (Go Ouroboros protocol library)
- **Features:** 41 UTXO validation rules, Plutus smart contract execution, full chain sync
- **Related tools:**
  - gouroboros: https://github.com/blinklabs-io/gouroboros (Go miniprotocol library)
  - nview: node monitoring TUI (supports Dingo, Amaru, and Haskell node)
- **Treasury proposal:** https://forum.cardano.org/t/dingo-treasury-proposal-2026-building-a-production-ready-cardano-block-producer-in-go/153500

### 1.4 Torsten (Rust — Michael Fazio / Sandstone Pool)
- **Repo:** https://github.com/michaeljfazio/torsten
- **Language:** Rust (99.2%)
- **Author:** Michael Fazio (Sandstone Pool)
- **License:** MIT
- **Status:** Alpha — "ledger validation is incomplete and may accept invalid transactions or reject valid ones"
- **Architecture:** 10-crate Cargo workspace:
  | Crate | Purpose |
  |-------|---------|
  | torsten-primitives | Core types, protocol parameters (Byron–Conway) |
  | torsten-crypto | Ed25519, VRF, KES |
  | torsten-serialization | CBOR via pallas |
  | torsten-network | Ouroboros miniprotocols, peer networking |
  | torsten-consensus | Ouroboros Praos |
  | torsten-ledger | UTxO management, tx validation |
  | torsten-mempool | Transaction mempool |
  | torsten-storage | ChainDB (ImmutableDB + VolatileDB) |
  | torsten-node | Main binary, configuration |
  | torsten-cli | cardano-cli compatible interface |
- **Notable features:**
  - Pipelined chain sync (~275 blocks/s)
  - Mithril snapshot import (4M blocks in ~2 min)
  - Plutus V1/V2/V3 support
  - Conway governance (DRep, voting)
  - LSM-backed UTxO storage, io_uring async I/O
  - Prometheus metrics
- **Key concern:** The tweet about "3 critical conformance issues in 5 minutes" was likely about this or a similar project

### 1.5 Acropolis (Rust — IOG/IOHK)
- **Repo:** https://github.com/input-output-hk/acropolis
- **Language:** Rust
- **Organization:** Input Output (IOG/IOHK)
- **Status:** In development, beta programs expected late 2025, full implementation 2026
- **Architecture:** Modular microservice architecture using the Caryatid framework
  - Uses message bus (RabbitMQ) for inter-module communication
  - Designed for language flexibility — modules can be in different languages
- **Goals:** Transform Cardano node from monolithic Haskell to modular Rust-based architecture
- **Roadmap:** Data node → validation → full Praos block production → replace legacy tooling
- **Docs:** https://www.iog.io/news/cardano-nodes-evolution-towards-diversity-and-modular-design

### 1.6 Antithesis (Testing Platform — Cardano Foundation)
- **Repo:** https://github.com/cardano-foundation/cardano-node-antithesis
- **NOT a node implementation** — it's a testing/fuzzing platform
- **Purpose:** Deterministic hypervisor that tests Cardano nodes by emulating OS and network complexity
- **Capabilities:** Property-based testing, fuzzing, deterministic bug reproduction
- **Results:** Successfully identified both known and unknown bugs in cardano-node
- **Used with:** Haskell node and Amaru
- **Blog:** https://cardanofoundation.org/blog/improving-cardano-antithesis
- **Relevance:** We should aim to run our Scala node through Antithesis for conformance validation

---

## 2. JVM Ecosystem Libraries

### 2.1 bloxbean/yaci — Ouroboros Miniprotocols (Java)
- **Repo:** https://github.com/bloxbean/yaci
- **Language:** Java
- **License:** MIT
- **Maturity:** Medium-High, used in production (Yaci Store, Yaci DevKit)
- **Protocols implemented:**
  - **Node-to-Client (N2C):** Chain Sync, Local Tx Submission, Local State Query, Local Tx Monitor
  - **Node-to-Node (N2N):** Chain Sync, Block Fetch, Tx Submission, Handshake
  - **Multiplexer:** Full Ouroboros multiplexer over single connection
- **Block decoding:** All eras (Byron through Conway)
- **Connection:** Unix socket (N2C) and TCP (N2N)
- **API style:** Reactive/event-driven (Project Reactor)
- **LIMITATION:** Client/initiator side only — a full node also needs server/responder side
- **Related:**
  - yaci-store: https://github.com/bloxbean/yaci-store (indexer)
  - yaci-devkit: https://github.com/bloxbean/yaci-devkit (local dev environment)

### 2.2 bloxbean/cardano-client-lib — Cardano SDK (Java)
- **Repo:** https://github.com/bloxbean/cardano-client-lib
- **Language:** Java
- **License:** MIT
- **Maturity:** Production-ready, actively maintained
- **Provides:**
  - Full CBOR serialization/deserialization for Cardano types
  - Transaction building (payments, minting, Plutus scripts V1/V2/V3)
  - Address handling (Shelley/Byron, BIP32-Ed25519)
  - Coin selection algorithms
  - CIP support (CIP-20, CIP-25, CIP-30, CIP-68)
  - Backend providers (Blockfrost, Koios, Ogmios)
- **Relevance:** CBOR codecs and type definitions are reusable; no miniprotocol support

### 2.3 nau/scalus — Plutus Evaluator (Scala 3)
- **Repo:** https://github.com/nau/scalus
- **Language:** Scala 3
- **Author:** Alexander Nemish (nau/lanter)
- **License:** Apache 2.0
- **Maturity:** Medium-High, used in production
- **Provides:**
  - Full CEK machine implementation (Plutus Core evaluator)
  - Plutus V1, V2, V3 support
  - UPLC/Flat codec (on-chain serialization format)
  - All Plutus builtins (crypto, BLS12-381, bytestring ops, etc.)
  - Execution budgets (CPU/memory) with cost model matching on-chain
  - Scala-to-Plutus compiler (ScalusPlugin)
- **Relevance:** CRITICAL — directly integrable as our Plutus script validation engine

### 2.4 Summary Matrix

| Library | Lang | Networking | CBOR | Tx Building | Plutus Eval | Node Relevance |
|---------|------|-----------|------|------------|-------------|----------------|
| yaci | Java | Yes (N2C+N2N) | Yes (all eras) | No | No | Network layer |
| cardano-client-lib | Java | No | Yes (full) | Yes | Partial | Types, codecs |
| scalus | Scala 3 | No | UPLC/Flat | No | Yes (full) | Script validation |

---

## 3. Official Specifications & Documentation

### 3.1 Formal Ledger Specifications

All formal specs are in: https://github.com/IntersectMBO/cardano-ledger

| Era | Path | Key Contents |
|-----|------|-------------|
| Byron | `eras/byron/ledger/formal-spec` | Byron ledger rules |
| Shelley | `eras/shelley/formal-spec` | Core delegation, rewards, UTxO rules |
| Allegra | `eras/allegra/formal-spec` | Time locks (delta to Shelley) |
| Mary | `eras/mary/formal-spec` | Multi-asset / native tokens |
| Alonzo | `eras/alonzo/formal-spec` | Plutus integration, script validation |
| Babbage | `eras/babbage/formal-spec` | Plutus V2, reference inputs/scripts, inline datums |
| Conway | `eras/conway/formal-spec` | On-chain governance (CIP-1694) |

**Agda mechanized specs:** https://github.com/IntersectMBO/formal-ledger-specifications

### 3.2 CDDL Schemas (Binary Encoding)
Located in cardano-ledger under `eras/*/impl/cddl-files/*.cddl`
- These are the authoritative source for CBOR serialization of blocks, transactions, headers, certificates, etc.

### 3.3 Ouroboros Consensus
- **Repo:** https://github.com/IntersectMBO/ouroboros-consensus
- **Docs:** https://ouroboros-consensus.cardano.intersectmbo.org/
- **Key papers:**
  - Ouroboros Classic: https://eprint.iacr.org/2016/889.pdf
  - Ouroboros Praos: https://eprint.iacr.org/2017/573.pdf
  - Ouroboros Genesis: https://eprint.iacr.org/2018/378.pdf
  - Ouroboros BFT: https://eprint.iacr.org/2018/1049.pdf
  - Ouroboros Leios: https://eprint.iacr.org/2024/773.pdf

### 3.4 Network Protocols
- **Repo:** https://github.com/IntersectMBO/ouroboros-network
- **Typed protocols framework:** State-machine-based protocol definitions with session types
- **N2N protocols:** Chain Sync (headers), Block Fetch, Tx Submission, Keep Alive
- **N2C protocols:** Chain Sync (full blocks), Local Tx Submission, Local State Query, Local Tx Monitor
- **Multiplexer:** 32-byte frame headers (timestamp, miniprotocol ID, payload length)
- **Handshake:** Version negotiation with network magic

### 3.5 Plutus
- **Repo:** https://github.com/IntersectMBO/plutus
- Contains Plutus Core language spec, CEK machine, cost models, builtin functions
- Metatheory in Agda: `plutus-metatheory/`

### 3.6 Key CIPs for Node Implementation
| CIP | Title | Relevance |
|-----|-------|-----------|
| CIP-0009 | Protocol Parameters (Shelley) | Updatable params |
| CIP-0019 | Cardano Addresses | Address formats |
| CIP-0032 | Inline Datums | Babbage feature |
| CIP-0033 | Reference Scripts | Babbage feature |
| CIP-0040 | Collateral Output | Babbage feature |
| CIP-1694 | On-Chain Governance | Conway era — DReps, CC, treasury |
| CIP-1852 | HD Wallets | Key derivation paths |

Full CIP repo: https://github.com/cardano-foundation/CIPs

---

## 4. Conformance Testing

### 4.1 The Problem
The November 2025 "Poison Piggy" incident demonstrated the critical importance of conformance:
- A single malformed transaction caused the network to split in two
- 846 blocks produced on the invalid "pig chain" vs ~13,900 on the canonical chain
- ~3.3% of transactions were temporarily on the wrong fork
- Self-healed after ~14 hours, but highlighted that validation disagreements = network splits

### 4.2 Existing Conformance Resources
- **cardano-ledger-conformance** — Haskell package that tests production code against Agda-extracted specifications
- **Per-era test suites** in the cardano-ledger repo (Shelley, Alonzo, Conway test suites)
- **Antithesis platform** — deterministic fuzzing/testing for Cardano nodes
- **Formal ledger specs in Agda** — executable, can extract Haskell for comparison

### 4.3 Our Conformance Strategy
1. **Unit-level:** Each validation rule tested against known-good/known-bad test vectors
2. **Property-based:** ScalaCheck generators for transactions/blocks, compare validation results with Haskell node
3. **Integration:** Run our node alongside Haskell nodes in a private devnet, compare tip selection
4. **Fuzzing:** Use Antithesis platform (or similar) to find edge cases
5. **Mainnet replay:** Replay historical mainnet blocks and verify identical state transitions

---

## 5. Gap Analysis: What We Must Build

### What We Get From Libraries
| Component | Library | Coverage |
|-----------|---------|----------|
| N2N/N2C miniprotocols (client side) | yaci | Good — all protocols implemented |
| CBOR serialization | yaci + cardano-client-lib | Good — all eras |
| Plutus script evaluation | scalus | Good — V1/V2/V3 with cost model |
| Crypto primitives | cardano-client-lib | Partial — Ed25519, some key derivation |

### What We Must Build From Scratch
| Component | Complexity | Spec Source |
|-----------|-----------|-------------|
| **Ouroboros Praos consensus** | Very High | Praos paper + ouroboros-consensus docs |
| **Ledger validation rules (all eras)** | Very High | Formal ledger specs (LaTeX + Agda) |
| **Hard fork combinator** | High | ouroboros-consensus |
| **Chain selection (chain quality, density)** | High | Praos paper |
| **VRF leader election** | High | Praos paper |
| **KES key management** | High | Praos paper |
| **UTxO state management** | High | Ledger specs |
| **Reward calculation** | High | Shelley spec (delegation design) |
| **Stake distribution snapshots** | High | Shelley spec |
| **Protocol parameter updates** | Medium | Per-era specs |
| **N2N/N2C server/responder side** | Medium | ouroboros-network |
| **Mempool management** | Medium | ouroboros-consensus |
| **Persistent storage (ChainDB)** | High | ouroboros-consensus |
| **Crash recovery** | High | ouroboros-consensus (ImmutableDB/VolatileDB design) |
| **Block forging** | Medium | Praos paper |
| **Conway governance** | High | Conway spec + CIP-1694 |
| **Mithril snapshot import** | Low-Medium | Mithril docs |
| **Conformance test harness** | Medium | Our own design |
| **Monitoring/metrics** | Low | Prometheus |

### Build Order (Suggested Milestones)
1. **M0:** Project skeleton, build setup, CI
2. **M1:** Core types, CBOR serialization (leverage yaci/cardano-client-lib)
3. **M2:** Network layer — connect to existing nodes, sync headers (leverage yaci)
4. **M3:** Chain sync — download and store blocks
5. **M4:** Ledger state — UTxO tracking, basic Shelley validation
6. **M5:** Multi-era validation (Allegra, Mary, Alonzo, Babbage, Conway)
7. **M6:** Consensus — Ouroboros Praos, chain selection
8. **M7:** Block production — VRF, KES, forging
9. **M8:** Full node — mempool, N2C protocols, crash recovery
10. **M9:** Conformance testing, performance tuning, mainnet readiness
