# Native Tokens Specification

> Source of truth for protocol-level fungible assets — mint, transfer, burn, with no contract and
> no gas.
> Extracted from `WHITEPAPER.md` §5.6 and source analysis.
> **Status**: Draft — needs review

## Overview

A token launch should cost **one transaction**, not a contract deploy. Rhizome supports fungible
tokens two ways: as WASM contracts (`token.rs`, see [contracts](../contracts/spec.md) — for tokens
that need custom logic) and, for the common case, as a **protocol-level native asset** with no
contract and no gas.

This follows Ergo's native-token idea, but in Rhizome's **account model** rather than Ergo's boxes:
a token is an account-based balance map, not a value carried inside UTXOs — which fits a chain whose
ledger is already account-based and whose boxes are single-owner cells, not multi-input/output
UTXOs.

Reducing a memecoin launch to a single ~fee-priced transaction is the "cheap token launches" goal,
met without giving up the composability of contract tokens for the cases that need it.

## Scope

**Owns**

| Area | Source |
|---|---|
| Token metadata & payload | [TokenMeta.java](../../lib-core/src/main/java/rhizome/core/token/TokenMeta.java), [TokenPayload.java](../../lib-core/src/main/java/rhizome/core/token/TokenPayload.java) |
| Lifecycle execution | [TokenProcessor.java](../../lib-core/src/main/java/rhizome/core/token/TokenProcessor.java), [DefaultTokenProcessor.java](../../lib-core/src/main/java/rhizome/core/token/DefaultTokenProcessor.java) |
| Store abstraction | [TokenStore.java](../../lib-core/src/main/java/rhizome/core/token/TokenStore.java), [InMemoryTokenStore.java](../../lib-core/src/main/java/rhizome/core/token/InMemoryTokenStore.java) |
| Persisted store + indexes | [RocksDbTokenStore.java](../../lib-persistence/src/main/java/rhizome/persistence/rocksdb/RocksDbTokenStore.java) |
| HTTP surface | [TokenApi.java](../../app-node/src/main/java/rhizome/node/TokenApi.java) |

**Does not own**

- The `TOKEN_*` transaction envelope → [transactions](../transactions/spec.md)
- Contract-based tokens (`token.rs`, allowances, `transfer_from`) → [contracts](../contracts/spec.md)
- The `TOKEN_META` / `TOKEN_BALANCE` state-root domains → [state](../state/spec.md)

## Features

### N-1 — The asset *(implemented)*

- **Unique 32-byte id** = `SHA-256(minter ‖ nonce ‖ "rztoken")` — unforgeable and non-repeating,
  like a box id or contract address.
- Carries a **minter**, a **symbol** (≤ `maxTokenSymbolBytes` = 16), a **name**
  (≤ `maxTokenNameBytes` = 64), **decimals** (≤ `maxTokenDecimals` = 18), and a current **total
  supply**.
- Balances are a per-`(token, address)` map held **alongside** the native PDN ledger — not inside
  boxes.

### N-2 — Lifecycle *(implemented)*

Three transaction kinds, **deterministic and gas-free**, paid by the ordinary fee:

| Kind | Effect |
|---|---|
| `TOKEN_MINT` | creates a token — derives its id from the minter and nonce, records metadata, credits the whole initial supply to the recipient |
| `TOKEN_TRANSFER` | moves an amount from the sender to `to`, checked against the sender's token balance |
| `TOKEN_BURN` | destroys the sender's tokens, reducing total supply |

There is **no way to mint the same id twice** (the nonce never repeats), so supply is fixed at
issuance unless the token is later burned.

### N-3 — Token ops move no PDN *(implemented)*

A token op moves **no PDN**: the token amount lives in the payload and the PDN `amount` field must
be **zero**. Only the fee moves, to the miner.

### N-4 — Reorg-safe balances *(implemented)*

Balances live in the token store with a **persisted per-block undo journal**, so a reorg reverses
token state **and the fee** exactly, atomically with the block — the same guarantee boxes and
contracts get.

### N-5 — Indexes and queries *(implemented)*

Minter and holder indexes back:

- `GET /token?id=` — metadata + supply
- `GET /token_balance` — a holding
- `GET /tokens?minter=` / `?holder=` — enumeration

Token lifecycle events `token.minted` / `transferred` / `burned` join the log/SSE feed. The wallet
gains `token-mint` / `token-transfer` / `token-burn` / `token-show` / `token-balance` /
`token-list`; the dashboard surfaces holdings on the **Wallet** page.

### N-6 — Light-client provability *(implemented)*

Token metadata and balances are committed in the **authenticated state root** (domains
`TOKEN_META = 0x03`, `TOKEN_BALANCE = 0x04`), so a light client can prove a holding against a
header. See [state](../state/spec.md).

## Invariants (must never regress)

- Token state must move atomically with its block and reverse exactly on reorg (persisted undo
  journal).
- A token op's PDN `amount` field must be zero; only the fee moves.
- Token ids are derived from `(minter, nonce)` and can never repeat.
- Secondary indexes (minter, holder) are **recomputed locally** on snapshot import, never
  transferred — an untrusted index cannot be smuggled in.
- A token transaction is refused at mempool admission below `tokenActivationHeight`, symmetric with
  the executor's gate.

## Key parameters

| Parameter | Value |
|---|---|
| `tokenActivationHeight` | 0 |
| `maxTokenSymbolBytes` | 16 |
| `maxTokenNameBytes` | 64 |
| `maxTokenDecimals` | 18 |

## References

- `WHITEPAPER.md` §5.6 (native tokens), §5.7 (state-root commitment), §9 (memecoin primitives),
  §10 (Ergo native-token lineage)
