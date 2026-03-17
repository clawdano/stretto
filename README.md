# Stretto

### A Cardano Full Node in Scala 3

> *In music, a **stretto** is where multiple voices converge, overlapping and reinforcing each other toward resolution — much like nodes reaching consensus.*

**Status:** Full preprod sync operational — headers, blocks, UTxO tracking, N2C relay

## Vision

Stretto is an independent Cardano node implementation written in Scala 3, targeting full spec compliance with the Haskell reference node. The project aims to:

- Provide the Cardano community with another independent node implementation
- Leverage the JVM ecosystem (scalus for Plutus, scodec for binary, cats-effect/fs2 for streaming)
- Demonstrate that a production-grade blockchain node can be vibe-coded with AI assistance
- Strengthen network resilience through client diversity

## Quick Start

### Prerequisites

- Java 21 (LTS)
- sbt 1.10.x

### Build

```bash
sbt compile
```

### Run Tests

```bash
sbt test
```

## CLI Usage

All commands are run via sbt:

```bash
sbt 'cli/run <command> [options]'
```

### Commands

#### `relay` — Lightweight N2C Relay Node

Syncs blocks from an upstream N2N peer and serves them to local N2C clients via ChainSync. Designed to replace heavy Haskell nodes (8-16 GB RAM) for chain-following workloads, targeting 256-512 MB RAM.

```bash
# Sync from preprod and serve N2C on localhost:3001
sbt 'cli/run relay --network preprod --peer panic-station:30010 --listen 127.0.0.1:3001'

# With custom database path and max clients
sbt 'cli/run relay --network preprod --peer panic-station:30010 --listen 127.0.0.1:3001 --db ./my-relay-data --max-clients 64'

# Mainnet relay exposed on all interfaces (use with caution)
sbt 'cli/run relay --network mainnet --peer relay:3001 --listen 0.0.0.0:3001'
```

| Option | Description | Default |
|--------|-------------|---------|
| `-n, --network <name>` | Network: `mainnet`, `preprod`, `preview` | — |
| `-p, --peer <host:port>` | Upstream N2N peer address | Network default |
| `-l, --listen <host:port>` | N2C listen address | `127.0.0.1:3001` |
| `-d, --db <path>` | Database directory | `./data/<network>-relay` |
| `--max-clients <n>` | Max concurrent N2C clients | `32` |
| `--magic <number>` | Custom network magic | From `--network` |

The relay node:
- Connects upstream via N2N (ChainSync + BlockFetch + KeepAlive)
- Stores blocks in RocksDB
- Publishes tip updates via fs2 Topic
- Accepts N2C connections and serves ChainSync (protocol ID 5)
- Auto-reconnects to upstream on connection loss
- Binds to `127.0.0.1` by default for security

#### `sync-blocks` — Full Block Sync

Downloads full blocks (headers + bodies) from a Cardano node and stores them in RocksDB.

```bash
# Sync all blocks from preprod
sbt 'cli/run sync-blocks --network preprod --peer panic-station:30010'

# Sync first 1000 blocks
sbt 'cli/run sync-blocks --network preprod --peer panic-station:30010 --max-blocks 1000'

# Mainnet with custom database path
sbt 'cli/run sync-blocks --network mainnet --peer relay:3001 --db ./mainnet-data'
```

| Option | Description | Default |
|--------|-------------|---------|
| `-n, --network <name>` | Network: `mainnet`, `preprod`, `preview` | — |
| `-p, --peer <host:port>` | Peer address | Network default |
| `-d, --db <path>` | Database directory | `./data/<network>` |
| `-m, --max-blocks <n>` | Max blocks to sync (0 = unlimited) | `0` |
| `--magic <number>` | Custom network magic | From `--network` |

#### `sync-headers` — Header-Only Sync

Streams block headers only (no block bodies). Useful for testing or lightweight chain following.

```bash
# Sync all headers from preprod
sbt 'cli/run sync-headers --network preprod'

# Sync first 5000 headers from mainnet
sbt 'cli/run sync-headers --network mainnet --peer relay:3001 --max-headers 5000'
```

| Option | Description | Default |
|--------|-------------|---------|
| `-n, --network <name>` | Network: `mainnet`, `preprod`, `preview` | — |
| `-p, --peer <host:port>` | Peer address | Network default |
| `-d, --db <path>` | Database directory | `./data/<network>` |
| `-m, --max-headers <n>` | Max headers to sync (0 = unlimited) | `0` |
| `--magic <number>` | Custom network magic | From `--network` |

#### `version`

```bash
sbt 'cli/run version'
```

#### `help`

```bash
sbt 'cli/run help'
```

### Network Presets

When using `--network`, default relay peers are provided:

| Network | Magic | Default Peers |
|---------|-------|---------------|
| `mainnet` | 764824073 | `backbone.cardano.iog.io:3001` |
| `preprod` | 1 | `preprod-node.play.dev.cardano.org:3001` |
| `preview` | 2 | `preview-node.play.dev.cardano.org:3001` |

You can override the peer with `--peer` or use `--magic` for custom networks.

## Docker

### Build the Image

```bash
sbt cli/docker:publishLocal
```

This produces `clawdanoai/stretto:0.1.0-SNAPSHOT` (~226 MB) based on `eclipse-temurin:21-jre-jammy`.

### Run a Relay Node

```bash
# Preprod relay on localhost:3001
docker run -d --name stretto-relay \
  -p 3001:3001 \
  -v stretto-data:/data \
  clawdanoai/stretto:0.1.0-SNAPSHOT \
  relay --network preprod --peer preprod-node.play.dev.cardano.org:3001 --listen 0.0.0.0:3001 --db /data

# Mainnet relay
docker run -d --name stretto-mainnet \
  -p 3001:3001 \
  -v stretto-mainnet-data:/data \
  clawdanoai/stretto:0.1.0-SNAPSHOT \
  relay --network mainnet --peer backbone.cardano.iog.io:3001 --listen 0.0.0.0:3001 --db /data
```

### Run Block Sync

```bash
docker run -d --name stretto-sync \
  -v stretto-data:/data \
  clawdanoai/stretto:0.1.0-SNAPSHOT \
  sync-blocks --network preprod --peer preprod-node.play.dev.cardano.org:3001 --db /data
```

### Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `JAVA_OPTS` | JVM options | `-Xmx512m` |

The `/data` volume is used for RocksDB storage. All CLI options are passed as container arguments after the image name.

### Publish to Docker Hub

```bash
sbt cli/docker:publish
```

Pushes to `clawdanoai/stretto` on Docker Hub.

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
│        Core (Types) + Serialization (CBOR)       │
└──────────────────────────────────────────────────┘
```

### Relay Node Architecture

```
Upstream Peer (N2N)
    │
    ▼
[BlockSyncPipeline] ──store──▶ [RocksDB]
    │                              ▲
    └──publish──▶ [fs2 Topic]      │
                      │            │
               ┌──────┼──────┐    │
               │      │      │    │
             Client1 Client2 ...  │
               │                  │
               └───read blocks────┘
```

## Why Scala?

- **Type system** — ADTs, pattern matching, and opaque types are natural for modeling ledger rules and protocol state machines
- **Native Plutus evaluation** — scalus provides a Scala 3 Plutus Core evaluator (CEK machine)
- **Performance** — JVM with Java 21 virtual threads, modern GC, mature JIT
- **Effect system** — cats-effect + fs2 for safe concurrency and streaming
- **Unique lineage** — no existing Scala node implementation, ensuring originality

## Key Dependencies

| Library | Language | Purpose |
|---------|----------|---------|
| [scalus](https://github.com/nau/scalus) | Scala 3 | Plutus V1/V2/V3 script evaluation (CEK machine) |
| [scodec](https://scodec.org/) | Scala 3 | Binary codec primitives for CBOR and mux framing |
| [cats-effect](https://typelevel.org/cats-effect/) | Scala 3 | Asynchronous effect system (IO) |
| [fs2](https://fs2.io/) | Scala 3 | Functional streaming and TCP networking |
| [http4s](https://http4s.org/) | Scala 3 | Metrics and API server |
| [RocksDB](https://rocksdb.org/) | C++/JNI | Persistent key-value storage |

> Ouroboros miniprotocols (Handshake, ChainSync, BlockFetch) and CBOR codecs are implemented from scratch in pure Scala — no external Cardano Java libraries.

## Progress

| Module | Status | Highlights |
|--------|--------|------------|
| **core** | Done | Opaque types, Point, Tip, Block ADTs |
| **serialization** | Done | CBOR primitives, all-era block decoder |
| **network** | Done | Handshake (N2N+N2C), ChainSync (client+server), BlockFetch, KeepAlive, Mux |
| **storage** | Done | RocksDB with 5 column families, atomic batch writes |
| **ledger** | Partial | UTxO state, block applicator (all eras) |
| **node** | Done | Block sync pipeline, relay node, N2C listener |
| **cli** | Done | sync-headers, sync-blocks, relay commands |
| **consensus** | Planned | Ouroboros Praos, chain selection |
| **mempool** | Planned | Transaction pool |

## Testing

287 tests passing, 9 ignored (integration tests requiring live nodes).

```bash
sbt test
```

## Existing Cardano Node Implementations

| Name | Language | Organization | Status |
|------|----------|-------------|--------|
| [cardano-node](https://github.com/IntersectMBO/cardano-node) | Haskell | Intersect | Production (reference) |
| [Amaru](https://github.com/pragma-org/amaru) | Rust | PRAGMA | Alpha (relay-capable) |
| [Dingo](https://github.com/blinklabs-io/dingo) | Go | Blink Labs | Active development |
| [Torsten](https://github.com/michaeljfazio/torsten) | Rust | Sandstone Pool | Alpha |
| [Acropolis](https://github.com/input-output-hk/acropolis) | Rust | IOG | In development |
| **Stretto** | **Scala 3** | **Clawdano** | **Relay-capable** |

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
