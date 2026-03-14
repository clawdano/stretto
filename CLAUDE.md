# Stretto — CLAUDE.md

## Project Overview
**Stretto** is a spec-compliant Cardano full node implementation in Scala 3, leveraging existing JVM ecosystem libraries. Named after the musical term where voices converge toward resolution — like nodes reaching consensus. This is a vibe-coded project — all commits must include AI co-authorship tags and prompts.

## Language & Tooling
- **Language:** Scala 3.6.x (latest stable)
- **Build Tool:** sbt 1.10.x
- **JVM Target:** Java 21 (LTS, virtual threads, modern GC)
- **Testing:** MUnit for unit tests, ScalaCheck for property-based testing
- **CI:** GitHub Actions

## Key Dependencies (JVM Ecosystem)
- **nau/scalus** — Plutus script evaluation (Scala 3, native)
- **scodec** — Binary codec primitives for CBOR and mux framing
- **cats-effect 3** — Asynchronous effect system (IO)
- **fs2** — Functional streaming and TCP networking
- **http4s** — Metrics and API server

> Ouroboros miniprotocols and CBOR codecs are implemented from scratch in pure Scala.

## Project Structure
```
stretto/
├── CLAUDE.md
├── README.md
├── build.sbt
├── project/
├── docs/                    # Architecture docs, research, plans
│   ├── research/            # Research findings
│   ├── architecture/        # Design documents
│   └── plans/               # Implementation plans & milestones
├── modules/
│   ├── core/                # Shared types, primitives, crypto
│   ├── serialization/       # CBOR encoding/decoding (all eras)
│   ├── network/             # Ouroboros miniprotocols (N2N, N2C)
│   ├── consensus/           # Ouroboros Praos consensus
│   ├── ledger/              # Ledger rules, UTxO, validation
│   ├── mempool/             # Transaction mempool
│   ├── storage/             # Chain database, state persistence
│   ├── node/                # Main node assembly, configuration
│   └── cli/                 # CLI interface
└── tests/
    ├── unit/
    ├── property/
    └── conformance/         # Cross-validation with Haskell node
```

## Coding Conventions
- **Immutability first** — prefer `val`, immutable collections, case classes
- **ADTs for domain modeling** — use `enum` and sealed traits for protocol states, eras, validation results
- **Effect system** — use cats-effect `IO` for all effectful code
- **No nulls** — use `Option`, `Either`, or custom error types
- **Pattern matching** — leverage exhaustive matching for era-specific logic
- **Interop with Java** — wrap Java library calls in `IO` or pure Scala wrappers
- **Type safety** — use opaque types for domain primitives (SlotNo, BlockNo, Hash, etc.)
- **Error handling** — use `Either[Error, A]` for domain errors, `IO` for effects, never throw exceptions in business logic

## Commit Message Format
Every commit MUST follow this format:
```
<type>(<scope>): <short description>

<optional body with more detail>

Prompt: <the prompt or summary of prompts that led to this change>

Co-Authored-By: Claude <noreply@anthropic.com>
```

Types: feat, fix, refactor, test, docs, chore
Scopes: core, serialization, network, consensus, ledger, mempool, storage, node, cli, docs

## Architecture Principles
1. **Modularity** — each module is independently compilable and testable
2. **Era polymorphism** — ledger rules are parameterized by era, new eras are additive
3. **Spec fidelity** — ledger rules must match the formal Cardano specs exactly (conformance over convenience)
4. **Crash recovery** — all persistent state must be recoverable after power loss
5. **Performance** — target memory usage at or below the Haskell node
6. **Testability** — every validation rule must be independently testable against the Haskell node's behavior

## Important Rules
- NEVER copy code from existing node implementations (Haskell, Rust, Go). Implement from specs.
- ALWAYS reference the specific spec section when implementing ledger rules (e.g., "Shelley spec §13.2")
- ALWAYS include conformance tests when adding validation logic
- Prefer small, focused PRs over large monolithic changes
- Document architectural decisions in `docs/architecture/`

## External Specs Reference
- Ledger formal specs: https://github.com/IntersectMBO/cardano-ledger
- Ouroboros consensus: https://github.com/IntersectMBO/ouroboros-consensus
- Network protocols: https://github.com/IntersectMBO/ouroboros-network
- Plutus core: https://github.com/IntersectMBO/plutus
- CDDL schemas: In cardano-ledger repo under `eras/*/impl/cddl-files/`
- Agda formal specs: https://github.com/IntersectMBO/formal-ledger-specifications
- CIPs: https://github.com/cardano-foundation/CIPs
