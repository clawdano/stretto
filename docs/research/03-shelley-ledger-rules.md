# Shelley Ledger STS Rules Specification

> Research document for the Stretto project.
> Compiled: 2026-03-14

## Overview

The Shelley era introduced a complete proof-of-stake ledger with a formally specified
state transition system (STS). Every block, transaction, and certificate must satisfy
a hierarchy of validation rules before altering the ledger state. This document maps
every STS rule to the validation logic Stretto must implement.

**Primary references:**
- [Shelley Ledger Formal Spec (ShelleyMA)](https://github.com/intersectmbo/cardano-ledger/releases/latest/download/shelley-ledger.pdf)
- Haskell implementation: `cardano-ledger-shelley` package

---

## 1. STS Rule Hierarchy

The rules form a call tree rooted at `CHAIN`:

```
CHAIN
├── TICK (epoch boundary)
│   ├── NEWEPOCH
│   │   ├── EPOCH
│   │   │   ├── SNAP       (snapshot stake distribution)
│   │   │   ├── POOLREAP   (retire expired pools)
│   │   │   ├── NEWPP      (apply new protocol parameters)
│   │   │   └── MIR        (move instantaneous rewards)
│   │   └── RUPD           (reward update calculation)
│   └── (advance slot / epoch counters)
├── PRTCL (VRF / KES leader check)
└── BBODY (block body validation)
    └── LEDGERS (sequence of transactions)
        └── LEDGER (single transaction)
            ├── UTXOW (witnessed UTXO rule)
            │   └── UTXO (unwitnessed UTXO rule)
            └── DELEGS (delegation sequences)
                └── DELPL (single delegation/pool cert)
                    ├── DELEG (stake delegation certificates)
                    └── POOL  (pool registration/retirement)
```

Each node is an STS rule with:
- **Environment** — read-only context (protocol params, slot, etc.)
- **State** — mutable state threaded through
- **Signal** — the input triggering the transition (block, tx, cert)
- **Preconditions** — predicates that must hold for the transition to fire

---

## 2. Full State Tree — NewEpochState

```
NewEpochState
├── nesEL        : EpochNo              -- current epoch number
├── nesBprev     : BlocksMade           -- blocks made in previous epoch
├── nesBcur      : BlocksMade           -- blocks made in current epoch
├── nesEs        : EpochState
│   ├── esAccountState : AccountState
│   │   ├── asTreasury  : Coin
│   │   └── asReserves  : Coin
│   ├── esSnapshots    : SnapShots
│   │   ├── ssStakeMark   : SnapShot   -- mark snapshot (current)
│   │   ├── ssStakeSet    : SnapShot   -- set snapshot (prev epoch)
│   │   ├── ssStakeGo     : SnapShot   -- go snapshot (2 epochs ago)
│   │   └── ssFee         : Coin       -- fees for current epoch
│   ├── esLState       : LedgerState
│   │   ├── lsUTxOState : UTxOState
│   │   │   ├── utxo       : UTxO           -- full UTXO set
│   │   │   ├── deposited  : Coin           -- total deposits
│   │   │   ├── fees       : Coin           -- accumulated fees
│   │   │   ├── ppups      : PPUpdateState  -- protocol param proposals
│   │   │   └── stakeDistro: IncrementalStake
│   │   └── lsDPState  : DPState
│   │       ├── dsState : DState            -- delegation state
│   │       │   ├── rewards      : Map StakeCredential Coin
│   │       │   ├── delegations  : Map StakeCredential KeyHash
│   │       │   ├── ptrs         : Map Ptr StakeCredential
│   │       │   ├── fGenDelegs   : Map FutureGenDelegKey GenDelegPair
│   │       │   ├── genDelegs    : GenDelegs
│   │       │   └── irwd         : InstantaneousRewards
│   │       └── psState : PState            -- pool state
│   │           ├── pParams      : Map KeyHash PoolParams
│   │           ├── fPParams     : Map KeyHash PoolParams
│   │           └── retiring     : Map KeyHash EpochNo
│   └── esPp           : PParams           -- current protocol params
├── nesRu        : Maybe PulsingRewUpd  -- reward calculation in progress
├── nesPd        : PoolDistr            -- pool stake distribution
└── stashedAVVMAddresses : UTxO         -- Byron AVVM leftovers (removed at boundary)
```

---

## 3. UTXO Rule — 10 Preconditions

The UTXO rule validates a transaction against the current UTXO set **without**
checking witnesses. Environment: slot, protocol params, stake pools, genesis
key hashes.

| # | Rule ID | Precondition | Failure |
|---|---------|-------------|---------|
| 1 | `InputSetNonEmpty` | `txins tx ≠ ∅` | `InputSetEmptyUTxO` |
| 2 | `FeeTooSmall` | `minfee pp tx ≤ txfee tx` | `FeeTooSmallUTxO` |
| 3 | `ValueNotConserved` | `consumed pp utxo tx = produced pp stakePools tx` | `ValueNotConservedUTxO` |
| 4 | `OutputTooSmall` | `∀ out ∈ txouts tx : coin out ≥ minUTxOValue pp` | `OutputTooSmallUTxO` |
| 5 | `OutputBootAddrAttrsTooBig` | Bootstrap outputs have attribute size ≤ 64 | `OutputBootAddrAttrsTooBig` |
| 6 | `MaxTxSize` | `txsize tx ≤ maxTxSize pp` | `MaxTxSizeUTxO` |
| 7 | `InputsInUTxO` | `txins tx ⊆ dom utxo` | `BadInputsUTxO` |
| 8 | `SlotInFuture` | `txttl tx ≥ slot` | `ExpiredUTxO` |
| 9 | `UpdateValid` | update proposal is valid (if present) | `UpdateFailure` |
| 10 | `AdaOnlyOutputs` | All outputs carry only Ada (no multi-asset pre-MA) | `OutputContainsNonAdaValue` |

### Value Conservation Equation

```
consumed = balance(txins ◁ utxo) + wbalance(txwdrls tx) + keyRefunds pp tx
produced = balance(txouts tx) + txfee tx + totalDeposits pp stakePools (txcerts tx)
```

Where `keyRefunds` returns deposit refunds for deregistration certs and `totalDeposits`
sums deposits for new stake key registrations and pool registrations.

---

## 4. UTXOW Rule — 7 Witness Checks

The UTXOW rule wraps UTXO and adds witness/signature verification.

| # | Rule ID | Check | Failure |
|---|---------|-------|---------|
| 1 | `InvalidWitnesses` | All VKey witnesses verify against tx body hash | `InvalidWitnessesUTXOW` |
| 2 | `MissingVKeyWitnesses` | Required key hashes ⊆ provided witness key hashes | `MissingVKeyWitnessesUTXOW` |
| 3 | `MissingScriptWitnesses` | Required script hashes ⊆ provided scripts | `MissingScriptWitnessesUTXOW` |
| 4 | `ScriptValidation` | All native scripts evaluate to `True` | `ScriptWitnessNotValidatingUTXOW` |
| 5 | `MetadataHash` | If metadata present, hash matches; if hash present, metadata present | `MissingTxMetadata` / `MissingTxBodyMetadataHash` |
| 6 | `GenesisDelegation` | PP update proposals signed by genesis delegates | `MIRInsufficientGenesisSigsUTXOW` |
| 7 | `BootstrapWitnesses` | Byron bootstrap witnesses verify and cover all bootstrap inputs | `InvalidBootstrapWitnesses` |

### Required Witnesses Computation

```
witsVKeyNeeded utxo tx =
  { addrWits        -- payment key hashes from UTxO inputs
  , certWits        -- certificate required signers
  , wdrlWits        -- withdrawal address key hashes
  , updateWits      -- genesis delegate key hashes for pparam updates
  }
```

---

## 5. DELEG Rule — 5 Certificate Types

Stake credential delegation certificates processed by the DELEG STS rule:

### 5.1 RegKey (Stake Key Registration)

```
preconditions:
  - stakeCred ∉ dom rewards    -- not already registered
state transition:
  - rewards' = rewards ∪ {stakeCred → 0}
  - deposited' = deposited + keyDeposit
```

### 5.2 DeRegKey (Stake Key Deregistration)

```
preconditions:
  - stakeCred ∈ dom rewards    -- must be registered
  - rewards[stakeCred] = 0     -- must have zero reward balance
state transition:
  - rewards' = rewards \ {stakeCred}
  - delegations' = delegations \ {stakeCred}
  - deposited' = deposited - keyDeposit
```

### 5.3 Delegate (Stake Delegation)

```
preconditions:
  - stakeCred ∈ dom rewards                -- must be registered
  - targetPoolHash ∈ dom poolParams        -- pool must exist
state transition:
  - delegations' = delegations ∪ {stakeCred → targetPoolHash}
```

### 5.4 GenesisDelegate

```
preconditions:
  - genesisKeyHash ∈ dom genDelegs
  - (delegKeyHash, vrfKeyHash) not already in future or current genDelegs
state transition:
  - fGenDelegs' = fGenDelegs ∪ {(slot + stabilityWindow, genesisKeyHash) → (delegKeyHash, vrfKeyHash)}
```

### 5.5 MIRCert (Move Instantaneous Rewards)

```
preconditions:
  - sufficient genesis delegate signatures (quorum)
  - source pot has sufficient funds (or transfer between reserves/treasury)
  - not in first stabilityWindow slots of an epoch (slot restriction)
state transition:
  - irwd' updated with specified reward mappings
```

---

## 6. POOL Rule — 2 Certificate Types

### 6.1 RegPool (Pool Registration)

```
preconditions:
  - (none that cause failure — re-registration is an update)
state transition:
  if poolHash ∉ dom poolParams:
    -- new registration
    poolParams' = poolParams ∪ {poolHash → poolParams}
    deposited' = deposited + poolDeposit
  else:
    -- re-registration (update)
    poolParams' = poolParams ⊕ {poolHash → poolParams}  -- override
    retiring' = retiring \ {poolHash}                     -- cancel retirement
    fPParams' = fPParams ⊕ {poolHash → poolParams}
```

### 6.2 RetirePool (Pool Retirement)

```
preconditions:
  - epoch_retire ≤ currentEpoch + eMax    -- within maximum retirement window
  - epoch_retire > currentEpoch           -- must retire in a future epoch
state transition:
  - retiring' = retiring ∪ {poolHash → epoch_retire}
```

---

## 7. Epoch Boundary Transitions

At every slot where `epoch(slot) > currentEpoch`, the TICK rule fires NEWEPOCH,
which triggers the EPOCH rule containing four sub-rules:

### 7.1 SNAP — Stake Snapshot Rotation

```
state transition:
  ssStakeGo'   = ssStakeSet           -- go ← set
  ssStakeSet'  = ssStakeMark          -- set ← mark
  ssStakeMark' = computeSnapshot(     -- mark ← fresh from current state
    utxo, delegations, poolParams
  )
  ssFee' = fees                       -- capture fees for reward calc
```

The three snapshots provide a sliding window for reward calculation stability.

### 7.2 POOLREAP — Retire Expired Pools

```
state transition:
  for each (poolHash, retireEpoch) in retiring where retireEpoch ≤ currentEpoch:
    - remove poolHash from poolParams
    - remove poolHash from fPParams
    - remove poolHash from retiring
    - refund poolDeposit to pool reward account (or treasury if no reward acct)
    - remove all delegations pointing to retired pool
```

### 7.3 NEWPP — New Protocol Parameters

```
preconditions:
  - ppup proposals have reached quorum
  - resulting params satisfy: maxTxSize < maxBlockBodySize < maxBlockSize
state transition:
  - esPp' = merged protocol parameters
  - ppups' = emptyPPUpdateState
```

### 7.4 RUPD — Reward Update Calculation

The reward calculation is performed incrementally ("pulsing") across slots within
an epoch to spread CPU cost. At the epoch boundary the result is consumed.

```
state transition:
  if nesRu = Just rewardUpdate:
    - apply deltaT to treasury
    - apply deltaR to reserves
    - apply rs (reward map) to DState rewards
    - deltaF absorbed into fee pot
    - nesRu' = Nothing
```

### 7.5 MIR — Move Instantaneous Rewards (at boundary)

Applied at epoch boundary. Instantaneous reward transfers recorded via MIRCert
are resolved:

```
state transition:
  - for each (stakeCred, coin) in irwd:
    rewards[stakeCred] += coin
  - irwd' = empty
  - reserves/treasury adjusted accordingly
```

---

## 8. Reward Formula

The reward for a pool in an epoch:

```
maxPoolReward = R / (1 + a0)   -- R = total reward pot, a0 = influence factor

poolReward(σ, s, pledge) =
  maxPoolReward · (σ' + s' · a0 · (σ' - s' · ((σ' - s') / z0)) / z0)

where:
  σ  = pool's relative active stake (capped at z0 = 1/k)
  s  = pool's pledge relative stake (capped at z0)
  σ' = min(σ, z0)
  s' = min(s, z0)
  z0 = 1 / k                 -- k = desired number of pools (nOpt)
  a0 = pool pledge influence  -- protocol parameter
  R  = (reserves · ρ) + fees  -- ρ = monetary expansion rate

Individual delegator reward:
  memberReward = (1 - margin) · poolReward · (memberStake / totalPoolStake)

Pool operator reward:
  leaderReward = poolReward - sum(memberRewards)
  (operator keeps cost + margin + proportional share of remainder)
```

### Reward Pot Computation

```
rewardPot = reserves · ρ + fees
  where ρ = monetaryExpansion protocol parameter

treasuryCut = rewardPot · τ
  where τ = treasuryGrowthRate protocol parameter

R = rewardPot - treasuryCut    -- available for pool rewards
```

---

## 9. Fee Calculation

### Minimum Fee Formula

```
minfee(pp, tx) = txFeeFixed pp + txFeePerByte pp × txsize(tx)
```

Where `txsize` is the CBOR-serialized byte length of the transaction.

### Deposit Tracking

| Event | Deposit Change |
|-------|---------------|
| Stake key registration | `+keyDeposit` |
| Stake key deregistration | `-keyDeposit` |
| Pool registration (new) | `+poolDeposit` |
| Pool re-registration | no change |
| Pool retirement (at epoch boundary) | `-poolDeposit` (refund) |

---

## 10. Native Script Evaluation (Shelley)

Shelley supports only multi-signature scripts with these constructors:

```
NativeScript =
  | RequireSignature    KeyHash          -- sig from this key
  | RequireAllOf        [NativeScript]   -- all sub-scripts must pass
  | RequireAnyOf        [NativeScript]   -- at least one must pass
  | RequireMOf      Int [NativeScript]   -- at least M must pass
```

Timelock scripts (`RequireTimeExpire`, `RequireTimeStart`) are added in Allegra.

### Evaluation

```
evalScript(RequireSignature kh, signers) = kh ∈ signers
evalScript(RequireAllOf scripts, signers) = ∀ s ∈ scripts: evalScript(s, signers)
evalScript(RequireAnyOf scripts, signers) = ∃ s ∈ scripts: evalScript(s, signers)
evalScript(RequireMOf m scripts, signers) = |{s | evalScript(s, signers)}| ≥ m
```

---

## 11. Implementation Checklist for Stretto

### Phase 1 — Core State Types

- [ ] `NewEpochState` full state tree (all nested types)
- [ ] `UTxO` as `Map TxIn TxOut` with efficient lookup
- [ ] `DPState` (DState + PState) for delegation tracking
- [ ] `EpochState` with `AccountState` and `SnapShots`
- [ ] `PParams` — all Shelley protocol parameters

### Phase 2 — Transaction Validation (UTXO + UTXOW)

- [ ] UTXO 10 preconditions (value conservation, fees, sizes, etc.)
- [ ] Witness set verification (VKey, bootstrap, script)
- [ ] Required witnesses computation (`witsVKeyNeeded`)
- [ ] Fee calculation (`minfee`)
- [ ] Native script evaluation engine
- [ ] Metadata hash validation

### Phase 3 — Certificate Processing (DELEG + POOL)

- [ ] Stake key registration / deregistration
- [ ] Stake delegation
- [ ] Pool registration / re-registration / retirement
- [ ] Genesis delegation
- [ ] MIR certificate handling

### Phase 4 — Epoch Boundary

- [ ] SNAP: snapshot rotation and fresh snapshot computation
- [ ] POOLREAP: retire expired pools, refund deposits
- [ ] NEWPP: apply protocol parameter updates
- [ ] RUPD: incremental reward calculation (pulsing)
- [ ] MIR: apply instantaneous rewards at boundary
- [ ] Reward formula implementation (per-pool, per-member)

### Phase 5 — Integration

- [ ] LEDGER rule combining UTXOW + DELEGS
- [ ] LEDGERS rule: fold LEDGER over all transactions in a block
- [ ] BBODY rule: block body validation (tx sequence limits, body hash)
- [ ] CHAIN rule: integrate TICK + PRTCL + BBODY
- [ ] Property-based tests against Haskell reference (conformance)

---

## 12. Key Differences Across Eras

| Feature | Shelley | Allegra | Mary | Alonzo | Babbage | Conway |
|---------|---------|---------|------|--------|---------|--------|
| Scripts | MultiSig | +Timelock | same | +Plutus V1 | +Plutus V2 | +Plutus V3 |
| Outputs | Addr+Coin | same | +MultiAsset | +DatumHash | +InlineDatum+RefScript | same |
| UTXO rules | 10 | +validity interval | same | +collateral+script costs | +ref inputs | +treasury withdrawals |
| Certs | 5 types | same | same | same | same | +DRep+Committee |

---

## References

1. Shelley Formal Spec: https://github.com/intersectmbo/cardano-ledger/releases/latest/download/shelley-ledger.pdf
2. ShelleyMA Formal Spec (Allegra/Mary extensions): https://github.com/intersectmbo/cardano-ledger/releases/latest/download/shelley-ma.pdf
3. Haskell source: https://github.com/intersectmbo/cardano-ledger/tree/master/eras/shelley/impl
4. Small-step Semantics framework: https://github.com/intersectmbo/cardano-ledger/tree/master/libs/small-steps
