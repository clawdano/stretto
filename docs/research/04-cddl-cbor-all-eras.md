# CDDL/CBOR Schema Analysis Across All Eras

> Research document for the Stretto project.
> Compiled: 2026-03-14

## Overview

Cardano uses CBOR (Concise Binary Object Representation, RFC 7049/8949) as its
wire and storage format. Each era defines its types in CDDL (Concise Data
Definition Language, RFC 8610). Understanding the encoding across all seven eras
(Byron through Conway) is essential for Stretto to correctly serialize and
deserialize blocks, transactions, and auxiliary data.

**Primary references:**
- Byron: `cardano-ledger-byron` CDDL
- Shelley–Conway: `cardano-ledger` repo, `eras/*/test-suite/cddl-files/`
- https://github.com/intersectmbo/cardano-ledger/tree/master/eras

---

## 1. Multi-Era Wrapper Format

Cardano wraps each block in a top-level CBOR array that tags the era:

```cddl
block = [era_id, block_body]

; era_id values:
;   0 = Byron EBB (Epoch Boundary Block)
;   1 = Byron main block
;   2 = Shelley
;   3 = Allegra
;   4 = Mary
;   5 = Alonzo
;   6 = Babbage
;   7 = Conway
```

This means the decoder must peek at the first integer to determine which
era-specific decoder to invoke. Stretto should implement this as a two-pass
decode: read the tag, then dispatch to the era-specific codec.

### Network Wrapper

On the wire (via the chain-sync mini-protocol), blocks arrive wrapped in an
additional CBOR structure:

```cddl
; Wrapped block on the wire
wrapped_block = #6.24(bytes .cbor block)   ; CBOR tag 24 = embedded CBOR
```

---

## 2. Byron Era (Era IDs 0, 1)

### 2.1 Block Structure

Byron has two block types:

```cddl
; Epoch Boundary Block (EBB)
byron_ebb = [
  header    : ebb_head,
  body      : [epoch_id, [* stakeholder_id]],  ; stakeholder list
  extra     : [attributes]
]

; Main Block
byron_main_block = [
  header    : byron_block_header,
  body      : byron_block_body,
  extra     : [attributes]
]

byron_block_header = [
  protocol_magic  : uint,
  prev_block      : hash32,          ; hash of previous block header
  body_proof      : byron_body_proof,
  consensus_data  : byron_consensus_data,
  extra_data      : byron_extra_data
]
```

### 2.2 Transaction Body (Byron)

```cddl
byron_tx = [
  inputs  : [* byron_tx_in],
  outputs : [* byron_tx_out]
]

byron_tx_in = [0, #6.24(bytes .cbor [tx_id, uint])]   ; tag 0, embedded ref
byron_tx_out = [address, uint]                          ; address + lovelace

; Byron addresses use a complex structure with:
;   - address root (hash)
;   - address attributes (derivation path, network magic)
;   - address type (pubkey=0, script=1, redeem=2)
byron_address = [#6.24(bytes .cbor [hash28, attributes, addr_type]), crc32]
```

### 2.3 Key Byron Encoding Notes

- CRC32 checksum on addresses (dropped in Shelley)
- Attributes map for HD derivation path (encrypted)
- VSS certificates for OBFT consensus
- No script or metadata support

---

## 3. Shelley Era (Era ID 2)

### 3.1 Block Structure

```cddl
shelley_block = [
  header : shelley_header,
  body   : transaction_bodies,        ; [* transaction_body]
  wits   : transaction_witness_sets,  ; [* transaction_witness_set]
  meta   : transaction_metadata_set   ; {* uint => transaction_metadata}
]

shelley_header = [
  header_body    : header_body,
  body_signature : kes_signature     ; KES signature over header body
]

header_body = [
  block_number     : uint,
  slot             : uint,
  prev_hash        : hash32 / null,
  issuer_vkey      : vkey,           ; cold verification key
  vrf_vkey         : vrf_vkey,
  nonce_vrf        : vrf_cert,       ; VRF proof for epoch nonce
  leader_vrf       : vrf_cert,       ; VRF proof for leader election
  block_body_size  : uint,
  block_body_hash  : hash32,         ; hash of block body
  operational_cert : operational_cert,
  protocol_version : [uint, uint]    ; major, minor
]
```

### 3.2 Transaction Body

```cddl
shelley_transaction_body = {
  0 : set<transaction_input>,         ; inputs
  1 : [* transaction_output],         ; outputs
  2 : coin,                           ; fee
  3 : uint,                           ; ttl (time to live)
  ? 4 : [* certificate],             ; certificates
  ? 5 : withdrawals,                  ; reward withdrawals
  ? 6 : update,                       ; protocol parameter update proposal
  ? 7 : auxiliary_data_hash           ; metadata hash
}

transaction_input  = [transaction_id : hash32, index : uint]
transaction_output = [address, coin]     ; Shelley: Ada only
```

### 3.3 Witness Set

```cddl
shelley_transaction_witness_set = {
  ? 0 : [* vkeywitness],       ; VKey signatures
  ? 1 : [* multisig_script],   ; Native multi-sig scripts
  ? 2 : [* bootstrap_witness]  ; Byron bootstrap witnesses
}

vkeywitness      = [vkey, signature]
bootstrap_witness = [
  public_key  : vkey,
  signature   : signature,
  chain_code  : bytes .size 32,
  attributes  : bytes           ; Byron address attributes
]
```

### 3.4 Certificate Types (Shelley)

```cddl
certificate = [
    0, stake_credential           ; stake key registration
  / 1, stake_credential           ; stake key deregistration
  / 2, stake_credential, pool_keyhash  ; stake delegation
  / 3, pool_params                ; pool registration
  / 4, pool_keyhash, epoch        ; pool retirement
  / 5, genesis_key_delegation     ; genesis key delegation
  / 6, move_instantaneous_rewards_cert  ; MIR
]

stake_credential = [
    0, addr_keyhash    ; key hash credential
  / 1, scripthash      ; script hash credential
]
```

---

## 4. Allegra Era (Era ID 3)

### 4.1 Changes from Shelley

Allegra introduces **validity intervals** and **timelock scripts**.

```cddl
allegra_transaction_body = {
  0 : set<transaction_input>,
  1 : [* transaction_output],         ; still Ada-only
  2 : coin,
  ? 3 : uint,                         ; *** CHANGED: ttl is now optional
  ? 4 : [* certificate],
  ? 5 : withdrawals,
  ? 6 : update,
  ? 7 : auxiliary_data_hash,
  ? 8 : uint                          ; *** NEW: validity interval start
}
```

### 4.2 Timelock Scripts (New)

```cddl
native_script = [
    0, addr_keyhash                   ; sig (same as Shelley)
  / 1, [* native_script]              ; all_of
  / 2, [* native_script]              ; any_of
  / 3, uint, [* native_script]        ; m_of_n
  / 4, uint                           ; *** NEW: invalid_before (slot)
  / 5, uint                           ; *** NEW: invalid_hereafter (slot)
]
```

### 4.3 Auxiliary Data Format Change

```cddl
; Shelley: auxiliary_data = metadata (just a map)
; Allegra: auxiliary_data = metadata / [metadata, [* native_script]]
allegra_auxiliary_data =
    metadata
  / [transaction_metadata : metadata, auxiliary_scripts : [* native_script]]
```

---

## 5. Mary Era (Era ID 4)

### 5.1 Multi-Asset Outputs

The major change: outputs now carry multi-asset values.

```cddl
mary_transaction_output = [address, value]

value = coin / [coin, multiasset<uint>]

multiasset<a> = {* policy_id => {* asset_name => a}}
policy_id  = scripthash   ; hash of the minting script
asset_name = bytes .size (0..32)
```

### 5.2 Minting

```cddl
mary_transaction_body = {
  0 : set<transaction_input>,
  1 : [* mary_transaction_output],    ; *** CHANGED: multi-asset outputs
  2 : coin,
  ? 3 : uint,
  ? 4 : [* certificate],
  ? 5 : withdrawals,
  ? 6 : update,
  ? 7 : auxiliary_data_hash,
  ? 8 : uint,
  ? 9 : mint                          ; *** NEW: minting/burning field
}

mint = multiasset<int>                 ; int allows negative (burning)
```

### 5.3 Witness Set

Unchanged from Allegra. Native scripts are used for minting policy validation.

---

## 6. Alonzo Era (Era ID 5)

### 6.1 Plutus Scripts Introduction

Alonzo is the largest single-era change, adding smart contracts.

```cddl
alonzo_transaction_body = {
  0  : set<transaction_input>,
  1  : [* alonzo_transaction_output],
  2  : coin,
  ? 3  : uint,
  ? 4  : [* certificate],
  ? 5  : withdrawals,
  ? 6  : update,
  ? 7  : auxiliary_data_hash,
  ? 8  : uint,
  ? 9  : mint,
  ? 11 : script_data_hash,            ; *** NEW: hash of redeemers + datums + cost models
  ? 13 : set<transaction_input>,       ; *** NEW: collateral inputs
  ? 14 : set<addr_keyhash>,           ; *** NEW: required signers
  ? 15 : network_id                    ; *** NEW: optional network id
}
```

### 6.2 Transaction Output with Datum Hash

```cddl
alonzo_transaction_output = [address, value, ? datum_hash]
; datum_hash allows associating a Plutus datum with the output
```

### 6.3 Witness Set with Plutus

```cddl
alonzo_transaction_witness_set = {
  ? 0 : [* vkeywitness],
  ? 1 : [* native_script],
  ? 2 : [* bootstrap_witness],
  ? 3 : [* plutus_v1_script],         ; *** NEW: Plutus V1 scripts
  ? 4 : [* plutus_data],              ; *** NEW: datums
  ? 5 : redeemers                     ; *** NEW: redeemers
}

plutus_v1_script = bytes              ; flat-encoded Plutus Core
plutus_data = #6.121(plutus_constr)   ; CBOR-encoded Plutus data
            / {* plutus_data => plutus_data}
            / [* plutus_data]
            / int
            / bytes

redeemers = [* redeemer]
redeemer = [
  tag   : redeemer_tag,               ; 0=spend, 1=mint, 2=cert, 3=reward
  index : uint,                       ; index into the relevant list
  data  : plutus_data,
  ex_units : [mem: uint, steps: uint] ; execution budget
]
```

### 6.4 Script Data Hash Computation

```
script_data_hash = hash(
  redeemers_bytes ‖ datums_bytes ‖ language_views_bytes
)

; language_views = cost models encoding (canonical CBOR of cost model map)
; This is one of the trickiest parts to get right — encoding must be exact
```

### 6.5 Collateral and Two-Phase Validation

- Phase 1: All non-Plutus checks (inputs exist, fees, signatures)
- Phase 2: Plutus script execution
- If Phase 2 fails: collateral is consumed, no other effects
- `collateral inputs` must be VKey-locked (no scripts), Ada-only

---

## 7. Babbage Era (Era ID 6)

### 7.1 Inline Datums and Reference Scripts

```cddl
babbage_transaction_output =
    [address, value, ? datum_option, ? script_ref]  ; legacy array format
  / { 0 : address,                                   ; *** NEW: map format
      1 : value,
      ? 2 : datum_option,
      ? 3 : script_ref
    }

datum_option = [0, datum_hash] / [1, #6.24(bytes .cbor plutus_data)]  ; inline datum

script_ref = #6.24(bytes .cbor script)   ; reference script (any type)
script = [0, native_script] / [1, plutus_v1_script] / [2, plutus_v2_script]
```

### 7.2 Transaction Body Changes

```cddl
babbage_transaction_body = {
  0  : set<transaction_input>,
  1  : [* babbage_transaction_output],
  2  : coin,
  ? 3  : uint,
  ? 4  : [* certificate],
  ? 5  : withdrawals,
  ? 7  : auxiliary_data_hash,
  ? 8  : uint,
  ? 9  : mint,
  ? 11 : script_data_hash,
  ? 13 : set<transaction_input>,       ; collateral inputs
  ? 14 : set<addr_keyhash>,
  ? 15 : network_id,
  ? 16 : coin,                         ; *** NEW: collateral return output value
  ? 17 : coin,                         ; *** NEW: total collateral
  ? 18 : set<transaction_input>        ; *** NEW: reference inputs
}
```

### 7.3 Plutus V2

```cddl
plutus_v2_script = bytes    ; same container, different language version
; V2 scripts receive a ScriptContext with more data:
;   - reference inputs visible
;   - inline datums accessible
;   - reference scripts usable
```

### 7.4 Key Babbage Differences

| Feature | Alonzo | Babbage |
|---------|--------|---------|
| Output format | Array only | Array or Map |
| Datums | Hash-referenced only | +Inline datums |
| Scripts in outputs | No | Reference scripts |
| Reference inputs | No | Yes (read-only inputs) |
| Collateral return | No | Yes (change back) |
| Plutus versions | V1 only | V1 + V2 |
| Protocol param updates | `update` field (6) | Removed (uses governance) |

---

## 8. Conway Era (Era ID 7)

### 8.1 Governance and New Certificate Types

Conway introduces on-chain governance (CIP-1694).

```cddl
conway_transaction_body = {
  0  : set<transaction_input>,
  1  : [* babbage_transaction_output],  ; same output format as Babbage
  2  : coin,
  ? 3  : uint,
  ? 4  : [* conway_certificate],        ; *** CHANGED: expanded cert types
  ? 5  : withdrawals,
  ? 7  : auxiliary_data_hash,
  ? 8  : uint,
  ? 9  : mint,
  ? 11 : script_data_hash,
  ? 13 : set<transaction_input>,
  ? 14 : set<addr_keyhash>,
  ? 15 : network_id,
  ? 16 : coin,
  ? 17 : coin,
  ? 18 : set<transaction_input>,
  ? 19 : [* voting_procedure],          ; *** NEW: governance votes
  ? 20 : [* proposal_procedure],        ; *** NEW: governance proposals
  ? 21 : coin,                          ; *** NEW: treasury donation
  ? 22 : coin                           ; *** NEW: current treasury value
}
```

### 8.2 Conway Certificate Types

```cddl
conway_certificate = [
  ; -- existing (renumbered) --
    0, stake_credential, coin           ; reg cert (now includes deposit)
  / 1, stake_credential, coin           ; unreg cert (now includes deposit refund)
  / 2, stake_credential, pool_keyhash   ; stake delegation
  / 3, pool_params                      ; pool registration
  / 4, pool_keyhash, epoch              ; pool retirement
  ; -- new Conway types --
  / 5, stake_credential, drep            ; *** NEW: vote delegation
  / 6, stake_credential, pool_keyhash, drep  ; *** NEW: stake+vote delegation
  / 7, stake_credential, coin, pool_keyhash, drep  ; *** NEW: reg+stake+vote
  / 8, stake_credential, coin, drep      ; *** NEW: reg+vote delegation
  / 9, stake_credential, coin, pool_keyhash  ; *** NEW: reg+stake delegation
  / 10, drep_credential, coin, anchor    ; *** NEW: DRep registration
  / 11, drep_credential, coin            ; *** NEW: DRep deregistration
  / 12, drep_credential, anchor          ; *** NEW: DRep update
  / 13, committee_auth_hot               ; *** NEW: authorize committee hot key
  / 14, committee_resign_cold            ; *** NEW: resign committee cold key
]

drep = [0, addr_keyhash]    ; key hash DRep
     / [1, scripthash]      ; script DRep
     / [2]                   ; always abstain
     / [3]                   ; always no-confidence
```

### 8.3 Governance Actions

```cddl
proposal_procedure = [
  deposit        : coin,
  reward_account : reward_address,
  gov_action     : gov_action,
  anchor         : anchor
]

gov_action =
    [0, ? gov_action_id, [* [uint, constitution]]]  ; parameter change
  / [1, ? gov_action_id, [* [cold_credential, epoch]], uint, uint]  ; hard fork
  / [2, ? gov_action_id, withdrawals]                ; treasury withdrawal
  / [3, ? gov_action_id]                             ; no confidence
  / [4, ? gov_action_id, [* [cold_credential, epoch]], uint, uint]  ; update committee
  / [5, ? gov_action_id, constitution]               ; new constitution
  / [6]                                              ; info action

voting_procedure = [
  voter         : voter,
  gov_action_id : gov_action_id,
  vote          : vote,
  ? anchor      : anchor
]

voter = [0, addr_keyhash]    ; constitutional committee
      / [1, addr_keyhash]    ; DRep
      / [2, pool_keyhash]    ; SPO

vote = 0  ; no
     / 1  ; yes
     / 2  ; abstain
```

### 8.4 Plutus V3

```cddl
plutus_v3_script = bytes
; V3 scripts can:
;   - access governance actions in ScriptContext
;   - be used as DRep scripts, committee scripts
;   - use new Plutus builtins (BLS, bitwise, etc.)
```

### 8.5 Witness Set (Conway)

```cddl
conway_transaction_witness_set = {
  ? 0 : [* vkeywitness],
  ? 1 : [* native_script],
  ? 2 : [* bootstrap_witness],
  ? 3 : [* plutus_v1_script],
  ? 4 : [* plutus_data],
  ? 5 : redeemers,
  ? 6 : [* plutus_v2_script],         ; moved from key 3 sharing
  ? 7 : [* plutus_v3_script]          ; *** NEW
}
```

---

## 9. Summary of Era-by-Era Changes

| Aspect | Byron | Shelley | Allegra | Mary | Alonzo | Babbage | Conway |
|--------|-------|---------|---------|------|--------|---------|--------|
| Era ID | 0/1 | 2 | 3 | 4 | 5 | 6 | 7 |
| Output | addr+coin | addr+coin | addr+coin | addr+value | +datum_hash | +inline datum+ref script | same |
| Value | coin | coin | coin | coin+multiasset | same | same | same |
| Scripts | none | multisig | +timelock | same | +Plutus V1 | +Plutus V2 | +Plutus V3 |
| Cert types | none | 7 | same | same | same | same | 15+ |
| Tx body map keys | N/A | 0-7 | 0-8 | 0-9 | 0-15 | 0-18 | 0-22 |
| Metadata | none | field 7 | structured | same | same | same | same |
| Governance | none | pparam update | same | same | same | same | full CIP-1694 |

---

## 10. CBOR Encoding Pitfalls

### 10.1 Canonical vs Non-Canonical CBOR

Cardano uses **canonical CBOR** for hashing but not always for wire format:

- **Block headers**: canonical CBOR (for hash stability)
- **Transaction bodies**: canonical CBOR (tx hash = hash of canonical body bytes)
- **Witness sets**: non-canonical allowed (not hashed)
- **Auxiliary data**: non-canonical allowed

**Canonical rules:**
- Integers use minimum-length encoding
- Map keys sorted by CBOR byte comparison
- Definite-length arrays and maps (no streaming)
- No unnecessary tags

### 10.2 Preserving Original Bytes

For hash verification, Stretto must preserve the **original CBOR bytes** of:
- Transaction bodies (for txId computation)
- Block headers (for block hash)
- Script bodies (for script hash)

This means the decoder should capture raw bytes alongside the decoded structure.

### 10.3 Set Encoding

CDDL `set<T>` is encoded as:
- **Pre-Conway**: `#6.258([* T])` — tagged array (tag 258 = set)
- Some implementations also accept untagged arrays for compatibility
- The Haskell node uses tag 258 consistently

### 10.4 Coin vs Int

- `coin = uint` — always non-negative
- Minting: `mint = multiasset<int>` — int allows negative for burning
- Value conservation must handle negative minting values

---

## 11. Recommended scodec Implementation Strategy

[scodec](https://scodec.org/) is the natural fit for Stretto's CBOR codec layer
given its composable, purely functional design.

### 11.1 Architecture

```
Layer 1: CBOR primitives (cbor-codec)
  ├── CborValue ADT (uint, nint, bstr, tstr, array, map, tag, simple)
  ├── CborDecoder: BitVector => Attempt[DecodeResult[CborValue]]
  └── CborEncoder: CborValue => Attempt[BitVector]

Layer 2: Cardano domain codecs (era-specific)
  ├── ShelleyCodecs  : Codec[Bit, ShelleyBlock]
  ├── AlonzoCodecs   : Codec[Bit, AlonzoBlock]
  ├── BabbageCodecs  : Codec[Bit, BabbageBlock]
  ├── ConwayCodecs   : Codec[Bit, ConwayBlock]
  └── MultiEraCodec  : Codec[Bit, Block]   -- dispatches by era ID

Layer 3: Raw-bytes-preserving wrapper
  └── AnnotatedCodec[A] = Codec[Bit, (A, ByteVector)]
      -- decodes A and captures the original bytes for hashing
```

### 11.2 Core CBOR Codec Primitives

```scala
object CborCodec {
  // CBOR major type (3 bits) + additional info (5 bits)
  val majorType: Codec[Int] = uint(3)
  val addInfo: Codec[Int]   = uint(5)

  // Variable-length unsigned integer
  val cborUint: Codec[Long] = majorType.flatZip {
    case 0 => additionalInfoValue  // uint
    case _ => fail(Err("expected uint"))
  }.xmap(_._2, (0, _))

  // Bytes with length prefix
  val cborBytes: Codec[ByteVector] = ...

  // CBOR map with known keys (for transaction bodies)
  def cborMap[A](entries: MapCodecEntry[A]*): Codec[A] = ...
}
```

### 11.3 Era-Dispatching Codec

```scala
val multiEraBlock: Codec[Block] = {
  val eraId = CborCodec.cborUint.xmap(_.toInt, _.toLong)

  cborArray {
    eraId.flatZip {
      case 0 | 1 => byronBlockCodec
      case 2     => shelleyBlockCodec
      case 3     => allegraBlockCodec
      case 4     => maryBlockCodec
      case 5     => alonzoBlockCodec
      case 6     => babbageBlockCodec
      case 7     => conwayBlockCodec
      case n     => fail(Err(s"Unknown era: $n"))
    }
  }.xmap(_._2, b => (b.eraId, b))
}
```

### 11.4 Annotated (Raw-Bytes-Preserving) Codec

```scala
/** Decode A while capturing the raw bytes consumed */
def annotated[A](codec: Codec[A]): Codec[Annotated[A]] =
  new Codec[Annotated[A]] {
    def decode(bits: BitVector): Attempt[DecodeResult[Annotated[A]]] = {
      codec.decode(bits).map { result =>
        val consumed = bits.take(bits.size - result.remainder.size)
        DecodeResult(
          Annotated(result.value, consumed.bytes),
          result.remainder
        )
      }
    }
    def encode(a: Annotated[A]): Attempt[BitVector] =
      // Re-encode from original bytes if available (hash stability)
      if (a.rawBytes.nonEmpty) Attempt.successful(a.rawBytes.bits)
      else codec.encode(a.value)

    def sizeBound = codec.sizeBound
  }

case class Annotated[+A](value: A, rawBytes: ByteVector)
```

### 11.5 Key Implementation Notes

1. **Map ordering**: CBOR maps in tx bodies use integer keys. scodec should decode
   in any key order but encode in canonical (sorted) order for hashing.

2. **Optional fields**: CDDL `? key : type` means the key may be absent from the
   map. Model as `Option[T]` in Scala with conditional encode/decode.

3. **Set tag**: Use `#6.258(array)` for sets. Define a `cborSet` combinator that
   wraps arrays with tag 258.

4. **Backwards compatibility**: The decoder should be lenient about missing tags
   or slightly different encodings for older blocks. The encoder should always
   produce canonical CBOR.

5. **Performance**: For the UTXO set (millions of entries), consider lazy decoding —
   store raw `ByteVector` and decode on demand.

---

## 12. Implementation Checklist for Stretto

### Phase 1 — CBOR Codec Foundation

- [ ] `CborValue` ADT with all CBOR major types
- [ ] CBOR decoder (BitVector → CborValue) with canonical support
- [ ] CBOR encoder (CborValue → BitVector) with canonical output
- [ ] `Annotated[A]` wrapper for raw-bytes preservation
- [ ] Property tests: decode(encode(x)) = x for all CborValue

### Phase 2 — Era-Specific Block Codecs

- [ ] Byron block codec (EBB + main block)
- [ ] Shelley block codec (header, body, witnesses, metadata)
- [ ] Allegra extensions (validity interval, timelock scripts)
- [ ] Mary extensions (multi-asset value, mint field)
- [ ] Alonzo extensions (Plutus scripts, datums, redeemers, collateral)
- [ ] Babbage extensions (inline datums, ref scripts, ref inputs, map outputs)
- [ ] Conway extensions (governance, new certs, Plutus V3)

### Phase 3 — Multi-Era Dispatch

- [ ] Era ID detection and dispatch codec
- [ ] Network wire wrapper (tag 24 embedded CBOR)
- [ ] Round-trip tests against real mainnet blocks (golden tests)
- [ ] Hash verification: decoded block header hash matches expected

### Phase 4 — Transaction Codec

- [ ] Transaction body codec per era with all optional fields
- [ ] Witness set codec with all witness types
- [ ] Auxiliary data codec (all three formats: map, tuple, tagged)
- [ ] Transaction ID computation (hash of canonical body bytes)
- [ ] Script hash computation (blake2b-224 of script bytes)

---

## References

1. CBOR RFC 8949: https://www.rfc-editor.org/rfc/rfc8949
2. CDDL RFC 8610: https://www.rfc-editor.org/rfc/rfc8610
3. Shelley CDDL: https://github.com/intersectmbo/cardano-ledger/blob/master/eras/shelley/test-suite/cddl-files/shelley.cddl
4. Alonzo CDDL: https://github.com/intersectmbo/cardano-ledger/blob/master/eras/alonzo/test-suite/cddl-files/alonzo.cddl
5. Babbage CDDL: https://github.com/intersectmbo/cardano-ledger/blob/master/eras/babbage/test-suite/cddl-files/babbage.cddl
6. Conway CDDL: https://github.com/intersectmbo/cardano-ledger/blob/master/eras/conway/test-suite/cddl-files/conway.cddl
7. scodec documentation: https://scodec.org/guide/
