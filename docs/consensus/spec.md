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
  uncle/nephew bonus alike. That base is the *floored* one, so uncle and nephew rewards floor
  **through** it and never clamp independently: there is no second floor site to audit, the
  fractions and the per-bit work scaling are unchanged, and work scaling below the floored
  fraction stays intact — the guarantee `R_min` makes is about the miner's own block subsidy,
  not a promised uncle payout.

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

### C-10 — Supply-targeted logarithmic emission curve with a decaying target *(implemented, scheduled on mainnet and devnet)*

The block subsidy is a function of the chain's own header-committed circulating supply and of the
block's height: `R(S, h) = c · ln(S*(h)/S)`, evaluated entirely in integer arithmetic (a fixed-point
successive-squaring log, `BigInteger` at build time, `long` at evaluation time — never floating
point) so independent implementations cannot disagree and fork the chain. `S*(h)` is the supply
target — a pure function of height (below); its peak `S*_peak` (mainnet:
`2 997 924 580 000` base units) and `c` (mainnet: `23 750` base units, a calibration coefficient)
are pinned per network. The curve is a stepped table of `N = 256` uniform positions over
`(0, S*_peak]`, generated once per `NetworkParameters` construction from the published
`(S*_peak, c, N)` triple, evaluated in O(1) with linear interpolation; above the live target the
reward mirrors negative by ratio, an exact `0` only at `S = S*(h)`; consensus clamps the
mined reward to `max(R_min, raw(parentSupply, targetAt(h)))` at a single site — the miner revenue
floor (feature 05), `R_min = 800` base units on mainnet, the consensus-guaranteed minimum subsidy
at every curve-active height and every supply. The full generation/evaluation algorithm and its
calibration record (`τ ≈ 20 y`, launch reward ≈2.61 PDN/block, terminal supply, `R_min`, the two
reorg-continuity bounds below) are the normative WHITEPAPER §5.3; the checked-in
`lib-core/src/test/resources/emission/curve-vectors.json` pins the peak curve bit-for-bit and its
sibling `lib-core/src/test/resources/emission/target-decay-vectors.json` pins the decaying target
and the decayed evaluation, for independent implementers.

**The decaying target `S*(h)`.** Five per-network constants — never voted, never
environment-configurable — fix the schedule: the peak `S*_peak`, a decay-start height `H_d`
(`0` = never, the `powUpgradeHeight` polarity), an epoch length `E` in blocks, a per-epoch ratio
`num/den`, and a floor `S*_floor`. The target holds at the peak below `H_d`, decays
`×num/den` once per completed epoch (checked multiply-then-divide, floor at every step), and
clamps at the floor; the first decayed value appears at `H_d + E` (the boundary reading the spec
pins). `E_f` (epochs to floor) is computed by that same bounded iteration — never the continuous
closed form, which disagrees by one step at the shipped calibration — so evaluation costs at most
`E_f` iterations and `floorArrivalHeight = H_d + E_f × E` is exact. In practice it costs none: the
recurrence is tabulated once at construction (`T(0)…T(E_f)`, 4.4 KB at the mainnet calibration), so
`targetAt` is an array read, and only a calibration past 65 536 epochs iterates at all — resuming
from the last tabulated value rather than from the peak. The table is a different encoding of the
same pure function, not a cache of chain state: nothing invalidates it and nothing rolls it back.
The
target is total, non-increasing, strictly positive, identical on every node, and a pure function
of height: a reorg needs no target rollback code, and coinbase validation stays in the pre-PoW
structural pass with no ledger read. Mainnet's calibration: `H_d = 126 144 000` (20 years at 5 s,
one relaxation time), `E = 1 576 800` (one quarter), `799/800` = 0.4991 %/year,
`S*_floor = S*/2 = 1 498 962 290 000`; `E_f = 555` (the closed form's 554 is the documented
off-by-one), floor arrival `1 001 268 000`. For supply to actually track the target down, feature
08's burn must destroy ≈2 003 538 PDN/year — the target's fall **plus** the floor's own tail
issuance; that fee flow is not met at launch, so the obligation is published per block and
deliberately not enforced (and deliberately never accumulated: it would reach ~3.5× circulating
supply over 200 years and describe nothing).

**A misconfigured schedule refuses at boot, sentinel included.** Every degenerate constant is
refused when the schedule is built, so a node that cannot state its own monetary policy never
starts — and that extends to the *inert* constants: at the `H_d = 0` sentinel, `E`, `num`, `den`
and `S*_floor` must all be `0` too. They would be discarded there, but a profile stating a full
decay with an unstated start height has almost certainly meant to schedule one, and the polarity
makes that the easy mistake (`0` means *never* here, *from genesis* on `boxActivationHeight` and
its siblings). Silently discarding it would mint at the peak forever with no diagnostic. Testnet
and devnet therefore state their zeros explicitly rather than inheriting mainnet's schedule.

**The curve's evaluation domain is bounded and published.** Evaluating a live target below the
peak scales the argument to `⌊S × S*_peak / T⌋`, narrowed with a checked conversion, so `raw` is
defined exactly for `S ≤ Long.MAX_VALUE × T / S*_peak` and throws above it. Over every target a
schedule reaches that bound is `maxEvaluableSupply = ⌊Long.MAX_VALUE × S*_floor / S*_peak⌋` —
`Long.MAX_VALUE / 2` ≈ 4.6e18 at the shipped calibration, some 1.5 million times the peak target.
Construction refuses a calibration whose bound falls below the peak, so a curve that cannot be
evaluated at its own target is a boot-time refusal rather than a mid-chain throw. Every consensus
caller already treats the throw as a rejected block.

**Two separately named reorg-continuity bounds.** Two tips diverged within the finality window
differ in scheduled reward by at most the sum of **(1) the curve's interpolation term — ≤ 1 base
unit** (compounding floor rounding across two interpolations reaches exactly 1, never more), and
**(2) the per-epoch decay term — ≤ `D = 30` base units** (`⌈c·ln(den/num)⌉`), the headroom a
window spanning a decay-epoch boundary can contribute. Both are `≤`, not `<`.

**`S*(h)` is a target, not a cap.** Above the live target the raw value mirrors negative and
consensus floors to `R_min`, so supply does not stop at the target — under the floor it drifts
deliberately: from `S ≈ 0.9669 × S*(h)` onward the schedule is a flat `R_min`, supply crosses the
target, and keeps growing until feature 08's burn counterbalances it. An **uncle-free** block
mints exactly `R_min`
(≈ 0.168 % of `S*` per year at the shipped calibration) — the floor of the tail rate, not a cap:
uncle and nephew rewards derive from the same floored base (C-6), so a block carrying the maximum
`maxUnclesPerBlock = 2` uncles at zero difficulty deficit mints `2.0625 × R_min`, bounding the tail
at ≈ 0.347 % of `S*` per year. Under feature 03's zero clamp every one of those terms was zero
above `S*`; the floor is what makes uncle issuance persist there too. This tail emission is the
deliberate, rate-bounded trade the floor makes for permanent security funding (research.md
Decision 3/5); the uncle-free rate is pinned by the calibration test
(`emissionScheduleIsCalibratedForTheBlockCadence`).

`NetworkParameters.emissionCurveHeight` gates *which* rule governs a block: `0` (the polarity of
`powUpgradeHeight`, not `boxActivationHeight`) means **never** — the curve exists, is fully
specified and vector-pinned, but mints nothing on a profile that never schedules it. The shipped
schedule: **mainnet = 1** (genesis, height 1, pays no coinbase; height 2 — the first block that
pays any coinbase — is at/above activation, so it pays the calibrated curve value, not a geometric
leftover; mainnet is pre-launch, so nothing already-mined is reinterpreted), **devnet = 1**
(`scripts/local-testnet/start.sh` runs devnet, and a local network an operator watches must mint
under the same rule mainnet mints under), **testnet = 0,
never** (the test-shaped profile, not a deployed network with operators to notify; keeps the
geometric rule exercised and the existing suite's reward baseline deterministic). Below activation,
and permanently on testnet, the **legacy geometric rule** governs unchanged: the subsidy decays
geometrically (×2/3) once per `rewardEpochBlocks`, in integer arithmetic. Total issuance =
`epochBlocks × initialReward × 3 ≈ 100M PDN`. Both knobs are rescaled by the cadence ratio
(×18 = 90/5) so the **real-time schedule is preserved**: `rewardEpochBlocks ≈ 12 000 000`,
`initialReward = 2.7777 PDN`, epoch ≈ 1.9 yr, 48 000 PDN/day. Locked by a cadence-relative test
(`emissionScheduleIsCalibratedForTheBlockCadence`) that recomputes the geometric epoch span, the
curve's `τ_blocks` **and the decay schedule** (`decayStartHeight` = 20 years, `decayEpochBlocks` =
one quarter) from `desiredBlockTimeSec`, so changing block time forces all three to be revisited.

**Boot-time consistency.** A node's stored tip is checked against the schedule now in force every
time it boots: if the tip's committed supply no longer equals `parent.supply +
Issuance.minted(...)` under the current parameters — for example, a data directory minted before
activation, reopened after — the node refuses to start with a message naming the network, the
activation height, the expected and found supply, and the remedy (recreate the data directory),
rather than silently extending a chain no fresh peer can sync. Two header reads, no body, no
ledger; skipped, not failed, when the parent header is unavailable (snapshot bootstrap, pruned
prefix).

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
**inherit** `supplyTarget`, `emissionCoefficient` and `emissionTableSteps` — deliberately, unlike
the genesis-supply pin they explicitly reset (C-11): the calibration triple is shared regardless of
whether a given profile mints from it. `emissionCurveHeight` is a different story: `testnet()`
explicitly **resets** it to `0` (never), while `devnet()` explicitly **states** its own `1`
(active) — neither inherits `cleanMainnet()`'s value silently, since 006-emission-fork-activation
made the three profiles disagree on this one field. 008's decay constants get the same audit:
every shipped profile states its `decayStartHeight` explicitly — mainnet schedules the decay
(`126 144 000`), and **both `testnet()` and `devnet()` explicitly restate `0` (never)**: a devnet
lives for minutes, so a 20-year decay start would be theatre, and silent inheritance is the hazard
003, 005 and 006 each had to handle (WI-9). Every profile still *generates* and validates its
table and its schedule at construction, so a degenerate `(S*, c, N)` triple or a degenerate decay
configuration fails loudly at `NetworkParameters` build time rather than at the first curve-active
block.

*Reading the curve from outside:* the published monetary state, the schedule constants and the
sampled curve are served read-only by the node API — see [node-api](../node-api/spec.md) A-16
(`/info`/`/stats` fragment, `GET /emission`) and its dashboard rendering
([dashboard](../dashboard/spec.md) U-1). No consensus path reads any of it; this section is the
rule, A-16 is its reflection.

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
- **A stored chain must agree with the schedule now in force.** At boot, the tip's supply
  accounting identity is re-checked against the current parameters from **two headers only** (no
  body, no ledger, before the engine's lock is ever taken). A tip minted under a different emission
  schedule — or a supply-less chain opened under a scheduled curve, which can never begin
  committing — refuses to start with a message naming the network, the activation height, the
  expected and found supply, and the remedy. Absence of proof is not proof of fault: an
  unverifiable tip (parent header pruned or snapshot-bootstrapped) is **skipped, not failed**, and
  an identity that overflows refuses cleanly rather than surfacing a raw `ArithmeticException`.

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
| `minerRevenueFloor` (`R_min`) | 800 base units (0.08 PDN) — ≈ `R₀/32.6`, **must be > 0** |
| `emissionCurveHeight` | **1 (mainnet), 1 (devnet), 0 — never (testnet)** |

The three calibration constants (`supplyTarget`, `emissionCoefficient`, `emissionTableSteps`) are
inherited unchanged by `testnet()`/`devnet()`; `emissionCurveHeight` is not — each shipped profile
states its own value (see Profile inheritance above). `R_min` is likewise **not** inert on a
curve-active profile: a `toBuilder()` profile with a test-scale `supplyTarget` inherits the
mainnet-scale floor, and where `R_min` exceeds the curve's own first table entry the floor
swallows the whole schedule and every block pays a flat `R_min`. Curve-active profiles must set a
profile-scaled floor rather than assume the mainnet value stays out of the way.

## Open items

- Compact-block relay (BIP-152 style), header-first announce-then-pull, and persistent streaming
  connections — the three upgrades that would make a sub-5 s target safe (§6.3).
- Residual: the **51% attack** within the finality window is irreducible; the window bounds only
  its depth.
- **`emissionCurveActiveForNextBlock` still has no production caller.** Kept for symmetry with its
  `boxActiveForNextBlock`/`tokenActiveForNextBlock` siblings, which serve mempool admission — a use
  case a coinbase structurally does not have. `BlockAssembler` already judges the height it is
  building *for* through the ordinary two-arg `miningReward(height, parentSupply)` dispatch, which
  is mathematically the same predicate this method encodes; documented as a deliberate asymmetry,
  not an oversight (006-emission-fork-activation).

## References

- `WHITEPAPER.md` §3 (consensus model), §4 (corrected C++ bugs), §7.1–7.2 (invariants,
  determinism), §9 (GHOST)
- Pandanite issues #2, #19/#22, #29, #37
