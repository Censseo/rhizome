# Wallet Specification

> Source of truth for the CLI wallet, key custody, and client-side transaction signing.
> Extracted from README, `WHITEPAPER.md` §7 review passes, and source analysis.
> **Status**: Draft — needs review

## Overview

`app-wallet` is a standalone CLI that talks to a node **over HTTP** and never requires node-side key
custody. It signs locally, submits the signed transaction, and reads results back through the
public API — the same contract the browser wallet honours (see [dashboard](../dashboard/spec.md)).

Key handling is the security-critical part: the wallet is the one place a private key exists at
rest, so keystore encryption, file permissions, memory zeroing, and chain-id pinning are all
first-class requirements rather than conveniences.

## Scope

**Owns**

| Area | Source |
|---|---|
| CLI dispatch | [WalletCli.java](../../app-wallet/src/main/java/rhizome/wallet/WalletCli.java) |
| Key creation, load/save, signing, chain pin | [Wallet.java](../../app-wallet/src/main/java/rhizome/wallet/Wallet.java) |
| Encrypted keystore (scrypt) | [WalletKeystore.java](../../app-wallet/src/main/java/rhizome/wallet/WalletKeystore.java) |
| Node HTTP client | [WalletClient.java](../../app-wallet/src/main/java/rhizome/wallet/WalletClient.java) |

**Does not own**

- Ed25519 / address derivation primitives → [crypto](../crypto/spec.md)
- The routes it calls → [node-api](../node-api/spec.md)
- Browser key custody and in-page signing → [dashboard](../dashboard/spec.md)

## Commands

```bash
./gradlew :app-wallet:run --args="<command> ..."
```

| Group | Commands |
|---|---|
| **Keys** | `keygen <keyfile>`, `address <keyfile>` |
| **Native** | `balance <nodeUrl> <address>`, `send <nodeUrl> <keyfile> <to> <amount> [fee]` |
| **Contracts** | `deploy <nodeUrl> <keyfile> <wasmfile> [gasLimit] [gasPrice]`, `call <nodeUrl> <keyfile> <contract> <hexInput> [gasLimit] [gasPrice]`, `call-readonly` |
| **Boxes** | `box-create`, `box-update`, `box-spend`, `box-show`, `box-list` |
| **Tokens** | `token-mint`, `token-transfer`, `token-burn`, `token-show`, `token-balance`, `token-list` |
| **Register literals** | `bytes`, `bool`, `addr`, `hash`, `str` — typed register arguments for `box-*` |

## Features

### W-1 — Local signing, node-agnostic *(implemented)*

The wallet builds the signed preimage locally — including `chainId` and the account nonce fetched
from the node — signs with Ed25519, and submits. `WalletClient` covers `chainId`, `walletInfo`,
`submit`, `callReadonly`, `token`, `tokenBalance`, `tokensByHolder`, `box`, `boxesByOwner`. Signing
helpers are split per kind: `signedSend`, `signedContract`, `signedBox`, `signedToken`.

### W-2 — Encrypted keystore *(implemented)*

`WalletKeystore` derives the encryption key with **scrypt** (`deriveScryptKey`). A plaintext key
file is still possible but is explicitly gated (`checkPlaintextAllowed`) and carries a **loud
warning**, with **heap zeroing** around the unencrypted material.

### W-3 — Key-file permission hygiene *(implemented)*

Key files are written **owner-only** (`writeOwnerOnly`, `restrictToOwner`) and the containing
directory is **fsynced** (`fsyncDirectory`) so a crash cannot leave a partially written key. On
load, the wallet **warns if the file is group- or other-readable** (`warnIfGroupOrOtherReadable`).

Key material is read as `char[]`/byte arrays and zeroed rather than held in `String`s
(`seedJsonChars`, `readChars`, `extractPrivateKeySeed`).

### W-4 — Chain-id TOFU pin *(implemented)*

The wallet records a **trust-on-first-use chain-id pin** alongside the key (`TofuPin`,
`readChainIdPin`, `saveChainIdPin`, `chainIdPin`). `--expect-chain-id` confirms explicitly. This
defends against a **malicious chain swap** — a node that answers with a different `chainId` would
otherwise get a validly-signed transaction for a network the user did not intend.

### W-5 — Recipient address checksum *(implemented)*

`send` enforces the recipient-address checksum, with `--force` to override. A mistyped address is
otherwise an irrecoverable loss.

### W-6 — Read-only contract queries *(implemented)*

`call-readonly` runs a `CALL` against committed state via `POST /call_readonly` and discards every
write — contract state inspection with no transaction and no fee.

### W-7 — Typed box registers from the CLI *(implemented)*

`box-create` / `box-update` take register literals prefixed by type: `bytes`, `bool`, `addr`,
`hash`, `str` — mapping to `BYTES`, `BOOL`, `ADDRESS`, `HASH32`, `STRING` (plus `I64`). See
[boxes](../boxes/spec.md) B-1.

## Invariants (must never regress)

- The private key **never** leaves the wallet process — no endpoint, no log, no node call carries it.
- Key files are owner-only and fsynced; plaintext storage requires an explicit opt-in plus a warning.
- Key material is zeroed from the heap after use.
- The chain-id pin is checked before signing; a mismatch must fail, not warn-and-proceed.
- Recipient checksums are enforced by default.
- The wallet is a pure HTTP client of the public API — it must not require node-side state or
  privileged routes beyond what `RHIZOME_API_TOKEN` gates.

## Open items

- The CLI takes a node URL positionally on every command; there is no config file or environment
  default. Worth considering if command counts grow.

## References

- `README.md` — Wallet section
- `WHITEPAPER.md` §7.4, §7.6 (the eighth/tenth review passes that added the plaintext-key warning,
  heap zeroing, `--expect-chain-id`, domain-separated message signing, and the send checksum)
