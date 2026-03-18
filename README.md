# Stretto

### A Cardano Full Node in Scala 3

> *In music, a **stretto** is where multiple voices converge, overlapping and reinforcing each other toward resolution — much like nodes reaching consensus.*

**Status:** Full chain sync on mainnet, preprod, and preview. N2N + N2C relay with tip-following. Ouroboros Praos consensus module. Prometheus metrics.

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
sbt test    # 403 tests
```

## CLI Usage

All commands are run via sbt:

```bash
sbt 'cli/run <command> [options]'
```

### Commands

#### `relay` — Relay Node

Syncs blocks from an upstream N2N peer and serves them to downstream N2N peers and local N2C clients.

```bash
# Preprod relay with N2N on default port 3001
sbt 'cli/run relay --network preprod --peer preprod-node.play.dev.cardano.org:3001'

# With N2C enabled and Prometheus metrics
sbt 'cli/run relay --network preprod --peer upstream:3001 --n2c-port 3002 --metrics-port 9090'

# Mainnet relay
sbt 'cli/run relay --network mainnet --peer backbone.cardano.iog.io:3001'

# Stretto-to-stretto chain (downstream syncs from upstream stretto)
sbt 'cli/run relay --network preprod --peer first-stretto:3001'
```

| Option | Description | Default |
|--------|-------------|---------|
| `-n, --network <name>` | Network: `mainnet`, `preprod`, `preview` | — |
| `-p, --peer <host:port>` | Upstream N2N peer address | Network default |
| `--host <addr>` | Bind address for all listeners | `0.0.0.0` |
| `--n2n-port <port>` | N2N listen port (0 = disabled) | `3001` |
| `--n2c-port <port>` | N2C listen port (0 = disabled) | disabled |
| `--metrics-port <port>` | Prometheus metrics port (0 = disabled) | disabled |
| `-d, --db <path>` | Database directory | `./data/<network>-relay` |
| `--max-n2n-peers <n>` | Max concurrent N2N peers | `16` |
| `--max-n2c-clients <n>` | Max concurrent N2C clients | `32` |
| `--keep-alive-interval <s>` | KeepAlive ping interval in seconds | `10` |
| `--magic <number>` | Custom network magic | From `--network` |

The relay node:
- Connects upstream via N2N (ChainSync + BlockFetch + KeepAlive)
- Stores headers and blocks in RocksDB
- Serves downstream N2N peers (ChainSync + BlockFetch + KeepAlive)
- Optionally serves N2C clients (ChainSync + LocalStateQuery)
- Publishes tip updates via fs2 Topic for real-time tip-following
- Adaptive pipelining: 100-request window for bulk sync, shrinks to 1 near tip
- Auto-reconnects to upstream on connection loss

#### `sync-blocks` — Full Block Sync

Downloads full blocks (headers + bodies) from a Cardano node and stores them in RocksDB.

```bash
sbt 'cli/run sync-blocks --network preprod --peer preprod-node.play.dev.cardano.org:3001'
sbt 'cli/run sync-blocks --network mainnet --max-blocks 1000'
```

| Option | Description | Default |
|--------|-------------|---------|
| `-n, --network <name>` | Network: `mainnet`, `preprod`, `preview` | — |
| `-p, --peer <host:port>` | Peer address | Network default |
| `-d, --db <path>` | Database directory | `./data/<network>` |
| `-m, --max-blocks <n>` | Max blocks to sync (0 = unlimited) | `0` |
| `--magic <number>` | Custom network magic | From `--network` |

#### `sync-headers` — Header-Only Sync

```bash
sbt 'cli/run sync-headers --network preprod'
```

### Network Presets

| Network | Magic | Default Peers |
|---------|-------|---------------|
| `mainnet` | 764824073 | `backbone.cardano.iog.io:3001` |
| `preprod` | 1 | `preprod-node.play.dev.cardano.org:3001` |
| `preview` | 2 | `preview-node.play.dev.cardano.org:3001` |

## Docker

### Build the Image

```bash
sbt cli/docker:publishLocal
```

Produces `clawdanoai/stretto:0.1.0-SNAPSHOT` based on `eclipse-temurin:21-jre-jammy`.

### Run a Relay Node

```bash
# Preprod relay with N2N on port 3001 and metrics on 9090
docker run -d --name stretto-preprod \
  -p 3001:3001 \
  -p 9090:9090 \
  -v stretto-preprod-data:/data \
  clawdanoai/stretto:0.1.0-SNAPSHOT \
  relay --network preprod \
    --peer preprod-node.play.dev.cardano.org:3001 \
    --metrics-port 9090 \
    --db /data/db

# Mainnet relay with N2N + N2C + metrics
docker run -d --name stretto-mainnet \
  -p 3001:3001 \
  -p 3002:3002 \
  -p 9090:9090 \
  -v stretto-mainnet-data:/data \
  clawdanoai/stretto:0.1.0-SNAPSHOT \
  relay --network mainnet \
    --peer backbone.cardano.iog.io:3001 \
    --n2c-port 3002 \
    --metrics-port 9090 \
    --db /data/db

# Stretto-to-stretto: second node syncs from first
docker run -d --name stretto-downstream \
  -p 3003:3001 \
  -v stretto-downstream-data:/data \
  clawdanoai/stretto:0.1.0-SNAPSHOT \
  relay --network preprod \
    --peer stretto-preprod:3001 \
    --db /data/db
```

### Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `JAVA_OPTS` | JVM options | `-Xmx512m` |

Exposed ports: `3001` (N2N), `9090` (metrics). The `/data` volume is used for RocksDB storage.

### Publish to Docker Hub

```bash
sbt cli/docker:publish
```

Pushes to `clawdanoai/stretto` on Docker Hub.

## Prometheus Metrics

Enable with `--metrics-port 9090`, then point Prometheus at `http://stretto:9090/metrics`.

**Cardano chain metrics:**
| Metric | Description |
|--------|-------------|
| `stretto_chain_tip_slot` | Current chain tip slot |
| `stretto_chain_tip_block` | Current chain tip block number |
| `stretto_peer_tip_slot` | Upstream peer tip slot |
| `stretto_chain_density` | Blocks/slots ratio (~0.05 on mainnet) |
| `stretto_sync_progress` | Sync completion (0.0 to 1.0) |
| `stretto_epoch` | Current epoch number |
| `stretto_sync_blocks_total` | Total blocks synced |
| `stretto_n2n_peers_connected` | Connected N2N downstream peers |
| `stretto_n2c_clients_connected` | Connected N2C clients |
| `stretto_keepalive_rtt_ms` | Upstream peer RTT |

**JVM metrics:** `jvm_uptime_seconds`, `jvm_memory_heap_used_bytes`, `jvm_threads_current`, `jvm_gc_collection_seconds_total`, `jvm_system_load_average`, and more.

All metrics include `network="<name>"` labels for multi-network Grafana dashboards.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                    Stretto (Node)                     │
├──────────┬──────────┬──────────┬────────────────────┤
│ Network  │Consensus │  Ledger  │    Mempool          │
│(N2N/N2C) │ (Praos)  │ (Rules)  │                     │
├──────────┴──────────┴──────────┴────────────────────┤
│               Storage (RocksDB)                       │
├──────────────────────────────────────────────────────┤
│      Core (Types) + Serialization (CBOR) + KES       │
└──────────────────────────────────────────────────────┘
```

### Relay Node Architecture

```
Upstream Peer (N2N)
    │
    ▼
[BlockSyncPipeline] ──store──▶ [RocksDB] ◀──read──┐
    │                              ▲                │
    └──publish──▶ [fs2 Topic]      │                │
                      │            │                │
               ┌──────┼──────┐    │         ┌──────┴──────┐
               │      │      │    │         │             │
          N2C Client  ...   N2N Peer   [MetricsServer]
               │             │                │
               └──read───────┘          :9090/metrics
```

## Why Scala?

- **Type system** — ADTs, pattern matching, and opaque types for ledger rules and protocol state machines
- **Native Plutus evaluation** — scalus provides a Scala 3 Plutus Core evaluator (CEK machine)
- **Performance** — JVM with Java 21 virtual threads, modern GC, mature JIT
- **Effect system** — cats-effect + fs2 for safe concurrency and streaming
- **Unique lineage** — no existing Scala node implementation, ensuring originality

## Key Dependencies

| Library | Purpose |
|---------|---------|
| [scalus](https://github.com/nau/scalus) | Plutus V1/V2/V3 script evaluation |
| [scodec](https://scodec.org/) | Binary codec primitives for CBOR and mux framing |
| [cats-effect](https://typelevel.org/cats-effect/) | Asynchronous effect system (IO) |
| [fs2](https://fs2.io/) | Functional streaming and TCP networking |
| [http4s](https://http4s.org/) | Metrics HTTP server |
| [RocksDB](https://rocksdb.org/) | Persistent key-value storage |
| [BouncyCastle](https://www.bouncycastle.org/) | Ed25519, Blake2b cryptographic primitives |

> Ouroboros miniprotocols (Handshake, ChainSync, BlockFetch, KeepAlive) and CBOR codecs are implemented from scratch in pure Scala.

## Progress

| Module | Status | Highlights |
|--------|--------|------------|
| **core** | Done | Opaque types, Point, Tip, Block ADTs, Ed25519, VRF |
| **serialization** | Done | CBOR primitives, all-era block decoder with consensus fields |
| **network** | Done | N2N client+server, N2C server, Handshake, ChainSync, BlockFetch, KeepAlive |
| **storage** | Done | RocksDB with 5 column families, atomic batch writes |
| **consensus** | Done | Ouroboros Praos: header validation, KES, VRF, leader check, epoch state, chain selection |
| **kes** | Done | Standalone CompactSum6KES library (`cardano.kes`) |
| **ledger** | Partial | UTxO state, block applicator, 6 validation rules |
| **node** | Done | Sync pipeline, relay node, N2N/N2C listeners, metrics server |
| **cli** | Done | relay, sync-blocks, sync-headers commands |
| **mempool** | Planned | Transaction pool |

### Verified Sync Performance

| Network | Blocks | Time | Rate |
|---------|--------|------|------|
| **Mainnet** | ~13.2M | ~10 hours | ~3,300 blocks/sec |
| **Preprod** | ~4.5M | ~8 min | ~9,400 blocks/sec |
| **Preview** | Full chain | ~6 min | — |

## Testing

403 tests passing, 9 ignored (integration tests requiring live nodes).

```bash
sbt test
```

## Existing Cardano Node Implementations

| Name | Language | Organization | Status |
|------|----------|-------------|--------|
| [cardano-node](https://github.com/IntersectMBO/cardano-node) | Haskell | Intersect | Production (reference) |
| [Amaru](https://github.com/pragma-org/amaru) | Rust | PRAGMA | Alpha |
| [Dingo](https://github.com/blinklabs-io/dingo) | Go | Blink Labs | Active development |
| **Stretto** | **Scala 3** | **Clawdano** | **Relay-capable, syncing all networks** |

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
