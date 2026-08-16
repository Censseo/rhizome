# Persistence Specification

> Source of truth for on-disk storage — RocksDB stores, atomic batches,
> undo journals, and crash reconciliation.
> Extracted from `WHITEPAPER.md` §6.2, §7.6 and source analysis.
> **Status**: Draft — needs review

## Overview

One durable backend implements the store interfaces declared in `lib-core`/`lib-vm`:

- **RocksDB** — the full-node path. Chain and ledger share **one database with column families**;
  append and pop are atomic `WriteBatch`es, so a crash never leaves half-written state. Chosen over
  iq80 LevelDB (pure-Java, slower) and LMDB for its write throughput and atomic batches.

The second real implementation of those ports is the `InMemory*` family in `lib-core`, which the
tests run against. (The `org.iq80.leveldb` dependency remains only for `PandaniteLedgerDumper`,
which iterates a *foreign* Pandanite ledger to seed genesis — it stores nothing.)

The load-bearing property is that **peripheral state moves atomically with the block and reverses
exactly on reorg**, including across a restart.

## Scope

**Owns**

| Area | Source |
|---|---|
| Chain + ledger + nonces + txindex (one DB) | [RocksDbNodeStore.java](../../lib-persistence/src/main/java/rhizome/persistence/rocksdb/RocksDbNodeStore.java) |
| Box store + owner/expiry indexes | [RocksDbBoxStore.java](../../lib-persistence/src/main/java/rhizome/persistence/rocksdb/RocksDbBoxStore.java) |
| Token store + minter/holder indexes | [RocksDbTokenStore.java](../../lib-persistence/src/main/java/rhizome/persistence/rocksdb/RocksDbTokenStore.java) |
| Contract store | [RocksDbContractStore.java](../../lib-persistence/src/main/java/rhizome/persistence/rocksdb/RocksDbContractStore.java) |
| SMT nodes + per-height roots | [RocksDbStateStore.java](../../lib-persistence/src/main/java/rhizome/persistence/rocksdb/RocksDbStateStore.java) |
| Pandanite ledger dump tool | [PandaniteLedgerDumper.java](../../lib-persistence/src/main/java/rhizome/persistence/tools/PandaniteLedgerDumper.java) |

**Does not own**

- The store *interfaces* (`ChainStore`, `NonceStore`, `ContractStore`, `BoxStore`, `TokenStore`,
  `SmtNodeStore`, `RootStore`) — those are declared in `lib-core` / `lib-vm` and owned by their
  functional domains.
- What gets journalled and why → the respective domain specs.

## Features

### D-1 — One database, column families, atomic append/pop *(implemented)*

`RocksDbNodeStore` holds a single database with column families:

| CF | Contents |
|---|---|
| `blocks` | block bodies |
| `headers` | block headers |
| `txindex` | transaction → height index |
| `ledger` | native PDN balances |
| `nonces` | per-account next nonce |
| `uncles` | uncle bookkeeping |
| `meta` | `height`, `prunedBelow`, `nonceHeight`, `bootstrapInProgress`, `headersBackfilledThrough` |

Append and pop are atomic `WriteBatch`es. A block's **ledger** writes ride that same batch — they
buffer in a **read-your-writes overlay** during execution and flush with the block and height — so
the ledger can never end a block ahead of, or behind, the chain height.

### D-2 — Peripheral stores and boot reconciliation *(implemented)*

Boxes, tokens, contracts and the sparse-Merkle root are **separate databases**, committed just
before the height. If a process crash lands between them and the height, **boot reconciliation**
rewinds any store found ahead of the chain height back down to it via its per-block undo journal
(bounded by the reorg window), so the node comes up at **one consistent height** rather than wedging
on the next block's state-root check.

### D-3 — Persisted undo journals and receipts *(implemented)*

Each peripheral store keeps a **per-block undo journal** (each written key's prior value) and, for
boxes and contracts, **persisted receipts** — so a reorg *after a restart* reverses cleanly instead
of crashing mid-rollback.

Each store commits its own mutations **and** its undo journal in **one atomic `WriteBatch`**, with
**double-apply journal guards**.

The SMT is the exception: content-addressed nodes make every historical root resolvable, so state
reversal is journal-free — reverting a block moves the current root back to the previous block's,
kept per height (see [state](../state/spec.md)).

### D-4 — Crash atomicity and fsync *(implemented)*

Height-advancing writes are **fsynced** (`sync(true)`) in every store, so an individual database
survives power loss.

The one exception is the snap-sync **bulk-seeding window**: between the synced
`bootstrapInProgress` marker and the barrier that clears it, the ledger, nonce and contract-slot
seed writes go unsynced with the WAL fsynced every 4096 writes, because one fsync per seeded
entry made importing a large state effectively unusable. The window is sound because the marker
is written **before the first seeding write**: a crash or power loss inside the window is
detected at the next boot, which refuses to run on half-seeded state (M8), and the closing
barrier (`syncWal` per store) runs before the marker clears. The SMT rebuild seeds through
bounded `WriteBatch` windows instead (content-addressed nodes need no marker coverage: an
interrupted rebuild leaves only unreferenced nodes). Outside the window no unsynced write path
exists.

`close()` **waits out the network scheduler** rather than closing a store under it — a RocksDB
use-after-free that had been aborting the JVM in integration tests. Node shutdown closes the HTTP
listener **first**, then closes the stores under the engine lock, because the newly fsynced writes
had widened a close-time race that could abort the JVM.

### D-5 — Body pruning *(implemented)*

`RHIZOME_PRUNE=N` discards each body as it falls out of the window — an **amortised O(1) delete in
the same write batch as the append**. Headers, genesis and the transaction index are always kept.
`prunedBelow` is persisted in `meta` and advertised on `/info`. See
[networking](../networking/spec.md) P-6.

### D-6 — Secondary indexes *(implemented)*

- Boxes: **owner** and **rent-expiry** indexes (the expiry index makes collectable-box selection
  O(1) per box rather than a scan).
- Tokens: **minter** and **holder** indexes.

All are **recomputed locally** on snapshot import, never transferred.

### D-7 — Fixed-layout binary codec *(implemented)*

Manual `ByteBuffer` big-endian serialisation replaces runtime-codegen serializers (ActiveJ/Fory):
deterministic, fast, and native-friendly. See
[core/serialization/](../../lib-core/src/main/java/rhizome/core/serialization/).

## Invariants (must never regress)

- Chain and ledger writes ride **one** atomic `WriteBatch` with the height — the ledger can never
  lead or lag the chain height.
- Every peripheral store commits its mutations and its undo journal in a single atomic
  `WriteBatch`, guarded against double-apply.
- Height-advancing writes are fsynced; the only unsynced path is the marker-guarded snap-sync
  seeding window (D-4), bracketed by the synced bootstrap marker and its closing barrier.
- Boot reconciliation rewinds any peripheral store found ahead of the chain height.
- Undo journals and receipts for boxes and contracts are **persisted**, so reorg-after-restart works.
- Secondary indexes are always derived locally, never imported.
- Shutdown order: HTTP listener first, then stores under the engine lock, after the network
  scheduler has drained.
- No runtime code generation anywhere on the persistence path (GraalVM native constraint).

## Configuration

| Variable | Purpose |
|---|---|
| `RHIZOME_DATA` | RocksDB data directory (default `./data`) |
| `RHIZOME_PRUNE` | keep only the most recent N block bodies |

## Open items

Two engineering residuals are **documented rather than closed** (§7.6):

- The independent databases share **no single cross-store atomic commit**. A power cut landing
  between two stores' commits is *reconciled at boot* (D-2) rather than prevented. Full power-loss
  durability across the independent state databases additionally needs a per-store fsync;
  process-crash consistency is complete without it.
- Full cross-store transactional commit would require either a single database for all state or a
  write-ahead intent log — neither currently justified by the observed failure mode.

## References

- `WHITEPAPER.md` §6.2 (performance stack, storage), §6.4 (pruning), §7.6 (residuals)
- Skill: `rocksdb-leveldb-patterns`
