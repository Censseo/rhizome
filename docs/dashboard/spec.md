# Dashboard Specification

> Source of truth for the node's embedded web UI.
> Extracted from README, source analysis, and the `frontend-designer` / `frontend-coder` agent definitions.
> **Status**: Draft — needs review

## Overview

Every node embeds a web dashboard served on the API port — open `http://localhost:3000/`. **No build
step, no npm, no bundler, no external assets**: dependency-free static HTML/CSS/JS served straight
from resources, so it works offline against your own node and stays GraalVM-native-friendly.

The load-bearing property: **wallet keys are generated and stored in the browser and transactions
are signed locally in JS — the node never sees a private key.**

## Scope

**Owns**

| File | Lines | Role |
|---|---|---|
| [index.html](../../app-node/src/main/resources/dashboard/index.html) | 40 | shell — nav, `#view` mount point, tip indicator, toast zone |
| [app.css](../../app-node/src/main/resources/dashboard/app.css) | 162 | the whole design system (dark operator aesthetic) |
| [app.js](../../app-node/src/main/resources/dashboard/app.js) | 1527 | router + all six pages + node API calls |
| [crypto.js](../../app-node/src/main/resources/dashboard/crypto.js) | 343 | `RzCrypto` — in-browser Ed25519, hashing, address derivation |
| [tx.js](../../app-node/src/main/resources/dashboard/tx.js) | 230 | `RzTx` — transaction construction and signing |
| [templates/](../../app-node/src/main/resources/dashboard/templates/) | — | bundled contract `.wasm` + `.rs` + `manifest.json` |

Loaded as three classic `<script>` tags (`crypto.js` → `tx.js` → `app.js`), each defining one
namespace const — no modules, no imports.

**Does not own**

- The routes it calls → [node-api](../node-api/spec.md)
- Contract template *sources* and their ABI → [contracts](../contracts/spec.md)
- The CLI wallet → [wallet](../wallet/spec.md) *(the browser wallet is specified here; key handling
  rules are shared)*

## Features

### U-1 — Six pages, hash-routed *(implemented)*

Nav entries are `data-page` attributes on the shell; `app.js` swaps `#view`.

| Page | Content |
|---|---|
| **Dashboard** | live network stats — height, difficulty, block time, mempool, peers, reward — plus a live contract-event feed over SSE |
| **Explorer** | browse blocks, transactions and addresses; search by height, txid or address |
| **Wallet** | browser-local keys, send, native-token holdings (mint/transfer/burn) |
| **Contrats** | bundled templates with Rust sources, one-click deploy, typed call builder, read-only queries |
| **Agents IA** | agent wallets — init/exec, grant/revoke capped session keys, inspect sessions, watch grant/spend events live |
| **Boxes** | browse, create, update, spend and rent-collect data boxes with typed registers |

### U-2 — Browser-custodied keys *(implemented)*

Keys are generated and stored **in the browser** (localStorage) and transactions signed locally in
JS (Ed25519 via `RzCrypto`). The node never receives a private key. `RzTx` builds the signed
preimage — including `chainId` and the account nonce — client-side.

### U-3 — Contract template gallery *(implemented)*

[templates/manifest.json](../../app-node/src/main/resources/dashboard/templates/manifest.json)
describes each bundled contract: id, name, `.wasm` and `.rs` filenames, a description, and a typed
`methods` list. The payload encoding is documented in the manifest itself:

> `address` = 25-byte hex, `u64` = 8 bytes little-endian, `bytes` = raw hex tail. Payload = selector
> byte followed by the args in order.

The typed call builder generates the payload from that manifest, so adding a template is a manifest
edit plus the two files — no JS change.

Bundled: `counter`, `emitter`, `token`, `amm`, `pair`, `router`, `launchpad`, `agent_wallet`.

### U-4 — Read-only contract queries *(implemented)*

`POST /contract/query` → `/call_readonly` executes the VM against a throwaway state overlay, so the
Contrats page can read contract state without a transaction or a fee.

### U-5 — Feature-flag gating *(implemented)*

The Boxes and token surfaces activate from `GET /features`, so a node built without those layers
keeps the pages **dormant** rather than erroring.

### U-6 — Client-side economic computation *(implemented)*

On the Boxes page the **minimum locked value is computed client-side** from the node's
`minValuePerByte`, so the UI shows the anti-dust floor before the user submits. Because
`minValuePerByte` is miner-votable, the value must be read from the node, never hardcoded.

### U-7 — Live event feed *(implemented)*

The Dashboard and Agents pages subscribe to `GET /logs/stream` (SSE). Contract logs and box/token
lifecycle events share the feed. On disconnect, resume via the `fromHeight` cursor — **push for
liveness, cursor for correctness**.

## Conventions (must not regress)

- **Zero dependencies, zero build step.** Vanilla ES2020, served as-is from resources. No npm, no
  bundler, no CDN, no external font or image.
- Three classic scripts, one namespace const each (`RzCrypto`, `RzTx`, and the app's own) — no ES
  modules.
- Private keys never leave the browser and are never sent to any endpoint.
- Anything votable or configurable (`minValuePerByte`, `minFee`, reward, chain id) is **read from
  the node**, never hardcoded in JS.
- Optional layers gate on `GET /features` rather than failing at runtime.
- State-changing POSTs must carry the `X-Rhizome-Request` header and be same-origin — see
  [node-api](../node-api/spec.md) A-3.
- Dark operator aesthetic; the design system lives entirely in the 162-line `app.css`.

## Open items

- UI copy is **mixed French and English** (`Contrats`, `Agents IA` in nav; French template
  descriptions in `manifest.json`; English elsewhere). No i18n layer exists — pick one language or
  add one.
- `app.js` holds the router and all six pages in a single 1527-line file. Splitting is constrained
  by the no-bundler rule (it would mean more `<script>` tags).

## References

- `README.md` — Dashboard section
- Agents: `frontend-designer`, `frontend-coder`
- `WHITEPAPER.md` §5.4 (logs/SSE), §5.5 (boxes, scans), §5.6 (tokens)
