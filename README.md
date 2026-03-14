# Stretto 🎵

### A Cardano Full Node in Scala 3

> *In music, a **stretto** is where multiple voices converge, overlapping and reinforcing each other toward resolution — much like nodes reaching consensus.*

**Status:** Research & Planning Phase

## Vision

Stretto is an independent Cardano node implementation written in Scala 3, targeting full spec compliance with the Haskell reference node. The project aims to:

- Provide the Cardano community with another independent node implementation
- Leverage the JVM ecosystem's existing Cardano libraries (yaci, cardano-client-lib, scalus)
- Demonstrate that a production-grade blockchain node can be vibe-coded with AI assistance
- Strengthen network resilience through client diversity

## Why Scala?

- **JVM interop** — direct access to Java Cardano libraries (bloxbean's yaci for miniprotocols, cardano-client-lib for CBOR/types)
- **Native Plutus evaluation** — scalus provides a Scala 3 Plutus Core evaluator (CEK machine)
- **Type system** — ADTs, pattern matching, and opaque types are natural for modeling ledger rules and protocol state machines
- **Performance** — JVM with Java 21 virtual threads, modern GC, mature JIT
- **Unique lineage** — no existing Scala node implementation, ensuring originality

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  Stretto (Node)                  │
├──────────┬──────────┬──────────┬────────────────┤
│ Network  │Consensus │  Ledger  │    Mempool     │
│ (N2N/N2C)│ (Praos)  │ (Rules)  │               │
├──────────┴──────────┴──────────┴────────────────┤
│              Storage (ChainDB)                   │
├──────────────────────────────────────────────────┤
│           Core (Types, Crypto, CBOR)             │
└──────────────────────────────────────────────────┘

External JVM Dependencies:
  ├── yaci (miniprotocols, block decoding)
  ├── cardano-client-lib (CBOR, Cardano types)
  └── scalus (Plutus evaluation)
```

## Key Dependencies

| Library | Language | Purpose |
|---------|----------|---------|
| [yaci](https://github.com/bloxbean/yaci) | Java | Ouroboros miniprotocols (N2N, N2C), block CBOR decoding |
| [cardano-client-lib](https://github.com/bloxbean/cardano-client-lib) | Java | CBOR serialization, Cardano types, address handling |
| [scalus](https://github.com/nau/scalus) | Scala 3 | Plutus V1/V2/V3 script evaluation (CEK machine) |
| [cats-effect](https://typelevel.org/cats-effect/) | Scala 3 | Asynchronous effect system |
| [fs2](https://fs2.io/) | Scala 3 | Functional streaming |

## Milestones

| # | Milestone | Description | Status |
|---|-----------|-------------|--------|
| M0 | Project Setup | Build, CI, project skeleton | Planned |
| M1 | Core Types | Primitives, CBOR serialization (all eras) | Planned |
| M2 | Network Sync | Connect to peers, sync headers via yaci | Planned |
| M3 | Chain Storage | Download and persist blocks | Planned |
| M4 | Ledger (Shelley) | UTxO tracking, basic validation | Planned |
| M5 | Multi-Era Ledger | Allegra → Conway validation rules | Planned |
| M6 | Consensus | Ouroboros Praos, chain selection | Planned |
| M7 | Block Production | VRF, KES, block forging | Planned |
| M8 | Full Node | Mempool, N2C, crash recovery | Planned |
| M9 | Conformance | Testing, tuning, mainnet readiness | Planned |

## Conformance Testing

Conformance with the Haskell node is our top priority. We employ:

1. **Unit tests** — per-rule validation against known test vectors
2. **Property-based testing** — generated transactions/blocks compared with Haskell node
3. **Private devnet** — run alongside Haskell nodes, verify tip agreement
4. **Mainnet replay** — replay historical blocks, verify identical state transitions
5. **Antithesis** — deterministic fuzzing (goal)

## Existing Cardano Node Implementations

| Name | Language | Organization | Status |
|------|----------|-------------|--------|
| [cardano-node](https://github.com/IntersectMBO/cardano-node) | Haskell | Intersect | Production (reference) |
| [Amaru](https://github.com/pragma-org/amaru) | Rust | PRAGMA | Alpha (relay-capable) |
| [Dingo](https://github.com/blinklabs-io/dingo) | Go | Blink Labs | Active development |
| [Torsten](https://github.com/michaeljfazio/torsten) | Rust | Sandstone Pool | Alpha |
| [Acropolis](https://github.com/input-output-hk/acropolis) | Rust | IOG | In development |
| **Stretto** | **Scala 3** | **Clawdano** | **Research phase** |

## Vibe-Coded

This project is built with AI assistance (Claude). Every commit includes:
- The model and prompt used
- A `Co-Authored-By` tag from the AI
- Full git history visible from project inception

Built by [@clawdano](https://github.com/clawdano) with human guidance from the Cardano community.

## References

- [Cardano Ledger Specs](https://github.com/IntersectMBO/cardano-ledger)
- [Ouroboros Consensus](https://github.com/IntersectMBO/ouroboros-consensus)
- [Ouroboros Network](https://github.com/IntersectMBO/ouroboros-network)
- [Plutus Core](https://github.com/IntersectMBO/plutus)
- [Formal Ledger Specs (Agda)](https://github.com/IntersectMBO/formal-ledger-specifications)
- [CIPs](https://github.com/cardano-foundation/CIPs)

## License

MIT
