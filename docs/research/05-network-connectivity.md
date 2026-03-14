# Network Connectivity & Ouroboros Wire Format

> Research document for the Stretto project.
> Compiled: 2026-03-14

## Table of Contents
1. [Preprod Node Endpoints](#1-preprod-node-endpoints)
2. [Connectivity Tests](#2-connectivity-tests)
3. [Ouroboros Multiplexer Framing](#3-ouroboros-multiplexer-framing)
4. [Handshake Protocol Wire Format](#4-handshake-protocol-wire-format)
5. [N2N Version Numbers](#5-n2n-version-numbers)
6. [Constructing a Handshake Message](#6-constructing-a-handshake-message)
7. [Next Steps](#7-next-steps)

---

## 1. Preprod Node Endpoints

| Protocol | Host | Port | Description |
|----------|------|------|-------------|
| N2N (node-to-node) | panic-station | 30010 | TCP, Ouroboros multiplexed |
| N2C (node-to-client) | panic-station | 30110 | TCP, Ouroboros multiplexed |

**Network magic values:**

| Network | Magic |
|---------|-------|
| Preprod | 1 |
| Preview | 2 |
| Mainnet | 764824073 |

The network magic is a 32-bit unsigned integer included in every handshake. It prevents nodes on different networks from communicating.

---

## 2. Connectivity Tests

> **STATUS: NOT YET RUN** -- Bash access was unavailable during this research session.
> These commands must be run manually to verify connectivity.

### DNS Resolution
```bash
host panic-station
# or
nslookup panic-station
```

### TCP Connectivity
```bash
nc -zv panic-station 30010   # N2N
nc -zv panic-station 30110   # N2C
```

### Capture Raw Bytes (with timeout)
```bash
# Connect, wait for server to send data (it won't -- the client must speak first)
echo -n "" | timeout 5 nc panic-station 30010 | xxd | head -50

# To actually see a response, we need to send a valid handshake proposal.
# See Section 6 for the exact bytes to send.
```

**Expected behavior:** The Ouroboros protocol requires the *client* (initiator) to send the first message (MsgProposeVersions). The server will not send anything until it receives a valid handshake proposal. So a bare `nc` connection will just hang until timeout.

---

## 3. Ouroboros Multiplexer Framing

Every message on the wire is wrapped in a multiplexer frame (also called a "segment" or "SDU"). The frame header is **8 bytes**:

```
Byte offset  Length  Field
──────────── ──────  ─────────────────────────────────
0            4       Transmission time (32-bit, microseconds, network byte order)
4            2       Mini-protocol ID (16-bit, network byte order)
6            2       Payload length (16-bit, network byte order)
```

**Bit 15 of the mini-protocol ID** (the MSB) encodes the direction:
- `0` = Initiator (client -> server)
- `1` = Responder (server -> client)

The remaining 15 bits are the actual mini-protocol ID.

### Mini-Protocol IDs

| ID | N2N Protocol | N2C Protocol |
|----|-------------|-------------|
| 0  | Handshake | Handshake |
| 2  | Chain Sync (headers) | Chain Sync (blocks) |
| 3  | Block Fetch | -- |
| 4  | Tx Submission | -- |
| 5  | -- | Local Tx Submission |
| 6  | Keep Alive | -- |
| 7  | -- | Local State Query |
| 8  | -- | Local Tx Monitor |
| 9  | Peer Sharing | -- |

### Frame Size Limits
- Maximum payload per frame: 65535 bytes (16-bit length field)
- Messages larger than this are segmented across multiple frames
- Maximum SDU size (configurable): typically 12288 bytes for the mux layer

### Example: An 8-byte mux header for a Handshake message from the initiator

```
00 00 00 00   -- transmission time (0 at start)
00 00         -- mini-protocol ID 0 (handshake), initiator bit = 0
00 XX         -- payload length (XX = length of CBOR payload)
```

---

## 4. Handshake Protocol Wire Format

The handshake is the first (and only) mini-protocol that runs on mini-protocol ID 0. It has three message types:

### Message Types (CBOR encoding)

Each message is a CBOR array where the first element is a tag integer:

| Tag | Message | Direction | CBOR Structure |
|-----|---------|-----------|---------------|
| 0 | `MsgProposeVersions` | Initiator -> Responder | `[0, {version: params, ...}]` |
| 1 | `MsgAcceptVersion` | Responder -> Initiator | `[1, version, params]` |
| 2 | `MsgRefuse` | Responder -> Initiator | `[2, reason]` |

### MsgProposeVersions (tag 0)

The initiator sends a CBOR array:
```
[0, { versionNumber: versionData, ... }]
```

- Element 0: integer `0` (message tag)
- Element 1: a CBOR **map** from version numbers (unsigned integers) to version data

### MsgAcceptVersion (tag 1)

The responder picks the highest mutually-supported version and replies:
```
[1, versionNumber, versionData]
```

### MsgRefuse (tag 2)

If no version matches:
```
[2, [reasonTag, ...]]
```

Refuse reasons:
- `[0, [supportedVersions...], versionNumber]` -- VersionMismatch
- `[1, versionNumber, errorMessage]` -- HandshakeDecodeError
- `[2, versionNumber, errorMessage]` -- Refused

---

## 5. N2N Version Numbers

Node-to-node version numbers (as of Conway era, cardano-node 10.x):

| Version | Era introduced | Key features |
|---------|---------------|-------------|
| 7 | Alonzo | Base N2N, Alonzo blocks |
| 8 | Alonzo | Alonzo + Keep-Alive |
| 9 | Babbage | Babbage blocks |
| 10 | Babbage | Full duplex |
| 11 | Conway | Peer sharing |
| 12 | Conway | Peer sharing (updated) |
| 13 | Conway | Conway features, latest |
| 14 | Conway | Query support |

### N2N Version Data (versionParams)

For N2N, the version data for each proposed version is a CBOR array:
```
[networkMagic, initiatorOnlyDiffusionMode, peerSharing, query]
```

Where:
- `networkMagic` (uint): the network discriminator (1 for preprod)
- `initiatorOnlyDiffusionMode` (bool): `false` for a full node, `true` for a client-only connection
- `peerSharing` (uint, versions >= 11): `0` = NoPeerSharing, `1` = PeerSharingPrivate, `2` = PeerSharingPublic
- `query` (bool, versions >= 14): `false` normally

For older versions (7-10), the version data is just:
```
[networkMagic, initiatorOnlyDiffusionMode]
```

### N2C Version Numbers

Node-to-client uses a different version numbering scheme (starting higher):

| Version | Notes |
|---------|-------|
| 16 | Babbage |
| 17 | Conway |
| 18 | Conway (latest) |

N2C version data is simpler:
```
networkMagic   (just the uint, not wrapped in an array for older versions)
```

---

## 6. Constructing a Handshake Message

### Step-by-step: N2N Handshake for Preprod

We want to send `MsgProposeVersions` proposing version 13 with preprod magic.

#### 1. Build the CBOR payload

The message in CBOR diagnostic notation:
```
[0, {13: [1, false, 0, false]}]
```

Breaking down the CBOR encoding:

```
82                 -- CBOR array of length 2
  00               -- unsigned integer 0 (MsgProposeVersions tag)
  A1               -- CBOR map of 1 entry
    0D             -- unsigned integer 13 (version number)
    84             -- CBOR array of length 4
      01           -- unsigned integer 1 (networkMagic = preprod)
      F4           -- CBOR false (initiatorOnlyDiffusionMode = false)
      00           -- unsigned integer 0 (peerSharing = NoPeerSharing)
      F4           -- CBOR false (query = false)
```

CBOR payload (hex): `82 00 A1 0D 84 01 F4 00 F4`
Payload length: 9 bytes

#### 2. Wrap in multiplexer frame

```
00 00 00 00        -- transmission time (0)
00 00              -- mini-protocol ID 0 (handshake), initiator direction
00 09              -- payload length = 9
82 00 A1 0D 84     -- CBOR payload start
01 F4 00 F4        -- CBOR payload end
```

**Complete 17-byte message (hex):**
```
00 00 00 00 00 00 00 09 82 00 A1 0D 84 01 F4 00 F4
```

#### 3. Proposing multiple versions (recommended)

In practice, a node should propose all supported versions. Here is a more complete proposal for versions 11, 12, and 13:

CBOR diagnostic:
```
[0, {
  11: [1, false, 0],
  12: [1, false, 0],
  13: [1, false, 0, false]
}]
```

```
82                 -- array(2)
  00               -- uint 0 (tag)
  A3               -- map(3)
    0B             -- uint 11
    83             -- array(3)
      01           --   uint 1 (magic)
      F4           --   false
      00           --   uint 0 (no peer sharing)
    0C             -- uint 12
    83             -- array(3)
      01           --   uint 1
      F4           --   false
      00           --   uint 0
    0D             -- uint 13
    84             -- array(4)
      01           --   uint 1
      F4           --   false
      00           --   uint 0
      F4           --   false (query)
```

CBOR payload (hex): `82 00 A3 0B 83 01 F4 00 0C 83 01 F4 00 0D 84 01 F4 00 F4`
Payload length: 19 bytes (0x13)

Complete mux-framed message:
```
00 00 00 00 00 00 00 13 82 00 A3 0B 83 01 F4 00 0C 83 01 F4 00 0D 84 01 F4 00 F4
```

### Verification Command

To send this handshake and capture the response:
```bash
# Single-version proposal (version 13, preprod magic)
printf '\x00\x00\x00\x00\x00\x00\x00\x09\x82\x00\xa1\x0d\x84\x01\xf4\x00\xf4' \
  | timeout 5 nc panic-station 30010 | xxd
```

Expected response: an 8-byte mux header followed by CBOR `[1, 13, [1, false, 0, false]]` (MsgAcceptVersion).

The responder's mux header will have the MSB of the mini-protocol ID set (bit 15 = 1), so the mini-protocol field will be `0x8000` instead of `0x0000`.

---

## 7. Next Steps

### Immediate Actions
1. **Run connectivity tests** -- execute the commands in Section 2 to verify panic-station is reachable
2. **Send handshake** -- use the printf command in Section 6 to perform a real handshake
3. **Capture and decode response** -- verify the server accepts our version proposal
4. **Check if panic-station needs IP resolution** -- it may be a local hostname defined in `/etc/hosts` or mDNS

### Implementation Plan for `modules/network`
1. **Multiplexer** -- implement the 8-byte frame header encode/decode using scodec
2. **Handshake codec** -- CBOR encode/decode for MsgProposeVersions, MsgAcceptVersion, MsgRefuse
3. **Handshake protocol** -- state machine: StPropose -> StConfirm -> StDone
4. **Connection manager** -- TCP connection with cats-effect `Network[IO]` (fs2)
5. **Integration test** -- connect to preprod node, complete handshake, verify accepted version

### Key References
- Ouroboros network source: https://github.com/IntersectMBO/ouroboros-network
- Handshake protocol types: `ouroboros-network-protocols/src/Ouroboros/Network/Protocol/Handshake/Type.hs`
- Handshake CBOR codec: `ouroboros-network-protocols/src/Ouroboros/Network/Protocol/Handshake/Codec.hs`
- Multiplexer (MUX): `ouroboros-network-framework/src/Ouroboros/Network/Mux/`
- Node-to-node versions: `ouroboros-network-api/src/Ouroboros/Network/NodeToNode/Version.hs`
- Node-to-client versions: `ouroboros-network-api/src/Ouroboros/Network/NodeToClient/Version.hs`
- yaci (Java reference): `com.bloxbean.cardano.yaci.core.protocol.handshake` package
