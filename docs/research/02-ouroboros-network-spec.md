# Ouroboros Network Protocol Specification

> Research document for the Stretto (Cardano Scala Node) project.
> Purpose: Comprehensive reference for implementing Ouroboros miniprotocols from scratch
> in Scala 3 using cats-effect IO + fs2 streams.
> Compiled: 2026-03-14
> Sources: ouroboros-network spec PDF, ouroboros-network GitHub repo, typed-protocols framework

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Bearer Layer](#2-bearer-layer)
3. [Multiplexer / MUX-DeMUX](#3-multiplexer--mux-demux)
4. [Handshake Miniprotocol](#4-handshake-miniprotocol)
5. [Chain Sync Miniprotocol](#5-chain-sync-miniprotocol)
6. [Block Fetch Miniprotocol](#6-block-fetch-miniprotocol)
7. [Tx Submission Miniprotocol (N2N)](#7-tx-submission-miniprotocol-n2n)
8. [Local Tx Submission Miniprotocol (N2C)](#8-local-tx-submission-miniprotocol-n2c)
9. [Local State Query Miniprotocol (N2C)](#9-local-state-query-miniprotocol-n2c)
10. [Local Tx Monitor Miniprotocol (N2C)](#10-local-tx-monitor-miniprotocol-n2c)
11. [Keep Alive Miniprotocol (N2N)](#11-keep-alive-miniprotocol-n2n)
12. [Peer Sharing Miniprotocol (N2N)](#12-peer-sharing-miniprotocol-n2n)
13. [CBOR Encoding Conventions](#13-cbor-encoding-conventions)
14. [Implementation Notes for Scala](#14-implementation-notes-for-scala)

---

## 1. Architecture Overview

### 1.1 Connection Types

The Ouroboros network uses two distinct connection types:

- **Node-to-Node (N2N):** Communication between full nodes over TCP. Used for chain
  synchronization, block propagation, and transaction relay across the network.
- **Node-to-Client (N2C):** Communication between a node and local clients (wallets,
  CLI tools, indexers) over Unix domain sockets (or named pipes on Windows).

### 1.2 Protocol Layering

```
┌─────────────────────────────────────────────────────┐
│              Miniprotocols (typed-protocols)         │
│  ChainSync │ BlockFetch │ TxSub │ LocalStateQuery …  │
├─────────────────────────────────────────────────────┤
│              Multiplexer / DeMux                     │
│     (framing, miniprotocol ID routing, timestamps)   │
├─────────────────────────────────────────────────────┤
│              Handshake (version negotiation)          │
├─────────────────────────────────────────────────────┤
│              Bearer (TCP socket / Unix socket)        │
└─────────────────────────────────────────────────────┘
```

### 1.3 Agency Model (typed-protocols)

Every miniprotocol is modeled as a state machine where exactly one party has "agency"
(the right to send) in each state. The two roles are:

- **Client** (also called "initiator"): The party that initiates the connection.
  In N2N, this is the node that opens the TCP connection. In N2C, this is the
  wallet/tool connecting to the node's socket.
- **Server** (also called "responder"): The party that accepts the connection.

In each state, exactly one of {Client, Server} has agency. The party with agency
sends a message that transitions the protocol to a new state, where agency may
transfer to the other party. Terminal states (like `StDone`) have `NobodyAgency` --
neither party sends.

### 1.4 Miniprotocol ID Assignments

| Miniprotocol        | ID  | Connection | Initiator |
|---------------------|-----|------------|-----------|
| Handshake           | 0   | Both       | Client    |
| ChainSync           | 2   | Both*      | Client    |
| BlockFetch          | 3   | N2N        | Client    |
| TxSubmission2       | 6   | N2N        | Server**  |
| LocalTxSubmission   | 8   | N2C        | Client    |
| LocalStateQuery     | 7   | N2C        | Client    |
| LocalTxMonitor      | 9   | N2C        | Client    |
| KeepAlive           | 8   | N2N        | Client    |
| PeerSharing         | 10  | N2N        | Client    |

*ChainSync uses miniprotocol ID 5 for N2C (local chain sync).
**TxSubmission2 is unique: the server drives the protocol by requesting transactions.

### 1.5 Pipelining

The typed-protocols framework supports **pipelining**: a party with agency can send
multiple messages without waiting for responses, as long as the protocol state machine
allows it. This is critical for performance in ChainSync and BlockFetch.

Pipelining works by allowing the sender to "collect" responses later. The framework
tracks outstanding pipelined requests and ensures they are collected in order.

---

## 2. Bearer Layer

### 2.1 TCP Bearer (Node-to-Node)

- Standard TCP socket connection
- Default port: **3001** (configurable)
- The initiator opens the TCP connection to the responder
- After the TCP connection is established, the Handshake miniprotocol runs first
- All subsequent miniprotocols are multiplexed over this single TCP connection
- TCP_NODELAY should be set (disable Nagle's algorithm) for latency
- Socket-level keepalive should be enabled alongside the protocol-level KeepAlive

### 2.2 Unix Domain Socket Bearer (Node-to-Client)

- Unix domain socket (AF_UNIX / SOCK_STREAM)
- Socket path is configurable (e.g., `/run/cardano-node/node.socket`)
- On Windows: named pipes (`\\.\pipe\cardano-node`)
- Same multiplexing as TCP, but only N2C miniprotocols are available
- The client connects to the node's listening socket

### 2.3 Socket-to-Bearer Abstraction

In our Scala implementation, we should model a `Bearer` trait:

```
trait Bearer:
  def read(n: Int): IO[ByteVector]
  def write(bytes: ByteVector): IO[Unit]
  def close: IO[Unit]
```

This abstracts over TCP and Unix sockets, letting the multiplexer work identically
regardless of bearer type. Use `fs2.io.net.Socket` for TCP and
`fs2.io.net.unixsocket.UnixSockets` for Unix domain sockets.

---

## 3. Multiplexer / MUX-DeMUX

### 3.1 Overview

The multiplexer allows multiple miniprotocols to share a single bearer (TCP or Unix
socket). Each miniprotocol gets a logical bidirectional channel. The multiplexer
breaks messages into segments, adds a header to each segment, and transmits them
over the bearer. The demultiplexer on the other side reads headers, routes segments
to the appropriate miniprotocol channel, and reassembles messages.

### 3.2 Segment Header Format

Each segment has an **8-byte header** (NOT 32 bytes as sometimes stated -- the spec
uses 8 bytes):

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
├─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┤
│                    Transmission Time (32 bits)                    │
├─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┤
│M│   Mini Protocol ID (15 bits)    │     Payload Length (16 bits)   │
├─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┼─┤
```

**Fields:**

| Field               | Size    | Byte Offset | Description |
|---------------------|---------|-------------|-------------|
| Transmission Time   | 4 bytes | 0-3         | Monotonic timestamp in microseconds (0.01s resolution). Used for bearer capacity estimation. Big-endian (network byte order). |
| Mode (M)            | 1 bit   | 4 (MSB)     | **0** = Initiator-to-Responder (InitiatorDir), **1** = Responder-to-Initiator (ResponderDir) |
| Mini Protocol ID    | 15 bits | 4-5         | ID of the miniprotocol (see table above). Big-endian, lower 15 bits of the 2-byte field. |
| Payload Length      | 2 bytes | 6-7         | Length of the payload that follows this header, in bytes. Big-endian. |

**Key details:**

- **Total header size: 8 bytes**
- **All multi-byte fields are big-endian (network byte order)**
- **Maximum segment payload size (SDU): 65535 bytes** (limited by 16-bit length field)
- In practice, the maximum segment size used is **2^16 = 65535 bytes** but implementations
  typically use a smaller limit of **MaxSDU = 12288 bytes (12 KiB)** for the data segments,
  though this is not a hard protocol requirement.
- The Haskell implementation uses `maxTransmissionUnit = 12288` by default.

### 3.3 Mode Bit Semantics

The **mode bit** (M, bit 15 of the second 16-bit word) indicates the direction:

- **M = 0 (0x0000 | protocolId):** Message sent from **Initiator to Responder**
- **M = 1 (0x8000 | protocolId):** Message sent from **Responder to Initiator**

This means:
- When the **client** (initiator) sends data, the mode bit is 0.
- When the **server** (responder) sends data, the mode bit is 1.

Both sides use the same miniprotocol ID; the mode bit distinguishes the direction.

### 3.4 Multiplexing Algorithm

**Sender side (MUX):**
1. Each miniprotocol has an egress queue (bounded)
2. The miniprotocol serializes its message to CBOR bytes
3. If the serialized message exceeds the maximum SDU, it is split into multiple segments
4. Each segment gets an 8-byte header prepended
5. Segments from different miniprotocols are interleaved on the bearer
6. The multiplexer uses round-robin or priority-based scheduling across miniprotocols

**Receiver side (DeMUX):**
1. Read 8-byte header from the bearer
2. Extract miniprotocol ID and payload length
3. Read `payload_length` bytes of payload
4. Route the payload to the appropriate miniprotocol's ingress queue
5. If the payload is part of a multi-segment message, the miniprotocol reassembles it
6. The CBOR decoder in the miniprotocol handles reassembly -- each miniprotocol
   reads from its ingress byte stream and decodes complete CBOR values

### 3.5 Transmission Time

The transmission time field is a 32-bit unsigned integer representing a monotonic
timestamp. It is measured in units of **1 microsecond**. The timestamp represents
the time when the segment was sent. It is used for:

- Estimating bearer capacity / bandwidth
- Detecting slow peers
- Not used for protocol correctness -- purely informational

When a node receives segments, it can compare the claimed transmission time with
its own clock to estimate network latency and bearer throughput.

### 3.6 Connection Lifecycle

1. Bearer established (TCP connect or Unix socket connect)
2. Handshake miniprotocol runs (always miniprotocol ID 0)
3. On successful handshake, agreed version and parameters are established
4. All other miniprotocols start concurrently, sharing the bearer via the multiplexer
5. Each miniprotocol runs independently with its own state machine
6. Connection terminates when the bearer is closed (or on protocol error)

### 3.7 Ingress Queue and Flow Control

Each miniprotocol has an ingress queue on the demux side. The queue is bounded.
If a miniprotocol is slow to consume data, its ingress queue fills up, and the
demux will stop reading from the bearer (applying backpressure). This is important
to prevent unbounded memory usage.

In fs2, this maps naturally to bounded `Queue[IO, ByteVector]` instances.

---

## 4. Handshake Miniprotocol

### 4.1 Overview

| Property          | Value |
|-------------------|-------|
| Miniprotocol ID   | 0     |
| Connection type   | Both (N2N and N2C) |
| Initiator         | Client |
| Pipelining        | No    |

The Handshake runs immediately after the bearer is established. It negotiates the
protocol version and associated parameters. It must complete before any other
miniprotocol can run.

### 4.2 State Machine

```
        ┌──────────┐
        │ StPropose │  (Client has agency)
        └─────┬─────┘
              │ MsgProposeVersions(versions)
              ▼
        ┌──────────┐
        │ StConfirm │  (Server has agency)
        └─────┬─────┘
              │
         ┌────┴────┐
         │         │
    MsgAcceptVersion   MsgRefuse
    (versionNumber,    (reason)
     versionData)
         │         │
         ▼         ▼
    ┌────────┐  ┌────────┐
    │ StDone │  │ StDone │
    └────────┘  └────────┘
```

**States:**

| State       | Agency | Description |
|-------------|--------|-------------|
| StPropose   | Client | Initial state. Client must propose versions. |
| StConfirm   | Server | Server evaluates proposals. |
| StDone      | Nobody | Terminal state. Handshake complete (success or failure). |

### 4.3 Messages

#### MsgProposeVersions

Sent by the client. Contains a map of version numbers to version data (parameters).

```
MsgProposeVersions :: Map VersionNumber VersionData -> Message
```

#### MsgAcceptVersion

Sent by the server on success. Contains the selected version number and the
agreed-upon version data.

```
MsgAcceptVersion :: VersionNumber -> VersionData -> Message
```

#### MsgRefuse

Sent by the server on failure. Contains a refusal reason.

```
MsgRefuse :: RefuseReason -> Message
```

**RefuseReason variants:**
- `VersionMismatch [VersionNumber]` -- no common version; includes server's versions
- `HandshakeDecodeError VersionNumber Text` -- could not decode version data
- `Refused VersionNumber Text` -- version was refused for some reason

### 4.4 CBOR Encoding

All messages are encoded as CBOR arrays with a leading tag integer:

**MsgProposeVersions:**
```cbor
[0, { versionNumber: versionData, ... }]
```
- Tag: 0
- Body: CBOR map from version number (unsigned integer) to version data

**MsgAcceptVersion:**
```cbor
[1, versionNumber, versionData]
```
- Tag: 1
- versionNumber: unsigned integer
- versionData: version-specific parameters

**MsgRefuse:**
```cbor
[2, refuseReason]
```
- Tag: 2
- refuseReason is encoded as:
  - VersionMismatch: `[0, [version1, version2, ...]]`
  - HandshakeDecodeError: `[1, versionNumber, errorText]`
  - Refused: `[2, versionNumber, reasonText]`

### 4.5 Version Numbers and Parameters

#### Node-to-Node Versions

| Version | Number | Description |
|---------|--------|-------------|
| N2N v7  | 7      | Alonzo era (deprecated) |
| N2N v8  | 8      | Alonzo era |
| N2N v9  | 9      | Alonzo era |
| N2N v10 | 10     | Babbage era |
| N2N v11 | 11     | Conway era, peer sharing |
| N2N v12 | 12     | Conway era, updated peer sharing |
| N2N v13 | 13     | Conway era, latest |
| N2N v14 | 14     | Latest (as of 2026) |

**N2N VersionData parameters:**

```cbor
VersionData (N2N) = [
  networkMagic,     -- Word32: network identifier (764824073 for mainnet)
  initiatorOnlyDiffusionMode,  -- Bool: true if initiator-only mode
  peerSharing,      -- Word8: 0 = NoPeerSharing, 1 = PeerSharingPublic, 2 = PeerSharingPrivate
  query             -- Bool: true if this is a query-only connection (no block fetch)
]
```

For earlier versions (v7-v9), VersionData was simpler:
```cbor
VersionData (N2N, old) = [networkMagic, initiatorOnlyDiffusionMode]
```

For v11+, peer sharing was added:
```cbor
VersionData (N2N, v11+) = [networkMagic, initiatorOnlyDiffusionMode, peerSharing, query]
```

#### Node-to-Client Versions

| Version  | Number  | Description |
|----------|---------|-------------|
| N2C v9   | 32777   | (9 + 0x8000) |
| N2C v10  | 32778   | (10 + 0x8000) |
| N2C v11  | 32779   | (11 + 0x8000) |
| N2C v12  | 32780   | (12 + 0x8000) |
| N2C v13  | 32781   | (13 + 0x8000) |
| N2C v14  | 32782   | (14 + 0x8000) |
| N2C v15  | 32783   | (15 + 0x8000) |
| N2C v16  | 32784   | (16 + 0x8000) |
| N2C v17  | 32785   | (17 + 0x8000) |

Note: N2C version numbers are offset by **0x8000 (32768)** to distinguish them
from N2N versions on the wire.

**N2C VersionData parameters:**
```cbor
VersionData (N2C) = networkMagic  -- just a Word32
```
For newer N2C versions (v15+):
```cbor
VersionData (N2C, v15+) = [networkMagic, query]
```

Where `query` is a boolean indicating whether the client wants a query-only connection
(e.g., for local state queries without chain sync).

#### Network Magic Values

| Network     | Magic Number |
|-------------|-------------|
| Mainnet     | 764824073   |
| Preview     | 2           |
| Preprod     | 1           |
| SanchoNet   | 4           |

### 4.6 Version Negotiation Algorithm

1. Client sends `MsgProposeVersions` with all versions it supports, each with the
   associated VersionData.
2. Server examines the proposed versions and finds the **highest version number**
   that both sides support.
3. Server validates the VersionData for that version (e.g., checks networkMagic).
4. If valid, server sends `MsgAcceptVersion` with the chosen version and agreed data.
5. If no common version or invalid data, server sends `MsgRefuse`.

---

## 5. Chain Sync Miniprotocol

### 5.1 Overview

| Property          | Value |
|-------------------|-------|
| Miniprotocol ID   | 2 (N2N), 5 (N2C) |
| Connection type   | Both |
| Initiator         | Client |
| Pipelining        | Yes (heavily used) |

ChainSync allows the client to follow the server's chain. The protocol provides
headers (N2N) or full blocks (N2C), and the client can request them by rolling
forward through the chain.

**N2N variant:** Sends **block headers** only. The client uses BlockFetch to
download full blocks separately.

**N2C variant:** Sends **full blocks** (including the block body). No separate
fetch needed.

### 5.2 State Machine

```
                    ┌────────┐
                    │ StIdle │  (Client has agency)
                    └───┬────┘
                        │
           ┌────────────┼──────────────────┐
           │            │                  │
   MsgRequestNext  MsgFindIntersect   MsgDone
           │         (points)              │
           ▼            ▼                  ▼
     ┌─────────┐  ┌───────────────┐  ┌────────┐
     │ StNext  │  │ StIntersect   │  │ StDone │
     └────┬────┘  └───────┬───────┘  └────────┘
          │               │
     ┌────┴────┐     ┌────┴────────────┐
     │         │     │                 │
MsgRollForward  MsgRollBackward  MsgIntersectFound  MsgIntersectNotFound
(header/block,  (point, tip)    (point, tip)       (tip)
 tip)                │                 │
     │         │     │                 │
     ▼         ▼     ▼                 ▼
  ┌────────┐        ┌────────┐
  │ StIdle │        │ StIdle │
  └────────┘        └────────┘
```

Additionally, when the client is at the tip and requests next:
```
  StNext ──── MsgAwaitReply ────► StNext (Server → Client)
         (server has no new data, client should wait)
```
But actually `MsgAwaitReply` transitions to a state where the client waits:

```
  StNext ──── MsgAwaitReply ────► StMustReply (Server sends this to say "wait")
  StMustReply ── MsgRollForward/MsgRollBackward ──► StIdle
```

### 5.3 Complete State Machine

**States:**

| State           | Agency | Description |
|-----------------|--------|-------------|
| StIdle          | Client | Client can request next, find intersection, or terminate |
| StNext          | Server | Server can respond with roll forward, roll backward, or await reply |
| StMustReply     | Server | Like StNext, but server MUST eventually reply (no more await) |
| StIntersect     | Server | Server responds to intersection query |
| StDone          | Nobody | Terminal state |

**Transitions:**

| From        | Message              | To          | Agency at To | Description |
|-------------|----------------------|-------------|-------------|-------------|
| StIdle      | MsgRequestNext       | StNext      | Server      | Ask for the next header/block |
| StIdle      | MsgFindIntersect     | StIntersect | Server      | Find common point in chain |
| StIdle      | MsgDone              | StDone      | Nobody      | Client terminates protocol |
| StNext      | MsgRollForward       | StIdle      | Client      | Server sends header/block + tip |
| StNext      | MsgRollBackward      | StIdle      | Client      | Server asks client to roll back |
| StNext      | MsgAwaitReply        | StMustReply | Server      | Server says "no data yet, wait" |
| StMustReply | MsgRollForward       | StIdle      | Client      | Server sends header/block after wait |
| StMustReply | MsgRollBackward      | StIdle      | Client      | Server asks rollback after wait |
| StIntersect | MsgIntersectFound    | StIdle      | Client      | Server found a common point |
| StIntersect | MsgIntersectNotFound | StIdle      | Client      | No common point found |

### 5.4 Messages and CBOR Encoding

#### MsgRequestNext
```cbor
[0]
```
- Tag: 0
- No arguments

#### MsgAwaitReply
```cbor
[1]
```
- Tag: 1
- Sent by server to tell client to wait

#### MsgRollForward
```cbor
[2, header_or_block, tip]
```
- Tag: 2
- `header_or_block`: The block header (N2N) or full wrapped block (N2C).
  For N2N, this is a CBOR-encoded block header wrapped as a tagged value.
  For N2C, this is a full serialized block.
- `tip`: The current tip of the server's chain, encoded as:
  ```cbor
  [point, blockNo]
  ```
  where `point` is `[slotNo, headerHash]` and `blockNo` is an unsigned integer.

#### MsgRollBackward
```cbor
[3, point, tip]
```
- Tag: 3
- `point`: The point to roll back to. Either:
  - `[slotNo, headerHash]` -- a specific point
  - `[]` (empty array) -- the genesis point (origin)
- `tip`: Current tip (same format as in MsgRollForward)

#### MsgFindIntersect
```cbor
[4, [point1, point2, ...]]
```
- Tag: 4
- Array of points. Each point is `[slotNo, headerHash]` or `[]` for origin.
- The client sends a list of known points (typically exponentially spaced
  back from its tip) and asks the server to find the most recent one they
  share.

#### MsgIntersectFound
```cbor
[5, point, tip]
```
- Tag: 5
- `point`: The intersection point found
- `tip`: Server's current tip

#### MsgIntersectNotFound
```cbor
[6, tip]
```
- Tag: 6
- `tip`: Server's current tip (client must start from genesis)

#### MsgDone
```cbor
[7]
```
- Tag: 7
- Terminates the protocol

### 5.5 Point Encoding

A **Point** represents a position on the chain:

```cbor
-- Origin (genesis):
origin = []   -- empty array, or tag 0 in some encodings

-- Specific point:
point = [slotNo, headerHash]
-- slotNo: unsigned integer (Word64)
-- headerHash: byte string (32 bytes, Blake2b-256)
```

### 5.6 Tip Encoding

```cbor
tip = [point, blockNo]
-- point: as above
-- blockNo: unsigned integer (Word64)
```

### 5.7 Intersection Finding Algorithm (Client Side)

The client should send an exponentially spaced list of known points:

1. Start from the client's current tip
2. Go back 1 block, then 2, 4, 8, 16, 32, ... blocks
3. Always include the genesis/origin point at the end
4. This allows finding the intersection in O(log n) messages

### 5.8 Pipelining in ChainSync

ChainSync supports pipelining of `MsgRequestNext`:

- The client can send many `MsgRequestNext` messages without waiting for responses
- Responses are collected in order
- This is essential for fast initial sync -- the client can pipeline hundreds of
  requests ahead
- The pipelining depth should be bounded (e.g., 100-200 outstanding requests)
  to avoid overwhelming the server

### 5.9 N2N vs N2C Differences

| Aspect          | N2N (ID=2)              | N2C (ID=5)              |
|-----------------|-------------------------|-------------------------|
| Data sent       | Block headers only      | Full blocks             |
| Header wrapping | Wrapped header bytes    | Wrapped block bytes     |
| Use case        | Discover chain, then    | Direct block delivery   |
|                 | use BlockFetch for      | to local clients        |
|                 | full blocks             |                         |
| Miniprotocol ID | 2                       | 5                       |

---

## 6. Block Fetch Miniprotocol

### 6.1 Overview

| Property          | Value |
|-------------------|-------|
| Miniprotocol ID   | 3     |
| Connection type   | N2N only |
| Initiator         | Client |
| Pipelining        | Yes   |

BlockFetch is used to download blocks by range. After ChainSync informs the client
about new headers, BlockFetch is used to download the actual block bodies.

### 6.2 State Machine

```
          ┌────────┐
          │ StIdle │  (Client has agency)
          └───┬────┘
              │
    ┌─────────┼──────────┐
    │                     │
MsgRequestRange      MsgClientDone
(from, to)                │
    │                     ▼
    ▼               ┌────────┐
┌─────────┐         │ StDone │
│ StBusy  │         └────────┘
└────┬────┘
     │  (Server has agency)
     │
┌────┴──────────────────┐
│                       │
MsgStartBatch       MsgNoBlocks
│                       │
▼                       ▼
┌──────────────┐    ┌────────┐
│ StStreaming   │    │ StIdle │
└──────┬───────┘    └────────┘
       │ (Server has agency)
       │
  ┌────┴────┐
  │         │
MsgBlock  MsgBatchDone
(block)       │
  │           ▼
  ▼       ┌────────┐
  │       │ StIdle │
  │       └────────┘
  └──► StStreaming (loop: more blocks)
```

### 6.3 States

| State        | Agency | Description |
|-------------|--------|-------------|
| StIdle      | Client | Client can request a block range or terminate |
| StBusy      | Server | Server decides whether to send blocks or refuse |
| StStreaming  | Server | Server is streaming blocks one at a time |
| StDone      | Nobody | Terminal state |

### 6.4 Messages and CBOR Encoding

#### MsgRequestRange
```cbor
[0, [fromSlot, fromHash], [toSlot, toHash]]
```
- Tag: 0
- Two points defining the range (inclusive)

#### MsgClientDone
```cbor
[1]
```
- Tag: 1
- Client terminates the protocol

#### MsgStartBatch
```cbor
[2]
```
- Tag: 2
- Server indicates it will start sending blocks

#### MsgNoBlocks
```cbor
[3]
```
- Tag: 3
- Server does not have the requested range

#### MsgBlock
```cbor
[4, blockBytes]
```
- Tag: 4
- `blockBytes`: The complete serialized block (CBOR byte string)

#### MsgBatchDone
```cbor
[5]
```
- Tag: 5
- Server signals end of the current batch

### 6.5 Pipelining in BlockFetch

BlockFetch supports pipelining of `MsgRequestRange`:

- The client can send multiple range requests while previous ones are being streamed
- This allows overlapping network transfer with block validation
- The server processes ranges in order and responds to each in sequence
- In practice, the decision logic determines how many blocks to request from each
  peer in parallel

### 6.6 Block Fetch Decision Logic (Client Side)

The client typically runs a "block fetch decision" algorithm:

1. ChainSync provides candidate chains from each peer (headers)
2. The decision logic compares candidate chains with the current chain
3. It selects which blocks to download and from which peers
4. It issues `MsgRequestRange` for each block range
5. Multiple BlockFetch clients (one per peer) can work in parallel

---

## 7. Tx Submission Miniprotocol (N2N)

### 7.1 Overview

| Property          | Value |
|-------------------|-------|
| Miniprotocol ID   | 6     |
| Connection type   | N2N only |
| Initiator         | Client (but Server drives the request cycle -- see below) |
| Pipelining        | Yes   |

TxSubmission2 is the node-to-node transaction submission protocol. It uses a
**pull-based** design: the server (downstream node) requests transaction IDs and
then selectively requests full transactions. This prevents flooding.

**Important:** Although the client is the "initiator" in the connection sense,
the server drives the protocol by requesting transaction IDs. The client responds
with available transactions.

### 7.2 State Machine

```
                    ┌────────┐
           ┌───────►│ StIdle │  (Server has agency)
           │        └───┬────┘
           │            │
           │   ┌────────┼──────────────┐
           │   │        │              │
           │ MsgRequestTxIds      MsgRequestTxs     (to client: "give me txs")
           │ (blocking, ack,       (txIds)
           │  reqCount)              │
           │   │                     │
           │   ▼                     ▼
           │ ┌────────────┐    ┌───────────┐
           │ │ StTxIds    │    │ StTxs     │
           │ └─────┬──────┘    └─────┬─────┘
           │       │                 │
           │  MsgReplyTxIds     MsgReplyTxs
           │  (txIds+sizes)    (txs)
           │       │                 │
           └───────┴────┬────────────┘
                        │
                   MsgDone (from StIdle, client terminates)
                        │
                        ▼
                   ┌────────┐
                   │ StDone │
                   └────────┘
```

Wait -- let me correct. The agency model for TxSubmission2 is:

**In StIdle:** The **server** has agency (it requests tx IDs or txs).
**In StTxIds and StTxs:** The **client** has agency (it provides the data).

But `MsgDone` can only be sent when the **client** has agency -- so actually, there's
a subtlety. Let me re-examine.

### 7.3 Revised State Machine

Actually, in TxSubmission2:

| State      | Agency | Description |
|-----------|--------|-------------|
| StInit    | Client | Initial state. Client sends MsgInit. |
| StIdle    | Server | Server requests tx IDs or full txs |
| StTxIds   | Client | Client responds with tx IDs |
| StTxs     | Client | Client responds with full txs |
| StDone    | Nobody | Terminal state |

**Transitions:**

| From     | Message           | To       | Sender |
|----------|-------------------|----------|--------|
| StInit   | MsgInit           | StIdle   | Client |
| StIdle   | MsgRequestTxIds   | StTxIds  | Server |
| StIdle   | MsgRequestTxs     | StTxs    | Server |
| StTxIds  | MsgReplyTxIds     | StIdle   | Client |
| StTxIds  | MsgDone           | StDone   | Client |
| StTxs    | MsgReplyTxs       | StIdle   | Client |

### 7.4 Messages and CBOR Encoding

#### MsgInit
```cbor
[6]
```
- Tag: 6
- Sent by client to initiate the protocol (TxSubmission2 addition)

#### MsgRequestTxIds
```cbor
[0, blocking, ackCount, reqCount]
```
- Tag: 0
- `blocking`: boolean -- if true, the client should block until it has at least one
  tx to offer (used when the server is at the tip and waiting for new txs)
- `ackCount`: Word16 -- number of previously offered tx IDs the server acknowledges
  having processed (successfully received or rejected)
- `reqCount`: Word16 -- number of new tx IDs the server wants

#### MsgReplyTxIds
```cbor
-- Non-blocking reply:
[1, [[txId, txSizeInBytes], ...]]

-- Blocking reply (when there are txs):
[1, [[txId, txSizeInBytes], ...]]

-- Done (no more txs, client terminates -- only valid for blocking request):
[1, []]   -- empty list signals done
```
- Tag: 1
- Array of pairs: `[txId, sizeInBytes]`
- `txId`: transaction ID (32-byte hash)
- `sizeInBytes`: Word32, size of the serialized transaction

#### MsgRequestTxs
```cbor
[2, [txId1, txId2, ...]]
```
- Tag: 2
- Array of transaction IDs the server wants to download

#### MsgReplyTxs
```cbor
[3, [tx1, tx2, ...]]
```
- Tag: 3
- Array of serialized transactions (CBOR byte strings)
- The order should match the requested txIds
- If a tx is no longer available, it may be omitted

#### MsgDone
```cbor
[4]
```
- Tag: 4
- Client terminates the protocol (only from StTxIds when blocking and no txs)

### 7.5 Flow Control Mechanism

The TxSubmission2 protocol implements **explicit flow control**:

1. Server sends `MsgRequestTxIds(blocking=false, ack=0, req=N)` to bootstrap
2. Client replies with up to N tx IDs and their sizes
3. Server selects which txs to download (filtering duplicates, checking size limits)
4. Server sends `MsgRequestTxs([txId1, txId2, ...])` for desired txs
5. Client replies with full transactions
6. Server sends next `MsgRequestTxIds(blocking, ack=M, req=K)` where M is the
   number of previously offered tx IDs now processed
7. This ack mechanism allows the client to clean up offered tx IDs

The `blocking` flag is used when the server has consumed all available tx IDs:
- If `blocking=true`, the client blocks until it has a new transaction (or terminates)
- If `blocking=false`, the client replies with an empty list if it has nothing

---

## 8. Local Tx Submission Miniprotocol (N2C)

### 8.1 Overview

| Property          | Value |
|-------------------|-------|
| Miniprotocol ID   | 8     |
| Connection type   | N2C only |
| Initiator         | Client |
| Pipelining        | Yes   |

Simple request-response protocol for submitting transactions from a local client
(wallet, CLI) to the node.

### 8.2 State Machine

```
     ┌────────┐
     │ StIdle │  (Client has agency)
     └───┬────┘
         │
    ┌────┴─────┐
    │          │
MsgSubmitTx  MsgDone
(tx)           │
    │          ▼
    ▼     ┌────────┐
┌───────┐ │ StDone │
│ StBusy│ └────────┘
└───┬───┘
    │  (Server has agency)
    │
┌───┴───────┐
│           │
MsgAcceptTx  MsgRejectTx
│           (reason)
▼           │
┌────────┐  ▼
│ StIdle │  ┌────────┐
└────────┘  │ StIdle │
            └────────┘
```

### 8.3 States

| State   | Agency | Description |
|---------|--------|-------------|
| StIdle  | Client | Client can submit a tx or terminate |
| StBusy  | Server | Server validates the tx |
| StDone  | Nobody | Terminal state |

### 8.4 Messages and CBOR Encoding

#### MsgSubmitTx
```cbor
[0, txBytes]
```
- Tag: 0
- `txBytes`: The complete serialized transaction (era-tagged CBOR)

#### MsgAcceptTx
```cbor
[1]
```
- Tag: 1
- Transaction was accepted into the mempool

#### MsgRejectTx
```cbor
[2, rejectReason]
```
- Tag: 2
- `rejectReason`: Era-specific validation error encoded as CBOR
  (the exact format depends on the ledger era and the specific validation failure)

#### MsgDone
```cbor
[3]
```
- Tag: 3
- Client terminates the protocol

---

## 9. Local State Query Miniprotocol (N2C)

### 9.1 Overview

| Property          | Value |
|-------------------|-------|
| Miniprotocol ID   | 7     |
| Connection type   | N2C only |
| Initiator         | Client |
| Pipelining        | Yes   |

Allows a local client to query the node's ledger state at a specific point on the
chain. This is how wallets query UTxO sets, protocol parameters, stake distribution,
etc.

### 9.2 State Machine

```
       ┌────────┐
       │ StIdle │  (Client has agency)
       └───┬────┘
           │
  ┌────────┼──────────┐
  │                    │
MsgAcquire          MsgDone
(point)                │
  │                    ▼
  ▼              ┌────────┐
┌────────────┐   │ StDone │
│ StAcquiring│   └────────┘
└─────┬──────┘
      │ (Server has agency)
      │
 ┌────┴──────────┐
 │               │
MsgAcquired   MsgFailure
 │            (reason)
 ▼               │
┌────────────┐   ▼
│ StAcquired │  ┌────────┐
└─────┬──────┘  │ StIdle │
      │         └────────┘
      │ (Client has agency)
      │
 ┌────┼──────────────┬───────────┐
 │                   │           │
MsgQuery        MsgReAcquire  MsgRelease
(query)         (point)
 │                   │           │
 ▼                   ▼           ▼
┌──────────┐   ┌────────────┐  ┌────────┐
│ StQuerying│  │ StAcquiring│  │ StIdle │
└─────┬─────┘  └────────────┘  └────────┘
      │ (Server has agency)
      │
MsgResult
(result)
      │
      ▼
┌────────────┐
│ StAcquired │
└────────────┘
```

### 9.3 States

| State         | Agency | Description |
|---------------|--------|-------------|
| StIdle        | Client | Client can acquire a state or terminate |
| StAcquiring   | Server | Server is acquiring the ledger state at the requested point |
| StAcquired    | Client | Ledger state acquired; client can query, re-acquire, or release |
| StQuerying    | Server | Server is processing a query |
| StDone        | Nobody | Terminal state |

### 9.4 Messages and CBOR Encoding

#### MsgAcquire
```cbor
[0, point]
```
- Tag: 0
- `point`: The chain point to acquire state for.
  - `[slotNo, headerHash]` for a specific point
  - In newer versions, a special value for "volatile tip" (current tip)

#### MsgAcquired
```cbor
[1]
```
- Tag: 1
- State successfully acquired

#### MsgFailure
```cbor
[2, failureReason]
```
- Tag: 2
- `failureReason`:
  - `0` = AcquireFailurePointTooOld -- point is before the immutable tip
  - `1` = AcquireFailurePointNotOnChain -- point is not on the current chain

#### MsgQuery
```cbor
[3, query]
```
- Tag: 3
- `query`: The specific query, encoded as era-specific CBOR (see below)

#### MsgResult
```cbor
[4, result]
```
- Tag: 4
- `result`: Query result encoded as era-specific CBOR

#### MsgRelease
```cbor
[5]
```
- Tag: 5
- Release the acquired state

#### MsgReAcquire
```cbor
[6, point]
```
- Tag: 6
- Acquire state at a different point without going back to StIdle

#### MsgDone
```cbor
[7]
```
- Tag: 7
- Client terminates the protocol

### 9.5 Available Queries

Queries are wrapped in the "hard fork combinator query" format. The outermost
encoding is:

```cbor
-- Query the current era:
[2, eraIndex, query]    -- BlockQuery for a specific era

-- Query the hard fork:
[0, ...]                -- GetInterpreter (for time translation)
[1]                     -- GetCurrentEra

-- In newer protocol versions:
query is just the inner query, and era selection may be implicit
```

**Common Shelley-era queries:**

| Query                        | Encoding                    | Result |
|------------------------------|-----------------------------|--------|
| GetLedgerTip                 | `[0]`                       | Point |
| GetEpochNo                   | `[1]`                       | Word64 |
| GetNonMyopicMemberRewards    | `[2, credentials]`          | Map |
| GetCurrentPParams            | `[3]`                       | ProtocolParameters |
| GetProposedPParamsUpdates    | `[4]`                       | Map |
| GetStakeDistribution         | `[5]`                       | Map |
| GetUTxOByAddress             | `[6, addrs]`                | UTxO map |
| GetUTxOWhole                 | `[7]`                       | Full UTxO set |
| DebugEpochState              | `[8]`                       | EpochState |
| GetCBOR                      | `[9, query]`                | Raw CBOR bytes |
| GetFilteredDelegationsAndRewardAccounts | `[10, credentials]` | Map |
| GetGenesisConfig             | `[11]`                      | GenesisConfig |
| DebugNewEpochState           | `[12]`                      | NewEpochState |
| DebugChainDepState           | `[13]`                      | ChainDepState |
| GetRewardProvenance          | `[14]`                      | RewardProvenance |
| GetUTxOByTxIn                | `[15, txins]`               | UTxO map |
| GetStakePools                | `[16]`                      | Set of pool IDs |
| GetStakePoolParams           | `[17, poolIds]`             | Map |
| GetRewardInfoPools           | `[18]`                      | RewardInfoPools |
| GetPoolState                 | `[19, poolIds]`             | PoolState |
| GetStakeSnapshots            | `[20, poolId]`              | StakeSnapshots |
| GetPoolDistr                 | `[21, poolIds]`             | PoolDistr |

**Conway-era additional queries:**

| Query                        | Encoding                    | Result |
|------------------------------|-----------------------------|--------|
| GetConstitution              | `[22]`                      | Constitution |
| GetGovState                  | `[23]`                      | GovState |
| GetDRepState                 | `[24, drepCreds]`           | Map |
| GetDRepStakeDistr            | `[25, drepCreds]`           | Map |
| GetCommitteeMembersState     | `[26, ...]`                 | CommitteeState |
| GetFilteredVoteDelegatees    | `[27, credentials]`         | Map |
| GetAccountState              | `[28]`                      | AccountState |

### 9.6 Hard Fork Query Wrapping

For nodes running the hard fork combinator (which all Cardano nodes do), queries
are wrapped:

```cbor
-- To query the current era's ledger:
[2, eraIndex, innerQuery]

-- eraIndex for each era:
-- 0 = Byron
-- 1 = Shelley
-- 2 = Allegra
-- 3 = Mary
-- 4 = Alonzo
-- 5 = Babbage
-- 6 = Conway

-- Alternatively, to get cross-era info:
[0]  -- GetInterpreter: returns EraHistory for time/slot translation
[1]  -- GetCurrentEra: returns the current era index
```

In practice, with newer N2C versions, there's a simpler wrapping. Clients
typically use the `GetCurrentEra` query first, then issue era-specific queries.

---

## 10. Local Tx Monitor Miniprotocol (N2C)

### 10.1 Overview

| Property          | Value |
|-------------------|-------|
| Miniprotocol ID   | 9     |
| Connection type   | N2C only |
| Initiator         | Client |
| Pipelining        | Yes   |

Allows a local client to monitor the node's mempool -- get a snapshot of pending
transactions, check if a specific transaction is in the mempool, query mempool
capacity, etc.

### 10.2 State Machine

```
          ┌────────┐
          │ StIdle │  (Client has agency)
          └───┬────┘
              │
     ┌────────┼──────────┐
     │                    │
  MsgAcquire          MsgDone
     │                    │
     ▼                    ▼
┌──────────────┐    ┌────────┐
│ StAcquiring  │    │ StDone │
└──────┬───────┘    └────────┘
       │ (Server has agency)
       │
  MsgAcquired
  (slotNo)
       │
       ▼
┌──────────────┐
│ StAcquired   │  (Client has agency)
└──────┬───────┘
       │
  ┌────┼──────────────────────┬─────────────────┐
  │                           │                 │
MsgNextTx              MsgHasTx            MsgGetSizes
  │                    (txId)                   │
  ▼                       ▼                     ▼
┌───────────────┐  ┌──────────────┐    ┌──────────────────┐
│ StBusy(NextTx)│  │ StBusy(HasTx)│    │ StBusy(GetSizes) │
└───────┬───────┘  └──────┬───────┘    └────────┬─────────┘
        │                 │                     │
  MsgReplyNextTx    MsgReplyHasTx        MsgReplyGetSizes
  (maybeTx)        (bool)               (sizes)
        │                 │                     │
        ▼                 ▼                     ▼
  ┌──────────────┐                        ┌──────────────┐
  │ StAcquired   │◄───────────────────────│ StAcquired   │
  └──────────────┘                        └──────────────┘

From StAcquired, client can also:
  MsgRelease → StIdle    (release the snapshot, re-acquire later)
```

### 10.3 States

| State                | Agency | Description |
|---------------------|--------|-------------|
| StIdle              | Client | Client can acquire a mempool snapshot or terminate |
| StAcquiring         | Server | Server is acquiring a consistent mempool snapshot |
| StAcquired          | Client | Snapshot acquired; client can query |
| StBusy(NextTx)      | Server | Server finding next tx in snapshot |
| StBusy(HasTx)       | Server | Server checking if tx is in mempool |
| StBusy(GetSizes)    | Server | Server computing mempool sizes |
| StDone              | Nobody | Terminal state |

### 10.4 Messages and CBOR Encoding

#### MsgAcquire
```cbor
[0]
```
- Tag: 0
- Acquire a consistent snapshot of the current mempool

#### MsgAcquired
```cbor
[1, slotNo]
```
- Tag: 1
- `slotNo`: The slot number at which the snapshot was taken

#### MsgRelease
```cbor
[2]
```
- Tag: 2
- Release the current snapshot

#### MsgNextTx
```cbor
[3]
```
- Tag: 3
- Request the next transaction from the snapshot (iterator-style)

#### MsgReplyNextTx
```cbor
-- Has a transaction:
[4, tx]

-- No more transactions:
[4, null]
```
- Tag: 4
- `tx`: Serialized transaction (era-wrapped CBOR) or null

#### MsgHasTx
```cbor
[5, txId]
```
- Tag: 5
- `txId`: Transaction ID to check (32-byte hash)

#### MsgReplyHasTx
```cbor
[6, bool]
```
- Tag: 6
- `bool`: true if the tx is in the mempool snapshot, false otherwise

#### MsgGetSizes
```cbor
[7]
```
- Tag: 7
- Request mempool capacity/size information

#### MsgReplyGetSizes
```cbor
[8, [capacityInBytes, sizeInBytes, numberOfTxs]]
```
- Tag: 8
- `capacityInBytes`: Word32, maximum mempool capacity in bytes
- `sizeInBytes`: Word32, current mempool size in bytes
- `numberOfTxs`: Word32, number of transactions in the mempool

#### MsgDone
```cbor
[9]
```
- Tag: 9
- Client terminates the protocol

### 10.5 Usage Pattern

Typical usage for monitoring the mempool:

1. `MsgAcquire` -- get a snapshot
2. Repeatedly `MsgNextTx` until `MsgReplyNextTx(null)` -- iterate all txs
3. `MsgRelease` -- release the snapshot
4. Back to step 1 for the next snapshot

For checking a specific tx:
1. `MsgAcquire`
2. `MsgHasTx(txId)`
3. Check `MsgReplyHasTx` result
4. `MsgRelease`

---

## 11. Keep Alive Miniprotocol (N2N)

### 11.1 Overview

| Property          | Value |
|-------------------|-------|
| Miniprotocol ID   | 8     |
| Connection type   | N2N only |
| Initiator         | Client |
| Pipelining        | No    |

Simple ping/pong protocol to detect dead connections and measure round-trip time.
Note: shares miniprotocol ID 8 with LocalTxSubmission, but they run on different
connection types (N2N vs N2C) so there's no conflict.

### 11.2 State Machine

```
     ┌────────┐
     │ StClient│  (Client has agency)
     └───┬────┘
         │
    ┌────┴─────┐
    │          │
MsgKeepAlive  MsgDone
(cookie)         │
    │            ▼
    ▼       ┌────────┐
┌─────────┐ │ StDone │
│ StServer│ └────────┘
└───┬─────┘
    │ (Server has agency)
    │
MsgKeepAliveResponse
(cookie)
    │
    ▼
┌────────┐
│StClient│
└────────┘
```

### 11.3 States

| State     | Agency | Description |
|-----------|--------|-------------|
| StClient  | Client | Client can send a ping or terminate |
| StServer  | Server | Server must respond with pong |
| StDone    | Nobody | Terminal state |

### 11.4 Messages and CBOR Encoding

#### MsgKeepAlive
```cbor
[0, cookie]
```
- Tag: 0
- `cookie`: Word16, an arbitrary value echoed back by the server (used to match
  responses and measure RTT)

#### MsgKeepAliveResponse
```cbor
[1, cookie]
```
- Tag: 1
- `cookie`: The same Word16 value from the request

#### MsgDone
```cbor
[2]
```
- Tag: 2
- Client terminates the protocol

### 11.5 Keep Alive Timing

- The client should send keep-alive pings periodically (e.g., every 10-20 seconds)
- If no response is received within a timeout (e.g., 30 seconds), the connection
  should be considered dead
- The cookie can be used to correlate requests and measure RTT
- RTT measurements feed into peer quality metrics for peer selection

---

## 12. Peer Sharing Miniprotocol (N2N)

### 12.1 Overview

| Property          | Value |
|-------------------|-------|
| Miniprotocol ID   | 10    |
| Connection type   | N2N only |
| Initiator         | Client |
| Pipelining        | No    |

Allows nodes to share known peer addresses with each other. This is how new
nodes discover peers beyond the initial bootstrap/relay list. Only available when
both nodes negotiate peer sharing support during handshake (N2N v11+).

### 12.2 State Machine

```
     ┌────────┐
     │ StIdle │  (Client has agency)
     └───┬────┘
         │
    ┌────┴─────────┐
    │              │
MsgShareRequest  MsgDone
(amount)           │
    │              ▼
    ▼         ┌────────┐
┌─────────┐   │ StDone │
│ StBusy  │   └────────┘
└───┬─────┘
    │ (Server has agency)
    │
MsgSharePeers
([peerAddresses])
    │
    ▼
┌────────┐
│ StIdle │
└────────┘
```

### 12.3 States

| State   | Agency | Description |
|---------|--------|-------------|
| StIdle  | Client | Client can request peers or terminate |
| StBusy  | Server | Server gathers peer addresses |
| StDone  | Nobody | Terminal state |

### 12.4 Messages and CBOR Encoding

#### MsgShareRequest
```cbor
[0, amount]
```
- Tag: 0
- `amount`: Word8, requested number of peers (the server may return fewer)

#### MsgSharePeers
```cbor
[1, [peerAddr1, peerAddr2, ...]]
```
- Tag: 1
- Each `peerAddr` is encoded as:
  ```cbor
  -- IPv4:
  [0, word32, portNumber]
  -- IPv6:
  [1, word32, word32, word32, word32, portNumber]
  ```

#### MsgDone
```cbor
[2]
```
- Tag: 2
- Client terminates

---

## 13. CBOR Encoding Conventions

### 13.1 General Message Framing

All miniprotocol messages are encoded as **CBOR arrays** where the first element
is an **unsigned integer tag** identifying the message type. This is consistent
across all miniprotocols.

```cbor
[tag, arg1, arg2, ...]
```

### 13.2 Message Encoding Within the Multiplexer

1. The miniprotocol serializes a message to CBOR bytes
2. The CBOR bytes become the **payload** of one or more multiplexer segments
3. Each segment gets an 8-byte MUX header prepended
4. The receiver reads the MUX header, extracts the payload, routes it to the
   correct miniprotocol
5. The miniprotocol's CBOR decoder reads from the incoming byte stream

If a CBOR-encoded message is larger than the maximum segment payload size, it
is split across multiple segments. The CBOR decoder on the receiving side naturally
handles this because it reads from a continuous byte stream.

### 13.3 Era-Wrapped Types

Many types in the protocol are "era-wrapped" due to the Hard Fork Combinator:

```cbor
-- Era-wrapped block (in ChainSync N2C):
[eraTag, blockCbor]

-- eraTag values:
-- 0 = Byron EBB
-- 1 = Byron regular block
-- 2 = Shelley
-- 3 = Allegra
-- 4 = Mary
-- 5 = Alonzo
-- 6 = Babbage
-- 7 = Conway

-- Era-wrapped header (in ChainSync N2N):
[eraTag, headerCbor]

-- Era-wrapped transaction (in TxSubmission/LocalTxSubmission):
[eraTag, txCbor]
```

### 13.4 Common CBOR Types

**Hash (Blake2b-256):** 32-byte CBOR byte string
```cbor
h'<64 hex characters>'   -- #6.24 in some contexts, or just bytes(32)
```

**SlotNo:** Unsigned 64-bit integer
```cbor
uint   -- CBOR major type 0
```

**BlockNo:** Unsigned 64-bit integer
```cbor
uint   -- CBOR major type 0
```

**Point:**
```cbor
-- Origin:
[]    -- empty CBOR array

-- Specific point:
[slotNo, headerHash]
```

**Tip:**
```cbor
[point, blockNo]
```

### 13.5 CBOR Diagnostic Notation Examples

**Handshake propose (N2N, offering versions 11-13):**
```cbor
[0, {
  11: [764824073, false, 1, false],
  12: [764824073, false, 1, false],
  13: [764824073, false, 1, false]
}]
```

**ChainSync MsgRollForward (N2N, Babbage header):**
```cbor
[2,
  [5, h'<serialized babbage header>'],
  [[42000000, h'<header hash 32 bytes>'], 42000001]
]
```

**BlockFetch MsgRequestRange:**
```cbor
[0,
  [41999990, h'<from hash>'],
  [42000000, h'<to hash>']
]
```

---

## 14. Implementation Notes for Scala

### 14.1 Architecture Overview for Stretto

```
stretto-network/
├── mux/
│   ├── MuxHeader.scala          -- 8-byte header codec (scodec)
│   ├── Multiplexer.scala        -- MUX: message → segments → bearer
│   ├── Demultiplexer.scala      -- DeMUX: bearer → segments → miniprotocol channels
│   └── Bearer.scala             -- TCP / Unix socket abstraction
├── protocol/
│   ├── Protocol.scala           -- Base typed-protocol state machine
│   ├── Agency.scala             -- ClientAgency / ServerAgency / NobodyAgency
│   ├── Peer.scala               -- AsClient / AsServer role wrappers
│   └── Codec.scala              -- Encode/decode messages per state
├── handshake/
│   ├── HandshakeState.scala     -- StPropose, StConfirm, StDone
│   ├── HandshakeMessage.scala   -- MsgProposeVersions, MsgAcceptVersion, MsgRefuse
│   ├── HandshakeCodec.scala     -- CBOR encode/decode
│   ├── HandshakeClient.scala    -- Client-side driver (IO)
│   └── HandshakeServer.scala    -- Server-side driver (IO)
├── chainsync/
│   ├── ChainSyncState.scala     -- StIdle, StNext, StMustReply, StIntersect, StDone
│   ├── ChainSyncMessage.scala   -- All ChainSync messages
│   ├── ChainSyncCodec.scala     -- CBOR encode/decode
│   ├── ChainSyncClient.scala    -- Client-side driver
│   └── ChainSyncServer.scala    -- Server-side driver
├── blockfetch/
│   ├── BlockFetchState.scala
│   ├── BlockFetchMessage.scala
│   ├── BlockFetchCodec.scala
│   ├── BlockFetchClient.scala
│   └── BlockFetchServer.scala
├── txsubmission/
│   ├── TxSubmissionState.scala
│   ├── TxSubmissionMessage.scala
│   ├── TxSubmissionCodec.scala
│   ├── TxSubmissionClient.scala
│   └── TxSubmissionServer.scala
├── localtxsubmission/
│   ├── ... (similar structure)
├── localstatequery/
│   ├── ... (similar structure)
│   └── Query.scala              -- All query types as ADT
├── localtxmonitor/
│   ├── ... (similar structure)
├── keepalive/
│   ├── ... (similar structure)
└── peersharing/
    ├── ... (similar structure)
```

### 14.2 Key Design Decisions

1. **No Java library wrapping.** All miniprotocols implemented from scratch using
   the Ouroboros network spec and typed-protocols model.

2. **cats-effect IO** for all effects (socket reads, state transitions, timeouts).

3. **fs2 Streams** for:
   - Reading from / writing to bearers (`fs2.io.net.Socket`)
   - The multiplexer/demultiplexer pipeline
   - ChainSync and BlockFetch streaming data to consumers
   - Backpressure via bounded `fs2.concurrent.Channel` or `cats.effect.std.Queue`

4. **scodec** for binary encoding:
   - MUX header codec (8 bytes, big-endian)
   - CBOR codec wrapper (using a CBOR library underneath)

5. **CBOR library:** Use `io.bullet:borer` (Scala CBOR/JSON library, pure Scala)
   or `co.nstant.in:cbor-java` with thin wrappers.

6. **ADTs for protocol states and messages:**
   ```scala
   enum ChainSyncState:
     case StIdle
     case StNext
     case StMustReply
     case StIntersect
     case StDone

   enum ChainSyncMessage:
     case MsgRequestNext
     case MsgAwaitReply
     case MsgRollForward(header: WrappedHeader, tip: Tip)
     case MsgRollBackward(point: Point, tip: Tip)
     case MsgFindIntersect(points: List[Point])
     case MsgIntersectFound(point: Point, tip: Tip)
     case MsgIntersectNotFound(tip: Tip)
     case MsgDone
   ```

7. **Type-safe state transitions:** Consider using phantom types or GADTs to
   ensure at compile time that only valid messages can be sent in each state.
   ```scala
   trait Protocol[S]:
     type Message[From, To]
     type Agency[S] <: ClientAgency | ServerAgency | NobodyAgency
   ```

### 14.3 Multiplexer Implementation with fs2

```scala
// Conceptual outline:

// MUX: Take messages from miniprotocol egress channels, frame them, write to bearer
def multiplex(
  channels: Map[MiniProtocolId, Queue[IO, ByteVector]],
  bearer: Socket[IO],
  mode: MuxMode  // Initiator or Responder
): Stream[IO, Nothing] =
  // Round-robin across channels, read messages, add MUX headers, write to socket
  Stream.emits(channels.toList)
    .covary[IO]
    .repeat
    .evalMap { case (protoId, queue) => queue.tryTake.map(_.map(protoId -> _)) }
    .unNone
    .evalMap { case (protoId, payload) =>
      val header = MuxHeader(
        transmissionTime = currentTimeMicros,
        mode = mode,
        miniProtocolId = protoId,
        payloadLength = payload.length.toInt
      )
      bearer.write(Chunk.byteVector(header.encode ++ payload))
    }

// DeMUX: Read from bearer, parse MUX headers, route to miniprotocol ingress channels
def demultiplex(
  channels: Map[MiniProtocolId, Queue[IO, ByteVector]],
  bearer: Socket[IO]
): Stream[IO, Nothing] =
  Stream.repeatEval {
    for
      headerBytes <- bearer.readN(8)
      header      <- IO.fromEither(MuxHeader.decode(headerBytes))
      payload     <- bearer.readN(header.payloadLength)
      _           <- channels(header.miniProtocolId).offer(payload)
    yield ()
  }.drain
```

### 14.4 Bearer Abstraction

```scala
trait Bearer[F[_]]:
  def read(numBytes: Int): F[Chunk[Byte]]
  def write(bytes: Chunk[Byte]): F[Unit]
  def close: F[Unit]

object Bearer:
  def tcp(socket: Socket[IO]): Bearer[IO] = ...
  def unix(socket: Socket[IO]): Bearer[IO] = ...  // via UnixSockets
```

### 14.5 Connection Establishment Flow

```scala
def connectN2N(host: String, port: Int, networkMagic: Long): Resource[IO, N2NConnection] =
  for
    socket     <- Network[IO].client(SocketAddress(host, port))
    bearer     = Bearer.tcp(socket)
    channels   <- Resource.eval(allocateChannels(n2nProtocols))
    muxFiber   <- multiplex(channels, bearer, Initiator).compile.drain.background
    demuxFiber <- demultiplex(channels, bearer).compile.drain.background
    version    <- Resource.eval(runHandshake(channels(0), networkMagic))
  yield N2NConnection(version, channels)

def listenN2C(socketPath: Path): Stream[IO, N2CConnection] =
  UnixSockets[IO].server(UnixSocketAddress(socketPath)).map { socket =>
    // similar setup but as Responder
  }
```

### 14.6 CBOR Codec Pattern

```scala
trait MiniProtocolCodec[Msg]:
  def encode(msg: Msg): ByteVector
  def decode(bytes: ByteVector): Either[DecodeError, Msg]

object ChainSyncCodec extends MiniProtocolCodec[ChainSyncMessage]:
  def encode(msg: ChainSyncMessage): ByteVector = msg match
    case MsgRequestNext          => Cbor.encodeArray(0)
    case MsgAwaitReply           => Cbor.encodeArray(1)
    case MsgRollForward(h, tip)  => Cbor.encodeArray(2, h.toCbor, tip.toCbor)
    case MsgRollBackward(pt, t)  => Cbor.encodeArray(3, pt.toCbor, t.toCbor)
    case MsgFindIntersect(pts)   => Cbor.encodeArray(4, pts.map(_.toCbor))
    case MsgIntersectFound(p, t) => Cbor.encodeArray(5, p.toCbor, t.toCbor)
    case MsgIntersectNotFound(t) => Cbor.encodeArray(6, t.toCbor)
    case MsgDone                 => Cbor.encodeArray(7)

  def decode(bytes: ByteVector): Either[DecodeError, ChainSyncMessage] =
    Cbor.decodeArray(bytes).flatMap { items =>
      items.head.asInt match
        case 0 => Right(MsgRequestNext)
        case 1 => Right(MsgAwaitReply)
        case 2 => for
            header <- WrappedHeader.fromCbor(items(1))
            tip    <- Tip.fromCbor(items(2))
          yield MsgRollForward(header, tip)
        // ... etc
    }
```

### 14.7 Testing Strategy

1. **Unit tests:** Encode/decode round-trip for every message type
2. **State machine tests:** Verify only valid transitions are possible
3. **Integration tests:** Connect to a real cardano-node and:
   - Perform handshake
   - Sync a few headers via ChainSync
   - Fetch blocks via BlockFetch
   - Query ledger state via LocalStateQuery
4. **Property-based tests:** Generate random valid message sequences and verify
   codec round-trip
5. **Conformance:** Compare our CBOR encoding byte-for-byte with the Haskell
   implementation's output

### 14.8 Performance Considerations

1. **Pipelining depth:** For ChainSync, pipeline up to 100-200 `MsgRequestNext`
   messages during initial sync. Reduce to 1-2 at the tip.
2. **Block Fetch parallelism:** Fetch from multiple peers simultaneously.
   The block fetch decision logic should distribute ranges across peers.
3. **Zero-copy where possible:** Use `ByteVector` (scodec-bits) or `Chunk[Byte]`
   (fs2) to avoid unnecessary copying during mux/demux.
4. **Bounded queues:** All ingress/egress queues must be bounded to prevent OOM.
   Use `Queue.bounded[IO, ByteVector](capacity)`.
5. **Segment size:** Use 12288 bytes as the default max segment payload size
   to match the Haskell implementation.

---

## Appendix A: Quick Reference — All Miniprotocol Messages

### Handshake (ID: 0)
| Tag | Message             | Direction | CBOR |
|-----|---------------------|-----------|------|
| 0   | MsgProposeVersions  | C → S     | `[0, {ver: data, ...}]` |
| 1   | MsgAcceptVersion    | S → C     | `[1, ver, data]` |
| 2   | MsgRefuse           | S → C     | `[2, reason]` |

### ChainSync (ID: 2/5)
| Tag | Message              | Direction | CBOR |
|-----|----------------------|-----------|------|
| 0   | MsgRequestNext       | C → S     | `[0]` |
| 1   | MsgAwaitReply        | S → C     | `[1]` |
| 2   | MsgRollForward       | S → C     | `[2, header/block, tip]` |
| 3   | MsgRollBackward      | S → C     | `[3, point, tip]` |
| 4   | MsgFindIntersect     | C → S     | `[4, [points...]]` |
| 5   | MsgIntersectFound    | S → C     | `[5, point, tip]` |
| 6   | MsgIntersectNotFound | S → C     | `[6, tip]` |
| 7   | MsgDone              | C → S     | `[7]` |

### BlockFetch (ID: 3)
| Tag | Message          | Direction | CBOR |
|-----|------------------|-----------|------|
| 0   | MsgRequestRange  | C → S     | `[0, point, point]` |
| 1   | MsgClientDone    | C → S     | `[1]` |
| 2   | MsgStartBatch    | S → C     | `[2]` |
| 3   | MsgNoBlocks      | S → C     | `[3]` |
| 4   | MsgBlock         | S → C     | `[4, blockBytes]` |
| 5   | MsgBatchDone     | S → C     | `[5]` |

### TxSubmission2 (ID: 6)
| Tag | Message          | Direction | CBOR |
|-----|------------------|-----------|------|
| 0   | MsgRequestTxIds  | S → C     | `[0, blocking, ack, req]` |
| 1   | MsgReplyTxIds    | C → S     | `[1, [[txId, size], ...]]` |
| 2   | MsgRequestTxs    | S → C     | `[2, [txIds...]]` |
| 3   | MsgReplyTxs      | C → S     | `[3, [txs...]]` |
| 4   | MsgDone          | C → S     | `[4]` |
| 6   | MsgInit          | C → S     | `[6]` |

### LocalTxSubmission (ID: 8, N2C)
| Tag | Message      | Direction | CBOR |
|-----|--------------|-----------|------|
| 0   | MsgSubmitTx  | C → S     | `[0, tx]` |
| 1   | MsgAcceptTx  | S → C     | `[1]` |
| 2   | MsgRejectTx  | S → C     | `[2, reason]` |
| 3   | MsgDone      | C → S     | `[3]` |

### LocalStateQuery (ID: 7, N2C)
| Tag | Message        | Direction | CBOR |
|-----|----------------|-----------|------|
| 0   | MsgAcquire     | C → S     | `[0, point]` |
| 1   | MsgAcquired    | S → C     | `[1]` |
| 2   | MsgFailure     | S → C     | `[2, reason]` |
| 3   | MsgQuery       | C → S     | `[3, query]` |
| 4   | MsgResult      | S → C     | `[4, result]` |
| 5   | MsgRelease     | C → S     | `[5]` |
| 6   | MsgReAcquire   | C → S     | `[6, point]` |
| 7   | MsgDone        | C → S     | `[7]` |

### LocalTxMonitor (ID: 9, N2C)
| Tag | Message           | Direction | CBOR |
|-----|-------------------|-----------|------|
| 0   | MsgAcquire        | C → S     | `[0]` |
| 1   | MsgAcquired       | S → C     | `[1, slotNo]` |
| 2   | MsgRelease        | C → S     | `[2]` |
| 3   | MsgNextTx         | C → S     | `[3]` |
| 4   | MsgReplyNextTx    | S → C     | `[4, tx_or_null]` |
| 5   | MsgHasTx          | C → S     | `[5, txId]` |
| 6   | MsgReplyHasTx     | S → C     | `[6, bool]` |
| 7   | MsgGetSizes       | C → S     | `[7]` |
| 8   | MsgReplyGetSizes  | S → C     | `[8, [cap, size, count]]` |
| 9   | MsgDone           | C → S     | `[9]` |

### KeepAlive (ID: 8, N2N)
| Tag | Message               | Direction | CBOR |
|-----|-----------------------|-----------|------|
| 0   | MsgKeepAlive          | C → S     | `[0, cookie]` |
| 1   | MsgKeepAliveResponse  | S → C     | `[1, cookie]` |
| 2   | MsgDone               | C → S     | `[2]` |

### PeerSharing (ID: 10, N2N)
| Tag | Message          | Direction | CBOR |
|-----|------------------|-----------|------|
| 0   | MsgShareRequest  | C → S     | `[0, amount]` |
| 1   | MsgSharePeers    | S → C     | `[1, [addrs...]]` |
| 2   | MsgDone          | C → S     | `[2]` |

---

## Appendix B: Multiplexer Header Byte Layout

```
Offset  Size  Field
──────  ────  ─────────────────────────────────
0       4     Transmission time (uint32, big-endian, microseconds)
4       2     Mode bit (1 bit) + Mini Protocol ID (15 bits), big-endian
              Bit 15 (MSB of byte 4): 0=Initiator→Responder, 1=Responder→Initiator
              Bits 14-0: Mini Protocol ID
6       2     Payload length (uint16, big-endian)
──────  ────  ─────────────────────────────────
Total:  8     bytes
```

**Encoding example (Initiator sending on ChainSync, ID=2, payload=256 bytes):**
```
Bytes: [TT TT TT TT] [00 02] [01 00]
       ╰──timestamp──╯ ╰─ID──╯ ╰len─╯

Mode bit = 0 (initiator), Protocol ID = 2
=> second word = 0x0002

Payload length = 256 = 0x0100
```

**Encoding example (Responder sending on ChainSync, ID=2, payload=1024 bytes):**
```
Bytes: [TT TT TT TT] [80 02] [04 00]
       ╰──timestamp──╯ ╰─ID──╯ ╰len─╯

Mode bit = 1 (responder), Protocol ID = 2
=> second word = 0x8002

Payload length = 1024 = 0x0400
```

---

## Appendix C: Version Negotiation Wire Example

**Client proposes N2N versions 11, 12, 13 for mainnet:**
```
MUX header: [timestamp] [0x0000] [payload_len]
                         ╰─ handshake ID=0, initiator mode

CBOR payload:
[0, {
  11: [764824073, false, 1, false],
  12: [764824073, false, 1, false],
  13: [764824073, false, 1, false]
}]

CBOR hex (approximate):
82                  -- array(2)
  00                -- 0 (MsgProposeVersions tag)
  A3                -- map(3)
    0B              -- 11
    84              -- array(4)
      1A 2D964A09   -- 764824073
      F4            -- false
      01            -- 1 (PeerSharingPublic)
      F4            -- false
    0C              -- 12
    84              -- array(4)
      1A 2D964A09
      F4
      01
      F4
    0D              -- 13
    84              -- array(4)
      1A 2D964A09
      F4
      01
      F4
```

**Server accepts version 13:**
```
MUX header: [timestamp] [0x8000] [payload_len]
                         ╰─ handshake ID=0, responder mode

CBOR payload:
[1, 13, [764824073, false, 1, false]]

CBOR hex (approximate):
83                  -- array(3)
  01                -- 1 (MsgAcceptVersion tag)
  0D                -- 13
  84                -- array(4)
    1A 2D964A09
    F4
    01
    F4
```
