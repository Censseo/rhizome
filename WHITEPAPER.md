# Rhizome — Technical Whitepaper

> Version 0.10 (alpha) · A Java proof-of-work smart-contract chain · WASM contracts, built for memecoins and autonomous agents

## Abstract

Rhizome is a proof-of-work blockchain written in Java, descended from
[Pandanite](https://github.com/pandanite-crypto/pandanite) (C++) but rebuilt as a
**smart-contract chain** for cheap token launches and **autonomous AI agents**.
Contracts are **WebAssembly**, executed deterministically on the pure-Java
[Chicory](https://github.com/dylibso/chicory) runtime (no JNI), so any language that
compiles to WASM can target the chain and the node stays GraalVM-native-friendly.

It starts a **clean chain** — new genesis, corrected rules — whose **initial state is a
sanitised snapshot of the balances** of the existing Pandanite chain, so holders keep
their balance while the network restarts on sound rules.

Four goals drive the design:

1. **Correctness** — every known consensus bug of the Pandanite C++ node is fixed from
   the start (§4), validation is ordered so denial of service is expensive, and contract
   state moves atomically with its block (including exact reversal on reorg, §5).
2. **Deterministic, sandboxed contracts** — a metered WASM VM where gas bounds both
   compute (per instruction) and storage, so untrusted code can neither hang a node nor
   escape its sandbox (§5).
3. **Pufferfish2 proof of work** — from genesis, no SHA-256 phase and no algorithm
   switch, ported in pure Java and validated bit-for-bit against a C reference.
4. **Fast, safe cadence** — a ~1-block/second target paced by difficulty (§3.4), with a
   GHOST-style fork choice (§9) that credits and rewards orphaned (uncle) work, making
   sub-5-second blocks safe against the orphaning a naïve longest chain suffers.

The node is functional and covered by **207 tests**: consensus, the WASM contract VM
and its persistence, execution, storage, mempool, HTTP API, block production, P2P
synchronisation with reorganisation, GHOST uncles, and a wallet that deploys and calls
contracts.

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
         |  Executor  (transactional apply; dispatches contract txs)   |
         |  MemPool   ·  Pufferfish2 (PoW)  ·  Difficulty  ·  Merkle    |
         |  ContractProcessor (interface) <--- implemented by lib-vm   |
         +------------------|-------------------------|----------------+
                            v                         v
        +-------- lib-vm (WASM contracts) ----+  +--- lib-persistence ---+
        |  WasmVm (Chicory, gas-metered)      |  |  RocksDbNodeStore:     |
        |  WasmContractProcessor (sessions,   |  |  ChainStore + Ledger   |
        |  undo journals, receipts)           |  |  + txdb (one DB, CFs,  |
        |  ContractStore <-- RocksDbContract  |  |  atomic WriteBatch)    |
        +-------------------------------------+  +-----------------------+
```

`lib-vm` depends on `lib-core` and implements its `ContractProcessor` interface, so the
consensus core dispatches contracts without ever depending on the WASM runtime. Gradle
multi-module project (Java 21): `lib-core`, `lib-vm`, `lib-persistence`, `lib-net`,
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

### 3.4 Cadence: one block per second, paced by difficulty

The target is one block per second **on average, paced by difficulty** — the standard
proof-of-work mechanism — not by a per-block time floor. This is a deliberate reversal
of an earlier design that set `minBlockTime = desiredBlockTime`: making the floor equal
the target *starves* the retarget. The producer floors every timestamp to
`parent + minBlockTime`, so a full window then always measures ≈ the desired duration
and difficulty never rises to track hashrate — it stays pinned near `minDifficulty`
regardless of the real hashrate. Proof-of-work cost would collapse to a fixed
`2^minDifficulty`, and an attacker could rewrite history or win the future-bound reward
race for near-free (the chain would be secured by clocks, not work).

So on mainnet **`minBlockTime = 0`**: difficulty does the pacing, which is what makes
each block cost real work, so outpacing the chain requires majority hashrate. The flip
side is honest: at a one-second average the block-time distribution is Poisson (bursts
and gaps), and single-chain propagation becomes the real limiter — see §6.3. The future
bound is kept tight (`maxFutureBlockTime = 5 s`) because at one-second blocks it is also
the number of blocks an attacker could pre-mine into the future before release;
median-time-past spans ~60 blocks so a few consecutive blocks cannot drag the chain's
notion of past time. The `minBlockTime` rule still exists as an optional sanity floor
for networks that want one, but it must stay well below the target.

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

A transaction has a **kind**: `TRANSFER` (the default, unchanged), `DEPLOY` (install
contract code) or `CALL` (invoke a contract). Contract kinds additionally carry a
variable-length `data` payload (code or call input) and a gas budget
(`gasLimit`, `gasPrice`); these fields are part of the signed preimage and the wire
format only for contract transactions, so a transfer is byte-for-byte what it was before
contracts existed. Every transaction is self-delimiting on the wire, so a block still
packs variable-length transactions back to back, and the whole block is bounded by
`maxBlockSizeBytes` (§5.4).

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

### 5.4 Smart contracts

Contracts are **WebAssembly**, run on the pure-Java [Chicory](https://github.com/dylibso/chicory)
runtime — no JNI, no native dependency, deterministic across nodes because every node
executes the same interpreter. A contract imports a small host ABI from module `env` —
`storage_read`, `storage_write`, `set_output`, `emit_log`, and the call context
(`get_caller`, `get_input`, `get_value`) — and exports a `call` entry point; the WASM
sandbox denies it any other I/O. A reference fungible-token contract
(`contracts/token.rs`, the memecoin base) exercises the whole ABI: it mints a supply to
its deployer, transfers between accounts, and emits a `transfer` event on each move.

**Event logs.** `emit_log(topic, data)` records an event during a call — the channel
autonomous agents watch to react to on-chain state. Logs are gas-metered, kept only when
the call succeeds, and never read back by contract code, so they carry no consensus weight
beyond the gas paid. The processor collects each block's logs (dropping them exactly on a
reorg, like contract state), and the node exposes them at `GET /logs?height=N` and, for
streaming, a height-cursor scan `GET /logs?fromHeight=N` whose `toHeight` is the next
cursor — an agent follows the chain by polling forward from where it left off.

**Gas.** Every executed instruction is charged via the interpreter's execution listener,
and every host call is charged on top, so a contract that loops forever is aborted
deterministically at the same step on every node rather than hanging one. Out-of-gas is a
clean, identical failure everywhere.

**Deploy and call.** A `DEPLOY` installs code at a deterministic address —
`SHA-256(deployer ‖ nonce)[:25]`, so addresses are predictable and collision-free. A
`CALL` runs the target's `call` export with the transaction's input and any attached
value. The consensus core never depends on the WASM runtime: the `Executor` invokes a
`ContractProcessor` **interface** (implemented in the VM module), so lib-core stays free
of Chicory.

**Fees and atomicity.** The caller must be able to afford `value + gasLimit × gasPrice`.
It always pays `gasUsed × gasPrice` to the miner — even on revert or out-of-gas, the work
was done (Ethereum-style) — while the value transfer and the contract's storage writes
apply only on success. A reverted contract call is still *included* in its block and pays
gas, but does not invalidate the block; only an unaffordable or malformed contract
transaction fails the block. Contract storage is staged in a per-block session that
flushes to the store only when the block is accepted, and a per-block **undo journal**
(each written key's prior value) plus **receipts** (gas used, success) let a reorg reverse
both the contract state and the contract-transaction ledger effects **exactly** when a
block is popped — so contract state moves atomically with its block.

**Bounds.** Contract payloads are variable length, so a block is capped at
`maxBlockSizeBytes` (4 MiB) — checked before any expensive work — and the block builder
stops adding transactions before crossing it, so a payload-laden block can never be a
download or storage denial of service.

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

### 6.3 Block propagation and the block-time floor

At a one-second cadence, **propagation latency — not CPU — is the binding constraint**.
Verification is fast (§6.2), but a block must reach most of a global network before the
next is found, or the two collide into an orphan. Orphans waste work, raise the effective
reorg rate, and favour the best-connected miners (centralisation). The floor is physical:
even an optimal relay needs roughly a few hundred milliseconds to a couple of seconds to
cover a worldwide peer graph, which is a large fraction of a one-second interval.

The current relay is deliberately simple — a node **pushes each accepted block in full**
to its peers over one-shot HTTP, and periodically pulls ranges over `/sync`. Three
upgrades bring it in line with fast-chain practice, in decreasing order of impact:

1. **Compact-block relay (BIP-152 style).** Announce a header plus short transaction IDs;
   the receiver reconstructs the block from its mempool and requests only the few missing
   transactions. Because transactions are gossiped continuously, the block on the wire
   shrinks to a few kilobytes regardless of how full it is — propagation cost becomes
   size-independent.
2. **Header-first, announce-then-pull.** Relay and PoW-check the header immediately and
   forward it, then fetch/reconstruct the body; peers pull a body only if they lack it
   (`inv`/`getdata`), so each node receives a block once rather than from every peer.
3. **Persistent streaming connections.** Keep long-lived links to peers (WebSocket or a
   raw binary channel) so a header can be pushed the instant it is produced, with no
   per-block TCP/TLS handshake — the "streaming" that matters for inter-node relay.

A reference point: **Alephium** does not run a fast single chain at all. Its per-chain
block time is 64 s (16 s after Rhône, 8 s after Danube); throughput comes from **sharding
and a two-dimensional DAG (BlockFlow)** — many parallel chains, each slow enough that
propagation is a small fraction of its interval. Its WebSocket block streaming is a
*client-facing* API, not the inter-node mechanism. The lesson is that a robust fast chain
either keeps the per-chain interval well above propagation time (a few seconds), or
parallelises (sharding / a GHOST-style fork choice that credits orphaned work), rather
than pushing a single longest chain below its own propagation latency. Rhizome's
one-second figure should therefore be read as an average target contingent on the relay
upgrades above, with a slower per-chain interval or a DAG fork choice as the honest paths
to genuinely high throughput.

---

## 7. Security model

Defended vectors: double-spend (in-block / already-executed, via the signature-free
content hash), signature malleability, sender spoofing, cross-chain and nonce replay,
inflated reward, **negative amounts** (money minting), **balance overflow**, timestamp
and difficulty (time-warp) manipulation, deep reorg (finality), lying peer (claimed but
unproven work), block/request flooding, and network DoS (bounded bodies, ban-score). A
**per-sender** mempool cap ensures fairness between accounts so one sender cannot squat
the pool.

**Contract-specific:** untrusted WASM is sandboxed by the runtime (no I/O beyond the
host ABI) and bounded by gas on both compute (per instruction) and storage, so it can
neither hang a node nor escape; a variable-length payload cannot bloat a block past
`maxBlockSizeBytes`; and a reorg reverses contract state and contract-transaction ledger
effects exactly, so a rewritten block leaves no contract residue.

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
discovery; hardening (checkpoints, finality, bounded rate limiting, ban-score, block-size
cap); a full security review; and the **WASM smart-contract layer** — a Chicory-backed
metered VM, a persistent contract store, `DEPLOY`/`CALL` transactions with gas fees,
atomic per-block contract state with exact reorg reversal, and wallet `deploy`/`call`
commands. **207 tests, 0 failures.**

**GHOST fork choice.** A ~1-block/second single longest chain orphans blocks because
propagation takes a meaningful fraction of the interval (§6.3). A GHOST-style fork
choice — the heaviest *subtree*, crediting the work of referenced orphan (uncle)
blocks — lets the target drop toward a few seconds without the centralisation and reorg
churn a naïve longest chain suffers. It is implemented end to end:

- **Uncle references.** Each block may carry up to `maxUnclesPerBlock` (2) uncle
  references. A reference commits the uncle's hash, difficulty *and* miner address; all
  three are checked against the real orphan at admission, so difficulty (work) cannot be
  inflated and the reward cannot be redirected.
- **Orphan pool.** A bounded LRU of valid off-chain blocks (PoW-gated), fed from blocks
  that lose a reorg and from siblings submitted to the node.
- **Validation.** An uncle must be recent (within `uncleMaxDepth`, 7), fork from a recent
  main-chain block, not be the canonical block at its height, and not have been referenced
  already — no double-crediting.
- **Weighting.** The referenced uncle work (`2^difficulty`) folds into the cumulative
  chain weight and survives pop and restart from the committed difficulties alone. The
  block assembler cites eligible orphans automatically when producing a block.
- **Rewards.** An included uncle pays its miner a flat fraction of the block reward
  (`uncleRewardNum/uncleRewardDen`, default 1/2) and the nephew a bonus per uncle
  (`miningReward/nephewRewardDivisor`, default 1/32). Both are fresh issuance, but every
  uncle is a real proof-of-work block, so nothing is minted without matching work. The
  amounts are flat (not distance-scaled), so they are derivable from the committed uncle
  refs alone and reorg reversal is exact.

**In progress — autonomous-agent primitives.** Contract event logs are implemented (see
§5): contracts `emit_log`, the processor collects them per block reorg-safely, and the
node serves them by height and by a pollable height cursor so agents follow on-chain state
in near-real-time. Next: push streaming (SSE/WebSocket) over the same event source, and
account abstraction (contract accounts as agents — session keys, spend limits, gas
sponsorship).

**In progress — memecoin primitives.** Two reference contracts are implemented and
tested through consensus: a fungible token (mint, transfer, `transfer` logs) and a
constant-product AMM (`x*y=k` with a 0.3% fee, `swap` logs, exact integer math verified
against the same formula in the test). Both are self-contained today; a `call_contract`
host function — with bounded call depth, forwarded gas, and reentrancy guards — is the
next step that lets the AMM move a real token and unlocks a fair-launch launchpad.

**Environment-dependent** — GraalVM native build (`native-image` not installed in the
current dev environment); production of the real Pandanite snapshot (a synchronised C++
node is required); a public multi-node testnet.

**Later** — DeFi use cases, cross-chain protocols and a bridge; optional sharding
(parallel chains) if per-chain throughput becomes the limit.

---

## 10. References

- Origin: [Pandanite (C++)](https://github.com/pandanite-crypto/pandanite)
- Historical consensus bugs: Pandanite issues #2 (lastBlockHash), #19/#22 (timestamp),
  #29 (Merkle), #37 (Ed25519 malleability), #52 (rate-limiter leak).
- Fossilised incidents: `pandanite/invalid.json`, `blacklist.txt`, `config.cpp`
  `bannedHashes`, the `blockchain.cpp` difficulty hack (blocks 536100–536200).
