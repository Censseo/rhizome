# Data Boxes Specification

> Source of truth for data boxes — addressable on-chain state objects with typed registers, an
> anti-dust deposit, and storage rent.
> Extracted from `WHITEPAPER.md` §5.5, §5.8 and source analysis.
> **Status**: Draft — needs review

## Overview

Contract key/value storage is anonymous and untyped — good for a contract's private bookkeeping,
poor as a place for an **autonomous agent to keep information other parties can find, read and
prove**. A **data box** is a first-class, addressable state object: inspired by Ergo's extended-UTXO
box but adapted to this chain's account model and WASM contracts rather than replacing them. It is
the substrate for agent memory, directories, and oracles.

Boxes are gated behind a `/features` flag, so a node built without the layer keeps the dashboard
page dormant, and behind `boxActivationHeight` at consensus.

## Scope

**Owns**

| Area | Source |
|---|---|
| Box object, registers, payload | [Box.java](../../lib-core/src/main/java/rhizome/core/box/Box.java), [BoxRegister.java](../../lib-core/src/main/java/rhizome/core/box/BoxRegister.java), [BoxRegisterType.java](../../lib-core/src/main/java/rhizome/core/box/BoxRegisterType.java), [BoxPayload.java](../../lib-core/src/main/java/rhizome/core/box/BoxPayload.java) |
| Lifecycle execution & rent | [BoxProcessor.java](../../lib-core/src/main/java/rhizome/core/box/BoxProcessor.java), [DefaultBoxProcessor.java](../../lib-core/src/main/java/rhizome/core/box/DefaultBoxProcessor.java) |
| Receipts (reorg reversal) | [BoxReceiptCodec.java](../../lib-core/src/main/java/rhizome/core/box/BoxReceiptCodec.java) |
| Store abstraction | [BoxStore.java](../../lib-core/src/main/java/rhizome/core/box/BoxStore.java), [InMemoryBoxStore.java](../../lib-core/src/main/java/rhizome/core/box/InMemoryBoxStore.java) |
| Declarative scan predicates | [ScanPredicate.java](../../lib-core/src/main/java/rhizome/core/box/ScanPredicate.java), [ScanRegistry.java](../../app-node/src/main/java/rhizome/node/ScanRegistry.java) |
| Persisted store + indexes | [RocksDbBoxStore.java](../../lib-persistence/src/main/java/rhizome/persistence/rocksdb/RocksDbBoxStore.java) |
| Contract-side read | `box_read` in [BoxReader.java](../../lib-vm/src/main/java/rhizome/vm/BoxReader.java) |
| HTTP surface | [BoxApi.java](../../app-node/src/main/java/rhizome/node/BoxApi.java) |

**Does not own**

- The `BOX_*` transaction envelope → [transactions](../transactions/spec.md)
- Miner voting on `storageFeeFactor` / `minValuePerByte` → [consensus](../consensus/spec.md) C-8
- The `BOX` state-root domain → [state](../state/spec.md)

## Features

### B-1 — The box object *(implemented)*

- **Stable 32-byte id** = `SHA-256(creator ‖ nonce ‖ "rzbox")` — derived once from the creating
  account and its nonce; the domain suffix keeps it disjoint from a contract address. An agent
  references "its memory" or "the oracle box" **permanently**.
- Carries an **owner** (an account *or* a contract), a **value** in base units locked into the box,
  `createdHeight`, `rentPaidHeight`, and up to `maxBoxRegisters` (**6**) typed registers.
- Register types: `BYTES`, `I64`, `BOOL`, `ADDRESS` (25-byte reference), `HASH32` (content hash of
  an off-chain blob), `STRING` (UTF-8). The protocol validates each register's **shape** against
  its tag but attaches **no meaning** to the value — tags are annotations for readers (agents,
  indexers, wallets).
- A box serialises to at most `maxBoxSizeBytes` = **64 KiB**.

**Deliberate divergence from Ergo**, twice:

1. The id is **stable and the content mutable**, where an Ergo box is content-addressed (its id
   changes on every update, forcing the ecosystem into a singleton-NFT pattern to give an oracle a
   durable identity). Rhizome gains nothing from content-addressing and everything from a permanent
   handle.
2. A box is guarded by its **owner** — an account signature, or the approval of the controlling
   contract — not by a script language. Rhizome already has a Turing-complete guard (WASM), so
   Ergo's "self-replicating box" becomes simply "a box owned by a contract", whose transition rules
   the contract enforces.

The 64-KiB ceiling (vs Ergo's 4 KiB) follows the same reasoning: Ergo's limit bounds its AVL+
inclusion proofs and script contexts, neither of which applies here — the real bounds are the
128-KiB transaction wire cap and the 4-MiB block. Larger objects use a `HASH32` register over
off-chain data, or chunk across boxes.

### B-2 — Lifecycle *(implemented)*

Four transaction kinds, all **deterministic — no VM, no gas**, paid by the ordinary fee:

| Kind | Effect |
|---|---|
| `BOX_CREATE` | locks `value` into a new box at the derived id. The value **leaves the ledger** into the box — the chain's total money is now account balances *plus* box values |
| `BOX_UPDATE` | *(owner only)* replaces the registers, optionally tops up the value, resets the rent clock — but re-arming pays the accrued rent first |
| `BOX_SPEND` | *(owner only)* destroys the box and returns its value to the owner, less accrued rent (capped at what the box holds) |
| `BOX_COLLECT` | charges storage rent (B-4) |

### B-3 — Anti-dust deposit *(implemented)*

A box must lock at least `size × minValuePerByte`, so writing data on-chain costs in proportion to
the state it occupies **from the moment of creation** — essential when the writers are programs. The
locked value is a **refundable deposit**, returned on spend, **not a fee**.

### B-4 — Storage rent *(implemented)*

Rent accrues at `storageFeeFactor × size` for every **full `storagePeriodBlocks` period** (6 307 200
blocks ≈ 1 year at 5 s) elapsed since the box's rent clock was last set, and is charged out of the
box's locked value **to the block miner** — never back to the owner — on **every** operation that
touches the box (`BOX_UPDATE`, `BOX_SPEND`, `BOX_COLLECT`), each settling all periods accrued so far.

That closes two loopholes:

- an active owner resetting the clock for free with one zero-value update per period (permanent
  on-chain storage for nothing);
- a deeply overdue box shedding its debt one period at a time.

After a storage period a box becomes **collectable**: a collect recreates it with reduced value —
registers, owner and id preserved, rent clock reset. If the charge would drop the value below the
dust floor, the whole box is collected and **destroyed** instead: abandoned state is
garbage-collected economically, and the collected rent gives miners a revenue stream that outlives
the block subsidy. A box funded at the minimum is recycled after roughly one storage period. Rent
collection is an **opportunity, never an obligation** — a block without it is valid.

### B-5 — Permissionless, unsigned `BOX_COLLECT` *(implemented)*

Unsigned and self-authorised, like the coinbase: the **block producer mints it**, crediting the rent
to the miner, so no private key is needed to run the collector. Bounded per block
(`maxBoxCollectsPerBlock` = 32) and drawn from a rent-ordered **expiry index**, so selecting
collectable boxes is O(1) per box rather than a scan. Because a collect only ever touches an
already-expired box and credits a named recipient, it can neither steal nor forge.

### B-6 — Data inputs for contracts *(implemented)*

A contract reads any box through `box_read(id)` — the box is **not consumed** (Ergo's data-input
idea) and reads see boxes written earlier in the same block. This is the **contention-free oracle
pattern**: an oracle agent updates its box each tick; any number of consumer contracts read it in
the same block without racing for it, at a flat cost, with no cross-contract call.

### B-7 — Persistence and exact reorg reversal *(implemented)*

Boxes live in RocksDB with **owner** and **rent-expiry** secondary indexes and a **persisted
per-block undo journal** — unlike the contract store's in-memory journals, so box state is exactly
restorable on a reorg *even after a restart*. A box op's ledger effects (value locked or released)
are reversed from persisted per-block receipts, so a popped block rewinds both box state and
balances exactly, atomically with the block.

### B-8 — Observability and queries *(implemented)*

- Lifecycle events `box.created` / `updated` / `spent` / `collected` ride the same feed as contract
  logs — `GET /logs` and the live SSE stream — so an agent watches state changes with machinery it
  already uses.
- `GET /box?id=` reads a box; `GET /boxes?owner=` lists an account's boxes.
- **Declarative scan** (EIP-1 style): register a composable predicate over the owner and registers
  (`equals`/`contains`, combined with `and`/`or`) at `POST /scan/register`, then `GET /scan/boxes`
  returns matches in bounded, cursor-paged windows — using the owner index when the predicate is
  owner-anchored, a full-table page otherwise. **Scans are node-local, not consensus.**
- Scan ids are CSPRNG-generated so an unauthenticated caller cannot enumerate and wipe another
  app's scans; per-client caps with LRU eviction bound the registry.
- Wallet gains `box-create` / `box-update` / `box-spend` / `box-show` / `box-list`.

### B-9 — Light-client provability *(implemented)*

Boxes are committed in the **authenticated state root** (domain `BOX = 0x02`), so a light client can
prove a box against a block header without trusting a node. See [state](../state/spec.md).

## Invariants (must never regress)

- Box state must move atomically with its block and reverse **exactly** on reorg — via the
  *persisted* undo journal and *persisted* receipts (restart-safe, unlike contract in-memory
  journals).
- `BOX_COLLECT` is pinned to `from == empty`, `fee == 0`, `amount == 0` and is block-minted only.
- The chain's total money is account balances **plus** box values — `BOX_CREATE` moves value *out*
  of the ledger.
- Rent is charged **to the miner**, never refunded to the owner, on every touch.
- `storageFeeFactor` and `minValuePerByte` are **floored at 1** by the vote (see
  [consensus](../consensus/spec.md) C-8) — a majority voting either to zero would make permanent
  on-chain storage free.
- The box processor reads the **live voted values at execution time**, so every node validates a
  block with the parameters in force at its height.
- A box transaction is refused at mempool admission below `boxActivationHeight`, symmetric with the
  executor's gate.
- `box_read` charges its gas **before** touching guest memory.

## Key parameters

| Parameter | Value |
|---|---|
| `maxBoxSizeBytes` | 65 536 |
| `maxBoxRegisters` | 6 |
| `minValuePerByte` | 1 *(votable, min 1, max 1 000)* |
| `storagePeriodBlocks` | 6 307 200 (~1 yr at 5 s) |
| `storageFeeFactor` | 1 *(votable, min 1, max 1 000)* |
| `maxBoxCollectsPerBlock` | 32 |
| `boxActivationHeight` | 0 |

## References

- `WHITEPAPER.md` §5.5 (data boxes), §5.8 (miner-voted parameters), §7.1 (`BOX_COLLECT` pinning),
  §9 (agent primitives), §10 (Ergo lineage, EIP-1 scan model)

