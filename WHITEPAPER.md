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
4. **Fast, safe cadence** — a 5-second block target paced by difficulty (§3.4), with a
   GHOST-style fork choice (§9) that credits and rewards orphaned (uncle) work, making
   the fast cadence safe against the orphaning a naïve longest chain suffers.

The node is functional and covered by **401 tests**: consensus, the WASM contract VM
and its persistence, execution, storage, mempool, HTTP API, block production, P2P
synchronisation with reorganisation, GHOST uncles, data boxes (agent-facing on-chain
storage with typed registers, an anti-dust deposit and storage rent), native tokens
(one-transaction fungible-asset launches), an authenticated state root committed in every
header (light-client proofs of any ledger, nonce, box, token or contract entry),
**headers-first sync, pruned nodes, and trust-minimised snapshot bootstrap** (§6.4),
miner-voted economic parameters, and a wallet that deploys and calls contracts and manages
boxes and tokens.

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
                |  BlockProducer                                |
                |  ChainSynchronizer (periodic sync + reorg)    |
                +----------|---------------------|--------------+
                           v                     |
        +------ lib-net (p2p transport) ------+  |
        |  HttpPeerSource  PeerBroadcaster    |  |
        |  PeerDiscovery   PeerRegistry/Ban   |  |
        +------------------|------------------+  |
                           v                     v
         +-------------------------- lib-core -------------------------+
         |  ChainEngine  (addBlock/popBlock, nonces, work, difficulty) |
         |  Executor  (transactional apply; dispatches contract txs)   |
         |  MemPool   ·  Difficulty  ·  Merkle  ·  PeerSource (iface)   |
         |  ContractProcessor (interface) <--- implemented by lib-vm   |
         +--------|-----------------|-------------------|--------------+
                  v                 v                   v
  +- lib-crypto (primitives) -+  +- lib-vm (WASM) ---+  +- lib-persistence -+
  |  Ed25519 (keys, signing)  |  |  WasmVm (Chicory, |  |  RocksDbNodeStore: |
  |  Pufferfish2 (PoW)        |  |  gas-metered)     |  |  ChainStore+Ledger |
  |  SHA256/RIPEMD160, hex    |  |  sessions, undo   |  |  +txdb (one DB,    |
  +---------------------------+  |  journals         |  |  atomic WriteBatch)|
                                 +-------------------+  +--------------------+
```

`lib-vm` depends on `lib-core` and implements its `ContractProcessor` interface, so the
consensus core dispatches contracts without ever depending on the WASM runtime.
`lib-net` implements lib-core's `PeerSource` abstraction over HTTP, so sync logic never
depends on a transport. `lib-crypto` holds the primitives (Ed25519, Pufferfish2, hashes,
hex) with BouncyCastle as its only dependency. Gradle multi-module project (Java 21):
`lib-crypto`, `lib-core`, `lib-net`, `lib-vm`, `lib-persistence`, `app-node`,
`app-wallet`.

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
re-timestamped block yields a different hash, hence an invalid proof of work. Three optional
fields — the referenced uncles (§3.7), the authenticated **state root** (§5.7) and the
miner's parameter **vote** (§5.8) — are folded in only when present, so a plain, stateless,
abstaining block hashes byte-for-byte as it did before those features existed.

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

### 3.4 Cadence: one block every five seconds, paced by difficulty

The target is one block every five seconds **on average, paced by difficulty** — the
standard proof-of-work mechanism — not by a per-block time floor. This is a deliberate
reversal of an earlier design that set `minBlockTime = desiredBlockTime`: making the
floor equal the target *starves* the retarget. The producer floors every timestamp to
`parent + minBlockTime`, so a full window then always measures ≈ the desired duration
and difficulty never rises to track hashrate — it stays pinned near `minDifficulty`
regardless of the real hashrate. Proof-of-work cost would collapse to a fixed
`2^minDifficulty`, and an attacker could rewrite history or win the future-bound reward
race for near-free (the chain would be secured by clocks, not work).

So on mainnet **`minBlockTime = 0`**: difficulty does the pacing, which is what makes
each block cost real work, so outpacing the chain requires majority hashrate. The flip
side is honest: at a fast average the block-time distribution is Poisson (bursts and
gaps), and propagation is the binding constraint — see §6.3. **Why 5 s and not 1 s**: at
~200 ms real-world propagation, a 1-second target orphans ≈18% of honest blocks
(`1 − e^(−0.2)`) versus ≈4% at 5 seconds. GHOST (§9) credits orphaned work either way,
but a high steady orphan rate still favours a selfish miner — whose private chain never
races itself — and multiplies bandwidth and storage. Five seconds keeps near-instant UX
with a comfortable margin; one second becomes viable once compact-block relay exists.

The future bound is kept tight (`maxFutureBlockTime = 15 s`, ≈3 blocks) because divided
by the block time it is the number of blocks an attacker can pre-mine into the future
before release; median-time-past spans ~60 blocks (≈5 minutes) so a few consecutive
blocks cannot drag the chain's notion of past time. The `minBlockTime` rule still exists
as an optional sanity floor for networks that want one, but it must stay well below the
target.

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
8. the block's **own** proof of work;
9. uncle validation — structural limits plus each referenced orphan's (memory-hard)
   PoW. Deliberately **after** step 8: a submitted block triggers up to `maxUnclesPerBlock`
   memory-hard uncle hashes, so gating them behind the block's own proven work stops a
   PoW-free `/submit` from forcing that hashing as a cheap event-loop amplifier;
10. the `Executor` applies the transactions transactionally.

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

- **Finality window** — a reorg deeper than `maxReorgDepth` (120 blocks, ≈10 minutes)
  is refused outright: buried history cannot be rewritten, whatever work is claimed.
- **No free rollbacks** — before any local mutation, a bounded prefix of the peer's
  branch is fetched and **validated statelessly** (id continuity, hash chaining from the
  fork point, per-block PoW, and verified work strictly above ours). A peer that merely
  *claims* huge work therefore costs a bounded download, never a pop/restore cycle.
- **One work metric, base-only** — every gate ranks branches by *own-block* PoW
  (`Σ 2^difficulty`, no uncle term), consistently: the "should I even look?" prefilter
  and the "should I adopt?" decision use the same quantity. Counting a header's *claimed*
  uncle work at the gate would let a cheaply-mined branch pad each header with in-range
  fake uncle references and inflate its apparent work ~3×, forcing an expensive
  pop/restore; genuine uncle (GHOST) work still decides the final fork choice once the
  bodies validate its eligibility. A structurally valid branch that is merely *lighter* is
  a lost fork race, not a protocol violation, so the peer is left connected rather than
  banned.
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

A transaction has a **kind**: `TRANSFER` (the default, unchanged), the contract kinds
`DEPLOY` (install contract code) and `CALL` (invoke a contract), the data-box kinds
`BOX_CREATE`/`BOX_UPDATE`/`BOX_SPEND`/`BOX_COLLECT` (§5.5), or the native-token kinds
`TOKEN_MINT`/`TOKEN_TRANSFER`/`TOKEN_BURN` (§5.6). Every non-transfer kind carries a
variable-length `data` payload and the gas fields (`gasLimit`, `gasPrice`) in its signed
preimage and wire format, so a transfer is byte-for-byte what it was before contracts
existed; the box and token kinds reuse that same suffix with the gas fields pinned to zero
(they run no VM). Every transaction is self-delimiting on the wire, so a block still packs
variable-length transactions back to back, and the whole block is bounded by
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

**Calibration for fast blocks.** The decay epoch is denominated in *blocks*, so its
real-time length depends on the block rate. Pandanite's 666 666-block epoch spans about
1.9 years at 90 s/block — but only **38 days at 5 s/block**. Left unchanged, the whole
subsidy would drain in a few years, with the first epoch alone (a third of all coins)
emitted in ~5 weeks — a launch-fairness and mining-incentive collapse, even though the
*total* supply is unaffected. Rhizome therefore rescales both knobs by the cadence ratio
(×18 = 90/5) so the **real-time schedule is preserved** regardless of block rate:

| | Original (90 s) | Naïve at 5 s | **Rhizome (5 s)** |
|---|---|---|---|
| `rewardEpochBlocks` | 666 666 | 666 666 | **~12 000 000** |
| `initialReward` | 50 PDN | 50 PDN | **2.7777 PDN** |
| Epoch in real time | ~1.9 yr | 38 days | **~1.9 yr** |
| Subsidy dry-up | ~decades | ~few years | **~decades** |
| Emission at launch | 48 000 PDN/day | 864 000 PDN/day | **48 000 PDN/day** |
| Total issuance | ~100M PDN | ~100M PDN | **~100M PDN** |

Per-block rewards are small because there are 17 280 blocks per day; daily and total
emission match the intended economics. The invariant is enforced by a cadence-relative
test (`emissionScheduleIsCalibratedForTheBlockCadence`): it recomputes the epoch's
real-time span and total issuance from `desiredBlockTimeSec`, so changing the block time
forces the epoch to be revisited with it.

### 5.4 Smart contracts

Contracts are **WebAssembly**, run on the pure-Java [Chicory](https://github.com/dylibso/chicory)
runtime — no JNI, no native dependency, deterministic across nodes because every node
executes the same interpreter. A contract imports a small host ABI from module `env` —
`storage_read`, `storage_write`, `set_output`, `emit_log`, the call context
(`get_caller`, `get_input`, `get_value`, `get_self`), and `call_contract` — and exports
a `call` entry point; the WASM sandbox denies it any other I/O. Reference contracts
(real Rust→WASM, driven through consensus in the tests) exercise the whole ABI: a
fungible token (`contracts/token.rs` — mint, transfer, allowances via
`approve`/`transfer_from`, `transfer`/`approve` events; `transfer_from` *traps* on an
insufficient allowance or balance so composing contracts observe the failure), a
self-contained constant-product AMM (`contracts/amm.rs`), a token-backed AMM pair
(`contracts/pair.rs`) that pulls and pays two real tokens via `call_contract` along
`x*y=k`, and a fair-launch launchpad (`contracts/launchpad.rs`) that sells the token it
holds for attached native coin, reverting when it cannot deliver so the buyer's coin
only moves when tokens do. Rounding out the suite, an **agent wallet**
(`contracts/agent_wallet.rs`) is account abstraction for autonomous agents: a contract
account whose owner drives arbitrary calls through it (`exec` — the wallet is the
callee's caller, so it owns tokens and positions), and can grant **session keys** —
other addresses allowed to move at most a capped amount of one token out of the wallet,
decremented per spend and revocable at any time. An AI agent operates with its own key
inside a hard budget; it never holds the treasury's keys.

**Cross-contract calls.** `call_contract(addr, input) -> output | -1` lets a contract
drive another (a callee sees the calling *contract* as its caller). Each call frame runs
against its own store overlay, flushed into the parent frame only on success — so a
failed sub-call leaves no trace, and a caller that reverts after a successful sub-call
discards the sub-call's writes with its own: nested state is atomic with the top-level
call. Gas is shared across frames (true forwarded gas — a sub-call cannot resurrect a
spent budget), call depth is bounded (8), and reentrancy is refused outright — a callee
already on the call stack returns failure to the caller instead of executing, closing
the classic DeFi exploit class by construction. Logs from sub-frames survive only if
every enclosing frame succeeds, each stamped with its emitting contract.

**Event logs.** `emit_log(topic, data)` records an event during a call — the channel
autonomous agents watch to react to on-chain state. Logs are gas-metered, kept only when
the call succeeds, and never read back by contract code, so they carry no consensus weight
beyond the gas paid. The processor collects each block's logs (dropping them exactly on a
reorg, like contract state), and the node exposes them three ways: `GET /logs?height=N`
(one block), a height-cursor scan `GET /logs?fromHeight=N` whose `toHeight` is the next
cursor, and **live push over Server-Sent Events** at `GET /logs/stream` — one heartbeat
comment per applied block (a natural keepalive at the 5-second cadence, whatever path
the block arrived by: submit, gossip, sync or local production) and one `data:` event
per log, with the block height as the SSE event id. A subscriber that cannot keep up is
disconnected rather than buffered without bound, and resumes exactly where it left off
via the `fromHeight` cursor — push for liveness, cursor for correctness.

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

### 5.5 Data boxes

Contract key/value storage is anonymous and untyped — good for a contract's private
bookkeeping, poor as a place for an **autonomous agent to keep information other parties
can find, read and prove**. Rhizome adds a **data box**: a first-class, addressable state
object, inspired by Ergo's extended-UTXO box but adapted to this chain's account model and
WASM contracts rather than replacing them. It is the substrate for agent memory,
directories, and oracles.

**The object.** A box has a **stable 32-byte id** = `SHA-256(creator ‖ nonce ‖ "rzbox")`
— derived once from the creating account and its nonce (the domain suffix keeps it
disjoint from a contract address), so an agent references "its memory" or "the oracle box"
permanently. It carries an **owner** (an account or a contract), a **value** in base units
locked into the box, its `createdHeight` and `rentPaidHeight`, and up to **six typed
registers**: `BYTES`, `I64`, `BOOL`, `ADDRESS` (25-byte reference), `HASH32` (content hash
of an off-chain blob), and `STRING` (UTF-8). The protocol validates each register's shape
against its tag but attaches no meaning to the value — tags are annotations for readers
(agents, indexers, wallets). A box serializes to at most **64 KiB**.

This is deliberately **not** Ergo's design in two ways, both because Rhizome's constraints
differ. First, the id is **stable and the content mutable**, where an Ergo box is
content-addressed (its id changes on every update, forcing the ecosystem into a singleton-
NFT pattern to give an oracle a durable identity); with no authenticated-state leaf to
commit yet, Rhizome has nothing to gain from content-addressing and everything to gain from
a permanent handle. Second, a box is guarded by its **owner** — an account signature, or the
approval of the controlling contract — not by a script language: Rhizome already has a
Turing-complete guard (WASM), so Ergo's "self-replicating box" becomes simply "a box owned
by a contract," whose transition rules the contract enforces. The 64-KiB ceiling (versus
Ergo's 4 KiB) follows from the same reasoning: Ergo's limit bounds the size of its AVL+
inclusion proofs (the box *is* the leaf) and of script execution contexts, neither of which
applies here — the real bounds are the transaction wire cap (128 KiB) and the 4-MiB block.
Larger objects use a `HASH32` register over off-chain data, or chunk across boxes.

**Lifecycle.** Four transaction kinds operate on boxes, all deterministic (no VM, no gas —
paid by the ordinary fee):

- `BOX_CREATE` locks `value` into a new box at the derived id. The value **leaves the
  ledger** into the box (the chain's total money is now account balances *plus* box values).
- `BOX_UPDATE` (owner only) replaces the registers, optionally tops up the value, and resets
  the rent clock — touching a box keeps it alive.
- `BOX_SPEND` (owner only) destroys the box and returns its value to the owner.
- `BOX_COLLECT` charges storage rent (below).

**Anti-dust.** A box must lock at least `size × minValuePerByte`, so writing data on-chain
costs in proportion to the state it occupies from the moment of creation — essential when
the writers are programs. The locked value is a **refundable deposit**, returned on spend,
not a fee.

**Storage rent.** After `storagePeriodBlocks` a box becomes collectable. Anyone — in
practice the miner — may then charge rent of `storageFeeFactor × size`, recreating the box
with reduced value, its registers, owner and id preserved and its rent clock reset. If the
charge would drop the value below the dust floor, the whole box is collected and destroyed
instead: **abandoned state is garbage-collected economically**, and the collected rent
gives miners a revenue stream that outlives the block subsidy. A box funded at the minimum
is recycled after roughly one storage period. Rent collection is an *opportunity*, never an
obligation; a block without it is valid.

`BOX_COLLECT` is **unsigned and self-authorized**, like the coinbase: the block producer
mints it, crediting the rent to the miner, so no private key is needed to run the
collector. It is bounded per block (`maxBoxCollectsPerBlock`) and drawn from a
rent-ordered **expiry index**, so selecting collectable boxes is O(1) per box rather than a
scan. Because a collect only ever touches an already-expired box and credits a named
recipient, an unsigned, permissionless collect can neither steal nor forge.

**Data inputs.** A contract reads any box through a `box_read(id)` host call — the box is
**not consumed** (Ergo's data-input idea) and reads see boxes written earlier in the same
block. This is the contention-free oracle pattern: an oracle agent updates its box each
tick; any number of consumer contracts read it in the same block without racing for it, at
a flat cost, with no cross-contract call.

**Persistence and reorg.** Boxes live in RocksDB with owner and rent-expiry secondary
indexes and a **persisted per-block undo journal** — unlike the contract store's in-memory
journals, so box state is exactly restorable on a reorg even after a restart. A box op's
ledger effects (value locked or released) are reversed from per-block receipts, so a popped
block rewinds both box state and balances exactly, atomically with the block.

**Observability and tooling.** Box lifecycle events (`box.created`/`updated`/`spent`/
`collected`) ride the same feed as contract logs — `GET /logs` and the live SSE stream — so
an agent watches state changes with the machinery it already uses. `GET /box?id=` reads a
box and `GET /boxes?owner=` lists an account's boxes. For richer queries, a **declarative
scan** (EIP-1 style) registers a composable predicate over the owner and registers
(`equals`/`contains`, combined with `and`/`or`) at `POST /scan/register`, then
`GET /scan/boxes` returns the matching boxes in bounded, cursor-paged windows — using the
owner index when the predicate is owner-anchored, a full-table page otherwise; scans are
node-local, not consensus. Contract state is queryable without a transaction via a
**read-only dry run**, `POST /call_readonly`, which runs a `CALL` against committed state
and discards every write. The wallet gains `box-create`/`update`/`spend`/`show`/`list` and
`call-readonly`. Together with the agent wallet (§5.4), an agent on Rhizome has identity,
funds, **persistent addressable memory**, and real-time observability — the full on-chain
loop.

Boxes are committed in the **authenticated state root** (§5.7), so a light client can prove
a box against a block header without trusting a node.

### 5.6 Native tokens

A token launch should cost one transaction, not a contract deploy. Rhizome supports
fungible tokens two ways: as WASM contracts (`token.rs`, §5.4 — for tokens that need custom
logic) and, for the common case, as a **protocol-level native asset** with no contract and
no gas. This follows Ergo's native-token idea, but in Rhizome's account model rather than
Ergo's boxes: a token is an account-based balance map, not a value carried inside UTXOs —
which fits a chain whose ledger is already account-based and whose boxes are single-owner
cells, not multi-input/output UTXOs.

**The asset.** A token has a **unique 32-byte id** = `SHA-256(minter ‖ nonce ‖ "rztoken")`
(unforgeable and non-repeating, like a box id or contract address), a minter, a symbol and
name, decimals, and a current total supply. Balances are a per-`(token, address)` map held
alongside the native PDN ledger — not inside boxes.

**Lifecycle.** Three deterministic transaction kinds, gas-free, paid by the ordinary fee:

- `TOKEN_MINT` creates a token — deriving its id from the minter and nonce, recording its
  metadata, and crediting the whole initial supply to the recipient. There is no way to mint
  the same id twice (the nonce never repeats), so supply is fixed at issuance unless the
  token is later burned.
- `TOKEN_TRANSFER` moves an amount of a token from the sender to `to`, checked against the
  sender's token balance.
- `TOKEN_BURN` destroys the sender's tokens, reducing total supply.

A token op moves **no PDN** — the token amount lives in the payload and the PDN `amount`
field must be zero; only the fee moves, to the miner. Balances live in the token store with
a **persisted per-block undo journal**, so a reorg reverses token state (and the fee)
exactly, atomically with the block — the same guarantee boxes and contracts get. Minter and
holder indexes back the queries `GET /token?id=` (metadata + supply), `GET /token_balance`,
and `GET /tokens?minter=`/`?holder=`; token lifecycle events (`token.minted`/`transferred`/
`burned`) join the log/SSE feed; and the wallet gains `token-mint`/`transfer`/`burn`/`show`/
`balance`/`list`. Reducing a memecoin launch to a single ~fee-priced transaction is the
"cheap token launches" goal, met without giving up the composability of contract tokens for
the cases that need it.

### 5.7 Authenticated state root

Every block header commits a 32-byte **state root** — an authenticated commitment to the
whole value state (the native ledger, all data boxes, and all token metadata and balances)
— so a **light client can prove any single state entry against a header** it trusts,
without holding or trusting a full node. This is the property Ergo buys with its AVL+ tree;
Rhizome uses a **sparse Merkle tree** keyed by `H(domain ‖ rawKey)`, whose leaf commits
`H(value)`. Nodes are **content-addressed** (a node is keyed by its own hash), which makes
them immutable and dedup naturally, and — the useful part — leaves every old root
resolvable, so reorg reversal is journal-free: reverting a block just moves the current root
back to the previous block's (kept per height in a small store).

**Determinism.** The root is a function of the binding *set* alone — independent of the
order changes are applied within a block — because a leaf commits its full key and sits at
its shortest unique prefix (proven by an order-independence test over shuffled inserts).
That means the node collecting a block's ledger/box/token changes need not canonicalise
their order; any node re-deriving the block arrives at the same root.

**Production and validation.** A block a node produces is applied to compute the resulting
root, which is stamped into the header *before* the proof-of-work is solved, so the PoW
binds it (the header hash commits the state root when set — a stateless block, produced
without the accumulator, hashes exactly as before). Every receiving node re-derives the root
by applying the block and **rejects a header whose committed root doesn't match**, rolling
the block back fully (ledger, box, token and accumulator state) — a state-root mismatch is a
first-class block-invalidity, exercised by a tamper test. On a reorg the root rewinds with
the block.

**Light-client proofs.** `GET /state` returns the current root; `GET /state/proof?domain=…&
key=…` returns the value hash and the sibling hashes along the path. A client re-derives the
SMT key from `(domain, rawKey)` and folds the siblings to check it against a root it already
trusts — the stateless verifier is a few lines and needs nothing else. A genesis seed commits
the snapshot balances so block 2 builds on them.

Committed today: **the full state** — ledger balances, **account nonces**, boxes, token
metadata/balances, and **contract code and key/value storage** — so a light client can prove
any of them against a header (`GET /state/proof?domain=…` covers all seven domains). The
account-nonce domain (§6.5) was added so a snapshot-bootstrapped node obtains replay-protection
state **verifiably** rather than on trust — the one deliberate change it makes to every root,
free while the chain is pre-launch. This root is what makes **snapshot bootstrap**
trust-minimised: a new node rebuilds a recent state tree and accepts it only when it reproduces
the root committed in a proof-of-work-validated header (§6.4).

### 5.8 Miner-voted parameters

The box economic parameters are not frozen: miners **vote** to adjust them within bounds,
without a hard fork. Each block header carries an `int` **vote** (committed in the header hash
only when cast, so an abstaining block is unchanged): `±1` for the storage-rent factor, `±2`
for the anti-dust `minValuePerByte`. At each **voting-epoch** boundary the engine tallies that
epoch's votes and moves a parameter one bounded step when the net vote exceeds half the epoch
— the change taking effect the next epoch. The current values are **derived from chain
history** (like difficulty): kept as a per-epoch-boundary snapshot recomputed from the epoch's
block votes, so a reorg across a boundary simply drops that snapshot and restores the previous
values — reorg-safe with no reversible tally. The box processor reads the live values at
execution time, so every node validates a block with the parameters in force at its height;
`GET /info` reports them, and a miner sets its vote with `RHIZOME_VOTE`. Only economic
parameters are votable (not supply or the PoW) — governance over fees, not money.

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
- **Headers-first sync** — initial synchronisation validates the header chain before
  downloading any body (`/headers`), which turns the anti-DoS work gate from a full-block
  download into a ~150 B/header one; pruned nodes and snapshot bootstrap build on the same
  machinery (`/state/snapshot/*`). See §6.4.

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
  that makes a fast block cadence realistic under load.

GraalVM native compilation is a standing design constraint (no runtime codegen; JNI for
RocksDB declared; reflection kept to compile-time Lombok plus registrable `org.json`),
even though `native-image` is not installed in the current development environment.

### 6.3 Block propagation and the block-time floor

At a fast cadence, **propagation latency — not CPU — is the binding constraint**.
Verification is fast (§6.2), but a block must reach most of a global network before the
next is found, or the two collide into an orphan. Orphans waste work, raise the effective
reorg rate, and favour the best-connected miners (centralisation). The floor is physical:
even an optimal relay needs roughly a few hundred milliseconds to a couple of seconds to
cover a worldwide peer graph. This is precisely why the mainnet target is **5 seconds**
rather than 1: at ~200 ms propagation the expected orphan rate is ≈4% (versus ≈18% at a
1-second target), and GHOST (§9) credits what little is orphaned.

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
than pushing a single longest chain below its own propagation latency. Rhizome applies
that lesson twice over: the 5-second interval keeps propagation a small fraction of the
cadence, and GHOST (§9) credits and rewards whatever is still orphaned. Dropping toward
1 s remains possible later, contingent on the relay upgrades above.

### 6.4 Headers-first sync, pruned nodes, and snapshot bootstrap

Initial sync happens in two phases: **validate the header chain first, download bodies
second**. A block header carries its whole proof-of-work preimage plus its uncle
references, so a `BlockHeader` verifies its own PoW without the body — headers are the
canonical hash carrier, and `BlockImpl.hash()` delegates to them, so the two can never
diverge. Nodes serve `GET /headers?start=&end=` (a self-framing binary stream, ~150 B per
header versus up to 4 MiB for a full block).

**The state machine.** The synchroniser (`HeaderSynchronizer`, subsuming the older
block-based one) finds the common ancestor on headers, then downloads the contested range's
headers and validates them **statelessly** (`HeaderChain`): id continuity, hash chaining,
per-header PoW, the difficulty recomputed from header timestamps, median-time-past, the
future bound, and structural uncle limits. It returns the branch's **base-only work** —
each header's `2^difficulty`, deliberately *not* its committed uncle difficulties. The
uncle references are checked structurally but their claimed work is excluded from the gate:
a header's uncle *eligibility* (a real, recent, uncredited orphan) cannot be confirmed from
headers alone, so counting it would let a cheap branch inflate its apparent work with
in-range fake uncle references and force a pop/restore. Only when the base-only proven work
strictly exceeds the local branch (measured the same way — §3.7) does it enter body sync:
fetch bodies in batches, **verify each against its already-validated header** (hash equality)
before execution, with the same restore-on-failure and orphan-registration a reorg always
had; genuine uncle (GHOST) work then folds into the authoritative chain weight as each body
proves its uncles eligible.

The payoff is the anti-DoS gate. Previously a peer that merely *claimed* huge total work
cost us a full-block download before we could refute it; now it costs a bounded **header**
download (capped per round), after which the work gate refuses it without a single body
fetched. A peer that predates `/headers` (a 404) transparently falls back to the old
full-block path — the change is additive, no wire format changed.

For this to work the engine had to stop reading historical **bodies** for its derived state.
Difficulty, median-time, uncle work and vote tallies now read only `headerAt(h)`; account
nonces — the one piece of derived state at transaction granularity — are **persisted** in a
`nonces` column family, advanced in lockstep with the chain and watermarked by height, so a
restart rebuilds derived state in `O(headers)` and never re-reads a body (§6.5).

**Pruned nodes.** With the body dependency gone, a node configured with `RHIZOME_PRUNE=N`
keeps only the most recent `N` block bodies (plus genesis, all headers, and the transaction
index), discarding each body as it falls out of the window — an amortised `O(1)` delete in
the same write batch as the append. `N` is floored at boot to at least the deepest history
the engine can read (reorg window, uncle depth, difficulty and median-time windows) plus a
margin, so pruning can never remove a body a reorg still needs. A pruned node mines,
validates, serves every header and its recent bodies, and restarts correctly; `/sync` for a
discarded range answers **410 Gone** with the prune watermark (advertised on `/info`), and
the synchroniser routes deep body requests to an archive peer instead of penalising the
pruned one.

**Snapshot bootstrap (snap-sync).** A brand-new node can skip replaying history entirely.
Its trust reduces to exactly what full validation gives, in four steps (the model is Ergo's
bootstrap §8, adapted to a sparse-Merkle root):

1. **Genesis is built locally** from the network parameters and balance snapshot — chain
   identity is never taken from a peer.
2. **The header chain** from genesis up to the pivot's finality depth is validated under
   full PoW, so the state root committed in the pivot header carries the chain's accumulated
   work. Validation is **incremental, in bounded windows** — each window is checked chaining
   from the already-validated prefix — and stops at `pivot + maxReorgDepth`, never the peer's
   untrusted advertised height. A peer serving cheap invalid headers is therefore rejected
   after a single window rather than by first buffering its whole advertised span (an
   allocation a hostile seed could otherwise turn into an OOM before any check ran).
3. The node picks a **pivot** buried at least `maxReorgDepth` under the peer tip. Because
   every node refuses reorgs deeper than that (§3.7), the imported state can never be forced
   to unwind — no "de-import" path is ever needed.
4. It downloads the state **snapshot** at the pivot and rebuilds the sparse-Merkle tree from
   it, accepting the state **only when it reproduces the pivot header's committed root**.
   Any tampered, dropped or duplicated entry changes the rebuilt root and the whole import is
   refused with the stores untouched.

The snapshot itself is a flat per-domain dump — for each of the seven state domains, a paged
run of `(rawKey, value)` chunks. Unlike Ergo's AVL+ manifest-and-subtree scheme, **it
carries no tree structure**: the sparse-Merkle root is a function of the binding *set* alone
(§5.7), so the importer can insert chunks in any order and the single final root equality
verifies the lot. Servers **materialise** a consistent snapshot periodically
(`RHIZOME_SNAPSHOT_EVERY` blocks) under a point-in-time lock and advertise
`(pivotHeight, stateRoot, chunkCount)` on `GET /state/snapshot/info`, serving chunks by index.
Secondary indexes (box owner/expiry, token minter/holder) are **recomputed locally** from the
verified values, never transferred — an untrusted index cannot be smuggled in. After import
the node holds genesis, headers to the pivot (body-less, marked pruned), and the full pivot
state; ordinary headers-first sync then pulls only the body suffix above the pivot. A
snap-synced node is a first-class citizen from the first block: it validates, serves, and
proves state entries against the root like any other.

### 6.5 Account nonces as authenticated state

Sequential per-account nonces are the replay-protection rule, and they are the only derived
state computed at *transaction* granularity rather than from headers. Two needs converge on
persisting them. **Pruning:** without historical bodies a node cannot reconstruct nonces at
boot, so they live in a persistent store, updated on every add and pop and watermarked by the
height through which they are current — a persistent store reports the tip, so a restart (even
a pruned node's, even a chain that has never seen an account transaction) reads no body;
reconstructing nonces from bodies that lie below the prune watermark is refused loudly rather
than silently under-counting. **Snapshot bootstrap:** a bootstrapped node must obtain nonces
*verifiably*, or it could not validate the next block's nonce rule. So the account nonce is a
committed state domain (`ACCOUNT_NONCE`, key = address, value = next nonce): each sender's
`max(txNonce)+1` over the block is folded into the state root, derived from block content
(sequentiality already checked) so producer and validators agree. This is the chantier's one
consensus change — it alters every state root, hence must land while the chain is pre-launch;
after launch the same change would need a height-activated fork.

---

## 7. Security model

Defended vectors: double-spend (in-block / already-executed, via the signature-free
content hash), signature malleability, sender spoofing, cross-chain and nonce replay,
inflated reward, **negative amounts** (money minting), **balance overflow**, timestamp
and difficulty (time-warp) manipulation, deep reorg (finality), lying peer (claimed but
unproven work), transaction-layer censorship, block/request flooding, and network DoS
(bounded bodies, ban-score). This section is the consolidated security reference; the
history below records five successive review passes and the invariants they established.

### 7.1 Load-bearing invariants (must never regress)

- **No value moves without the owner's key.** Identity is the **signature-free**
  `hashContents` (Ed25519-malleability-immune); `PublicAddress.of(signingKey) == from` is
  bound at both mempool admission and consensus; `chainId` + a strictly-increasing account
  nonce sit in the signed preimage (cross-network + replay protection). The one
  self-authorised transaction, permissionless `BOX_COLLECT`, is pinned to `from == empty`,
  `fee == 0`, `amount == 0` and is block-minted only, so it can never name a funded sender.
- **Validity is a pure function of balance, never of ledger key-presence.** An absent
  wallet reads as balance 0 on every path, so a 0-balance "phantom" key left by any
  apply-then-rollback cannot make a zero-cost transaction valid on one node and invalid on
  another (the state root already treats a 0 balance as absent).
- **Checked ledger arithmetic.** Every credit uses `Math.addExact`, every debit guards
  `>= 0`; negative amount/fee is rejected at both admission and consensus; a failed
  transaction rolls the block back exactly (apply/rollback are exact inverses for
  transfers, contracts, boxes, tokens, and scaled GHOST uncle rewards).
- **Integer-only determinism.** The mining reward is an integer table by height; difficulty
  is a pure function of stored header timestamps (recomputed after every add *and* pop,
  never read from a field); GHOST uncle rewards scale by `>>>` shifts. No consensus quantity
  is ever a floating-point comparison.
- **Merkle second-preimage** is neutralised by 0x00/0x01 domain separation, a committed
  `numTransactions`, and the Executor's load-bearing in-block content-hash dedup
  (CVE-2012-2459 shape).
- **Authenticated state root.** A sparse-Merkle commitment to the full state (ledger, boxes,
  tokens, contract code/storage, account nonces) is in every header, stamped by the producer
  and revalidated by every node with full rollback on mismatch; the root is order-independent
  (a function of the binding *set*), so it can never fork on map iteration order.

### 7.2 Determinism and fork resistance

The largest residual fork risk in a metered-VM chain is **node-local nondeterminism**, so
the VM is deterministic by construction: scalar float, `V128`, and vector-float lanes are
rejected at deploy; gas is integer/saturating; the WASM call-depth cap, per-instance and
**tree-wide** linear-memory caps, table count/aggregate caps and locals cap are fixed
network constants (not `-Xmx`/`-Xss`-dependent), reserved *before* the runtime allocates;
`OutOfMemoryError`/`StackOverflowError` normalise to a deterministic full-gas out-of-gas.
Gas that feeds the fee — and hence the state root — is charged identically on every node:
notably the module-parse cost is levied on **every** call, cache hit or miss, so a warm/cold
module cache cannot change `gasUsed`. Call context (`caller`, `self`, `value`, `deployer`)
is host-supplied per frame and unspoofable; the reentrancy guard and per-frame atomic
overlay make a rewritten block leave no contract residue.

### 7.3 Resource-exhaustion bounds

Every unbounded surface is capped. **Memory:** bounded collections throughout (orphan pool,
mempool, module cache, Pufferfish cache, rate-limiter, peer/ban tables), decode-time bounds
on all counts/lengths, and windowed (not whole-span) streaming for `/sync`, `/headers` and
snapshot bootstrap. **CPU / event loop:** the memory-hard PoW is verified **last** (block's
own PoW before the memory-hard uncle checks); and, because the single event-loop thread runs
synchronous work under the consensus lock, three **aggregate** (all-IP) token-bucket gates
sit above the per-IP rate limiter, each shedding with HTTP 429 before doing the work — one
bounding submit-triggered PoW hashes, one bounding `/call_readonly` VM gas, one bounding the
explorer reads that decode blocks under the lock. **Transaction layer:** a per-sender cap
plus eviction of *fully-parked* (nonce-gapped, never-minable) transactions in favour of ready
or higher-fee ones, so the pool cannot be cheaply and permanently stuffed to censor honest
traffic. **Storage** growth is gas-priced and reorg-reversible via the persistent undo journal.

### 7.4 Network

The node binds `0.0.0.0` with an unauthenticated `/add_peer`, so peer handling is
secure-by-default: added peers must resolve to routable IPs (SSRF/rebinding filter on by
default, opt-out only), the connect target is re-pinned to the validated literal on every
send, redirects are refused, PEX is capped, subnet-bucketed and ban-scored (Sybil/eclipse),
and a browser state-changing POST must be same-origin *and* carry a non-simple
`X-Rhizome-Request` header the origin's own dashboard sets but a cross-site/DNS-rebinding
page cannot (CSRF). BouncyCastle is pinned to a patched release. Snapshot bootstrap is
pinned to a PoW-validated pivot root and rebuilds secondary indexes locally, so no untrusted
state or index is ever adopted.

### 7.5 Contract sandbox

Untrusted WASM is sandboxed by the runtime (no I/O beyond the host ABI) and bounded by gas on
compute (per instruction), memory, and storage, so it can neither hang a node nor escape; a
variable-length payload cannot bloat a block past `maxBlockSizeBytes`; and a reorg reverses
contract state and contract-transaction ledger effects exactly. The bundled templates
(token, AMM, launchpad, agent wallet…) are hardened too: deployer-bound `init` (no mempool
front-run), swap slippage floors, real LP-share accounting, native `transfer_value` for
recoverable proceeds, and `checked_*` arithmetic throughout.

### 7.6 Residual

The irreducible residual is the **51% attack**: an adversary with a sustained majority of
hash power can reorg within the finality window, which bounds only its depth — nothing in any
PoW chain removes it entirely. **Coinbase maturity** (a UTXO concept) does not apply to the
balance-based ledger — the reward is immediately fungible with no distinct coinbase coin to
mature — and the orphaned-reward risk is already covered by the finality window.

Five parallel-subsystem review passes hardened this model: from the first (negative-amount
minting, deposit-overflow rollback) through consensus-fork classes (unscaled uncle-reward
rollback, heap-dependent VM OOM, cache-dependent gas, phantom-wallet validity, reorg-gate
work-metric mismatch), theft and liveness (unsigned `BOX_COLLECT` drain, mempool-poisoning
and nonce-gap censorship), and DoS amplifiers (submit/readonly/read aggregate gates). Every
fix carries a regression test; a dependency bump is validated by the same suite (one caught a
silent CSRF-guard fail-open from a library header-lookup change).

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
cap); five parallel-subsystem security-review passes (§7); the **WASM smart-contract layer** — a Chicory-backed
metered VM, a persistent contract store, `DEPLOY`/`CALL` transactions with gas fees,
atomic per-block contract state with exact reorg reversal, and wallet `deploy`/`call`
commands; and the **data-box layer** (§5.5) — stable-id, typed-register storage objects
with an anti-dust deposit, permissionless miner-collected storage rent, read-only
`box_read` data inputs for contracts, a persisted per-block undo journal for exact reorg
reversal, owner/expiry indexes, `GET /box`+`/boxes`, box lifecycle events on the log/SSE
feed, and wallet `box-*` commands; **native tokens** (§5.6) — `TOKEN_MINT`/`TRANSFER`/`BURN`
with unique ids, minter/holder indexes, reorg-safe balances, `GET /token(s)` and wallet
`token-*` commands; the **authenticated state root** (§5.7) — a sparse-Merkle commitment
to the full state (ledger, boxes, tokens and contract code/storage) in every header, stamped
by the producer and validated by every node with full rollback on mismatch, reorg-safe, with
`GET /state`+`/state/proof` and a stateless light-client verifier; and **miner-voted
parameters** (§5.8) — a header vote, per-epoch tally and reorg-safe derived params for the box
fee/dust factors; and **headers-first sync, pruning and snapshot bootstrap** (§6.4) — a
first-class `BlockHeader`/`HeaderCodec`, a stateless `HeaderChain` validator, a
`HeaderSynchronizer` whose work gate refuses a lying peer on headers alone, persisted account
nonces plus an `ACCOUNT_NONCE` state domain (§6.5) so the engine's derived state is header-only,
configurable body pruning (`RHIZOME_PRUNE`) with a `/sync` 410 and a `prunedBelow` advert, and
trust-minimised snap-sync (`RHIZOME_SYNC=snap`) — periodic per-domain snapshot materialisation,
`/state/snapshot/*`, and a bootstrap that adopts a peer's state at a buried pivot only when it
reproduces a PoW-validated header's root. **401 tests, 0 failures.**

**GHOST fork choice.** A fast single longest chain orphans blocks because propagation
takes a meaningful fraction of the interval (§6.3). A GHOST-style fork choice — the
heaviest *subtree*, crediting the work of referenced orphan (uncle) blocks — is what
makes the 5-second target safe (and a future drop toward 1 s possible) without the
centralisation and reorg churn a naïve longest chain suffers. It is implemented end to
end:

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
- **Rewards.** An included uncle pays its miner a fraction of the block reward
  (`uncleRewardNum/uncleRewardDen`, default 1/2) and the nephew a bonus per uncle
  (`miningReward/nephewRewardDivisor`, default 1/32), each **scaled to the uncle's proven
  work**: the base amount is halved once for every bit the uncle's difficulty falls short
  of the including (nephew) block's difficulty (`base × 2^(uncleDifficulty − nephewDifficulty)`,
  an exact integer shift). An uncle mined at the chain's contemporaneous difficulty earns
  the full fraction; a much easier orphan earns essentially nothing. This keeps issuance
  matched to work — a *flat* reward would let a miner staple cheap minimum-difficulty
  orphans onto a real high-difficulty block and roughly double emission for negligible
  hashing — while remaining deterministic and derivable from the committed uncle and
  nephew difficulties alone, so reorg reversal is exact. Both are fresh issuance, but every
  uncle is a real proof-of-work block and its reward is proportional to that work, so
  nothing is minted without matching work.

**Autonomous-agent primitives.** Contract event logs are implemented (see §5):
contracts `emit_log`, the processor collects them per block reorg-safely, and the node
serves them by height, by a pollable height cursor, and by live SSE push at
`/logs/stream` (§5.4) — so agents follow on-chain state in real time. **Persistent
addressable memory** is covered by data boxes (§5.5): typed-register storage objects an
agent creates, updates and proves, read contention-free by any contract via `box_read`,
kept honest by an anti-dust deposit and storage rent that recycles the memory of dead
agents. Account abstraction is covered by the agent wallet (§5.4): contract accounts with
owner-driven `exec` and revocable, per-token-capped session keys — and any of that state is
provable to a light client against the authenticated root (§5.7), which a snap-synced or
pruned agent node can rely on without holding history (§6.4). The remaining protocol-level
work is gas sponsorship (a third party paying a transaction's gas).

**Memecoin primitives.** Native tokens (§5.6) make a fungible-asset launch a single
gas-free transaction — `TOKEN_MINT`/`TRANSFER`/`BURN`, unique unforgeable ids, minter and
holder indexes, reorg-safe balances — for the common case that needs no custom logic. Token
balances and metadata are committed in the authenticated state root (§5.7), so a light
client can prove a holding against a header. For the cases that need custom logic, three
reference contracts are implemented and tested through consensus: a fungible token (mint,
transfer, `transfer` logs), a constant-product AMM
(`x*y=k` with a 0.3% fee, `swap` logs, exact integer math verified against the same
formula in the test), and a fair-launch launchpad — a fixed-price, first-come sale where
a buyer's attached native coin only moves when the tokens are delivered (the launchpad
checks its own token balance through a `call_contract` output round-trip and reverts
otherwise). Composition rests on `call_contract` (§5): per-frame savepoints, forwarded
gas, bounded depth and reentrancy refusal, additionally proven by a router contract
driving the token through consensus. The token-backed AMM pair completes the suite: the
token grew allowances (`approve`/`transfer_from`), and the pair pulls the input leg and
pays the output leg on two real token contracts atomically — an unauthorised swap
unwinds both legs.

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
- Design lineage: [Ergo](https://github.com/ergoplatform/ergo) — the extended-UTXO
  **box** model (adapted to account-based **data boxes**, §5.5), the **authenticated
  state root** with light-client proofs (an AVL+ tree there, a sparse Merkle tree here,
  §5.7), and the **buried-pivot snapshot bootstrap** (Ergo's §8 bootstrapping, adapted to
  an order-independent sparse-Merkle root with flat per-domain dumps rather than an AVL+
  manifest, §6.4). The EIP-1 wallet **scan** predicate model informs `/scan` (§5.5).
