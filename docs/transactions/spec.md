# Transactions Specification

> Source of truth for the transaction model, ledger arithmetic, execution, and mempool policy.
> Extracted from `WHITEPAPER.md` §5.1–5.3, §6.5 and source analysis.
> **Status**: Draft — needs review

## Overview

A transaction commits, in its **signed preimage**, to the `chainId` and a per-sender **account
nonce**. Its identity for deduplication is the **signature-free content hash**, immune to Ed25519
malleability. The `Executor` applies a block's transactions in two passes — a structural pass that
touches no state, then a transactional application pass over a checked-arithmetic ledger with full
rollback.

This domain owns *what a transaction is and what it does to balances and nonces*. The per-kind
semantics of contract, box and token transactions live in their own domains; this spec owns the
envelope, the signature/nonce/replay rules, the ledger, execution ordering, fees, and the mempool.

## Scope

**Owns**

| Area | Source |
|---|---|
| Transaction model, kinds, signature, codecs | [core/transaction/](../../lib-core/src/main/java/rhizome/core/transaction/) |
| Ledger (checked arithmetic, snapshots) | [core/ledger/](../../lib-core/src/main/java/rhizome/core/ledger/) |
| Transactional apply/rollback | [Executor.java](../../lib-core/src/main/java/rhizome/core/blockchain/Executor.java) |
| Mempool admission, eviction, fee market | [MemPool.java](../../lib-core/src/main/java/rhizome/core/mempool/MemPool.java), [AccountView.java](../../lib-core/src/main/java/rhizome/core/mempool/AccountView.java) |
| Block selection by miner revenue | [BlockAssembler.java](../../lib-core/src/main/java/rhizome/core/blockchain/BlockAssembler.java) |
| Account nonces | [NonceStore.java](../../lib-core/src/main/java/rhizome/core/blockchain/NonceStore.java), [InMemoryNonceStore.java](../../lib-core/src/main/java/rhizome/core/blockchain/InMemoryNonceStore.java) |
| Cached parallel signature verification | [SignatureVerifier.java](../../lib-core/src/main/java/rhizome/core/blockchain/SignatureVerifier.java) |
| Addresses / users | [PublicAddress.java](../../lib-core/src/main/java/rhizome/core/ledger/PublicAddress.java), [core/user/](../../lib-core/src/main/java/rhizome/core/user/) |

**Does not own**

- `DEPLOY`/`CALL` execution → [contracts](../contracts/spec.md)
- `BOX_*` execution → [boxes](../boxes/spec.md); `TOKEN_*` execution → [tokens](../tokens/spec.md)
- Block validity ordering and the emission schedule → [consensus](../consensus/spec.md)
- Ed25519 primitives → [crypto](../crypto/spec.md)

## Features

### T-1 — Transaction envelope and kinds *(implemented)*

Ten kinds: `TRANSFER` (default), the contract kinds `DEPLOY` / `CALL`, the box kinds `BOX_CREATE` /
`BOX_UPDATE` / `BOX_SPEND` / `BOX_COLLECT`, and the token kinds `TOKEN_MINT` / `TOKEN_TRANSFER` /
`TOKEN_BURN`.

Every non-transfer kind carries a variable-length `data` payload plus `gasLimit` / `gasPrice` in
its signed preimage and wire format, so **a transfer is byte-for-byte what it was before contracts
existed**. Box and token kinds reuse that same suffix with the gas fields pinned to zero (they run
no VM). Every transaction is self-delimiting on the wire, so a block packs variable-length
transactions back to back, bounded by `maxBlockSizeBytes`; the transaction wire cap is 128 KiB.

Amounts and fees are integers in base units (`decimalScaleFactor` = 10 000 → "PDN") and are
conceptually **unsigned**: any negative amount or fee is rejected — a negative withdrawal would
mint money for the sender, a negative deposit would drive the recipient below zero.

### T-2 — Replay protection: chain-id + account nonce *(implemented)*

The signed preimage commits `chainId` (prevents cross-network replay) and a strictly-sequential
per-sender **account nonce** (prevents replay and ordering ambiguity within a network). Pandanite's
`hashContents` had neither, and its anti-replay key included the *signature*, so Ed25519
malleability (#37) allowed double execution.

Replay protection is a per-account **monotonic counter** — one committed leaf per account, not an
unbounded `(address, nonce)` seen-set.

### T-3 — Two-pass transactional execution *(implemented)*

1. **Structural pass** (no state touched): exactly one coinbase whose amount equals the expected
   reward at that height (integer, never a floating-point compare); every other transaction targets
   this chain, has a valid signature whose key matches the sender address, is not a duplicate
   (in-block or already executed), and has amount/fee ≥ 0. Gas ceilings (`maxTxGas`,
   `maxBlockGas`) are checked here, **before any instruction runs**.
2. **Application pass** (transactional): balances go through a **checked-arithmetic** ledger.
   Insufficient balance (underflow) and 64-bit overflow are both rejected cleanly with **full
   rollback** — unchecked `uint64` arithmetic is what inflated balances in Pandanite's
   `invalid.json` incident (§4.1).

Money is conserved: each transaction is debited then credited, and the coinbase is pinned to the
height's reward.

### T-4 — Persisted account nonces *(implemented)*

Nonces are the only derived state computed at *transaction* granularity rather than from headers.
They live in a persistent `nonces` column family, updated on every add and pop and **watermarked by
height**, so a restart (even a pruned node's, even a chain that has never seen an account
transaction) reads no body. Reconstructing nonces from bodies below the prune watermark is refused
loudly rather than silently under-counting.

They are also a committed state domain (`ACCOUNT_NONCE`, key = address, value = next nonce) so a
snapshot-bootstrapped node obtains replay-protection state **verifiably**. Each sender's
`max(txNonce)+1` over the block is folded into the state root, derived from block content
(sequentiality already checked) so producer and validators agree.

> This is the one deliberate consensus change of the headers-first chantier — it alters every state
> root, hence must land while the chain is pre-launch; after launch it would need a
> height-activated fork.

### T-5 — Fee market and mempool policy *(implemented)*

Fees are consensus-**optional** — a block may include a zero-fee transaction and still be valid —
but relaying and block-building enforce a mempool policy that keeps spam expensive:

- Mainnet `minFee` = 10 base units (0.001 PDN); for a contract `CALL` the declared gas budget
  `gasLimit × gasPrice` counts toward the floor. Testnet sets `minFee = 0` so local devnets
  transact with unfunded fees.
- The block builder fills greedily **by miner revenue** — fronting each sender's ready nonce run,
  with deterministic tie-breaks — rather than in raw address order, so a fee market forms under
  contention instead of first-come ordering.

### T-6 — Mempool DoS resistance *(implemented)*

- A per-sender cap, plus eviction of **fully-parked** (nonce-gapped, never-minable) transactions in
  favour of ready or higher-fee ones — so the pool cannot be cheaply and permanently stuffed to
  censor honest traffic.
- That eviction runs **only after the newcomer's signature verifies**, so it is never a free
  unauthenticated lever.
- A box/token transaction is refused at admission below its activation height (the executor's gate
  was admission-asymmetric, so on a delayed-activation network a premature box/token tx would be
  selected into and invalidate every candidate block).
- Gas ceilings are mirrored at admission.

### T-7 — Cached parallel signature verification *(implemented)*

Signatures are verified once at mempool admission and cached, so block execution gets a cache hit;
batches verify in parallel. Measured **≈140 blocks/s warm versus ≈6.6 sequentially** — the single
change that makes a fast block cadence realistic under load. The transaction content-hash is
memoised on the validation path and hashing reuses a `ThreadLocal` digest.

## Invariants (must never regress)

- **No value moves without the owner's key.** Identity is the signature-free `hashContents`;
  `PublicAddress.of(signingKey) == from` is bound at **both** mempool admission and consensus;
  `chainId` + strictly-increasing account nonce sit in the signed preimage.
- The one self-authorised transaction, permissionless `BOX_COLLECT`, is pinned to `from == empty`,
  `fee == 0`, `amount == 0` and is **block-minted only**, so it can never name a funded sender.
- **Validity is a pure function of balance, never of ledger key-presence.** An absent wallet reads
  as balance 0 on every path, so a 0-balance "phantom" key left by any apply-then-rollback cannot
  make a zero-cost transaction valid on one node and invalid on another.
- **Checked ledger arithmetic** — every credit uses `Math.addExact`, every debit guards `>= 0`;
  negative amount/fee rejected at admission *and* consensus; apply/rollback are exact inverses for
  transfers, contracts, boxes, tokens, and scaled GHOST uncle rewards.
- Amounts and fees are integers; no floating-point anywhere on the value path.
- Transaction order within a block is committed by the Merkle root and is load-bearing for nonce
  validation — see [consensus](../consensus/spec.md) C-9.

## Open items

- **Gas sponsorship** (a third party paying a transaction's gas) is the remaining protocol-level
  agent primitive (§9).
- Residual: the `ACCOUNT_NONCE` domain keeps one permanent leaf per account that has ever
  transacted — unlike the ledger domain it cannot self-prune at zero balance without reopening
  replay. Growth is bounded economically by the `minFee` floor and by block space, not eliminated;
  the floor is a per-operator policy knob.

## References

- `WHITEPAPER.md` §5.1 (transaction), §5.2 (execution), §5.3 (economics, fee market), §6.5
  (nonces as authenticated state), §7.1 (invariants), §7.3 (transaction-layer bounds)
- `WHITEPAPER.md` §4.1, §4.6 — the C++ defects this design corrects
