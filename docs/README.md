# Rhizome Documentation

> Domain specs were **extracted** from [`WHITEPAPER.md`](../WHITEPAPER.md), [`README.md`](../README.md)
> and source analysis — they are not empty scaffolds, but every one is marked **Draft** and needs a
> review pass.

`WHITEPAPER.md` remains the *design* document — it explains **why** each consensus rule exists
(usually a specific Pandanite bug being fixed). These domain specs are the *functional* source of
truth: what each area does, what it owns, and which invariants must never regress.

## Domains

| Domain | Description | Modules | Status |
|---|---|---|---|
| [consensus](consensus/spec.md) | Block validation order, difficulty, cadence, GHOST fork choice, finality, emission, pinned genesis supply | `lib-core/blockchain`, `lib-core/block` | Draft |
| [transactions](transactions/spec.md) | Transaction envelope, nonces, ledger arithmetic, execution, mempool & fee market | `lib-core/transaction`, `ledger`, `mempool` | Draft |
| [contracts](contracts/spec.md) | WASM VM determinism, host ABI, gas, sessions & undo journals, reference contracts | `lib-vm` | Draft |
| [boxes](boxes/spec.md) | Data boxes — typed registers, anti-dust deposit, storage rent, scans | `lib-core/box` | Draft |
| [tokens](tokens/spec.md) | Native fungible assets — mint/transfer/burn with no contract and no gas | `lib-core/token` | Draft |
| [banknotes](banknotes/spec.md) | Paper bearer notes — irrevocable escrow, NFC-chip redemption, machine attestation | *(none yet)* | Design |
| [networking](networking/spec.md) | HTTP p2p, PEX, gossip, ban scoring, headers-first sync, pruning | `lib-net`, `lib-core` sync | Draft |
| [state](state/spec.md) | Sparse-Merkle state root, light-client proofs, snapshot bootstrap | `lib-core/state` | Draft |
| [persistence](persistence/spec.md) | RocksDB column families, atomic batches, undo journals | `lib-persistence` | Draft |
| [node-api](node-api/spec.md) | HTTP surface, env config, token auth, CSRF/rebinding gates, aggregate budgets | `app-node` | Draft |
| [dashboard](dashboard/spec.md) | Embedded zero-dependency web UI — 6 pages, browser key custody | `app-node/resources/dashboard` | Draft |
| [wallet](wallet/spec.md) | CLI wallet, encrypted keystore, chain-id pin, local signing | `app-wallet` | Draft |
| [crypto](crypto/spec.md) | Ed25519, Pufferfish2 PoW, hashes | `lib-crypto` | Draft |
| [platform](platform/spec.md) | Java 25 toolchain, Gradle wrapper, dependency pins & rationale, native image, lint gate | *(build-wide)* | Draft |
| [adversarial](adversarial/spec.md) | The exploit-scenario catalogue every domain is tested against — each scenario names its proof, machine-checked by `AdversarialProtocolTest` | *(test-wide)* | Active |

## Reading order

New to the codebase? **consensus → transactions → state** covers the core loop. Then branch by
interest: `contracts` + `boxes` for the agent story, `networking` + `persistence` for node
operation, `node-api` + `dashboard` + `wallet` for the surfaces. `platform` is the one to read
before touching `build.gradle` — every pin there is load-bearing and carries a reason.

## Cross-domain invariants

These span domains and are restated in each spec that carries part of them:

- **Atomicity with the block.** Contract, box and token state moves atomically with its block and
  reverses exactly on reorg — persisted undo journals and receipts, atomic `WriteBatch` per store,
  boot reconciliation for anything left ahead of the chain height.
- **Determinism.** No floating point on any consensus quantity; no host time or randomness in the
  VM; all VM budgets are fixed network constants, not JVM-heap-dependent.
- **Derived, never cached.** Difficulty and the voted economic parameters are recomputed from
  header history after every add *and* pop.
- **Header-only derived state.** Difficulty, median-time, uncle work and vote tallies read only
  `headerAt(h)` — this is what makes pruning and snap-sync possible. Nonces are the one exception
  and are persisted plus committed to the state root.
- **Cheapest-first validation.** PoW last, uncle PoW after the block's own PoW. Every request-path
  route meters before it works.
- **Single writer.** All public `ChainEngine` methods serialise on one lock; reorg phases are
  individually atomic.
- **Pinned genesis.** Genesis is an explicit authored allocation, never an import. A network profile
  that pins a genesis supply refuses to build or re-verify genesis against a snapshot whose total
  differs — on every boot path, before any balance is seeded and before the port is bound. The pin
  guards the *total*, the genesis commitment guards the *distribution*; see
  [consensus](consensus/spec.md) C-11 and [state](state/spec.md) S-7.
- **Explicit wiring.** No DI container, no reflection-based wiring, no runtime codegen anywhere —
  the node is assembled with plain constructors. This is what keeps the GraalVM native image
  buildable and the dependency graph readable; it is an architectural constraint, not a style
  preference. See [platform](platform/spec.md) P-5.

## Quick links

- [Whitepaper](../WHITEPAPER.md) — design rationale, security model, roadmap
- [Project README](../README.md) — build, run, configure
- [CLAUDE.md](../CLAUDE.md) — working conventions for this repo
