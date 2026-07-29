# Authenticated State Specification

> Source of truth for the sparse-Merkle state root, light-client proofs, and snapshot
> export/import.
> Extracted from `WHITEPAPER.md` §5.7, §6.4, §8 and source analysis.
> **Status**: Draft — needs review

## Overview

Every block header commits a 32-byte **state root** — an authenticated commitment to the whole value
state — so a **light client can prove any single state entry against a header it trusts**, without
holding or trusting a full node. This is the property Ergo buys with its AVL+ tree; Rhizome uses a
**sparse Merkle tree** keyed by `H(domain ‖ rawKey)`, whose leaf commits `H(value)`.

Nodes are **content-addressed** (a node is keyed by its own hash), which makes them immutable, dedup
naturally, and — the useful part — leaves every old root resolvable, so **reorg reversal is
journal-free**: reverting a block just moves the current root back to the previous block's, kept per
height in a small store.

This root is also what makes **snapshot bootstrap trust-minimised**: a new node rebuilds a recent
state tree and accepts it only when it reproduces the root committed in a PoW-validated header.

## Scope

**Owns**

| Area | Source |
|---|---|
| Sparse Merkle tree & proofs | [SparseMerkleTree.java](../../lib-core/src/main/java/rhizome/core/state/SparseMerkleTree.java), [StateProof.java](../../lib-core/src/main/java/rhizome/core/state/StateProof.java) |
| Domain-tagged key derivation | [StateKeys.java](../../lib-core/src/main/java/rhizome/core/state/StateKeys.java) |
| Change accumulation per block | [StateAccumulator.java](../../lib-core/src/main/java/rhizome/core/state/StateAccumulator.java), [StateChange.java](../../lib-core/src/main/java/rhizome/core/state/StateChange.java) |
| Node/root stores | [SmtNodeStore.java](../../lib-core/src/main/java/rhizome/core/state/SmtNodeStore.java), [RootStore.java](../../lib-core/src/main/java/rhizome/core/state/RootStore.java), in-memory impls |
| Snapshot export/import/chunking | [core/state/snapshot/](../../lib-core/src/main/java/rhizome/core/state/snapshot/) |
| Persisted SMT nodes + roots | [RocksDbStateStore.java](../../lib-persistence/src/main/java/rhizome/persistence/rocksdb/RocksDbStateStore.java) |
| Bootstrap driver | [SnapshotBootstrap.java](../../app-node/src/main/java/rhizome/node/SnapshotBootstrap.java) |
| Genesis snapshot seeding | [SnapshotLoader.java](../../lib-core/src/main/java/rhizome/core/ledger/SnapshotLoader.java), [LedgerSnapshot.java](../../lib-core/src/main/java/rhizome/core/ledger/LedgerSnapshot.java) |
| HTTP surface | [StateApi.java](../../app-node/src/main/java/rhizome/node/StateApi.java) |

**Does not own**

- Folding the root into the header preimage / rejecting a mismatched header →
  [consensus](../consensus/spec.md) C-1
- The transport that fetches headers and chunks → [networking](../networking/spec.md) P-7
- What each domain's values *mean* → the respective domain specs

## Features

### S-1 — Seven committed state domains *(implemented)*

`StateKeys` maps a domain-tagged raw key to the 32-byte SMT key `SHA-256(domain ‖ rawKey)`:

| Domain | Byte | Contents |
|---|---|---|
| `LEDGER` | `0x01` | native PDN balances |
| `BOX` | `0x02` | data boxes |
| `TOKEN_META` | `0x03` | token metadata + supply |
| `TOKEN_BALANCE` | `0x04` | per-`(token, address)` balances |
| `CONTRACT_CODE` | `0x05` | deployed contract code |
| `CONTRACT_STORAGE` | `0x06` | contract key/value storage |
| `ACCOUNT_NONCE` | `0x07` | per-account next nonce |

The domain byte keeps the domains disjoint. `GET /state/proof?domain=…` covers all seven.

### S-2 — Order-independent root *(implemented)*

The root is a function of the binding **set** alone — independent of the order changes are applied
within a block — because a leaf commits its full key and sits at its **shortest unique prefix**
(proven by an order-independence test over shuffled inserts).

That means the node collecting a block's ledger/box/token changes need not canonicalise their
order; any node re-deriving the block arrives at the same root. It also means the snapshot importer
can insert chunks in **any order**.

### S-3 — Production and validation *(implemented)*

A block a node produces is applied to compute the resulting root, which is stamped into the header
**before** the PoW is solved, so the proof of work binds it. The header hash commits the state root
**only when set** — a stateless block, produced without the accumulator, hashes exactly as before.

Every receiving node re-derives the root by applying the block and **rejects a header whose
committed root doesn't match**, rolling the block back fully (ledger, box, token and accumulator
state). A state-root mismatch is a **first-class block-invalidity**, exercised by a tamper test. On
a reorg the root rewinds with the block.

### S-4 — Light-client proofs *(implemented)*

- `GET /state` — the current root
- `GET /state/proof?domain=…&key=…` — the value hash plus the sibling hashes along the path

A client re-derives the SMT key from `(domain, rawKey)` and folds the siblings to check it against a
root it already trusts. **The stateless verifier is a few lines and needs nothing else.**

A genesis seed commits the snapshot balances so block 2 builds on them.

### S-5 — Snapshot materialisation and serving *(implemented)*

The snapshot is a **flat per-domain dump** — for each of the seven domains, a paged run of
`(rawKey, value)` chunks. Unlike Ergo's AVL+ manifest-and-subtree scheme, **it carries no tree
structure**: since the root is a function of the binding set alone (S-2), the importer inserts
chunks in any order and a single final root equality verifies the lot.

Servers materialise a consistent snapshot every `RHIZOME_SNAPSHOT_EVERY` blocks under a
point-in-time lock and advertise `(pivotHeight, stateRoot, chunkCount)` on
`GET /state/snapshot/info`, serving chunks by index on `GET /state/snapshot/chunk`.

### S-6 — Trust-minimised snapshot bootstrap *(implemented)*

A brand-new node can skip replaying history entirely. Its trust reduces to exactly what full
validation gives, in four steps (model: Ergo §8, adapted to a sparse-Merkle root):

1. **Genesis is built locally** from the network parameters and balance snapshot — chain identity is
   never taken from a peer.
2. **The header chain** from genesis up to the pivot's finality depth is validated under full PoW,
   so the state root committed in the pivot header carries the chain's accumulated work. Validation
   is incremental in bounded windows and stops at `pivot + maxReorgDepth`.
3. The node picks a **pivot buried at least `maxReorgDepth` under the peer tip**. Because every node
   refuses reorgs deeper than that, the imported state can never be forced to unwind — **no
   "de-import" path is ever needed**.
4. It downloads the state snapshot at the pivot, rebuilds the sparse-Merkle tree, and accepts the
   state **only when it reproduces the pivot header's committed root**. Any tampered, dropped or
   duplicated entry changes the rebuilt root and the whole import is refused with the stores
   untouched.

Secondary indexes (box owner/expiry, token minter/holder) are **recomputed locally** from the
verified values, **never transferred** — an untrusted index cannot be smuggled in.

After import the node holds genesis, headers to the pivot (body-less, marked pruned), and the full
pivot state; ordinary headers-first sync then pulls only the body suffix above the pivot. A
snap-synced node is a **first-class citizen from the first block**: it validates, serves, and proves
state entries against the root like any other.

### S-7 — Genesis snapshot seeding *(implemented)*

The genesis state is a snapshot of the Pandanite balances, **sanitised** (wallets from the inflation
incidents excluded). The `genesisCommitment` hashes `chainId ‖ snapshotCommitment`, so **two
different networks never share a genesis even with an empty snapshot**. The real snapshot is
produced by a dump tool
([PandaniteLedgerDumper.java](../../lib-persistence/src/main/java/rhizome/persistence/tools/PandaniteLedgerDumper.java))
reading the LevelDB ledger of a synchronised Pandanite node. Configured via `RHIZOME_SNAPSHOT`.

## Invariants (must never regress)

- The root is **order-independent** — a function of the binding set — so it can never fork on map
  iteration order.
- The state root is stamped **before** PoW is solved and revalidated by every node with **full
  rollback on mismatch**.
- The header hash commits the state root **only when set**, so stateless blocks hash unchanged.
- SMT nodes are content-addressed, so every historical root stays resolvable and reorg reversal is
  journal-free (roots kept per height).
- Snapshot import is all-or-nothing against the pivot header's committed root; stores are untouched
  on refusal.
- Secondary indexes are always **recomputed locally**, never imported.
- The pivot is buried at least `maxReorgDepth` deep, so imported state can never be unwound.
- Header validation during bootstrap is windowed and stops at `pivot + maxReorgDepth`, never at a
  peer's advertised height.

## Configuration

| Variable | Purpose |
|---|---|
| `RHIZOME_SNAPSHOT` | snapshot file seeding the genesis |
| `RHIZOME_SYNC=snap` | bootstrap from a peer's snapshot pivot |
| `RHIZOME_SNAPSHOT_EVERY` | materialisation interval in blocks (server side) |

## Open items

- Production of the **real Pandanite snapshot** is environment-dependent — it requires a synchronised
  C++ node (§9).

## References

- `WHITEPAPER.md` §5.7 (authenticated state root), §6.4 (snapshot bootstrap), §6.5 (`ACCOUNT_NONCE`
  domain), §8 (snapshot seeding), §7.1 (invariants), §10 (Ergo lineage)
