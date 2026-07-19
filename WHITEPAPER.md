# Rhizome — Technical Whitepaper

> Version 0.9 (alpha) · A Java port of Pandanite · A clean chain seeded from a balance snapshot

## Abstract

Rhizome is a proof-of-work blockchain, rewritten in Java from
[Pandanite](https://github.com/pandanite-crypto/pandanite) (C++). Rather than maintain
a codebase carrying fossilised consensus bugs, Rhizome starts a **clean chain** — new
genesis, corrected rules — whose **initial state is a sanitised snapshot of the
balances** of the existing Pandanite chain. Holders keep their balance; the network
restarts on sound rules.

Three goals drive the design:

1. **Correctness** — every known consensus bug of the Pandanite C++ node is fixed from
   the start (§4), and validation is ordered so that denial of service is expensive.
2. **Performance** — the target is **one block per second** validated by the network,
   on a stack built for throughput (§6).
3. **Pufferfish2 proof of work** — from genesis, with no SHA-256 phase and no algorithm
   switch, ported in pure Java and validated bit-for-bit against a C reference.

The node core is functional and covered by **166 tests**: consensus, execution,
storage, mempool, HTTP API, block production, P2P synchronisation with reorganisation,
and a wallet.

---

## 1. Motivation

Pandanite is a small, readable PoW blockchain, but its history froze several
consensus-level defects: unchecked `uint64` arithmetic causing monetary inflation
(`invalid.json`), a floating-point mining reward that diverges between implementations,
non-deterministic difficulty patched with hard-coded exceptions, missing chain-linking
validation over the first thousands of blocks, Ed25519 signature malleability, unbounded
network inputs, and inconsistent locking between the mempool and the blockchain.
Faithfully replaying that chain would mean replaying those bugs.

Rhizome makes the opposite choice: **start from a fresh genesis**. Because history need
not be replayed, none of those defects has to be reproduced. The only tie to the
existing chain is economic — **balances** are imported from a snapshot, sanitised of the
wallets produced by the inflation incidents.

---

## 2. Architecture overview

The node is assembled with explicit constructors (no reflection-based dependency
injection container), which keeps the dependency graph legible and friendly to GraalVM
native compilation.

```
                +------------------ app-node ------------------+
   HTTP  <----> |  NodeApi (rate limit + bounded body sizes)   |
                |  BlockProducer   PeerBroadcaster/Discovery    |
                |  ChainSynchronizer (periodic sync + reorg)    |
                +----------------------|------------------------+
                                       v
         +-------------------------- lib-core -------------------------+
         |  ChainEngine  (addBlock/popBlock, nonces, work, difficulty) |
         |  Executor     (transactional application to the ledger)     |
         |  MemPool      (admission, nonce-ordered selection, fairness) |
         |  Pufferfish2  (PoW)   DifficultyAdjustment   MerkleTree      |
         +-----------------------------|------------------------------+
                                       v
         +----------------------- lib-persistence --------------------+
         |  RocksDbNodeStore: ChainStore + Ledger + txdb (one DB,      |
         |  column families, atomic WriteBatch append/pop)             |
         +------------------------------------------------------------+
```

Gradle multi-module project (Java 21): `lib-core`, `lib-persistence`, `lib-net`,
`lib-crypto` (reserved), `app-node`, `app-wallet`, `app-dnsseeder`.

---

## 3. Consensus model

### 3.1 Block hash preimage

A block's hash commits to its entire header:

```
hash = H( merkleRoot || lastBlockHash || id || difficulty || numTransactions || timestamp )
```

(integers big-endian). Unlike the C++ node — whose `getHash` covered only
`{merkleRoot, lastBlockHash, difficulty, timestamp}`, leaving `id` and the PoW-algorithm
choice *outside* the preimage — **every header field is committed**. A reordered or
re-timestamped block yields a different hash, hence an invalid proof of work.

### 3.2 Proof of work — Pufferfish2

Pufferfish2 is a memory-hard key-derivation function (`$PF2$`, `cost_t=0`, `cost_m=8`,
all-zero salt → deterministic), ported in pure Java over HMAC-SHA512 (BouncyCastle) and
**validated bit-for-bit** against a C reference through golden vectors. `verifyNonce`
recomputes the PoW hash and requires `difficulty` leading zero bits. Because difficulty
is bounded at 255, a block claiming an absurd difficulty is rejected in constant time
with no costly allocation.

### 3.3 Difficulty adjustment

Difficulty is **recomputed deterministically from the timestamp history**: the genesis
difficulty, stepped once per completed retarget window (60 blocks on mainnet). Being
derived — never a cached field that goes stale after a `popBlock` — it cannot become
inconsistent across a reorg, the flaw behind Pandanite's hard-coded difficulty
exception for blocks 536100–536200. The step is **bounded and clamped**, which limits
the leverage of timestamp manipulation (time-warp).

### 3.4 Cadence: one block per second, enforced by the network

The producer paces its own loop locally, but the **consensus rule** `minBlockTimeSec` —
a block must be at least `minBlockTime` after its parent — is checked by **every** node.
On mainnet `minBlockTime = desiredBlockTime = 1 s`: the consensus floor *is* the
metronome. A modified node cannot flood the network with thousands of valid blocks to
"steal" the chain, because any block too close to its parent is rejected by the whole
network (majority miner included) — local producer pacing alone would not stop this.
The future bound (`maxFutureBlockTime = 15 s`) caps how many blocks can be mined "in
advance" before real time must catch up.

### 3.5 `addBlock` validation order

Cheapest and structural first, proof of work last so an invalid block cannot burn CPU (a
Pandanite DoS lesson):

1. `id` continuity, transaction count non-empty and ≤ max;
2. checkpoint (at a pinned height, only the published hash passes);
3. `lastBlockHash` chains to the tip (checked from block 2 — the historical bug #2 that
   forked Pandanite toward block ~7400 for lack of this check);
4. timestamp > median-time-past, ≥ parent + `minBlockTime`, ≤ local clock + future bound;
5. difficulty equals the value recomputed from history;
6. Merkle root matches the block's transactions;
7. account nonces strictly sequential per sender;
8. proof of work;
9. the `Executor` applies the transactions transactionally.

All public engine methods are serialised on a single lock: one writer at a time, and
reads see consistent state (Pandanite's unlocked getters produced torn reads of its
`BigInt` cumulative work, and its opposed mempool↔blockchain lock orders deadlocked
under load).

### 3.6 Merkle tree

The tree **preserves insertion order** of transactions: the root commits to the *order*,
not just the *set*. Sorting (as the C++ did — and it mutated the caller's block vector by
reference) would make `[t0,t1]` and `[t1,t0]` share a root, hence a block hash and its
PoW, while nonce validation is order-dependent: the same hash could be accepted or
rejected depending on the order a node received, splitting consensus. The historical
Merkle implementation was also structurally wrong for an odd leaf count (issue #29).
Duplicating the last transaction (CVE-2012-2459) stays neutralised by the Executor's
content-hash deduplication.

### 3.7 Fork choice and finality

Fork choice is objective: a peer's chain is adopted **only if its cumulative work is
strictly greater** (2^difficulty per block, summed as `BigInteger`). Pandanite forked
repeatedly for lack of such a rule.

Synchronisation is hardened against hostile peers:

- **Finality window** — a reorg deeper than `maxReorgDepth` (600 blocks) is refused
  outright: buried history cannot be rewritten, whatever work is claimed.
- **No free rollbacks** — before any local mutation, a bounded prefix of the peer's
  branch is fetched and **validated statelessly** (id continuity, hash chaining from the
  fork point, per-block PoW, and verified work strictly above ours). A peer that merely
  *claims* huge work therefore costs a bounded download, never a pop/restore cycle.
- **Restore on failure** — if the stateful apply still fails, the local chain is
  restored exactly.

---

## 4. Corrected consensus bugs (from the C++ audit)

Rhizome does not reproduce the following, each confirmed against the Pandanite source,
`pandanite-core`, and the upstream issues/PRs. Three real incidents are fossilised in
the repo (`invalid.json`, `blacklist.txt`, `bannedHashes`, the hard-coded difficulty
hack), evidence of bugs exploited in production.

| # | Bug (C++) | Rhizome |
|---|---|---|
| 4.1 | **Ledger `uint64` underflow → inflation.** `withdraw` did `value -= amt` with no `value >= amt` check (the `invalid.json` incident: `BALANCE_TOO_LOW` transactions accepted network-wide). | Checked arithmetic in the ledger itself; underflow **and** overflow rejected with full rollback. |
| 4.2 | **Floating-point mining reward → cross-implementation fork.** Reward computed by successive `*= 2.0/3.0` then truncated, compared by strict equality — any rounding drift splits the chain. | Integer-only reward table by height; never recomputed in floating point. |
| 4.3 | **Non-deterministic difficulty, stale after reorg.** After `popBlock`, difficulty was recomputed only when `numBlocks % LOOKBACK == 0` — otherwise kept the popped chain's value (origin of the hard-coded 536100–536200 hack). | Difficulty is a pure deterministic function of prior headers, recomputed after every add **and** pop, never read from a stored field. |
| 4.4 | **Merkle sort mutates the block.** `setItems` sorted the caller's vector by reference during validation, so transaction order was not committed by PoW; odd leaf counts were structurally wrong (#29). | Order-preserving Merkle on a copy; order committed in the block hash (§3.6). |
| 4.5 | **Incomplete block hash.** Preimage omitted `id` and `numTransactions`, and the PoW algorithm was chosen from an uncommitted `id`. | Full-header preimage (§3.1); single algorithm from genesis, no uncommitted switch. |
| 4.6 | **Weak anti-replay.** `hashContents` had no account nonce and no chain-id; the anti-replay key included the signature, so Ed25519 malleability (#37) allowed double execution. | Transactions identified by the **signature-free** content hash; **account nonce + chain-id** in the signed preimage. |
| 4.7 | **Weak time validation.** "Future" bounded by a Sybil-manipulable network-median time; median-time-past over only 10 blocks (miners pushed future timestamps to reject honest blocks, #19/#22). | Future bounded by the **local clock**; wider MTP window; single algorithm; documented. |
| 4.8 | **Missing chain-linking (historical bug #2).** `lastBlockHash` was long unchecked → massive forks toward block ~7400. | `lastBlockHash` and expected `id` validated from block 2, before any execution; covered by a two-node fork test. |
| 4.9 | **Deadlock + unlocked shared state.** Opposed lock orders (mempool↔blockchain) deadlocked; getters read `BigInt` work without a lock (torn reads). Half of Pandanite's merged PRs were lock/segfault patches. | Chain state behind a single lock order; no unsynchronised getter over mutated state. |
| 4.10 | **Unbounded network inputs (DoS).** Trusted `numTransactions` with no bound (heap over-read/OOM); handlers without `return` after `end()` disabled range/rate guards (`/sync` amplification); remote divide-by-zero; unbounded Pufferfish cache; costly PoW computed before cheap checks. | Bounded bodies and response sizes; range endpoints hard-bounded before work; per-client rate limiting; PoW checked last; bounded caches (§5, §7). |

---

## 5. Transaction model and execution

### 5.1 Transaction

A transaction commits, in its signed preimage, to the `chainId` **and** a per-sender
**account nonce**. The chain-id prevents cross-network replay; the sequential nonce
prevents replay and ordering ambiguity within a network. A transaction's identity for
deduplication is its **signature-free content hash** — immune to Ed25519 malleability.

Amounts and fees are integers (base units, scale 10 000 → "PDN"). They are conceptually
**unsigned**: any negative amount or fee is rejected, because a negative withdrawal
would mint money for the sender and a negative deposit would drive the recipient's
balance below zero.

### 5.2 Transactional execution

The `Executor` validates then applies a block's transactions:

1. **Structural pass** (no state touched): exactly one coinbase whose amount equals the
   expected reward at that height (integer, never a floating-point compare); every other
   transaction targets this chain, has a valid signature whose key matches the sender
   address, is not a duplicate (in-block or already executed), and has amount/fee ≥ 0.
2. **Application pass** (transactional): balances go through a **checked-arithmetic**
   ledger. Insufficient balance (underflow) and 64-bit overflow are both rejected
   cleanly with **full rollback** — the C++ node's unchecked `uint64` arithmetic is what
   inflated balances in the `invalid.json` incident.

Money is conserved: each transaction is debited then credited, and the coinbase is
pinned to the height's reward.

### 5.3 Economics

The block subsidy decays geometrically (×2/3) once per epoch, in integer arithmetic, so
issuance is bounded and deterministic. Total issuance is the geometric series
`epochBlocks × initialReward × 3 ≈ 100M PDN`.

**Calibration for one-second blocks.** The decay epoch is denominated in *blocks*, so its
real-time length depends on the block rate. Pandanite's 666 666-block epoch spans about
1.9 years at 90 s/block — but only **7.7 days at 1 s/block**. Left unchanged, the whole
subsidy would drain in about **8 months**, with the first epoch alone (a third of all
coins) emitted in a single week — a launch-fairness and mining-incentive collapse, even
though the *total* supply is unaffected. Rhizome therefore rescales both knobs by the
cadence ratio so the **real-time schedule is preserved** regardless of block rate:

| | Original (90 s) | Naïve at 1 s | **Rhizome (1 s)** |
|---|---|---|---|
| `rewardEpochBlocks` | 666 666 | 666 666 | **60 000 000** |
| `initialReward` | 50 PDN | 50 PDN | **0.5555 PDN** |
| Epoch in real time | ~1.9 yr | 7.7 days | **~1.9 yr** |
| Subsidy dry-up | ~decades | ~8 months | **~decades** |
| Emission at launch | 48 000 PDN/day | 4.32M PDN/day | **48 000 PDN/day** |
| Total issuance | ~100M PDN | ~100M PDN | **~100M PDN** |

Per-block rewards are small because there are 86 400 blocks per day; daily and total
emission match the intended economics. The invariant is enforced by a test
(`emissionScheduleIsCalibratedForOneBlockPerSecond`): change the block time and the epoch
length must be revisited with it.

---

## 6. Networking and performance

### 6.1 Transport and protocol

**HTTP** transport (parity with Pandanite, simpler to reason about, native-friendly),
with synchronisation running on its own thread in blocking I/O off the event loop. HTTP
is the right fit for request/response, cache-friendly, observable node APIs; the ActiveJ
RPC forks that were tried upstream were non-functional and depended on runtime codegen.

- **Active gossip** — accepted blocks and transactions are re-broadcast to peers; loops
  terminate because a peer that already has an item rejects it.
- **Peer discovery (PEX)** — every node serves `/peers`, accepts announcements, and
  periodically polls its peers, so the network self-organises from a few seeds.
  Repeatedly unreachable peers are pruned.
- **Ban-score banning** — a peer accrues points for protocol violations; over a
  threshold it is banned for a window (score decays over time). The key is the **host**
  (a peer cannot dodge a ban by rotating port or path). Serving an invalid chain bans on
  the first strike; a too-deep reorg or wrong network cost less. The peer registry is
  the single admission choke point: a banned host cannot be reintroduced via config,
  `/add_peer`, or PEX.
- **Rate and size limits** — each client is limited by a fixed window (bounded client
  table, fixing the Pandanite #52 memory leak), each POST body is capped, and the sync
  client **bounds peer response sizes** (a giant `/total_work` string would be an
  O(n²) BigInteger-parse CPU DoS).

### 6.2 Performance stack (target: 1 block/s)

Three principles govern the infrastructure choices: raw throughput, no runtime code
generation (so GraalVM native compilation stays viable), and a disciplined concurrency
model.

- **Fixed-layout binary codec** — manual `ByteBuffer` big-endian serialisation replaces
  runtime-codegen serializers (ActiveJ/Fory): deterministic, fast, and native-friendly.
- **RocksDB storage** — one database with column families (chain, ledger, txdb); append
  and pop are atomic `WriteBatch`es, so a crash never leaves half-written state. Chosen
  over iq80 LevelDB (pure-Java, slower) and LMDB for its write throughput and atomic
  batches.
- **Parallel, cached signature verification** — signatures are verified once at mempool
  admission and cached, so block execution gets a cache hit; batches verify in parallel.
  Measured ≈140 blocks/s with a warm cache versus ≈6.6 sequentially — the single change
  that makes a one-second block realistic under load.

GraalVM native compilation is a standing design constraint (no runtime codegen; JNI for
RocksDB declared; reflection kept to compile-time Lombok plus registrable `org.json`),
even though `native-image` is not installed in the current development environment.

---

## 7. Security model

Defended vectors: double-spend (in-block / already-executed, via the signature-free
content hash), signature malleability, sender spoofing, cross-chain and nonce replay,
inflated reward, **negative amounts** (money minting), **balance overflow**, timestamp
and difficulty (time-warp) manipulation, deep reorg (finality), lying peer (claimed but
unproven work), block/request flooding, and network DoS (bounded bodies, ban-score). A
**per-sender** mempool cap ensures fairness between accounts so one sender cannot squat
the pool.

Two bugs were found and fixed during a dedicated security review:

- **Negative amount/fee** — an unvalidated signed amount let a signed transaction mint
  money (`withdraw(sender, -1000)` credits the sender) and drive a recipient negative.
  Now rejected on the consensus path (Executor) and at mempool admission.
- **Deposit overflow** — a deposit overflowing a 64-bit balance threw `ArithmeticException`
  from the ledger's `Math.addExact`, which the Executor's `catch (LedgerException)` did
  not cover, leaving a partial mutation. Now caught and rolled back.

The irreducible residual is the **51% attack**: an adversary with a sustained majority
of hash power can reorg within the finality window. The finality window bounds its
depth; nothing in any PoW chain removes it entirely.

**Coinbase maturity** (a UTXO concept) is not applicable to the balance-based ledger —
the reward is immediately fungible with no distinct coinbase coin to mature — and the
orphaned-reward risk is already covered by the finality window.

---

## 8. Snapshot seeding

The genesis state is a snapshot of the Pandanite balances, **sanitised** (wallets from
the inflation incidents excluded). The `genesisCommitment` hashes
`chainId || snapshotCommitment`, so two different networks never share a genesis even
with an empty snapshot. The real snapshot is produced by a dump tool reading the LevelDB
ledger of a synchronised Pandanite node.

---

## 9. Status and roadmap

**Done** — crypto & Pufferfish2; consensus model & Executor; chain engine
(`addBlock`/`popBlock`, nonces, work, difficulty); RocksDB storage; mempool; HTTP API;
block production; synchronisation + reorg by cumulative work; wallet CLI; gossip & peer
discovery; hardening (checkpoints, finality, bounded rate limiting, ban-score); a full
security review. **166 tests, 0 failures.**

**Environment-dependent** — GraalVM native build (`native-image` not installed in the
current dev environment); production of the real Pandanite snapshot (a synchronised C++
node is required); a public multi-node testnet.

**Later** — smart contracts, DeFi use cases, cross-chain protocols and a bridge.

---

## 10. References

- Origin: [Pandanite (C++)](https://github.com/pandanite-crypto/pandanite)
- Historical consensus bugs: Pandanite issues #2 (lastBlockHash), #19/#22 (timestamp),
  #29 (Merkle), #37 (Ed25519 malleability), #52 (rate-limiter leak).
- Fossilised incidents: `pandanite/invalid.json`, `blacklist.txt`, `config.cpp`
  `bannedHashes`, the `blockchain.cpp` difficulty hack (blocks 536100–536200).
