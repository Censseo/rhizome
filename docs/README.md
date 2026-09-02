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
| [consensus](consensus/spec.md) | Block validation order, difficulty, cadence, GHOST fork choice, finality, supply-targeted emission curve (scheduled on mainnet and devnet) against a height-decaying target, miner revenue floor, pinned genesis supply | `lib-core/blockchain`, `lib-core/block` | Draft |
| [transactions](transactions/spec.md) | Transaction envelope, nonces, ledger arithmetic, execution, mempool & fee market | `lib-core/transaction`, `ledger`, `mempool` | Draft |
| [contracts](contracts/spec.md) | WASM VM determinism, host ABI, gas, sessions & undo journals, reference contracts | `lib-vm` | Draft |
| [boxes](boxes/spec.md) | Data boxes — typed registers, anti-dust deposit, storage rent, scans | `lib-core/box` | Draft |
| [tokens](tokens/spec.md) | Native fungible assets — mint/transfer/burn with no contract and no gas | `lib-core/token` | Draft |
| [banknotes](banknotes/spec.md) | Paper bearer notes — irrevocable escrow, NFC-chip redemption, machine attestation | *(none yet)* | Design |
| [networking](networking/spec.md) | HTTP p2p, PEX, gossip, ban scoring, headers-first sync, pruning | `lib-net`, `lib-core` sync | Draft |
| [state](state/spec.md) | Sparse-Merkle state root, light-client proofs, snapshot bootstrap | `lib-core/state` | Draft |
| [persistence](persistence/spec.md) | RocksDB column families, atomic batches, undo journals | `lib-persistence` | Draft |
| [node-api](node-api/spec.md) | HTTP surface, env config, token auth, CSRF/rebinding gates, aggregate budgets, published monetary state, live emission target & burn obligation | `app-node` | Draft |
| [dashboard](dashboard/spec.md) | Embedded zero-dependency web UI — 6 pages, browser key custody, live-target emission tiles & curve plot | `app-node/resources/dashboard` | Draft |
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
  VM; all VM budgets are fixed network constants, not JVM-heap-dependent. Where a rule is naturally
  continuous — the logarithmic emission curve — it is discretised into an integer table generated
  from published constants and pinned bit-for-bit by a checked-in vector file — with a sibling file
  pinning the decaying target and its decayed evaluation — so an independent implementation that
  disagrees fails a test instead of forking the chain. See [consensus](consensus/spec.md) C-10.
- **Derived, never cached.** Difficulty and the voted economic parameters are recomputed from
  header history after every add *and* pop.
- **Header-only derived state.** Difficulty, median-time, uncle work, vote tallies and the block
  subsidy read only `headerAt(h)` — this is what makes pruning and snap-sync possible, and it is
  why a supply-driven reward needs no accumulator and no rollback journal: the parent header
  already carries its only chain-state input, and the decaying target adds only the block's own
  height. Nonces are the one exception and are persisted plus committed to
  the state root. The same property is what lets a node re-check, from two headers at boot, that
  its stored tip was minted under the emission schedule now in force.
- **Cheapest-first validation.** PoW last, uncle PoW after the block's own PoW. Every request-path
  route meters before it works.
- **Single writer.** All public `ChainEngine` methods serialise on one lock; reorg phases are
  individually atomic.
- **A block always pays its miner.** Under an active emission curve — scheduled from height 1 on
  mainnet and devnet, never on testnet — the scheduled base subsidy is
  `max(R_min, raw(S, S*(h)))` at a single clamp site — strictly positive at every reachable supply,
  with uncle and nephew rewards derived from that same floored base rather than clamped again. A
  profile that configures a non-positive `minerRevenueFloor` refuses to build. The consequence is
  deliberate and documented: issuance does not terminate at the supply target but continues as a
  rate-bounded tail emission, traded for permanent security funding. A decaying target does not
  weaken this: above the live target the floor absorbs the whole reduction, so a falling target
  costs miners nothing; below it the reduction is bounded, monotone, published as a number
  (`≈ c·ln(den/num)` base units per elapsed decay epoch) and never carries the subsidy below
  `R_min`. See
  [consensus](consensus/spec.md) C-6 and C-10, and WHITEPAPER §5.3.
- **The monetary target is a pure function of height.** `S*(h)` holds at its pinned peak until a
  scheduled decay-start height, then decays geometrically once per epoch — checked integer
  multiply-then-divide, floored at every step — to a pinned positive floor. It reads *only* height:
  no timestamp a miner controls, no peer message, no chain state. That is what keeps reorg reversal
  structural — there is no target to roll back, and coinbase validation stays in the pre-PoW
  structural pass with no ledger read — and it is why every scenario in the `DECAY` family reduces
  to one con: pretending the chain is at a height it is not at. The recurrence is tabulated at
  construction, so evaluation is an array read rather than an iteration, and the table is a
  different encoding of the same pure function, not a cache of chain state. Where the target falls
  below supply the raw curve value goes negative *structurally* rather than transiently, and the
  floor absorbs it — yielding a per-block **burn obligation** that is derivable and published but
  **not enforced: no coin is destroyed, and no cumulative debt is accumulated**. See
  [consensus](consensus/spec.md) C-10 and [adversarial](adversarial/spec.md) `DECAY`.
- **What is published is what is paid.** Every monetary figure a node serves is either a value the
  chain already committed — the tip header's `supply` — or one O(1) evaluation of the *same*
  emission dispatch consensus pays; never a re-derivation, never a floating-point approximation of
  the curve. A test asserts each published sample equals that dispatch, so the served schedule
  cannot drift from the paid one. The figure named `target` is the **live** `S*(h)` governing the
  next block — the constant peak moved aside to `peakTarget` — so a consumer that never changed its
  field name still reads the number that actually governs. Amounts cross the wire as decimal strings
  in base units, because
  a 64-bit quantity must not become a JavaScript number. The read side adds no consensus rule, no
  persisted state and no header field, and a surface reporting a `(height, supply)` pair takes one
  lock acquisition so a reorg cannot tear it. See [node-api](node-api/spec.md) A-16 and
  [dashboard](dashboard/spec.md) U-1 — [consensus](consensus/spec.md) C-10 is the rule, A-16 its
  reflection.
- **A node refuses rather than diverges.** Every disagreement between a node's configuration and
  the chain it is asked to run is a **boot refusal, before the port is bound** — never a degraded
  mode, never a warning it keeps running past. The genesis total that misses its pin, the stored
  genesis that does not match the profile, the stored tip minted under a different emission
  schedule, and a decay schedule that cannot be coherently stated all end the process with a message
  naming the values and the remedy, so an operator is
  told at startup instead of discovering it when the first fresh peer cannot sync. Where the
  evidence is unavailable rather than contradictory — a pruned or snapshot-bootstrapped prefix with
  no parent header — the check is *skipped, not failed*: an unverifiable chain is not a wrong one.
  The refusal extends to constants that would be *inert*: at the `decayStartHeight == 0` sentinel
  the other four decay constants must state their zeros explicitly, because a profile stating a full
  decay with an unstated start height has almost certainly meant to schedule one, and silently
  discarding it would mint at the peak forever with no diagnostic.
  See [consensus](consensus/spec.md) C-10 and C-11, and [node-api](node-api/spec.md) A-13.

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
