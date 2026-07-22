# Rhizome — Blockchain Security & Exploit Audit (fifth pass)

**Date:** 2026-07-22 · **Scope:** whole tree · **Baseline:** post-`#8` (four
prior passes merged). Six subsystems (consensus/PoW, tx/ledger/mempool, WASM VM,
DeFi templates, P2P/net/API, crypto/codec/persistence) were re-audited in
parallel against the *current* source; every prior finding was re-verified and
new exploitable defects were hunted. **Two of the findings were introduced by the
fourth pass's own hardening** — a reminder that even minimal security fixes need
a determinism/DoS re-review.

## Fifth-pass findings & fixes

| # | Severity | Area | Title | Status |
|---|----------|------|-------|--------|
| Q1 | **High** | vm/consensus | `MODULE_PARSE` gas charged **only on a cache miss** → `gasUsed` (hence the fee and the state root) depends on node-local module-cache warmth → **consensus fork on any node restart / snapshot pivot / LRU churn** (regression from 4th-pass `vm F3`) | **Fixed** ✅ |
| Q2 | **High** | consensus/ledger | **Phantom 0-balance wallet**: `hasWallet` returns a key left behind by any apply-then-rollback, while the state root treats balance 0 as absent — so a `charged==0` (amount 0, fee 0) transfer is `SUCCESS` on a node that reverted the sender into existence and `SENDER_DOES_NOT_EXIST` on one that synced the winner directly → **permanent partition** | **Fixed** ✅ |
| Q3 | **High** | tx/mempool | **Nonce-gap parking**: no upper nonce-lookahead bound, no TTL, and a full pool blindly returns `QUEUE_FULL` → a one-time, near-free flood of individually-valid but never-minable gap transactions fills every pool permanently → **network-wide transaction censorship** | **Fixed** ✅ |
| Q4 | **High** | net/api | `/call_readonly` runs up to 50M interpreted VM instructions synchronously on the event-loop thread with **only a per-IP** rate limit → a few IPs pin the loop and starve ingestion/sync (the aggregate-vs-per-IP gap the 4th-pass `F1` closed for `/submit`) | **Fixed** ✅ |
| Q5 | Medium | consensus/net | **Uncle PoW verified before the block's own PoW**: `validateUncles` runs up to `maxUnclesPerBlock` memory-hard hashes at `addBlock` *before* the block's own `verifyNonce`, so a PoW-free `/submit` forces ~3× the hashing the `submitPowGate` budgets (corroborated independently by two auditors) | **Fixed** ✅ |
| Q6 | Medium | net/api | Explorer reads (`/stats`, `/blocks`) fully decode blocks **under the consensus lock** on the event loop yet were weighted cost 1 → one IP drives tens of thousands of lock-guarded decodes/s, contending block production/sync | **Fixed** ✅ (weighted) |
| Q7 | Info | code-org | Dead code removed: `ChainSync.java` (fully commented-out C++ stub), `PeerOLD`, `GossipSystemOLD`, `PeerManagerImplOLD` (unreferenced) | **Done** ✅ |

### Q1 — Cache-dependent module-parse gas forks consensus (High)

**File:** `lib-vm/vm/WasmVm.java` (`moduleFor`).

The 4th-pass `vm F3` fix priced the O(code) module reparse to stop CPU
amplification, but levied the charge **inside the cache-miss branch** — after the
`return cached`. `MODULE_CACHE` is a process-wide, non-persistent, LRU-bounded
(256) map whose occupancy differs across nodes (cleared on restart, empty after a
snapshot-sync pivot, evicted per local access order). Since `gasUsed` feeds
`gasFee = gasUsed × gasPrice`, hence the sender/miner balances and the
authenticated state root (`Executor.applyContract`), a validator with a cold
cache charges an extra `MODULE_PARSE_BASE + len·PER_BYTE`, computes a different
root, and **rejects the honest block a warm producer built** (`INVALID_STATE_ROOT`)
— a routine node restart forks the network. **Fix:** charge the parse cost on
*every* runtime call (hit and miss alike), before the cache lookup; the cache
stays a pure CPU optimization and the cost is a deterministic function of code
length. Regression: `WasmVmTest.moduleParseGasIsChargedIdenticallyOnWarmAndColdCache`.

### Q2 — Phantom 0-balance wallet forks consensus on a zero-cost tx (High)

**Files:** `lib-core/blockchain/Executor.java` (normal-transfer path, `applyContract`),
`InMemoryLedger.java` / `ChainEngine.collectStateChanges`.

Wallet *existence* (`hasWallet` = key present) is **not** a function of canonical
state: every apply-then-rollback (a failed pass-2, `popBlock` reorg,
`stampStateRoot` undo, `INVALID_STATE_ROOT` undo) leaves the credited-then-reverted
wallet in the map at balance 0, whereas `collectStateChanges` emits a `delete` for a
0 balance — so the ledger and the state root disagree, invisibly, until a
`charged==0` (amount 0, fee 0) transfer whose validity turns purely on `hasWallet`
is mined: `SUCCESS` on the phantom node, `SENDER_DOES_NOT_EXIST` on a node that
synced the winner directly → the same canonical block valid on one honest node and
invalid on another → **permanent partition**, double-spend across it. **Fix:** make
block validity a pure function of **balance** — an absent wallet reads as balance 0
(`Executor` normal-transfer and `applyContract`); `charged>0` still requires a real,
funded wallet, so no withdraw ever touches a non-existent one. Regression:
`ExecutorTest.zeroValueTransferIsValidRegardlessOfWhetherTheSenderWalletExists`
(the `unknownSenderRejected` status moved `SENDER_DOES_NOT_EXIST`→`BALANCE_TOO_LOW`,
same accept/reject decision).

### Q3 — Mempool nonce-gap parking → permanent censorship (High)

**File:** `lib-core/mempool/MemPool.java`.

Admission bounded a tx's nonce only from *below*; block selection emits only the
*contiguous* run from the confirmed nonce; `pruneStale` evicts only *below*-confirmed
nonces; and a full pool returns `QUEUE_FULL` with no eviction and no TTL. So a pool
can be filled once, cheaply and permanently, with individually-valid but never-minable
gap transactions (skip the confirmed nonce, queue the rest) — after which every honest
submission is rejected, network-wide, with no self-heal. The cumulative-balance defense
is bypassed (parked txs carry `amount 0, fee 0` and are never mined, so never spend).
**Fix:** when the pool is full, reclaim a slot held by a **fully-parked** sender (its
confirmed nonce absent, so none of its txs is minable) for a **ready or higher-fee**
newcomer — honest traffic always displaces parked dead weight, while a live sender is
never evicted (legitimate saturation still sheds). Regressions:
`MemPoolTest.readyTransactionDisplacesParkedDeadWeightWhenPoolIsFull`,
`parkedNewcomerCannotChurnAFullParkedPool`.

### Q4 — `/call_readonly` aggregate compute DoS (High)

**Files:** `app-node/node/NodeApi.java` (`callReadonly`), `NodeService.java`.

A dry-run runs the VM interpreter for up to `MAX_READONLY_GAS` (50M) instructions
synchronously on the sole event-loop thread; the only limiter was per-IP, so a few
IPs each within budget could pin the loop with back-to-back gas-sink runs and starve
`/submit` ingestion and sync — structurally identical to the `/submit` DoS the 4th
pass fixed with the process-wide `submitPowGate`, which had no `/call_readonly`
counterpart. **Fix:** a process-wide `readonlyGasGate` bounds aggregate dry-run
gas/second across all IPs; an over-budget call is shed (HTTP 429) charging the clamped
gasLimit up-front, **before** the VM runs. Sized to admit many cheap dashboard queries
while throttling repeated max-gas sinks. Regression:
`NodeApiTest.readonlyGasGateShedsCallsOnceTheGlobalBudgetIsSpent`.

### Q5 — Uncle PoW verified before the block's own PoW (Medium)

**File:** `lib-core/blockchain/ChainEngine.java` (`addBlock`).

`validateUncles` (which runs a memory-hard `verifyNonce` per referenced orphan, up to
`maxUnclesPerBlock`) ran *before* the block's own `verifyNonce`, so a PoW-free `/submit`
citing two pooled orphans forced ~3 memory-hard hashes where the `submitPowGate` budgets
one — pinning the event-loop thread under the consensus lock. **Fix:** move
`validateUncles` to run **after** the block's own PoW check, so uncle hashing happens
only for a block that already proved its own work (no longer a cheap amplifier).
Regression: `BlockUnclesTest.blocksOwnPowIsVerifiedBeforeUncleWork`.

### Q6 — Explorer reads under-weighted under the consensus lock (Medium)

**File:** `app-node/node/NodeApi.java` (`requestCost`).

`/stats` (reads `STATS_WINDOW`=32 blocks) and `/blocks` (up to 50 full blocks) fully
decode blocks from RocksDB **while holding the consensus lock** (`ChainEngine.blockAt`),
yet were weighted cost 1 — so one IP could drive tens of thousands of lock-guarded
decodes/s, contending block production and sync. **Fix:** weight `/blocks` by its block
span (like `/sync`) and `/stats` by `STATS_WINDOW`. *Documented, not changed:* serving
these reads from a lock-free snapshot view (so explorer traffic never takes the consensus
lock) is the deeper fix, left as a dedicated change to avoid consensus-path risk.

### Documented (verified, not changed this pass)

- **Reorg-gate work metric mismatch (Low).** `HeaderSynchronizer.syncFrom` early-outs on
  uncle-*inclusive* `totalWork` while the adoption gate compares base-only work; with
  genuine (costly) uncle work this could in principle stabilize a split. A refinement of
  the accepted M4 base-only tradeoff; make both gates use one metric if hardened.
- **Snapshot bootstrap buffering (Low).** `SnapshotBootstrap` accumulates every header to
  the untrusted peer height and all chunks before validation; bounded only by the
  operator-chosen seed trust boundary (the live `HeaderSynchronizer` caps at
  `MAX_HEADER_WINDOW`). Cap the bootstrap span and stream chunks.
- **Dead experimental P2P package (Info).** `lib-net/.../p2p/*` (`PeerSystem`,
  `GossipSystem`, `DiscoveryService`, `FloodDiscovery`, …) is unused by the live node
  (which uses `rhizome.node`); recommend removing it (and `FloodDiscoveryTest`) so future
  audits don't treat it as reachable surface. Left in place this pass (has a test) to keep
  the change minimal.
- **Codec parity (Info).** `BlockCodec.decode` doesn't bound uncle difficulty (unreachable
  today — forced equal to a registered orphan's bounded difficulty before use);
  `TransactionDto` single-object decode accepts trailing bytes (nil impact — identity is
  content-hash, not raw bytes). Add for defense-in-depth/wire uniqueness.

---

# Rhizome — Blockchain Security & Exploit Audit (fourth pass)

**Date:** 2026-07-21 · **Scope:** whole tree · **Baseline:** post-`#7` (three
prior passes merged). Six subsystems (consensus/PoW, tx/ledger/mempool, WASM VM,
DeFi templates, P2P/net/API, crypto/codec/persistence) were re-audited in
parallel; every prior finding was re-verified against the *current* source
(several the doc marked "documented/open" turned out already fixed; a few marked
"fixed" were only partially closed), and new exploitable defects were hunted.

## Fourth-pass findings & fixes

| # | Severity | Area | Title | Status |
|---|----------|------|-------|--------|
| P1 | **High** | tx/mempool | Box/token op with an unsatisfiable precondition aborts the whole block → free, permanent, network-wide **block-production halt** (mempool poisoning) | **Fixed** ✅ |
| P2 | **High** | vm | Uncapped **aggregate** table allocation (per-table cap only) → tens-of-GB eager alloc → heap-dependent fork + crash DoS (residual H4) | **Fixed** ✅ |
| P3 | Medium | consensus | M4 reorg work-gate fix landed on the fallback synchronizer only; `HeaderSynchronizer` (the live path) still counted **unverified** uncle work → ~⅓-work forced deep pop/restore | **Fixed** ✅ |
| P4 | Medium | consensus | `restore()` swallowed `addBlock` failures → silent self-truncation after a forced failed reorg | **Fixed** ✅ (fail-loud) |
| P5 | Low | net/crypto | Dashboard-served `token.wasm` was the **pre-T2** (vulnerable) build; only the test copy had been recompiled | **Fixed** ✅ |
| P6 | Low | crypto | `SparseMerkleTree.verify` still threw on a wrong-length sibling/key/valueHash → light-client crash (residual L3) | **Fixed** ✅ |
| P7 | Low | codec | Single-object `BlockCodec`/`HeaderCodec` `decode` accepted trailing bytes (latent malleability, L2) | **Fixed** ✅ |
| P8 | Low | codec | Codec uncle bound (128) was 64× the consensus max (2) → decode-accepted header/block bloat (L7) | **Fixed** ✅ (→16) |
| P9 | Low | contracts | `pair`/`amm` swap updated reserves with unchecked `+` → silent `u64` wrap corrupts the pool at extremes (residual T4) | **Fixed** ✅ (recompiled) |

**Also fixed (net/vm hardening batch):** net F2 CSRF/DNS-rebinding — the
`Origin==Host` guard was defeated by rebinding (which makes Origin==Host); a
state-changing browser POST now must also carry a non-simple `X-Rhizome-Request`
header, which a cross-site/rebinding page cannot set without a CORS preflight the
node never grants, so the browser blocks it (peers/CLI send no Origin and are
unaffected; the dashboard sends the header). net F3 `/headers` now streams its
window header-by-header instead of buffering it (mirrors the `/sync` M5 fix). vm
F3 a CALL that misses the module cache is now charged for the O(code) reparse +
scan (`MODULE_PARSE_BASE/_PER_BYTE`), so cycling distinct max-size contracts can't
force that work unpriced.

**net F1 — `/submit` memory-hard-hash DoS: verified GENUINELY exploitable, FIXED.**
A deeper analysis confirmed the H3 mitigation only removed the *double* hash, not
the DoS primitive: a single source IP resending one **PoW-free** crafted block
(valid public parent hash, in-window id, garbage nonce) forces one memory-hard
Pufferfish2 hash per submit at both `ChainEngine.addBlock` (verifyNonce last check)
and `registerOrphan`, **synchronously on the single ActiveJ event-loop thread under
the global consensus lock**. Measured ~25 ms/hash (~40 hashes/s/thread); the per-IP
rate budget is ~125 submits/s and there was **no global cap**, so one IP pins the
node's only I/O thread (and starves block production + sync on the shared lock).
*Fix:* a process-wide single-bucket limiter (`NodeService.submitPowGate`,
`SUBMIT_POW_MAX_PER_SEC`) bounds the **aggregate** submit-triggered PoW
verifications/s across every IP, below loop capacity; over budget the block is shed
(`SUBMIT_THROTTLED` → HTTP 429) *without* hashing. Safe because both call sites
already drop non-verifying blocks (orphan admission is best-effort) and honest
blocks still arrive via sync, which calls the engine directly and is not gated. No
locking/threading change. Regression:
`NodeApiTest.submitPowGateShedsBlocksOnceTheGlobalBudgetIsSpent`.

**net F4 — SSRF posture keyed off `selfUrl`: verified real, FIXED.**
`blockPrivatePeers` was derived from whether the *advertised* `selfUrl` was loopback
(`RhizomeNode.java`), but the node always binds `0.0.0.0` and `/add_peer` is
unauthenticated. So an exposed **testnet/custom-net** node left at the default
(loopback) advertise URL ran with the SSRF host filter + DNS-pin rejection **off** —
any network-reachable party (not just a browser) could add a `169.254.169.254`/RFC1918
peer that `syncRound` then GETs. (Mainnet was safe by default; the SSRF is *blind*
with fixed paths, so moderate, not credential-grade.) *Fix:* the filter is now
**secure-by-default** — `blockPrivatePeers = !(config.allowPrivatePeers() ||
RHIZOME_ALLOW_PRIVATE_PEERS)`, no longer keyed off `selfUrl`. Local dev/devnets opt in
via `NodeConfig.withAllowPrivatePeers(true)` or the env var; configured seed peers
bypass the filter regardless, so seeded deployments are unaffected. Regression:
`PeerDiscoveryTest.loopbackPeersAreNotDiscoveredByDefault` (default-off blocks loopback
PEX) alongside the existing opt-in mesh test.

**crypto F4 — SMT `load` does not re-hash the node blob: verified, LEAVE DOCUMENTED.**
Not remotely triggerable — the node store is content-addressed and written only by
the node from validated state; snap-sync rebuilds locally and gates on the
PoW-committed root, so no untrusted `(hash, blob)` reaches `load`. On genuine local
corruption the impact is availability-only (wrong root → the node diverges/halts, or
a proof that fails the light client's stateless `verify` — never a forged-yet-accepted
proof). The one-line `sha256(node)==hash` guard is safe but sits on the per-key/
per-block hot path; add it only in a dedicated corruption-hardening pass (or gate it
to the `prove`-serving path) rather than paying it unconditionally on block apply.

### Contract-template redesigns (T1, T3, T4) — now fixed

A follow-up pass hardened the bundled DeFi templates and added the minimal host
ABI they needed:

- **T1 — front-runnable `init` (High):** the host now records the deployer at
  deploy under a reserved (zero-length, contract-unwritable) storage key and
  exposes it via a new `get_deployer` host function; it rides the existing
  contract-storage commit path, so the state root, snapshot and reorg journal
  cover it with no new consensus state. `token`/`agent_wallet`/`pair`/`launchpad`/
  `amm` gate `init` to the deployer, so a mempool observer can no longer seize a
  token's supply or an agent wallet's ownership.
- **T3 — no swap slippage (Medium):** `amm`/`pair` swaps take a `min_out` floor
  (`[amount_in(8) || min_out(8)]`) and revert below it; `0`/absent preserves the
  old ABI. Defeats sandwich front-running.
- **T4 — locked funds (High):** `pair.add_liquidity` now mints LP shares
  (geometric mean on the first deposit, else the min across both legs) and a new
  `remove_liquidity` selector redeems them; a new `transfer_value` host function
  (a contract paying native coin from its own balance, bounded by its committed
  balance, applied by the executor and reversed on reorg) lets `launchpad` expose
  an owner-gated `withdraw` for the sale proceeds. Both were previously
  unrecoverable.

Regressions: deployer-bound-init (front-run is a no-op), swap-below-`min_out`
reverts, LP mint+redeem round-trips, and launchpad withdraw + its reorg reversal.

### P1 — Mempool-poisoning block-production halt (High, liveness)

**Files:** `blockchain/Executor.java` (`applyBox`/`applyToken`, `executeBlock`),
`token/DefaultTokenProcessor.java`, `box/DefaultBoxProcessor.java`.

A box/token transaction whose *precondition* fails (transfer a token you hold
none of, update/spend a box you do not own, collect a not-yet-expired box, a
malformed payload) returned a non-SUCCESS status that made `executeBlock`
`abort` — **rejecting the entire block**. The mempool holds no box/token state,
so it admits such a tx, gossips it, and selects it into *every* candidate block;
every producer then fails to build a block and the tx — never applied — is never
evicted. One signed, zero-fee, essentially free transaction **halts block
production network-wide, permanently.** (The same class was already fixed for the
fresh-keypair no-op via `senderExists`; box/token preconditions were the
unguarded remainder.)

**Fix:** box/token precondition failures now **soft-revert**, exactly like a
contract revert (Ethereum-style): the block stays valid, the fee is still charged
and the nonce consumed, only the state change is skipped — so the poisonous tx is
*minable*, its nonce advances, and it clears the pool instead of jamming it. Three
properties make this consensus-safe: (a) the token processor's transfer path was
reordered to compute the recipient credit (with its overflow check) *before*
staging the debit, so `run()` never partially stages on failure; (b) the box
processor now emits a zero-delta receipt for *every* box tx so `rollbackBlock`'s
per-box-tx receipt walk stays aligned across a reorg (the C2 aliasing class);
(c) the residual affordability failures stay hard errors — the mempool's
cumulative-balance selection makes them unreachable in an honestly-produced
block, so they only signal a malicious block, which is correctly rejected.
Regressions: `BoxOpTest.softRevertedBoxOpKeepsRollbackReceiptsAligned`, updated
soft-revert assertions across `BoxOpTest`/`TokenOpTest`.

### P2 — Uncapped aggregate WASM table allocation (High, fork + DoS; residual H4)

**File:** `vm/WasmVm.java` (`rejectOversizedAllocations`).

The H4 fix capped each table's `min` at 65 536 but bounded neither the *number*
of tables nor their *aggregate* size. Chicory 1.7.5 validates no table count and
allocates each table's backing arrays eagerly at instantiation, before the gas
listener runs — so a ≤256 KiB module declaring ~50 000 tables of 65 536 entries
forces tens of GB of unmetered allocation on every `CALL`: an OOM crash on
small-heap nodes and a **consensus fork** against large-heap nodes (the exact
heap-dependent class H2/H4 target).

**Fix:** cap the table count (`MAX_TABLES=16`) and the summed initial entries
across all tables (`MAX_TOTAL_TABLE_ENTRIES=65 536`), rejected at deploy — the
eager table allocation is now a small fixed network constant, heap-independent,
like `TREE_MAX_PAGES` for linear memory. Regression:
`WasmVmTest.rejectsModuleWhoseTablesAggregateOverTheCap`.

### P3 / P4 — Header-sync reorg gate & restore (Medium)

**Files:** `blockchain/HeaderChain.java`, `blockchain/HeaderSynchronizer.java`,
`blockchain/ChainSynchronizer.java`.

The M4 anti-DoS fix (compare *PoW-verified base* work only at the pre-pop gate)
had been applied to `ChainSynchronizer`, used only as a `/headers`-less fallback.
The live path, `HeaderSynchronizer`, still summed **committed-but-unverified**
uncle work on both sides of the gate: an attacker pads each header with
`maxUnclesPerBlock` same-difficulty fake uncles, inflating a cheap branch's
claimed work ~3× and passing the gate with ~⅓ honest work, forcing a deep
pop/restore that only fails later at body-level uncle validation. **Fix:**
`HeaderChain.validate` still checks uncles structurally but returns **base-only**
work; `HeaderSynchronizer.localWorkAboveFork` drops its uncle sum — both gates now
compare like PoW-verified work with like, while genuine uncle work still decides
true fork choice via `totalWork` once bodies validate. Regression:
`HeaderChainTest.committedUncleWorkDoesNotInflateTheReorgGateWork`.

P4: both `restore()` routines re-applied the saved local branch with
`addBlock(...)` while discarding the result. Re-adding a just-canonical block can
fail if an attacker floods the bounded orphan pool to evict an uncle it
references, silently truncating the node's own chain. **Fix:** `restore` now
throws on a non-SUCCESS re-add (fail-loud → resync), instead of continuing in a
silently-shorter state.

---

*The third-pass report follows.*

# Rhizome — Blockchain Security & Exploit Audit (third pass)

**Date:** 2026-07-21 · **Scope:** whole tree (`lib-core`, `lib-vm`, `lib-crypto`,
`lib-persistence`, `lib-net`, `app-node`, `app-wallet`, contract templates) ·
**Baseline:** post-`#6` (two prior audit passes already merged).

This pass hunted for **residual** exploitable defects that survived the earlier
reviews, and for **hardness** gaps — resistance to resource-exhaustion DoS and
non-determinism that could fork the chain. Six subsystems were audited in
parallel (consensus/PoW, transaction/ledger/mempool, WASM VM, DeFi contract
templates, P2P/sync/API, crypto/serialization/persistence) and every reported
finding was re-verified against the source.

The two most serious findings are **genuine, newly-identified consensus bugs**
(fund theft and ledger corruption), both now fixed with regression tests. All
382 tests pass.

---

## 1. Findings overview

| # | Severity | Area | Title | Status |
|---|----------|------|-------|--------|
| C1 | **Critical** | tx/box | `BOX_COLLECT` `fee` drains an arbitrary wallet with no signature | **Fixed** ✅ |
| C2 | **Critical** | consensus | Uncle-reward rollback un-scaled → ledger corruption / fork on reorg | **Fixed** ✅ |
| H1 | High | net/crypto | Unbounded `pufferfishCache` → remote OOM | **Fixed** ✅ |
| H2 | High | vm | No tree-wide linear-memory cap → heap-dependent OOM forks consensus | **Fixed** ✅ |
| H3 | High | net | `/submit` forces a memory-hard hash for every rejected block | Documented |
| H4 | High | vm | Uncapped table / locals allocation at instantiation → OOM/fork | Documented |
| M1 | Medium | net | Snapshot `chunkCount` unbounded pre-size → boot OOM | **Fixed** ✅ |
| M2 | Medium | vm | `storage_read` undercharges (full value materialised, copied bytes billed) | **Fixed** ✅ |
| M3 | Medium | vm | `get_input` undercharge (full clone for 0 gas) | Documented |
| M4 | Medium | consensus | Stateless reorg gate counts *unverified* uncle work | Documented |
| M5 | Medium | net | `/sync` server buffers the whole block window in memory | Documented |
| M6 | Medium | net | O(height) `findCommonAncestor` fallback (attacker-forced) | Documented |
| L1–L8 | Low/Info | various | Canonical-encoding, DNS-rebinding, SMT proof DoS, merkle dup, … | Documented |
| T1–T4 | High→Low | contracts | Template footguns (front-runnable init, silent transfer, no slippage/LP) | Documented |

"Documented" = verified real, with a concrete fix recommendation, but not
changed in this pass (out of the minimal-blast-radius set, or template-level).

---

## 2. Fixed findings

### C1 — `BOX_COLLECT` fee theft: unsigned drain of any wallet (Critical)

**Files:** `transaction/TransactionImpl.java:92` (`signatureValid()`),
`blockchain/ChainEngine.java:983` (`isSelfAuthorized`),
`blockchain/Executor.java` pass-1 + `applyBox`.

`BOX_COLLECT` (permissionless rent collection) is **self-authorized**: its
signature is never verified (`signatureValid()` returns `true` unconditionally)
and it consumes no account nonce (`isSelfAuthorized` skips the nonce rule). The
design assumes such a transaction carries an *empty* `from` and *zero* fee (as
`BlockAssembler` builds it), but the validator never enforced that. The only gate
on `from` is `PublicAddress.of(signingKey).equals(from)` — satisfiable with the
victim's **public** key (revealed the first time they ever transact).

**Exploit:** a block producer inserts a `BOX_COLLECT` with `from = victim`,
`signingKey = victim's public key`, `fee = victim's whole balance`, no signature.
Every check passes (signature bypassed, nonce skipped, `from`-binding satisfied),
then `applyBox` withdraws the `fee` from the victim and deposits it to the miner's
coinbase. Up to `maxBoxCollectsPerBlock` victims per block. This breaks the
chain's fundamental property: *no value moves without the owner's private key.*

**Fix:** in `Executor` pass-1, a `BOX_COLLECT` must carry `from == empty`,
`fee == 0`, `amount == 0` (exactly the honest `BlockAssembler` shape) — else
`BOX_PAYLOAD_INVALID`. A self-authorized tx can therefore never name a funded
sender. The mempool already refuses all `BOX_COLLECT` (minted in blocks only),
so no propagation path exists. Regression:
`BoxConsensusTest.maliciousBoxCollectCannotDrainAnArbitraryWallet`.

### C2 — Uncle-reward rollback used un-scaled amounts (Critical)

**Files:** `blockchain/Executor.java` — `payUncleRewards` (apply) vs
`rollbackBlock` (revert).

The C1-residual fix (prior pass) made the apply path scale each GHOST
uncle/nephew reward to the uncle's proven work: `base >>> (nephewDifficulty −
ref.difficulty())` via `scaleRewardToWork`. But `rollbackBlock` still reverted
the **flat, unscaled** `params.uncleReward/nephewReward` (its comment literally
read "same flat amounts"). For any sub-difficulty uncle (deficit > 0 — the normal
case just after a difficulty increase, when orphans mined at the old lower
difficulty are still valid uncles), apply credited `base >>> deficit` but the
revert subtracted the full `base`.

**Impact (three live paths — reorg via `popBlock`, `stampStateRoot` during
production, `INVALID_STATE_ROOT` undo):** the over-subtraction either throws
`LedgerException` mid-revert (partially-reverted, **corrupted ledger**; reorg
aborts) or, when the miner has other funds, **silently destroys coins**, so a
node that reorged across the block diverges from one that synced the winning
chain directly → **permanent consensus fork**. Invisible to the existing tests,
which only mine same-difficulty (deficit 0) uncles.

**Fix:** `rollbackBlock` now recomputes the identical per-uncle deficit and
reverts `scaleRewardToWork(base, deficit)`, with the same `> 0` guards — an exact
inverse of the apply path. Regression:
`BlockUnclesTest.subDifficultyUncleRewardIsScaledAndExactlyReversedOnPop`
(genuinely exercises deficit > 0; fails with the old code via `LedgerException`).

### H1 — Unbounded `pufferfishCache` → remote OOM (High)

**File:** `common/Crypto.java`.

Every PoW verification (`addBlock`, `registerOrphan`, header sync, fork-choice
branch validation) memoised its Pufferfish2 result into an unbounded
`ConcurrentHashMap` keyed by `SHA-256(target ‖ nonce)` — both fully
attacker-controlled. No eviction, no cap. Feeding `/submit` a stream of blocks
with fresh nonces grows the map ~1000 entries/s permanently → OOM. (The miner
uses `useCache=false`, so the cache only ever helped repeat verification.)

**Fix:** replaced with a bounded, access-ordered LRU (`LinkedHashMap` +
`removeEldestEntry`, cap 4096), synchronised — the same bounded-collection
discipline already applied to `OrphanPool`/`MemPool`/`RateLimiter`.

### H2 — No tree-wide WASM linear-memory cap → heap-dependent fork (High, hardness)

**File:** `vm/WasmVm.java`.

`MAX_CONTRACT_PAGES` (64 MiB) bounded a *single* Instance, but a chain of
`MAX_CALL_DEPTH = 8` distinct contracts holds 8 Instances alive simultaneously
(each parent suspended inside `call_contract` while the child runs) → up to
8 × 64 MiB = **512 MiB** of concurrently-allocated linear memory, priced at only
~0.002 gas/byte. Whether that allocation succeeds or throws `OutOfMemoryError`
depends on each node's `-Xmx`: a large-heap node returns `OK`, a small-heap node
reverts with out-of-gas → divergent result and state root → **chain split**, plus
a crash DoS on constrained validators. The `catch (OutOfMemoryError)` normalising
to out-of-gas does **not** make this deterministic.

**Fix:** a tree-wide page budget (`TREE_MAX_PAGES`, a thread-local running total),
reserved on initial-memory allocation and on `memory.grow`, enforced *before*
Chicory allocates and released per frame on unwind. The ceiling is now a fixed
numeric network constant — identical on every node regardless of heap — exactly
as the call-depth cap is tree-wide. (The call tree runs on one `onBoundedStack`
thread, so a `ThreadLocal` sums every live Instance's memory and stays balanced.)

### M1 — Snapshot `chunkCount` unbounded pre-size → boot OOM (Medium)

**File:** `app-node/node/SnapshotBootstrap.java`.

`new ArrayList<>(info.chunkCount())` pre-sized from an untrusted peer's
`/info` JSON; `"chunks": 2147483647` allocates a multi-GiB array and OOMs a
snap-syncing node before a single chunk is fetched or the root verified — the
exact bug class the block/tx codecs already guard against.

**Fix:** reject `chunkCount` outside `[0, MAX_SNAPSHOT_CHUNKS]` before pre-sizing.

### M2 — `storage_read` undercharges (Medium, hardness)

**File:** `vm/WasmVm.java`.

`storage_read` billed only the *copied* bytes, so `out_cap = 0` forced a full
O(valueLen) load-and-clone of a large value for the flat `STORAGE_READ_BASE` —
the identical undercharge `box_read` was previously fixed for.

**Fix:** charge the full `value.length` before copying, mirroring `box_read`.

---

## 3. Documented findings (verified, recommended)

**H3 — `/submit` amplifies a memory-hard hash.** A block with `id != height+1`
fails `addBlock` at the cheapest check but is then handed to `registerOrphan`,
which runs a full Pufferfish2 hash (memory-hard) on the event-loop thread for a
~0-cost attacker input. *Fix:* don't attempt orphan registration for blocks that
failed cheap structural checks; don't re-verify PoW already verified in
`addBlock`; weight `/submit` in the rate limiter.

**H4 — Uncapped table / locals allocation.** `validateCode` caps code size and
rejects float/SIMD, but nothing bounds a module's declared **table** `initial`
size or a function's **locals** count — both a few RLE bytes that force a
multi-GiB eager allocation at instantiation, *before* the gas listener runs →
same heap-dependent OOM/fork as H2. *Fix:* reject oversized table/element/locals
declarations in `validateCode` (verify exact Chicory 1.7.5 allocation semantics
first).

**M3 — `get_input` undercharge.** The context getters charge only copied bytes
via `copyOut`; `host.input()` clones the whole buffer every call, so
`get_input(ptr, 0)` clones a 64 KiB input for ~0 gas in a loop. *Fix:* add a base
cost and charge the true source length.

**M4 — Stateless reorg gate counts unverified uncle work.** `ChainSynchronizer`'s
pre-pop work gate sums *claimed* uncle work without confirming the uncles are
pooled/eligible, so an attacker with ~1/3 of honest work can inflate claimed work
~3× and force an expensive pop/restore cycle. *Fix:* only count confirmable uncle
work in the pre-pop gate, or exclude uncle work there.

**M5 — `/sync` server buffers the whole window.** The server materialises up to
200 × 4 MiB (~800 MiB, doubled by `toByteArray()`) on the event loop; a few
concurrent full-window requests OOM the node. *Fix:* stream the encoded blocks,
mirroring the client-side fix.

**M6 — O(height) ancestor search fallback.** A peer that 404s `/headers` forces
`ChainSynchronizer.findCommonAncestor` into one HTTP round-trip per height. *Fix:*
give it the header path's exponential-probe + binary-search locator, or cap the
descent at `maxReorgDepth`.

**Low / Informational.** L1 non-canonical `isTransactionFee` byte accepted on
decode (latent malleability). L2 single-object decoders accept trailing bytes.
L3 `SparseMerkleTree.verify()` throws instead of returning `false` on an
over-long proof (light-client DoS). L4 SMT nodes trusted on read-back without
length/hash validation. L5 `MerkleTree` odd-level duplication (CVE-2012-2459
shape; mitigated by in-block txid dedup — that dedup is load-bearing). L6 CSRF
`Origin==Host` check does not stop DNS-rebinding to P2P endpoints. L7 codec uncle
bound (128) looser than the consensus bound (2). L8 contract address is 25-byte
SHA-256 truncation with no version/domain tag (collision infeasible).

---

## 4. Contract-template findings (`lib-vm/contracts`)

These are bundled *templates*, not protocol code; the host sandbox is sound
(reentrancy guard, frame atomicity, unspoofable `get_caller/self/value`,
integer-only determinism). The templates themselves carry footguns:

- **T1 (High) — front-runnable `init`.** Every template's `init` is "first caller
  wins" with no binding to the deployer (deploy stores code but runs no
  constructor). A mempool observer can `init` a freshly-deployed `token` to
  itself and seize the entire supply, or become the owner of an `agent_wallet`.
  *Fix:* bind `init` to the recorded deployer, or fold init into deploy.
- **T2 (Medium) — silent transfer failure.** `token.transfer` (selector 1)
  discards `do_transfer`'s bool and returns length 0 on an insufficient balance
  (no trap), yet callers (`pair.pay`, `agent_wallet.pay`) treat only `< 0` as
  failure → they believe a payout succeeded when zero moved. *Fix:* make
  `transfer` trap on a short balance.
- **T3 (Medium) — no slippage/deadline** on any swap (`amm`/`pair`/`router`):
  classic sandwich exposure. *Fix:* add `min_out`.
- **T4 (Medium) — `pair.add_liquidity` mints no LP shares** and has no
  withdraw → liquidity is unrecoverable. Plus minor unchecked `u128` numerator /
  `u64` reserve overflows at extreme values (`amm`/`pair`). *Fix:* real LP-share
  accounting; `checked_*` arithmetic throughout.

---

## 5. Hardness analysis (determinism & resource exhaustion)

The user specifically asked for a hardness assessment. Summary:

**Determinism (fork resistance) — strong at the logic level, one heap-dependent
gap now closed.** Float/SIMD (incl. vector-float lanes) are rejected at deploy;
the state root is order-independent (SMT keyed by domain+key, commutes over
`HashMap` iteration order); gas arithmetic is integer/saturating; the WASM
call-depth cap is a tree-wide network constant, not `-Xss`-dependent; OOM/stack
overflow are normalised to a deterministic full-gas out-of-gas. The residual fork
risk was concentrated in **heap-dependent OOM from uncapped allocations**:
tree-wide linear memory (**H2, fixed**) and table/locals declarations (**H4,
recommended**). Closing H2 removes the largest such surface; H4 should follow.

**CPU exhaustion.** PoW verification is genuinely enforced (fails closed on
`challengeSize <= 0`), and the signature batch is correctly gated behind PoW. The
remaining CPU amplifiers are the memory-hard-hash-on-rejection (**H3**) and the
per-instruction gas meter's undercharges (**M2 fixed, M3 recommended**). The
merkle/nonce passes running *before* PoW in `addBlock` (L-level) contradict the
"PoW last" invariant and stack with H3 — recommend verifying the header PoW
first.

**Memory exhaustion.** The bounded-collection discipline (OrphanPool, MemPool,
RateLimiter, PeerBanList, module cache) is good, but three unbounded surfaces
were found: the Pufferfish cache (**H1, fixed**), snapshot `chunkCount` (**M1,
fixed**), and the `/sync` server buffer (**M5, recommended**). The VM memory cap
(**H2, fixed**) is both a determinism and a memory-DoS fix.

**Stack/disk.** The fixed 64 MiB exec stack + deterministic depth trap make stack
overflow unreachable before the trap. Storage growth is gas-priced
(`STORAGE_WRITE_BASE` + per byte) and reorg-reversible via the persistent undo
journal.

---

## 6. Confirmed strong defenses (do not regress)

Signature/txid malleability (identity = signature-free `hashContents`);
`signingKey → from` binding at both mempool and consensus; `chainId` + account
nonce in the signed preimage (cross-network + replay protection); negative
amount/fee rejection and `Math.addExact`/`multiplyExact` overflow guards
throughout the ledger; integer-only deterministic reward with `> 0` guard; GHOST
uncle validation (real pooled orphan, PoW, difficulty/recency bounds, no
double-crediting) with C1 work-scaling on the forward path; difficulty derived
purely from stored timestamps (never stale), integer-only, bounded step;
median-time-past + future-bound + min-block-time timestamp rules; merkle
second-preimage mitigation (0x00/0x01 domain separation + `numTransactions`
committed + in-block dedup); BouncyCastle **1.78.1** (patched CVE-2024-30172);
snapshot injection pinned to a PoW-validated pivot state root; SSRF/eclipse/Sybil
defenses (routable-IP pinning, subnet buckets, PEX caps, ban-score); WASM
sandbox (reentrancy guard, frame atomicity, unspoofable call context,
gas-metered bulk-memory ops, per-instance memory cap).

---

## 7. Changes made

**First commit** — the exploitable consensus/DoS defects: **C1, C2, H1, H2, M1,
M2** across `Executor.java`, `ChainEngine` (validated), `Crypto.java`,
`WasmVm.java`, `SnapshotBootstrap.java`, with two consensus regression tests.

**Second commit** — the remaining actionable findings: **H3, H4, M3, M4, M5, M6**
(net/VM hardness) and **L1, L3, L4** across `ChainEngine.java`, `NodeApi.java`,
`WasmVm.java`, `ChainSynchronizer.java`, `TransactionDto.java`,
`SparseMerkleTree.java`, plus **T2** (`token.rs` recompiled to `token.wasm`), with
a table-cap regression test.

**383/383 tests pass.** Still open by design: **T1/T3/T4** (need a
constructor-at-deploy protocol change or an LP/ABI redesign) and **L2/L6/L7** (a
decode-strictness / deployment trade-off left to the operator), all detailed in §3.

