# Ouroboros Praos Deep Dive: Implementation Guide

> Research document for the Stretto (Cardano Scala Node) project.
> Compiled: 2026-03-14
>
> Sources:
> - Ouroboros Praos paper: https://eprint.iacr.org/2017/573.pdf (Bernardo David, Peter Gazi, Aggelos Kiayias, Alexander Russell)
> - Ouroboros Genesis paper: https://eprint.iacr.org/2018/378.pdf
> - ouroboros-consensus repo: https://github.com/IntersectMBO/ouroboros-consensus
> - Shelley formal ledger spec: https://github.com/IntersectMBO/cardano-ledger
> - Cardano mainnet genesis configuration files

## Table of Contents
1. [Protocol Overview](#1-protocol-overview)
2. [Slot Leadership / VRF](#2-slot-leadership--vrf)
3. [Chain Selection](#3-chain-selection)
4. [KES (Key Evolving Signatures)](#4-kes-key-evolving-signatures)
5. [Epoch Structure](#5-epoch-structure)
6. [Block Structure](#6-block-structure)
7. [Hard Fork Combinator](#7-hard-fork-combinator)
8. [Genesis / Bootstrap](#8-genesis--bootstrap)
9. [Security Parameters & Constants](#9-security-parameters--constants)
10. [Implementation Data Structures](#10-implementation-data-structures)

---

## 1. Protocol Overview

Ouroboros Praos is a proof-of-stake consensus protocol that divides time into discrete **slots** grouped into **epochs**. In each slot, zero, one, or multiple stakeholders may be elected as **slot leaders** who can produce a block. The election is **private** --- a stakeholder knows they are elected but no one else does until they publish their block. This is achieved via VRF (Verifiable Random Functions).

Key properties that Praos guarantees (under honest majority of stake):
- **Common Prefix (CP)**: Any two honest parties' chains agree except for at most `k` trailing blocks
- **Chain Growth (CG)**: Honest parties' chains grow at a predictable rate
- **Chain Quality (CQ)**: A sufficient fraction of blocks on the chain are produced by honest parties

### Protocol vs. Cardano Implementation

The Praos paper defines an idealized protocol. Cardano's actual implementation (Shelley onward) makes several practical refinements:
- Stake distribution snapshots lag by one full epoch (for stability)
- Nonce evolution uses a specific hash-chain construction
- The overlay schedule (d parameter) allows a gradual transition from federated to decentralized
- KES key evolution provides forward security not in the original Praos paper
- Operational certificates bind cold keys to KES keys

---

## 2. Slot Leadership / VRF

### 2.1 Overview

Each slot, a stake pool operator (SPO) privately evaluates a VRF to determine whether they are elected as the slot leader. The VRF takes as input the epoch nonce and the slot number, and produces a pseudorandom output that is compared against a threshold derived from the pool's relative stake.

### 2.2 VRF Algorithm

Cardano uses **ECVRF-ED25519-SHA512-Elligator2** (as specified in the IETF VRF draft, later RFC 9381). This is based on elliptic curve operations on Curve25519.

A VRF produces two outputs:
- **VRF output (hash)**: A 64-byte pseudorandom value, deterministic for a given key and input
- **VRF proof**: A ~80-byte proof that can be verified against the VRF public key

### 2.3 Leader Election Check

For a given slot `sl` in epoch `e`, a pool with VRF signing key `sk_vrf` checks leadership:

```
Input:
  eta_0       : epoch nonce (32 bytes)
  sl          : slot number (8 bytes, big-endian)
  sk_vrf      : pool's VRF signing key
  sigma       : pool's relative active stake (rational number in [0, 1])
  f           : active slot coefficient

Algorithm:
  1. Construct VRF input:
     vrf_input = "L" || eta_0 || slotToBytes(sl)

     In Shelley through Babbage, the input is the CBOR encoding of:
       (slot_number, epoch_nonce)
     prefixed with a domain separator byte.

     Specifically for the leader VRF:
       vrf_seed = mkSeed nonceLeader slot eta_0

     where mkSeed constructs:
       hashBlake2b256(purposeByte || slot || eta_0)

  2. Evaluate VRF:
     (vrf_output, vrf_proof) = VRF_eval(sk_vrf, vrf_input)

  3. Compute the "certNat" from VRF output:
     cert_nat = bytesToNatural(vrf_output)
     This interprets the 64-byte VRF output as a natural number
     (big-endian unsigned integer)

  4. Compute the threshold:
     denominator = 2^512  (since VRF output is 64 bytes = 512 bits)

     The pool is a leader if:
       cert_nat < threshold

     where:
       threshold = denominator * phi_f(sigma)
       phi_f(sigma) = 1 - (1 - f)^sigma

  5. If cert_nat < threshold, the pool IS the slot leader for this slot.
     Include vrf_proof in the block header.
```

### 2.4 The phi Function (Active Slot Coefficient)

The probability that a pool with relative stake `sigma` is elected in any given slot:

```
phi_f(sigma) = 1 - (1 - f)^sigma
```

Where:
- `f` is the **active slot coefficient** (mainnet: `f = 0.05`, meaning ~5% of slots have blocks)
- `sigma` is the pool's relative active stake: `pool_stake / total_active_stake`
- The function is designed so that splitting stake across pools does not change the aggregate probability of leadership

**Important property**: `phi_f(sigma_1 + sigma_2) = 1 - (1 - phi_f(sigma_1)) * (1 - phi_f(sigma_2))`

This ensures that the probability of being elected is proportional to stake regardless of how the stake is distributed across pools --- a single pool with 10% stake has the same expected block production as ten pools with 1% each.

### 2.5 VRF Input Construction (Cardano-specific)

Two distinct VRF evaluations occur per slot check:

1. **Leader VRF** (for slot leadership election):
   ```
   seed = mkSeed 0 slot eta_0
   ```
   Purpose byte `0` designates "leader check"

2. **Nonce VRF** (for contributing to epoch randomness):
   ```
   seed = mkSeed 1 slot eta_0
   ```
   Purpose byte `1` designates "nonce contribution"

The `mkSeed` function:
```
mkSeed :: Natural -> SlotNo -> Nonce -> Seed
mkSeed purpose slot nonce =
  hashBlake2b256(purpose_bytes || slot_bytes || nonce_bytes)

where:
  purpose_bytes = purpose encoded as 8-byte big-endian
  slot_bytes    = slot number encoded as 8-byte big-endian
  nonce_bytes   = 32-byte nonce value
```

### 2.6 Numerical Precision

The threshold comparison `cert_nat < 2^512 * phi_f(sigma)` requires arbitrary-precision arithmetic or careful fixed-point computation:

- `cert_nat` is a 512-bit natural number
- `(1-f)^sigma` where sigma is a rational number `p/q` requires computing `(1-f)^(p/q)`
- The Haskell implementation uses a Taylor series expansion of `ln(1 - f)` to compute this:
  ```
  (1-f)^sigma = exp(sigma * ln(1-f))
  ```
- Alternatively, use a rational approximation with sufficient precision (the Haskell node uses a specific number of terms)

**Practical approach**: Use a high-precision decimal library or BigInteger arithmetic. The comparison needs enough precision that rounding errors never cause a wrong leader election decision. The Haskell implementation uses a continued fraction / Taylor series approach with a configurable number of terms (typically ~40 terms for sufficient precision).

### 2.7 Stake Distribution for Leadership

The stake distribution used for leader election in epoch `e` is the **stake snapshot** taken at the **epoch boundary** of epoch `e-2` (two epochs ago), as described in Section 5.

---

## 3. Chain Selection

### 3.1 The Basic Rule

Ouroboros Praos uses a **longest chain** rule (by block count, not by slot number). When a node sees two valid chains, it adopts the one with more blocks, provided it does not require rolling back more than `k` blocks from the current chain.

**Precise rule:**
```
Given current chain C and candidate chain C':
  let I = intersection point of C and C' (the last common block)

  Prefer C' over C if and only if:
    1. C' is valid (all blocks pass validation)
    2. blockNo(tip(C')) > blockNo(tip(C))
    3. The rollback from C to I is at most k blocks:
       blockNo(tip(C)) - blockNo(I) <= k
```

### 3.2 The k Security Parameter

- **Mainnet value**: `k = 2160`
- `k` is the maximum number of blocks a node will roll back
- After `k` blocks have been appended on top of a block, that block is considered **immutable** (it will never be rolled back under honest majority assumptions)
- The `k` parameter also determines the **stability window** (see Section 5.4)

### 3.3 Tie-Breaking

When two chains have the same block count:
1. **Prefer the current chain** (do not switch unnecessarily)
2. If neither is the current chain, compare the **hash of the tip block** --- lower hash wins (deterministic tie-breaking)

In Cardano's implementation, the exact tie-breaking depends on the consensus protocol:
- In TPraos (Shelley-era Praos): issue number of the operational certificate is used as a tiebreaker (higher issue number wins, indicating a more recent key rotation)
- If issue numbers are the same, VRF output comparison is used

### 3.4 Chain Selection with the Hard Fork Combinator

When comparing chains across era boundaries, the HFC ensures:
- Block numbers are continuous across eras
- Chain selection treats the combined chain as a single logical chain
- The intersection point is found by traversing back through era boundaries if necessary

### 3.5 Ouroboros Genesis Extension

Ouroboros Genesis (a refinement of Praos deployed in later Cardano versions) modifies chain selection for nodes that are syncing from scratch:

- When syncing, a node must not simply trust the longest chain from a single peer
- Instead, it uses a **Genesis density comparison**: compare the density of chains in a window after the intersection point
- This prevents long-range attacks where an adversary presents a longer but less dense chain

**Genesis Density Rule:**
```
Given chains C and C' with intersection I:
  window_start = slot(I) + 1
  window_end = slot(I) + s  (where s = 3k/f, the stability window, called the "Genesis window")

  If the node is "caught up" (tip is recent): use standard longest-chain
  If the node is syncing:
    count_C  = number of blocks in C within [window_start, window_end]
    count_C' = number of blocks in C' within [window_start, window_end]
    Prefer the chain with higher density in this window
```

### 3.6 Chain Validity Checks

A chain is valid if every block in it passes:
1. **Header validation**: correct slot, block number, previous hash, VRF proof, KES signature, operational certificate
2. **Ledger validation**: all transactions in the block body are valid under the ledger state at that point
3. **Protocol version**: the block's protocol version is supported

### 3.7 Rollback Limit and Immutability

```
Immutable tip = tip(currentChain) - k blocks
```

- Blocks at or below the immutable tip are moved to the **Immutable DB** (append-only, never modified)
- Blocks above the immutable tip are in the **Volatile DB** (can be rolled back)
- The `k` parameter thus determines how much volatile state a node must maintain

---

## 4. KES (Key Evolving Signatures)

### 4.1 Purpose: Forward Security

KES provides **forward security**: even if an attacker compromises a pool's signing key at time `t`, they cannot forge signatures for any time period before `t`. This prevents an attacker from rewriting history.

### 4.2 How KES Works

KES is a signature scheme where the signing key **evolves** at regular intervals:

```
Key Evolution:
  sk_0                → initial signing key
  sk_1 = evolve(sk_0) → key for period 1
  sk_2 = evolve(sk_1) → key for period 2
  ...
  sk_n = evolve(sk_{n-1})

Properties:
  - The verification key vk remains the same for ALL periods
  - Given sk_i, it is infeasible to compute sk_j for j < i
  - After evolving to period i+1, sk_i is securely deleted
```

### 4.3 KES Construction Used in Cardano

Cardano uses a **Sum KES** construction (also called "sum composition"):

- A KES scheme with depth `d` supports `2^d` time periods
- Cardano mainnet uses depth `d = 6`, giving `2^6 = 64` KES periods per operational certificate
- Each KES period is `129600` slots = `129600 seconds` = `36 hours`
- Total KES key lifetime: `64 * 36 hours = 2304 hours = 96 days`

**Sum KES (depth d)**:
```
If d = 0:
  It's a single Ed25519 key pair, valid for 1 period

If d > 0:
  Generate two sub-KES schemes of depth d-1 (left and right)
  The signing key is (sk_left, sk_right, period, vk_left, vk_right)

  For period p:
    If p < 2^(d-1): use left sub-scheme for period p
    If p >= 2^(d-1): use right sub-scheme for period (p - 2^(d-1))

  Verification key = H(vk_left || vk_right)

  Signature for period p:
    If p < 2^(d-1):
      (sig_left(msg, p), vk_right, p)
    Else:
      (sig_right(msg, p - 2^(d-1)), vk_left, p)
```

### 4.4 KES Period Calculation

```
kes_period(slot) = slot / slotsPerKESPeriod

Mainnet values:
  slotsPerKESPeriod = 129600
  maxKESEvolutions  = 62  (not 64; the first period is 0, so 62 evolutions = 63 periods used)

Current KES period for a block at slot s:
  current_kes_period = s / 129600

KES period within an operational certificate:
  The operational certificate records the KES period at which it was issued (kes_period_start).
  The key used to sign must be at evolution: current_kes_period - kes_period_start
  This must be >= 0 and <= maxKESEvolutions
```

### 4.5 Operational Certificates (OpCert)

An operational certificate **binds** a pool's cold key to a hot KES key, with a counter to prevent reuse:

```
OpCert = {
  hot_vk      : KES verification key (the hot key for this period)
  counter     : Word64 (monotonically increasing, prevents old key reuse)
  kes_period  : KESPeriod (the starting KES period for this certificate)
  cold_sig    : Ed25519 signature of (hot_vk || counter || kes_period)
                by the pool's cold key
}
```

**Validation rules:**
1. The cold key that signed the OpCert must be the registered pool operator key
2. The counter must be strictly greater than any previously seen counter for this pool (monotonically increasing)
3. `current_kes_period - opcert.kes_period` must be in `[0, maxKESEvolutions]`
4. The KES signature on the block header must verify under `hot_vk` at evolution `current_kes_period - opcert.kes_period`

### 4.6 Key Hierarchy

```
Pool Cold Key (Ed25519)
  - Kept offline / in cold storage
  - Used only to sign operational certificates
  - Registered on-chain as the pool operator key

  └─→ Operational Certificate
       - Binds cold key to hot KES key
       - Has a counter (monotonically increasing)
       - Has a starting KES period

       └─→ Hot KES Key (Sum KES, depth 6)
            - Used to sign block headers
            - Evolves every 129600 slots (36 hours)
            - Must be rotated (new OpCert) before maxKESEvolutions is reached

Pool VRF Key (VRF-ED25519)
  - Used for leader election (separate from KES)
  - Registered on-chain (hash only)
  - Does NOT evolve; remains constant
```

---

## 5. Epoch Structure

### 5.1 Time Division

```
Mainnet parameters:
  slotLength        = 1 second
  epochLength       = 432000 slots = 432000 seconds = 5 days
  activeSlotsCoeff  = 1/20 = 0.05 (f parameter)

Expected blocks per epoch = epochLength * f = 432000 * 0.05 = 21600 blocks
```

Slot numbering is **absolute** (from the start of the blockchain, not from the start of the epoch):
```
epoch(slot)      = slot / epochLength
slotInEpoch(slot) = slot % epochLength
epochStartSlot(e) = e * epochLength
```

### 5.2 Stake Distribution Snapshots

Cardano uses a **snapshot mechanism** that creates a stable view of the stake distribution for leader election. There is a deliberate **two-epoch delay** between when a snapshot is taken and when it becomes active for leader election:

```
Epoch timeline:

  Epoch e-2: Stake snapshot S is taken at the boundary (end of epoch e-2)
  Epoch e-1: Snapshot S is "set" (marked for use)
  Epoch e:   Snapshot S is "active" (used for leader election in epoch e)

In the Shelley formal spec, there are three snapshots maintained:
  - Mark:  the most recent snapshot (taken at the current epoch boundary)
  - Set:   the snapshot from one epoch ago (was the Mark last epoch)
  - Go:    the snapshot from two epochs ago (the one currently active for leader election)

At each epoch boundary:
  Go   <- Set
  Set  <- Mark
  Mark <- current stake distribution
```

**What the snapshot contains:**
- For each pool: the total active stake delegated to it
- Pool parameters (cost, margin, pledge)
- The total active stake in the system
- This determines `sigma_i` (relative stake) = `pool_stake_i / total_active_stake`

### 5.3 Nonce Evolution

The epoch nonce is used as the VRF input to ensure leader election is tied to on-chain randomness. The nonce evolves across epochs:

```
Epoch Nonce Construction:

  eta_0(epoch e) = hash(eta_v(epoch e-1) || nonce_e || extra_entropy)

Where:
  eta_v = "evolving nonce" — accumulated from VRF outputs during the epoch
  nonce_e = additional nonce (from the "nonce contribution" VRF outputs)
  extra_entropy = optional extra entropy from protocol parameter updates (can override)

Evolving Nonce (eta_v) during an epoch:
  eta_v starts at the epoch nonce from the previous epoch
  For each block produced in the epoch (within the first 2k/f slots):
    eta_v = hash(eta_v || vrf_nonce_output(block))

  After 2k/f slots, the nonce is "frozen" — no more VRF outputs are incorporated

The epoch nonce for epoch e+1:
  eta_0(e+1) = hash(eta_v(e) || eta_c(e))

  where eta_c(e) is the "candidate nonce" derived from the accumulated VRF nonce outputs
  during the first 2k/f slots of epoch e
```

### 5.4 The Stability Window (2k/f)

```
stability_window = 3 * k / f  (but historically called "2k/f" in some docs)

Mainnet:
  k = 2160
  f = 0.05
  stability_window = 3 * 2160 / 0.05 = 129600 slots = 1.5 days

But the randomness stabilization window is:
  randomness_stability_window = 4 * k / f = 4 * 2160 / 0.05 = 172800 slots = 2 days
```

The stability window serves two purposes:

1. **Nonce freezing**: VRF nonce outputs are only incorporated during the first `4k/f` slots of an epoch. After that, the epoch nonce for the next epoch is determined and cannot be influenced by an adversary who might grind blocks.

2. **Stake distribution stability**: By the time the nonce is frozen, it's also too late for stake movements to affect the snapshot that will be used for the next epoch's leader election.

**Detailed nonce timeline within an epoch:**
```
Epoch e (432000 slots):
  Slots 0 to 172799 (first 4k/f = 172800 slots):
    VRF nonce outputs from blocks ARE incorporated into eta_v
    This is the "nonce-gathering" window

  Slots 172800 to 431999 (remaining slots):
    VRF nonce outputs are NOT incorporated
    The evolving nonce is "frozen"
    Adversary cannot influence next epoch's randomness
```

### 5.5 Epoch Transition (TICK / NEWEPOCH)

At each epoch boundary, the ledger state undergoes several transitions:

```
NEWEPOCH transition (at the boundary between epoch e-1 and epoch e):
  1. Rotate stake snapshots: Go <- Set, Set <- Mark, Mark <- current
  2. Compute new epoch nonce: eta_0(e) from eta_v(e-1) and eta_c(e-1)
  3. Calculate and distribute rewards for epoch e-1
  4. Apply any pending protocol parameter updates
  5. Decay the treasury / reserves (monetary expansion)
  6. Reset the evolving nonce: eta_v = eta_0(e)
```

---

## 6. Block Structure

### 6.1 Praos Block Header

A Shelley/Praos-era block header contains:

```
BlockHeader = {
  header_body : HeaderBody
  kes_sig     : KES Signature over (header_body serialized as CBOR)
}

HeaderBody = {
  block_number       : Word64          -- monotonically increasing
  slot               : SlotNo          -- the slot this block is in
  prev_hash          : Hash32          -- Blake2b-256 hash of the previous block header
  issuer_vk          : VKey            -- pool's cold verification key (Ed25519)
  vrf_vk             : VRFVKey         -- pool's VRF verification key
  vrf_result         : VRFCert         -- VRF output and proof for the "nonce" input
  block_body_size    : Word32          -- size of the block body in bytes
  block_body_hash    : Hash32          -- Blake2b-256 hash of the block body
  operational_cert   : OpCert          -- operational certificate
  protocol_version   : (Word, Word)    -- major.minor protocol version
}

VRFCert = {
  vrf_output : ByteString (64 bytes)   -- the VRF output value
  vrf_proof  : ByteString (80 bytes)   -- the VRF proof
}

OpCert = {
  hot_vk      : KESVKey     -- hot KES verification key
  sequence_no : Word64      -- monotonically increasing counter
  kes_period  : KESPeriod   -- starting KES period for this cert
  sigma       : Signature   -- cold key signature over (hot_vk, sequence_no, kes_period)
}
```

### 6.2 Block Body

```
BlockBody = {
  transactions       : [Transaction]
  transaction_witness_sets : [TransactionWitnessSet]  -- one per tx
}
```

The exact transaction format varies by era (Shelley, Allegra, Mary, Alonzo, Babbage, Conway).

### 6.3 CBOR Encoding

Blocks are CBOR-encoded. The CDDL schema files in the cardano-ledger repo define the exact binary format:
- `eras/shelley/impl/cddl-files/shelley.cddl`
- `eras/babbage/impl/cddl-files/babbage.cddl`
- `eras/conway/impl/cddl-files/conway.cddl`

Key encoding notes:
- Block headers use **canonical CBOR** for hashing
- The header hash (used in chain references) is `Blake2b-256(CBOR(header_body))`
- Block body hash is `Blake2b-256(CBOR(block_body))`

### 6.4 Block Header Validation

```
validateHeader(header, ledgerState, currentSlot):
  1. Verify block_number = prev_block_number + 1
  2. Verify slot > prev_slot (strictly increasing)
  3. Verify slot <= currentSlot (not from the future)
  4. Verify prev_hash = hash(previous block header)
  5. Verify issuer_vk is a registered pool operator
  6. Verify vrf_vk matches the registered VRF key hash for this pool
  7. Verify VRF proof:
     a. Reconstruct VRF input: mkSeed 0 slot eta_0
     b. VRF_verify(vrf_vk, vrf_input, vrf_result) == true
     c. Check leadership threshold: certNat(vrf_output) < threshold(sigma, f)
  8. Verify VRF nonce proof (second VRF eval):
     a. Reconstruct nonce input: mkSeed 1 slot eta_0
     b. VRF_verify(vrf_vk, nonce_input, nonce_result) == true
  9. Verify operational certificate:
     a. Verify cold_sig over (hot_vk, sequence_no, kes_period) using issuer_vk
     b. Verify sequence_no > previous sequence_no for this pool (or first block)
     c. current_kes_period = slot / slotsPerKESPeriod
     d. kes_evolution = current_kes_period - opcert.kes_period
     e. Verify 0 <= kes_evolution <= maxKESEvolutions
  10. Verify KES signature:
      a. KES_verify(hot_vk, kes_evolution, header_body_cbor, kes_sig) == true
```

**Note on VRF changes across eras**: In the Babbage era and later, the block header contains a single VRF result (the leader VRF). The nonce VRF output is derived from the leader VRF output via hashing, eliminating the need for a separate VRF evaluation. Specifically:
```
Babbage+:
  leader_vrf_output = VRF_eval(sk_vrf, mkSeed 0 slot eta_0)
  nonce_contribution = hashBlake2b256(leader_vrf_output)

  Block header only contains one VRFCert (the leader VRF)
```

---

## 7. Hard Fork Combinator

### 7.1 Purpose

The Hard Fork Combinator (HFC) allows Cardano to transition between protocol eras (Byron -> Shelley -> Allegra -> Mary -> Alonzo -> Babbage -> Conway) without actual hard forks in the traditional sense. Each era has its own:
- Block format
- Ledger rules
- Consensus protocol (PBFT for Byron, TPraos for Shelley-Babbage, Praos for Conway)

The HFC combines these into a single unified chain.

### 7.2 Architecture

```
HardForkBlock = OneOf(ByronBlock, ShelleyBlock, AllegraBlock, MaryBlock, AlonzoBlock, BabbageBlock, ConwayBlock)

HardForkLedgerState = OneOf(ByronLedger, ShelleyLedger, ..., ConwayLedger)

HardForkChainSelConfig = All(ByronConfig, ShelleyConfig, ..., ConwayConfig)
```

The HFC is essentially a **type-level list** of eras, where:
- Each era defines its own block type, ledger state, and consensus
- The HFC wraps them in a sum type (tagged union)
- Chain selection, validation, and forging are dispatched to the appropriate era-specific implementation

### 7.3 Era Transitions

An era transition is triggered by a **protocol parameter update**:
- The current era's protocol version is updated to the next era's version
- The transition happens at the **next epoch boundary** after the update is adopted
- The HFC tracks the "transition point" (the epoch at which each era starts)

```
Era transition trigger:
  1. Protocol version update proposal (via on-chain governance or update mechanism)
  2. Update is adopted (sufficient support / quorum)
  3. At the next epoch boundary, the new era begins
  4. The ledger state is translated from the old era to the new era

Translation:
  translateLedgerState :: OldEraLedger -> NewEraLedger
  - Preserves all UTxOs
  - Converts protocol parameters to new format
  - Adjusts any era-specific state
```

### 7.4 Cross-Era Chain Selection

When comparing chains that span multiple eras:
1. Block numbers are continuous across era boundaries
2. The HFC compares chains using the **era-aware comparison**: within each era, use that era's chain selection; across eras, use block number
3. A chain in a later era is always preferred over one that stops in an earlier era (assuming same length), because the era transition implies consensus on the upgrade

### 7.5 Era-Specific Consensus

| Era | Consensus | Notes |
|-----|-----------|-------|
| Byron | OBFT (Ouroboros BFT) | Federated, round-robin slot leadership |
| Shelley | TPraos | Praos with transition overlay schedule |
| Allegra | TPraos | Same consensus as Shelley |
| Mary | TPraos | Same consensus as Shelley |
| Alonzo | TPraos | Same consensus as Shelley |
| Babbage | TPraos | Single VRF (optimization) |
| Conway | Praos | Full Praos (no overlay, d=0 enforced) |

**TPraos vs Praos**: TPraos ("Transitional Praos") includes support for the `d` parameter (decentralization parameter) and the overlay schedule. When `d > 0`, some slots are reserved for the BFT federation. When `d = 0` (which is the case since epoch 257 on mainnet), TPraos behaves identically to Praos. Conway era formalizes this by removing the `d` parameter entirely.

### 7.6 Implementation Approach for Stretto

For Stretto (targeting current mainnet), the practical implementation can:
1. Implement the HFC as a Scala sealed trait / enum with one variant per era
2. Focus on Conway (current era) for new block validation and production
3. Support Shelley-through-Conway for historical chain validation
4. Byron support is needed only for bootstrap/initial sync

---

## 8. Genesis / Bootstrap

### 8.1 Genesis Configuration

The genesis of the Cardano blockchain requires several configuration files:

```
Byron Genesis (byron-genesis.json):
  - Initial UTxO distribution
  - Protocol parameters (slot duration, epoch length for Byron)
  - Boot stakeholders (BFT leaders)
  - Network magic (764824073 for mainnet)
  - Start time (2017-09-23T21:44:51Z for mainnet)

Shelley Genesis (shelley-genesis.json):
  - Network magic
  - Epoch length: 432000
  - Slot length: 1 second
  - Active slots coefficient: 0.05
  - Security parameter k: 2160
  - Max KES evolutions: 62
  - Slots per KES period: 129600
  - Max Lovelace supply: 45000000000000000
  - Update quorum
  - Initial protocol parameters (minFeeA, minFeeB, etc.)
  - Initial funds (if any)
  - Initial staking (if any)
  - Shelley genesis hash (used in node configuration)

Alonzo Genesis (alonzo-genesis.json):
  - Plutus cost models (V1, V2)
  - Execution unit prices
  - Max tx execution units
  - Max block execution units
  - Collateral percentage

Conway Genesis (conway-genesis.json):
  - Governance action parameters
  - DRep parameters
  - Constitutional committee parameters
  - Plutus V3 cost model
```

### 8.2 Bootstrap Process

A new node bootstrapping from scratch:

```
1. Load genesis configuration files
2. Initialize Byron ledger state from byron-genesis.json
3. Begin chain sync:
   a. Connect to peers
   b. Download block headers (Chain Sync miniprotocol)
   c. Validate headers
   d. Download block bodies (Block Fetch miniprotocol)
   e. Apply blocks to ledger state
4. Byron era: validate using OBFT rules
5. At the Byron-to-Shelley transition epoch:
   a. Translate Byron ledger state to Shelley ledger state
   b. Apply Shelley genesis (initial funds, staking)
   c. Continue with TPraos validation
6. Continue through era transitions until caught up
7. Once caught up, begin normal operation (gossip, potential block production)
```

### 8.3 Byron-to-Shelley Transition

This is the most complex era transition:

```
Byron -> Shelley Translation:
  1. All Byron UTxOs are converted to Shelley UTxOs
     (Byron addresses remain valid, just wrapped)
  2. The Shelley genesis initial funds are added to the UTxO set
  3. The Shelley genesis initial staking is applied
  4. Protocol parameters switch to Shelley defaults
  5. The decentralization parameter d starts at 1.0 (fully federated)
     and is gradually decreased via protocol parameter updates
  6. The slot numbering continues (Byron slots map to Shelley slots
     based on the different slot lengths: Byron = 20s, Shelley = 1s,
     so Byron slot N maps to Shelley slot N * 20)
```

### 8.4 Mithril Bootstrap (Optimization)

For faster bootstrap, Cardano supports importing **Mithril snapshots**:
- Mithril is a stake-based threshold multi-signature scheme
- Trusted snapshots of the UTxO set / ledger state are periodically created
- A node can import a snapshot instead of replaying from genesis
- The node verifies the Mithril certificate chain and then starts syncing from the snapshot point

---

## 9. Security Parameters & Constants

### 9.1 Mainnet Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| `k` | 2160 | Security parameter (rollback limit) |
| `f` | 1/20 (0.05) | Active slot coefficient |
| `slotLength` | 1 second | Duration of one slot |
| `epochLength` | 432000 slots (5 days) | Slots per epoch |
| `slotsPerKESPeriod` | 129600 | Slots per KES key evolution |
| `maxKESEvolutions` | 62 | Max number of KES key evolutions |
| `stabilityWindow` | 3k/f = 129600 slots | Window for chain stability |
| `randomnessStabilizationWindow` | 4k/f = 172800 slots | Window for nonce freezing |
| `networkMagic` | 764824073 | Mainnet network identifier |
| `maxLovelaceSupply` | 45,000,000,000,000,000 | Maximum ADA supply (45B ADA) |
| `updateQuorum` | 5 | Number of genesis keys needed for updates |

### 9.2 Testnet (Preview) Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| `k` | 2160 | Same as mainnet |
| `f` | 1/20 | Same as mainnet |
| `slotLength` | 1 second | Same as mainnet |
| `epochLength` | 86400 slots (1 day) | Shorter for faster testing |
| `networkMagic` | 2 | Preview testnet |

### 9.3 Derived Values

```
Expected blocks per epoch     = epochLength * f = 21600
Expected slot gap between blocks = 1/f = 20 slots (20 seconds)
Immutability window           = k blocks = 2160 blocks
Time to immutability          = k/f slots = 43200 slots = 12 hours
KES key total lifetime        = maxKESEvolutions * slotsPerKESPeriod = 8,035,200 slots ~ 93 days
Epoch in which snapshot is active = snapshot_epoch + 2
```

---

## 10. Implementation Data Structures

### 10.1 Core Consensus Types (Scala 3)

Based on the existing Stretto types and what's needed for Praos:

```scala
// Already defined in stretto.core.Types:
//   SlotNo, BlockNo, EpochNo, Hash32, Hash28, VrfKeyHash, KesKeyHash, PoolId

// Additional types needed for consensus:

opaque type ActiveSlotCoeff = Double  // f parameter, e.g., 0.05
opaque type Nonce = ByteVector        // 32-byte epoch nonce
opaque type KESPeriod = Long
opaque type OpCertCounter = Long
opaque type Sigma = Rational          // relative stake, in [0, 1]
opaque type VRFOutput = ByteVector    // 64-byte VRF output
opaque type VRFProof = ByteVector     // 80-byte VRF proof
opaque type KESSignature = ByteVector // KES signature bytes
opaque type ColdVerificationKey = ByteVector  // Ed25519 VK (32 bytes)
opaque type VRFVerificationKey = ByteVector   // VRF VK (32 bytes)
opaque type KESVerificationKey = ByteVector   // KES VK (32 bytes)
```

### 10.2 Block Header

```scala
case class PraosHeader(
  body: HeaderBody,
  kesSig: KESSignature
)

case class HeaderBody(
  blockNo: BlockNo,
  slotNo: SlotNo,
  prevHash: Option[Hash32],  // None for genesis block
  issuerVk: ColdVerificationKey,
  vrfVk: VRFVerificationKey,
  vrfResult: VRFCert,        // leader VRF (Babbage+: also derives nonce)
  bodySize: Long,
  bodyHash: Hash32,
  opCert: OpCert,
  protocolVersion: ProtocolVersion
)

case class VRFCert(
  output: VRFOutput,
  proof: VRFProof
)

case class OpCert(
  hotVk: KESVerificationKey,
  counter: OpCertCounter,
  kesPeriod: KESPeriod,
  coldSig: ByteVector  // Ed25519 signature
)

case class ProtocolVersion(major: Int, minor: Int)
```

### 10.3 Consensus State

```scala
case class PraosState(
  // Current chain tip
  tipSlot: SlotNo,
  tipBlockNo: BlockNo,
  tipHash: Hash32,

  // Epoch-level state
  currentEpoch: EpochNo,
  epochNonce: Nonce,       // eta_0 for current epoch
  evolvingNonce: Nonce,    // eta_v, updated with each block's VRF nonce
  candidateNonce: Nonce,   // eta_c, accumulated from VRF nonce outputs

  // Stake distribution snapshots
  markSnapshot: StakeSnapshot,  // most recent (taken at last epoch boundary)
  setSnapshot: StakeSnapshot,   // one epoch old
  goSnapshot: StakeSnapshot,    // two epochs old (currently active)

  // OpCert counters (to prevent reuse)
  opCertCounters: Map[PoolId, OpCertCounter],

  // Nonce freezing
  nonceFrozen: Boolean  // true after 4k/f slots in the epoch
)

case class StakeSnapshot(
  poolStakes: Map[PoolId, Lovelace],
  poolParams: Map[PoolId, PoolParams],
  totalActiveStake: Lovelace
)

case class PoolParams(
  poolId: PoolId,
  vrfKeyHash: VrfKeyHash,
  pledge: Lovelace,
  cost: Lovelace,
  margin: Rational,
  rewardAccount: ByteVector
)
```

### 10.4 Leader Election

```scala
object LeaderElection:
  /**
   * Check if a pool is the slot leader for the given slot.
   *
   * @param vrfSk      pool's VRF signing key
   * @param slotNo     the slot to check
   * @param epochNonce the current epoch nonce (eta_0)
   * @param sigma      pool's relative active stake
   * @param f          active slot coefficient
   * @return Some(VRFCert) if the pool is elected, None otherwise
   */
  def checkLeadership(
    vrfSk: VRFSigningKey,
    slotNo: SlotNo,
    epochNonce: Nonce,
    sigma: Rational,
    f: ActiveSlotCoeff
  ): Option[VRFCert] =
    // 1. Construct VRF input seed
    val seed = mkSeed(purposeLeader = 0, slotNo, epochNonce)

    // 2. Evaluate VRF
    val (output, proof) = VRF.eval(vrfSk, seed)

    // 3. Check threshold
    val certNat = bytesToNatural(output) // interpret 64 bytes as big-endian natural
    val threshold = computeThreshold(sigma, f)

    if certNat < threshold then Some(VRFCert(output, proof))
    else None

  /**
   * Compute the leadership threshold.
   * A pool is elected if certNat < threshold.
   *
   * threshold = 2^512 * phi_f(sigma)
   * phi_f(sigma) = 1 - (1 - f)^sigma
   */
  def computeThreshold(sigma: Rational, f: ActiveSlotCoeff): BigInt =
    val phi = phiF(sigma, f)
    val twoPow512 = BigInt(2).pow(512)
    (BigDecimal(twoPow512) * phi).toBigInt

  /**
   * phi_f(sigma) = 1 - (1 - f)^sigma
   *
   * Since sigma is rational (p/q), this requires computing
   * (1-f)^(p/q) with high precision.
   */
  def phiF(sigma: Rational, f: ActiveSlotCoeff): BigDecimal =
    val base = BigDecimal(1.0) - BigDecimal(f)
    val exponent = BigDecimal(sigma.numerator) / BigDecimal(sigma.denominator)
    BigDecimal(1.0) - pow(base, exponent)
    // Use Taylor series: exp(exponent * ln(base))
    // ln(1-f) via series: -f - f^2/2 - f^3/3 - ...
    // exp(x) via series: 1 + x + x^2/2! + x^3/3! + ...

  def mkSeed(purpose: Int, slot: SlotNo, nonce: Nonce): ByteVector =
    val buf = ByteBuffer.allocate(8 + 8 + 32)
    buf.putLong(purpose.toLong)
    buf.putLong(slot.value)
    buf.put(nonce.toArray)
    Blake2b256.hash(ByteVector(buf.array()))
```

### 10.5 Chain Selection

```scala
object ChainSelection:
  /**
   * Compare two chains and determine which to adopt.
   *
   * @param current    our current chain
   * @param candidate  the candidate chain
   * @param k          security parameter (max rollback)
   * @return Prefer.Current or Prefer.Candidate
   */
  def compare(
    current: Chain,
    candidate: Chain,
    k: Int
  ): ChainPreference =
    val intersection = findIntersection(current, candidate)
    val rollbackDepth = current.tipBlockNo - intersection.blockNo

    // Never roll back more than k blocks
    if rollbackDepth > k then
      ChainPreference.Current
    // Prefer the longer chain
    else if candidate.tipBlockNo > current.tipBlockNo then
      ChainPreference.Candidate
    else if candidate.tipBlockNo < current.tipBlockNo then
      ChainPreference.Current
    // Tie-breaking: prefer current chain (don't switch unnecessarily)
    else
      ChainPreference.Current

  enum ChainPreference:
    case Current, Candidate
```

### 10.6 Nonce Evolution

```scala
object NonceEvolution:
  /**
   * Update the evolving nonce with a new block's VRF nonce output.
   * Only updates if we're within the nonce-gathering window (first 4k/f slots).
   */
  def updateNonce(
    state: PraosState,
    block: PraosHeader,
    params: ConsensusParams
  ): PraosState =
    val slotInEpoch = block.body.slotNo.value % params.epochLength
    val nonceWindow = 4 * params.k / params.f  // 172800 for mainnet

    if slotInEpoch < nonceWindow then
      // Derive nonce contribution from leader VRF output (Babbage+)
      val nonceContribution = Blake2b256.hash(block.body.vrfResult.output)
      val newEvolvingNonce = Blake2b256.hash(
        state.evolvingNonce.bytes ++ nonceContribution
      )
      state.copy(
        evolvingNonce = Nonce(newEvolvingNonce),
        candidateNonce = Nonce(newEvolvingNonce)
      )
    else
      state.copy(nonceFrozen = true)

  /**
   * Compute the epoch nonce for the next epoch at an epoch boundary.
   */
  def epochTransitionNonce(
    evolvingNonce: Nonce,
    candidateNonce: Nonce,
    extraEntropy: Option[Nonce]
  ): Nonce =
    val base = Blake2b256.hash(evolvingNonce.bytes ++ candidateNonce.bytes)
    extraEntropy match
      case Some(extra) => Nonce(Blake2b256.hash(base ++ extra.bytes))
      case None        => Nonce(base)
```

### 10.7 Epoch Boundary Processing

```scala
object EpochBoundary:
  /**
   * Process the epoch boundary transition.
   * Called when the first block of a new epoch arrives.
   */
  def transition(
    state: PraosState,
    newEpoch: EpochNo,
    ledgerState: LedgerState
  ): PraosState =
    // Rotate snapshots
    val newGoSnapshot = state.setSnapshot
    val newSetSnapshot = state.markSnapshot
    val newMarkSnapshot = computeStakeSnapshot(ledgerState)

    // Compute new epoch nonce
    val newEpochNonce = NonceEvolution.epochTransitionNonce(
      state.evolvingNonce,
      state.candidateNonce,
      ledgerState.extraEntropy
    )

    state.copy(
      currentEpoch = newEpoch,
      epochNonce = newEpochNonce,
      evolvingNonce = newEpochNonce,  // reset to new epoch nonce
      candidateNonce = Nonce.neutral, // reset
      nonceFrozen = false,
      markSnapshot = newMarkSnapshot,
      setSnapshot = newSetSnapshot,
      goSnapshot = newGoSnapshot
    )
```

### 10.8 Block Forging

```scala
object BlockForge:
  /**
   * Attempt to forge a block for the given slot.
   */
  def forgeBlock(
    slotNo: SlotNo,
    poolKeys: PoolKeys,
    state: PraosState,
    params: ConsensusParams,
    pendingTxs: List[Transaction],
    prevHash: Hash32
  ): Option[Block] =
    // Get active stake distribution
    val snapshot = state.goSnapshot
    val poolStake = snapshot.poolStakes.getOrElse(poolKeys.poolId, Lovelace.zero)
    val sigma = Rational(poolStake.lovelaceValue, snapshot.totalActiveStake.lovelaceValue)

    // Check slot leadership
    LeaderElection.checkLeadership(
      poolKeys.vrfSk,
      slotNo,
      state.epochNonce,
      sigma,
      params.activeSlotCoeff
    ).map { vrfCert =>
      // Compute KES period and evolution
      val currentKesPeriod = KESPeriod(slotNo.value / params.slotsPerKESPeriod)
      val kesEvolution = currentKesPeriod.value - poolKeys.opCert.kesPeriod.value

      // Build header body
      val body = assembleBlockBody(pendingTxs, /* ledger validation */)
      val bodyHash = Blake2b256.hash(CBOR.encode(body))

      val headerBody = HeaderBody(
        blockNo = state.tipBlockNo + 1,
        slotNo = slotNo,
        prevHash = Some(prevHash),
        issuerVk = poolKeys.coldVk,
        vrfVk = poolKeys.vrfVk,
        vrfResult = vrfCert,
        bodySize = CBOR.encode(body).size,
        bodyHash = bodyHash,
        opCert = poolKeys.opCert,
        protocolVersion = params.protocolVersion
      )

      // Sign with KES
      val headerBodyCbor = CBOR.encode(headerBody)
      val kesSig = KES.sign(poolKeys.kesSk, kesEvolution, headerBodyCbor)

      Block(PraosHeader(headerBody, kesSig), body)
    }

case class PoolKeys(
  poolId: PoolId,
  coldVk: ColdVerificationKey,
  vrfSk: VRFSigningKey,
  vrfVk: VRFVerificationKey,
  kesSk: KESSigningKey,
  opCert: OpCert
)
```

### 10.9 Crypto Primitives Needed

| Primitive | Algorithm | Library Options (JVM) |
|-----------|-----------|----------------------|
| Hashing | Blake2b-256 | Bouncy Castle, libsodium via JNI |
| VRF | ECVRF-ED25519-SHA512-Elligator2 | libsodium (ietf_ristretto255 VRF), custom JNI binding |
| KES | Sum KES (depth 6, based on Ed25519) | Custom implementation or JNI to cardano-crypto |
| Ed25519 | Ed25519 signatures | Bouncy Castle, libsodium |
| Block hash | Blake2b-256 of CBOR-encoded header body | Bouncy Castle |

**Critical note on VRF**: The JVM ecosystem does not have a native ECVRF implementation compatible with Cardano. Options:
1. **JNI binding to libsodium** (recommended): libsodium's `crypto_vrf_*` functions implement the exact VRF scheme Cardano uses
2. **JNI binding to cardano-crypto**: The Haskell node's C library (`cardano-crypto-praos`) implements VRF and KES
3. **Pure JVM implementation**: Possible but complex; would need to implement Elligator2 point encoding on Curve25519

**Critical note on KES**: Sum KES is not available in standard crypto libraries. Options:
1. **JNI binding to cardano-crypto**: Most practical approach
2. **Pure Scala implementation**: Build the recursive sum KES construction on top of Ed25519 (Bouncy Castle)

---

## Appendix A: VRF Leader Check — Complete Pseudocode

```
function isSlotLeader(pool, slot, epochNonce, stakeSnapshot, f):
    // 1. Look up pool's relative stake
    poolStake = stakeSnapshot.poolStakes[pool.poolId]
    totalStake = stakeSnapshot.totalActiveStake
    sigma = poolStake / totalStake  // rational number

    // 2. Compute VRF input
    purpose = 0  // leader check purpose
    seed = blake2b256(bigEndian64(purpose) ++ bigEndian64(slot) ++ epochNonce)

    // 3. Evaluate VRF
    (vrfOutput, vrfProof) = vrf_prove(pool.vrfSigningKey, seed)
    // vrfOutput is 64 bytes

    // 4. Compute certification natural number
    certNat = fromBigEndianBytes(vrfOutput)  // 512-bit unsigned integer

    // 5. Compute threshold
    // phi_f(sigma) = 1 - (1 - f)^sigma
    phi = 1 - pow(1 - f, sigma)
    threshold = pow(2, 512) * phi

    // 6. Compare
    if certNat < threshold:
        return (true, vrfOutput, vrfProof)
    else:
        return (false, null, null)
```

## Appendix B: Epoch Nonce Evolution — Complete Pseudocode

```
function processBlock(state, block, params):
    slot = block.header.slotNo
    epoch = slot / params.epochLength
    slotInEpoch = slot % params.epochLength

    // Check for epoch transition
    if epoch > state.currentEpoch:
        state = epochTransition(state, epoch)

    // Update evolving nonce (only in first 4k/f slots of epoch)
    randomnessWindow = 4 * params.k / params.f  // 172800 slots
    if slotInEpoch < randomnessWindow:
        // In Babbage+, nonce is derived from leader VRF output
        nonceContrib = blake2b256(block.header.vrfResult.output)
        state.evolvingNonce = blake2b256(state.evolvingNonce ++ nonceContrib)
        state.candidateNonce = state.evolvingNonce

    return state

function epochTransition(state, newEpoch):
    // Compute new epoch nonce
    newNonce = blake2b256(state.evolvingNonce ++ state.candidateNonce)
    if state.extraEntropy is not null:
        newNonce = blake2b256(newNonce ++ state.extraEntropy)

    // Rotate snapshots
    state.goSnapshot = state.setSnapshot
    state.setSnapshot = state.markSnapshot
    state.markSnapshot = currentStakeDistribution()

    // Reset
    state.currentEpoch = newEpoch
    state.epochNonce = newNonce
    state.evolvingNonce = newNonce
    state.candidateNonce = neutralNonce
    state.nonceFrozen = false

    return state
```

## Appendix C: Header Validation — Complete Pseudocode

```
function validateHeader(header, state, params):
    errors = []

    // 1. Block number is sequential
    if header.blockNo != state.tipBlockNo + 1:
        errors.add("Block number not sequential")

    // 2. Slot is strictly increasing
    if header.slotNo <= state.tipSlot:
        errors.add("Slot not strictly increasing")

    // 3. Previous hash matches
    if header.prevHash != state.tipHash:
        errors.add("Previous hash mismatch")

    // 4. Pool is registered
    pool = lookupPool(header.issuerVk, state.goSnapshot)
    if pool is null:
        errors.add("Unknown pool operator")

    // 5. VRF key matches registered key
    if blake2b256(header.vrfVk) != pool.vrfKeyHash:
        errors.add("VRF key mismatch")

    // 6. Verify VRF proof (leader check)
    seed = mkSeed(0, header.slotNo, state.epochNonce)
    if not vrf_verify(header.vrfVk, seed, header.vrfResult.proof, header.vrfResult.output):
        errors.add("VRF proof invalid")

    // 7. Check leadership threshold
    sigma = pool.stake / state.goSnapshot.totalActiveStake
    certNat = fromBigEndianBytes(header.vrfResult.output)
    phi = 1 - pow(1 - params.f, sigma)
    threshold = pow(2, 512) * phi
    if certNat >= threshold:
        errors.add("Not a valid slot leader")

    // 8. Verify operational certificate
    opcertPayload = header.opCert.hotVk ++ bigEndian64(header.opCert.counter)
                    ++ bigEndian64(header.opCert.kesPeriod)
    if not ed25519_verify(header.issuerVk, opcertPayload, header.opCert.coldSig):
        errors.add("OpCert signature invalid")

    // 9. Check OpCert counter
    prevCounter = state.opCertCounters.get(pool.poolId)
    if prevCounter is not null and header.opCert.counter <= prevCounter:
        errors.add("OpCert counter not increasing")

    // 10. Check KES period
    currentKesPeriod = header.slotNo / params.slotsPerKESPeriod
    kesEvolution = currentKesPeriod - header.opCert.kesPeriod
    if kesEvolution < 0 or kesEvolution > params.maxKESEvolutions:
        errors.add("KES period out of range")

    // 11. Verify KES signature
    headerBodyBytes = cbor_encode(header.body)
    if not kes_verify(header.opCert.hotVk, kesEvolution, headerBodyBytes, header.kesSig):
        errors.add("KES signature invalid")

    return errors
```

## Appendix D: Key References

| Document | URL | Relevant Sections |
|----------|-----|-------------------|
| Ouroboros Praos paper | https://eprint.iacr.org/2017/573.pdf | Sections 3-5 (protocol), Section 6 (security) |
| Ouroboros Genesis paper | https://eprint.iacr.org/2018/378.pdf | Sections 3-4 (chain selection for syncing nodes) |
| Shelley formal ledger spec | cardano-ledger/eras/shelley/formal-spec | OVERLAY, TICK, NEWEPOCH transitions |
| ouroboros-consensus docs | https://ouroboros-consensus.cardano.intersectmbo.org/ | Architecture, chain selection, HFC |
| CDDL schemas | cardano-ledger/eras/*/impl/cddl-files/ | Binary encoding of all data structures |
| Shelley genesis JSON | cardano-configurations repo | Protocol parameters, network config |
| RFC 9381 | https://www.rfc-editor.org/rfc/rfc9381 | ECVRF specification |
| cardano-crypto-praos | cardano-base repo | C implementation of VRF and KES |
