# Consensus Specification

> Source of truth for block validation, chain progression, difficulty, and fork choice.
> Extracted from `WHITEPAPER.md` §3, §4, §9 and source analysis.
> **Status**: Draft — needs review

## Overview

Rhizome is a proof-of-work chain with a **5-second block target paced by difficulty** (not by a
per-block time floor) and a **GHOST-style fork choice** that credits and rewards orphaned (uncle)
work. It starts from a fresh genesis rather than replaying Pandanite history, precisely so that
none of the ten catalogued C++ consensus defects (§4) has to be reproduced.

The domain owns everything that decides *whether a block is valid and whether a branch wins*:
the header preimage, the validation order, difficulty derivation, timestamp rules, the Merkle
commitment, uncle eligibility and reward scaling, cumulative-work comparison, and the finality
window. It does **not** own what a transaction means once a block is accepted — that is
[transactions](../transactions/spec.md).

## Scope

**Owns**

| Area | Source |
|---|---|
| Chain engine (`addBlock`/`popBlock`, single lock) | [ChainEngine.java](../../lib-core/src/main/java/rhizome/core/blockchain/ChainEngine.java) |
| Block/header model, codecs, hash preimage | [core/block/](../../lib-core/src/main/java/rhizome/core/block/) |
| Difficulty derivation | [DifficultyAdjustment.java](../../lib-core/src/main/java/rhizome/core/blockchain/DifficultyAdjustment.java) |
| Order-preserving Merkle tree | [MerkleTree.java](../../lib-core/src/main/java/rhizome/core/merkletree/MerkleTree.java) |
| Uncles / orphan pool / GHOST weighting | [OrphanPool.java](../../lib-core/src/main/java/rhizome/core/blockchain/OrphanPool.java), [UncleRef.java](../../lib-core/src/main/java/rhizome/core/block/UncleRef.java) |
| Block production & assembly | [BlockProducer.java](../../lib-core/src/main/java/rhizome/core/blockchain/BlockProducer.java), [BlockAssembler.java](../../lib-core/src/main/java/rhizome/core/blockchain/BlockAssembler.java), [Miner.java](../../lib-core/src/main/java/rhizome/core/blockchain/Miner.java) |
| Network constants & miner-voted params | [NetworkParameters.java](../../lib-core/src/main/java/rhizome/core/blockchain/NetworkParameters.java), [VoteableParams.java](../../lib-core/src/main/java/rhizome/core/blockchain/VoteableParams.java) |
| Emission schedule — curve generation & evaluation, per-block issuance | [EmissionCurve.java](../../lib-core/src/main/java/rhizome/core/blockchain/EmissionCurve.java), [Issuance.java](../../lib-core/src/main/java/rhizome/core/blockchain/Issuance.java) |
| Genesis construction | [GenesisBlock.java](../../lib-core/src/main/java/rhizome/core/blockchain/GenesisBlock.java), [GenesisLedger.java](../../lib-core/src/main/java/rhizome/core/ledger/GenesisLedger.java) |
| Header-chain stateless validation & fork-choice gate | [HeaderChain.java](../../lib-core/src/main/java/rhizome/core/blockchain/HeaderChain.java), [HeaderSynchronizer.java](../../lib-core/src/main/java/rhizome/core/blockchain/HeaderSynchronizer.java) |

**Does not own**

- Transaction semantics, ledger arithmetic, mempool policy → [transactions](../transactions/spec.md)
- The PoW primitive itself (Pufferfish2) → [crypto](../crypto/spec.md)
- Peer selection, transport, ban scoring → [networking](../networking/spec.md)
- The state-root commitment's construction → [state](../state/spec.md) (consensus only *validates* it)

## Features

### C-1 — Full-header block hash preimage *(implemented)*

`hash = H(merkleRoot ‖ lastBlockHash ‖ id ‖ difficulty ‖ numTransactions ‖ timestamp)`, integers
big-endian. Every header field is committed, unlike the C++ node whose `getHash` covered only
`{merkleRoot, lastBlockHash, difficulty, timestamp}`.

Four optional fields — the **state root** (§5.7), the miner **vote** (C-8), the header-committed
**supply** (§5.3) and uncle references (C-6) — are folded in, in that order, **only when
present**, so a plain stateless abstaining block hashes byte-for-byte as it did before those
features existed: `stateRoot` (32 bytes, when non-empty), `vote` (4 bytes, when non-zero),
`supply` (8 bytes big-endian, when `>= 0` — i.e. committed; `-1` is the absent sentinel and `0`
is a legal committed value, e.g. an empty genesis), then the uncle records (when any are
referenced).

### C-2 — `addBlock` validation order *(implemented)*

Cheapest and structural first, proof of work last. This ordering is **DoS armor, not style**:

1. `id` continuity; transaction count non-empty and ≤ `maxTransactionsPerBlock`
2. checkpoint (at a pinned height, only the published hash passes)
3. `lastBlockHash` chains to the tip — checked from block 2 (Pandanite bug #2)
4. timestamp > median-time-past, ≥ parent + `minBlockTime`, ≤ local clock + `maxFutureBlockTime`
5. difficulty equals the value recomputed from history
6. Merkle root matches the block's transactions
7. account nonces strictly sequential per sender
8. the block's **own** proof of work
9. **uncle validation** — structural limits plus each referenced orphan's memory-hard PoW.
   Deliberately after step 8, so a PoW-free `/submit` cannot force `maxUnclesPerBlock`
   memory-hard hashes as a cheap event-loop amplifier
10. the `Executor` applies transactions transactionally

### C-3 — Single-writer locking *(implemented)*

`ChainEngine` serialises on one lock — but the blanket claim "all public methods serialise on one
lock" is false, and a blanket claim that is false in places is worse than an accurate one, because
it stops anyone from asking which places. There are three tiers, mirroring the class javadoc:

1. **Serialised on the engine lock.** Every mutator (`addBlock`, `popBlock`, `stampStateRoot`,
   `runExclusive`, `withConsistentView`) and every getter that reads the ledger, the store or a
   derived cache (`height`, `tipHash`, `blockAt`, `headerAt`, `difficulty`, `totalWork`,
   `nextNonce`, `confirmedBalance`, `box`, `tokenBalance`, `voteableParams`, `stateRoot`, …). One
   writer at a time; reads see a consistent snapshot. The GHOST uncle machinery (`UncleRegistry`)
   is a lock-free collaborator called only from these already-locked paths, so the single-writer
   doctrine holds without it re-entering the lock.
2. **Deliberately lock-free, seqlock-guarded.** `scanBoxes`, `boxEvents` and `tokenEvents` read
   the box/token stores without the lock and fall back to it only if a `stampStateRoot` dry-run
   overlapped the read. This is the one intentional exception, and it exists because these are
   the scan-shaped reads that would otherwise hold the consensus lock for a whole window of
   entries.
3. **Lock-free by construction.** `isReorgInProgress`, `degradedState`, `isDegraded`,
   `boxesEnabled`, `tokensEnabled`, `params`, `nowMillis` and `setOnBlockApplied` touch only
   atomics, volatiles or final fields. They need no lock and must not take one — `isDegraded` and
   `isReorgInProgress` are polled from paths that must never block behind a block application.

The rule C-3 protects is unchanged: Pandanite's unlocked getters produced torn reads of its
`BigInt` cumulative work, and its opposed mempool↔blockchain lock orders deadlocked under load
(§4.9).

Reorg is atomic: the bounded small-reorg path runs the whole pop→apply→restore sequence under one
`withConsistentView` hold (branch prefetched, so no network I/O under the lock). The header-first
path streams bodies and cannot hold the lock across that I/O, so it makes its capture/pop and
restore/adopt phases individually atomic — a forward-apply race becomes a clean self-healing abort.

### C-4 — Derived, never-cached difficulty *(implemented)*

Difficulty is a pure deterministic function of prior **header** timestamps: genesis difficulty,
stepped once per completed retarget window (`difficultyLookback`, 60 blocks on mainnet), bounded
and clamped to limit time-warp leverage. Recomputed after every add **and** pop, never read from a
stored field — the flaw behind Pandanite's hard-coded 536100–536200 exception. Memoised
incrementally, but the memo is byte-identical to the derivation.

### C-5 — Cadence and timestamp rules *(implemented)*

Mainnet sets **`minBlockTime = 0`**: difficulty does the pacing. Setting `minBlockTime =
desiredBlockTime` starves the retarget — a full window always measures ≈ the desired duration, so
difficulty pins near `minDifficulty` regardless of real hashrate, collapsing PoW cost to a fixed
`2^minDifficulty`.

- Target **5 s**, not 1 s: at ~200 ms propagation a 1 s target orphans ≈18% of honest blocks
  (`1 − e^(−0.2)`) versus ≈4% at 5 s. 1 s becomes viable once compact-block relay exists.
- `maxFutureBlockTime = 15 s` (≈3 blocks) — divided by block time, that is how many blocks an
  attacker can pre-mine into the future before release.
- Median-time-past spans `medianTimeWindow` (~60 blocks, ≈5 min) so a few consecutive blocks
  cannot drag the chain's notion of past time.
- Future is bounded by the **local clock**, not a Sybil-manipulable network median (§4.7).

### C-6 — GHOST fork choice with uncles *(implemented)*

- **Uncle references** — up to `maxUnclesPerBlock` (2) per block. A reference commits the uncle's
  hash, difficulty *and* miner address; all three checked against the real orphan at admission, so
  work cannot be inflated and the reward cannot be redirected.
- **Orphan pool** — bounded LRU of valid off-chain blocks (PoW-gated), fed from reorg losers and
  from siblings submitted to the node.
- **Eligibility** — recent (within `uncleMaxDepth` = 7), forks from a recent main-chain block, is
  not the canonical block at its height, and has not been referenced already (no double-crediting).
- **Reward scaling** — an included uncle pays `uncleRewardNum/uncleRewardDen` (1/2) of the block
  reward, the nephew a bonus of `miningReward/nephewRewardDivisor` (1/32), each **halved once per
  bit** the uncle's difficulty falls short of the nephew's (`base × 2^(uncleDiff − nephewDiff)`, an
  exact integer shift). A flat reward would let a miner staple cheap min-difficulty orphans onto a
  real block and roughly double emission for negligible hashing.
- Both bases derive from *this block's own* mining reward, so under the emission curve (C-10) they
  are supply-aware too: `uncleReward(height, parentSupply)` and `nephewReward(height, parentSupply)`
  are computed from the same dispatched base the coinbase used, never from a second, independently
  dispatched one. One block, one parent supply, one emission decision — for the coinbase and every
  uncle/nephew bonus alike.

### C-7 — Fork choice, finality, and the anti-lying-peer gate *(implemented)*

- Cumulative work is `Σ 2^difficulty` summed as `BigInteger`; a peer's chain is adopted **only if
  strictly greater**.
- **Finality window** — a reorg deeper than `maxReorgDepth` (120 blocks, ≈10 min) is refused
  outright, whatever work is claimed.
- **One work metric, base-only, at the gate** — the "should I look?" prefilter and the "should I
  adopt?" decision both rank by *own-block* PoW with **no uncle term**. Counting a header's
  *claimed* uncle work would let a cheaply-mined branch pad each header with in-range fake uncle
  references and inflate apparent work ~3×, forcing an expensive pop/restore. Genuine uncle (GHOST)
  work still decides the final fork choice once bodies prove eligibility, and the adopt path is
  guarded so a reorg can never lower the chain's total work.
- **No free rollbacks** — a bounded prefix of the peer's branch is fetched and validated
  *statelessly* before any local mutation.
- **Restore on failure** — if the stateful apply fails, the local chain is restored exactly.
- A structurally valid but *lighter* branch is a lost fork race, not a protocol violation: the peer
  stays connected rather than being banned.

### C-8 — Miner-voted economic parameters *(implemented)*

Each header may carry an `int` **vote** (committed in the header hash only when cast): `±1` for
`storageFeeFactor`, `±2` for `minValuePerByte`. At each `votingEpochLength` (1024) boundary the
engine tallies that epoch's votes and moves a parameter one bounded step when the net vote exceeds
half the epoch, effective the next epoch.

Both parameters are **floored at 1** — a majority voting either to zero would make permanent
on-chain storage free, unbounded state bloat the voters do not internalise. Current values are
**derived from chain history** like difficulty (a per-epoch-boundary snapshot recomputed from the
epoch's block votes), so a reorg across a boundary drops the snapshot and restores the previous
values. Only economic parameters are votable — not supply, not the PoW. Miner sets `RHIZOME_VOTE`.

### C-9 — Order-preserving Merkle tree *(implemented)*

The tree preserves transaction **insertion order** — the root commits to the order, not just the
set. Sorting (as the C++ did, mutating the caller's vector by reference) would make `[t0,t1]` and
`[t1,t0]` share a root, hence a block hash and its PoW, while nonce validation is order-dependent:
the same hash could be accepted or rejected depending on receive order, splitting consensus.
Second-preimage is neutralised by 0x00/0x01 domain separation, a committed `numTransactions`, and
the Executor's in-block content-hash dedup (CVE-2012-2459 shape).

### C-10 — Supply-targeted logarithmic emission curve *(implemented, inactive on shipped networks)*

The block subsidy is a function of the chain's own header-committed circulating supply, not of
height: `R(S) = c · ln(S*/S)`, evaluated entirely in integer arithmetic (a fixed-point
successive-squaring log, `BigInteger` at build time, `long` at evaluation time — never floating
point) so independent implementations cannot disagree and fork the chain. `S*` (mainnet:
`2 997 924 580 000` base units) is a fixed monetary target; `c` (mainnet: `23 750` base units) is
a calibration coefficient. The curve is a stepped table of `N = 256` uniform positions over
`(0, S*]`, generated once per `NetworkParameters` construction from the published `(S*, c, N)`
triple, evaluated in O(1) with linear interpolation; above `S*` the reward mirrors negative by
ratio (`raw(S) = −(interp(⌊S*²/S⌋) + 1)`, an exact `0` only at `S = S*`); consensus clamps the
mined reward to `max(0, raw(S))` at a single site. The full generation/evaluation algorithm and
its calibration record (`τ ≈ 20 y`, launch reward ≈2.61 PDN/block, terminal supply, reorg
continuity ≤1 base unit) are the normative WHITEPAPER §5.3; the checked-in
`lib-core/src/test/resources/emission/curve-vectors.json` pins the generated output bit-for-bit
for independent implementers.

`NetworkParameters.emissionCurveHeight` gates *which* rule governs a block: `0` (the polarity of
`powUpgradeHeight`, not `boxActivationHeight`) means **never** — the curve exists, is fully
specified and vector-pinned, but mints nothing. Every network Rhizome ships today sets it to `0`;
below activation (i.e. always, at present) the **legacy geometric rule** governs unchanged: the
subsidy decays geometrically (×2/3) once per `rewardEpochBlocks`, in integer arithmetic. Total
issuance = `epochBlocks × initialReward × 3 ≈ 100M PDN`. Both knobs are rescaled by the cadence
ratio (×18 = 90/5) so the **real-time schedule is preserved**: `rewardEpochBlocks ≈ 12 000 000`,
`initialReward = 2.7777 PDN`, epoch ≈ 1.9 yr, 48 000 PDN/day. Locked by a cadence-relative test
(`emissionScheduleIsCalibratedForTheBlockCadence`) that recomputes both the geometric epoch span
and the curve's `τ_blocks` from `desiredBlockTimeSec`, so changing block time forces both
schedules to be revisited. A later feature schedules the real curve activation height once the
genesis allocation (C-11) is ratified.

**Plumbing.** The curve's sole input is the **parent's committed supply** — the C-1 header field,
never a ledger query, so the reward is as reorg-structural as the supply commitment itself. That
value is threaded to every site that mints or checks a reward: `Executor` (coinbase exactness and
the uncle/nephew mint), `BlockAssembler` (producer-side stamping), `ChainEngine.addBlock` and the
stateless `HeaderChain.validate` gate, all through `Issuance.minted(params, height, parentSupply,
uncles)` — still the single formula the two gates and the producer share. Three consequences worth
stating, because each is a place the plumbing could have gone wrong instead:

- **`SUPPLY_ABSENT` is a refusal, not a fallback.** A curve-active height whose parent commits no
  supply has no defined reward; the block is rejected rather than quietly paid the geometric value.
  `BlockAssembler` guards its coinbase computation the same way its `supply` field already was, so
  a misconfigured producer never mines a nonsense reward for the executor to reject afterwards.
- **Rollback derives, it does not re-dispatch.** `Executor.rollbackBlock` takes no `parentSupply` —
  under the curve `miningReward` is not a pure function of height, so re-deriving the uncle and
  nephew bases from a fresh dispatch would need an input reorg does not have. It reads them from
  the block's **own validated coinbase amount** instead, which pass 1 already pinned to the exact
  value the forward path computed. Apply and rollback stay exact inverses without a new argument or
  a new journal (`LedgerReversalExactnessTest`).
- **The rule in force is the one at the block's real position.** A reward is never a decision cached
  at assembly time: a reorg that moves a block across `emissionCurveHeight` pays whatever the height
  it actually occupies on the winning branch mandates.

**Profile inheritance.** `testnet()`/`devnet()` derive from `cleanMainnet().toBuilder()` and
therefore **inherit** `supplyTarget`, `emissionCoefficient` and `emissionTableSteps` — deliberately,
unlike the genesis-supply pin they explicitly reset (C-11). The calibration is shared because it is
inert: `emissionCurveHeight` is `0` on all three, so no profile mints from the curve. Every profile
still *generates* and validates its table at construction, so a degenerate `(S*, c, N)` triple fails
loudly at `NetworkParameters` build time rather than at the first curve-active block.

### C-11 — Pinned genesis supply *(implemented)*

`NetworkParameters.genesisSupply` is a fixed per-network consensus constant, set per profile
exactly like `chainId`: never miner-voted, never configurable by environment variable, never
derived from the snapshot at runtime (the check direction is always snapshot → constant). The
distinguished sentinel `GENESIS_SUPPLY_UNPINNED` (`-1`, outside the non-negative consensus
range) means "this profile pins nothing" — a pinned `0` remains a distinct, meaningful value
("this network mandates an empty genesis"), so `0` cannot double as the sentinel.

`GenesisBlock.build` composes the pin check with its existing guards in a fixed order, all
integer-only, all fail-loud, before any wallet is seeded:

1. `snapshot.chainId() == params.chainId()` (existing)
2. `snapshot.totalSupply() >= 0` in the signed 64-bit range (existing overflow guard)
3. **if `params.genesisSupply() != GENESIS_SUPPLY_UNPINNED`**, `snapshot.totalSupply() ==
   params.genesisSupply()` exactly — mismatch throws, naming both totals unsigned-aware
   (`Long.toUnsignedString`) and the network name
4. construct genesis and seed the ledger, per-balance negative guard (existing)

This one check covers every boot path from a single home: fresh initialisation
(`GenesisBlock.initChain`) and an existing data directory's reboot (`GenesisBlock.matches`)
both call `build`, so the pin runs before the stored-genesis re-verification and the operator
sees the more actionable message first.

Mainnet pins `S₀` non-zero and ships a checked-in classpath resource
(`genesis/rhizome-mainnet.json`) as its default boot input when `RHIZOME_SNAPSHOT` is unset
(`SnapshotLoader.forBoot`); testnet and devnet leave the pin **unset** by explicit per-profile
choice, not by a network-id branch in the check itself — the equality check is gated solely by
whether a pin is present, so funded genesis snapshots booted under a borrowed testnet/devnet
profile in ~20 existing suites are unaffected.

## Invariants (must never regress)

- Validation is ordered cheapest-first, PoW last, uncle PoW **after** the block's own PoW.
- All public `ChainEngine` methods serialise on a single lock; reorg phases are individually atomic.
- Difficulty is always recomputed from timestamp history, never cached across `popBlock`.
- The Merkle tree preserves transaction insertion order — never sort transactions.
- Optional header fields (uncles, state root, vote, supply) are folded into the hash **only when
  present**, so old blocks hash unchanged.
- **Supply accounting.** Whenever a block commits a supply, it must equal exactly
  `parent.supply + Issuance.minted(...)`: the mining reward scheduled by the emission rule in
  force at that height plus, for each referenced uncle, its work-scaled uncle and nephew reward
  (the same `scaleRewardToWork` shift `Executor` applies). The parent supply is read from the
  parent **header** only, never from ledger state. `Issuance.minted` is the single formula —
  shared byte-for-byte by `ChainEngine.addBlock`, the stateless `HeaderChain.validate` gate, and
  `BlockAssembler` — so an honestly-assembled candidate always satisfies the check it is later
  re-validated against.
- **One emission decision per block.** Every reward a block mints — its coinbase and each uncle and
  nephew bonus — is dispatched against the *same* parent supply and the *same* activation verdict.
  A block never mixes a curve-derived coinbase with height-only uncle divisors, in either gate.
- **Absent supply is a refusal.** Under an active curve, a parent that commits no supply
  (`SUPPLY_ABSENT`) yields no reward at all: the block is rejected, never paid a fallback value.
- **Rewards reverse from what was validated, not from what is recomputed.** Rollback derives the
  uncle and nephew bases from the block's own validated coinbase amount, so apply and rollback stay
  exact inverses for a reward that is not a pure function of height.
- **Supply prefix closure.** A block whose parent commits a supply MUST also commit one (dropping
  the commitment mid-chain is invalid); a block whose parent does not commit supply MUST NOT
  start one (no mid-chain opt-in). A chain therefore commits supply at every height from genesis,
  or at none. This is also what makes reorg reversal of supply free: popping back through a
  supply-committing branch needs no rollback of its own, because the popped-to header's committed
  value already IS the supply at that height.
- **Codec round-trip fidelity** — every codec (binary and JSON) must decode a block to one whose
  hash equals the original. Load-bearing: `vote`, `stateRoot` and `supply` enter the preimage only
  when non-zero / non-empty / `>= 0` respectively, so a decoder that drops one produces a body
  whose recomputed hash no longer matches the mined header (rejected network-wide) and hashes
  differently across wire forms (a latent split). Pinned by a `decode(encode(b)).hash()`
  round-trip test.
- No consensus quantity is ever a floating-point comparison. The subsidy is an integer schedule —
  a geometric step function of height, or (C-10) an integer stepped table indexed by the committed
  supply, generated once and evaluated by `floorDiv` interpolation — and GHOST rewards scale by
  `>>>` shifts. The curve's generated output is pinned bit-for-bit by a checked-in vector file, so
  a re-implementation that disagrees fails a test rather than forking the chain.
- Difficulty, median-time, uncle work, vote tallies and the block subsidy read **only**
  `headerAt(h)`, never bodies (this is what makes pruning and snap-sync possible).
- **Pinned genesis supply.** A network profile that pins a genesis supply (`genesisSupply !=
  GENESIS_SUPPLY_UNPINNED`) MUST refuse to build or re-verify genesis against a snapshot whose
  total differs, on every boot path, before any balance is seeded. The check is gated solely by
  the presence of a pin, never by network id — an unpinned profile (testnet/devnet by explicit
  choice) behaves exactly as it did before this check existed.

## Key parameters

| Parameter | Mainnet value |
|---|---|
| `desiredBlockTimeSec` | 5 |
| `minBlockTimeSec` | 0 |
| `maxFutureBlockTimeSec` | 15 |
| `difficultyLookback` | 60 |
| `medianTimeWindow` | ~60 |
| `maxReorgDepth` | 120 |
| `maxUnclesPerBlock` / `uncleMaxDepth` | 2 / 7 |
| `maxBlockSizeBytes` | 4 MiB |
| `maxTxGas` / `maxBlockGas` | 50 000 000 / 250 000 000 |
| `uncleRewardNum/Den`, `nephewRewardDivisor` | 1/2, 32 |
| `votingEpochLength` | 1024 |
| `supplyTarget` (`S*`) | 2 997 924 580 000 base units (299 792 458 PDN) |
| `emissionCoefficient` (`c`) | 23 750 base units |
| `emissionTableSteps` (`N`) | 256 |
| `emissionCurveHeight` | **0 — never**, on every shipped profile |

The three curve constants are inherited unchanged by `testnet()`/`devnet()`; only the activation
height decides whether they mint, and it is `0` everywhere today.

## Open items

- Compact-block relay (BIP-152 style), header-first announce-then-pull, and persistent streaming
  connections — the three upgrades that would make a sub-5 s target safe (§6.3).
- Residual: the **51% attack** within the finality window is irreducible; the window bounds only
  its depth.
- **Curve activation is unscheduled.** C-10 ships fully specified, vector-pinned and adversarially
  proven, but `emissionCurveHeight = 0` on every profile, so no shipped network mints from it. A
  later feature sets the height once the genesis allocation (C-11) is ratified — `S₀` and `c` are
  two ends of the same calibration, and the coefficient is only meaningful against a final `S₀`.
  Two known follow-ons ride on that feature: the dashboard's reward display still reads the
  height-only overload (see [dashboard](../dashboard/spec.md) U-1), and
  `NetworkParameters.emissionCurveActiveForNextBlock` — kept for symmetry with its
  `boxActiveForNextBlock`/`tokenActiveForNextBlock` siblings — has no production caller until one
  exists.

## References

- `WHITEPAPER.md` §3 (consensus model), §4 (corrected C++ bugs), §7.1–7.2 (invariants,
  determinism), §9 (GHOST)
- Pandanite issues #2, #19/#22, #29, #37
