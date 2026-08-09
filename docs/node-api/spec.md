# Node API Specification

> Source of truth for the node's HTTP surface, configuration, authentication, and request-path
> hardening.
> Extracted from `WHITEPAPER.md` §7.3, §7.4, README and source analysis.
> **Status**: Draft — needs review

## Overview

One ActiveJ HTTP server on `RHIZOME_PORT` (default 3000) serves everything: the P2P protocol, the
explorer/query API, the operator routes, and the embedded dashboard. [NodeApi.java](../../app-node/src/main/java/rhizome/node/NodeApi.java)
is the single `RoutingServlet`; the other `*Api` classes are handler collections it composes, and
[NodeService.java](../../app-node/src/main/java/rhizome/node/NodeService.java) is the facade they
call into.

The critical constraint: **the single event-loop thread runs synchronous work under the consensus
lock**, so every route that can trigger real work sits behind both a per-IP rate limiter and an
aggregate token-bucket gate that sheds with HTTP 429 *before* doing the work.

## Scope

**Owns**

| Area | Source |
|---|---|
| Routing servlet, middleware ordering | [NodeApi.java](../../app-node/src/main/java/rhizome/node/NodeApi.java) |
| Service facade & budgets | [NodeService.java](../../app-node/src/main/java/rhizome/node/NodeService.java) |
| Configuration from environment | [NodeConfig.java](../../app-node/src/main/java/rhizome/node/NodeConfig.java) |
| Node wiring / lifecycle | [RhizomeNode.java](../../app-node/src/main/java/rhizome/node/RhizomeNode.java) |
| Response helpers, guards, log sanitisation | [ApiResponses.java](../../app-node/src/main/java/rhizome/node/ApiResponses.java) |
| Explorer handlers | [ExplorerApi.java](../../app-node/src/main/java/rhizome/node/ExplorerApi.java) |
| Contract / log / SSE handlers | [ContractApi.java](../../app-node/src/main/java/rhizome/node/ContractApi.java), [SseLogHub.java](../../app-node/src/main/java/rhizome/node/SseLogHub.java) |
| Box, token, state, sync handlers | [BoxApi.java](../../app-node/src/main/java/rhizome/node/BoxApi.java), [TokenApi.java](../../app-node/src/main/java/rhizome/node/TokenApi.java), [StateApi.java](../../app-node/src/main/java/rhizome/node/StateApi.java), [SyncApi.java](../../app-node/src/main/java/rhizome/node/SyncApi.java) |
| Feature flags / stats / asset serving | [DashboardApi.java](../../app-node/src/main/java/rhizome/node/DashboardApi.java), [DashboardAssets.java](../../app-node/src/main/java/rhizome/node/DashboardAssets.java) |
| Scan registry | [ScanRegistry.java](../../app-node/src/main/java/rhizome/node/ScanRegistry.java) |

**Does not own**

- Outbound peer requests and peer-token scoping → [networking](../networking/spec.md)
- The dashboard's client-side code → [dashboard](../dashboard/spec.md)
- Domain semantics behind each route → the respective domain specs

## Route inventory

| Group | Routes |
|---|---|
| **P2P protocol** (stay open even with `RHIZOME_API_TOKEN`) | `/block`, `/blocks`, `/block_count`, `/headers`, `/sync`, `/total_work`, `/difficulty`, `/peers`, `/orphan`, `/state/snapshot/info`, `/state/snapshot/chunk` |
| **Operator / state-changing** (gated by `RHIZOME_API_TOKEN`) | `/add_peer`, `/add_transaction`, `/add_transaction_json`, `/submit`, `/call_readonly`, `/scan/register`, `/scan/deregister` |
| **Explorer / query** | `/transaction`, `/address_txs`, `/wallet`, `/mempool`, `/stats`, `/info`, `/features`, `/contract`, `/logs`, `/logs/stream` |
| **Boxes** | `/box`, `/boxes`, `/scan/boxes`, `/scan/list` |
| **Tokens** | `/token`, `/tokens`, `/token_balance` |
| **State** | `/state`, `/state/proof` |
| **Dashboard** | `/`, `/dashboard`, `/dashboard/*` |
| **Documentation** | `/docs/manifest.json`, `/docs/*.md` — the repository markdown bundled at build time, served with the dashboard's security headers; see [dashboard](../dashboard/spec.md) U-8 |

## Features

### A-1 — Environment-variable configuration *(implemented)*

| Variable | Default | Purpose |
|---|---|---|
| `RHIZOME_NETWORK` | `mainnet` | `mainnet`, `testnet` (low difficulty, `minFee = 0`) or `devnet`; any other value is **refused at startup** |
| `RHIZOME_PORT` | `3000` | HTTP API port |
| `RHIZOME_BIND_ADDRESS` | `127.0.0.1` | bind address; binding a public address additionally requires `RHIZOME_API_TOKEN` or `RHIZOME_ALLOW_OPEN_API=true` |
| `RHIZOME_API_TOKEN` | — | bearer token gating state-changing/operator routes |
| `RHIZOME_PEER_TOKEN` | — | outbound peer bearer token (see [networking](../networking/spec.md) P-8) |
| `RHIZOME_ALLOW_OPEN_API` | — | opt out of the operator-route gate |
| `RHIZOME_ALLOW_PRIVATE_PEERS` | — | opt out of the SSRF/private-IP peer filter |
| `RHIZOME_DATA` | `./data` | RocksDB data directory |
| `RHIZOME_SNAPSHOT` | — | snapshot file seeding the genesis |
| `RHIZOME_MINER` | — | reward address (enables block production) |
| `RHIZOME_PEERS` | — | comma-separated initial peers |
| `RHIZOME_ADVERTISE` | — | public URL advertised to peers; must be an `http(s)` URL with a host |
| `RHIZOME_BLOCK_INTERVAL_MS` | block target | producer pacing override (local devnets) |
| `RHIZOME_VOTE` | — | miner's economic-parameter vote |
| `RHIZOME_PRUNE` | — | keep only the most recent N block bodies |
| `RHIZOME_SYNC` | — | `snap` for snapshot bootstrap |
| `RHIZOME_SNAPSHOT_EVERY` | — | snapshot materialisation interval (blocks) |

### A-2 — API-token gating *(implemented)*

When `RHIZOME_API_TOKEN` is set, state-changing and operator routes require
`Authorization: Bearer <token>`; **P2P protocol endpoints stay open**.

> Consequence worth stating loudly: with a token set, gossip peers must also present it on `/submit`
> and `/add_transaction`. Set `RHIZOME_PEER_TOKEN` on **every** node of the deployment (with
> `https://` peer URLs) or cross-node pushes are refused (401) and gossip stops converging.

### A-3 — Browser-surface hardening: CSRF + DNS-rebinding *(implemented)*

A browser state-changing POST must clear **two** gates:

1. Its `Host` must be one of the node's configured authorities (advertised host plus loopback). A
   rebound page carries the attacker's own hostname, so it is refused **even though rebinding makes
   `Origin == Host` look same-origin**. The Host allowlist is the **load-bearing anti-rebinding
   control**.
2. It must be same-origin **and** carry a non-simple `X-Rhizome-Request` header.

The marker check alone does not stop rebinding, because a same-origin page sets custom headers
freely. The `Host` allowlist also applies to reads.

### A-4 — Aggregate token-bucket gates *(implemented)*

Because the single event-loop thread runs synchronous work under the consensus lock, three
**aggregate (all-IP)** token-bucket gates sit *above* the per-IP rate limiter, each shedding with
HTTP **429 before doing the work**:

| Gate | Bounds |
|---|---|
| submit budget | submit-triggered memory-hard PoW hashes |
| read-only gas budget | `/call_readonly` VM gas |
| read budget | explorer reads that decode blocks under the lock |

`/add_transaction` is metered by the same per-IP cost and aggregate-admission gate as `/submit`,
charged **before the body is decoded**. The submit-budget gate sits **ahead of block decode**.

### A-5 — Read-cost weighting *(implemented)*

`/transaction` and `/address_txs` decode up to `depth` full blocks under the consensus lock but were
weighted at the light header-scan rate (`depth/20`), ~20× below the per-block cost the aggregate
read gate was sized for. Both are now weighted by the blocks they **actually decode**, like
`/blocks` and `/stats`.

### A-6 — Prune-aware responses *(implemented)*

Explorer reads answer **410 GONE with the prune watermark** on a discarded height instead of a
generic 400, and their tip-backward scans **clamp to the watermark** rather than reading pruned
bodies. `/sync` for a discarded range answers 410 with the watermark; `prunedBelow` is advertised on
`/info`.

### A-7 — Slow-loris defence *(implemented)*

A read/write **inactivity timeout** closes a client that trickles a request or drains a response
byte by byte, so a pool of stalled sockets cannot occupy the single event-loop thread.

### A-8 — Feature flags *(implemented)*

`GET /features` reports which optional layers this build has (boxes, tokens, contracts, dry-run), so
the dashboard keeps a page dormant on a node built without a layer.

### A-9 — Log feed and SSE *(implemented)*

`GET /logs?height=N` (one block), `GET /logs?fromHeight=N` (height-cursor scan whose `toHeight` is
the next cursor), and `GET /logs/stream` (SSE). See [contracts](../contracts/spec.md) V-6 for the
push/cursor contract. Log output is sanitised (`ApiResponses.sanitizeForLog`).

### A-10 — Read-only contract dry run *(implemented)*

`POST /call_readonly` runs a `CALL` against committed state and **discards every write**, executing
against a throwaway state overlay. It pays `CALL_BASE` intrinsically and is bounded by the aggregate
read-only gas gate.

### A-11 — Scan registry *(implemented)*

`POST /scan/register` / `POST /scan/deregister` / `GET /scan/list` / `GET /scan/boxes`. Scan ids are
**CSPRNG-generated** so an unauthenticated caller cannot enumerate and wipe another app's scans;
per-client caps with LRU eviction bound the registry. Scans are **node-local, not consensus**.

Ownership is the source address **plus** an optional `X-Scan-Owner` secret the client chooses and
sends on all four routes. Send one whenever the node is reached through a NAT, a shared VPN or a
reverse proxy: there every co-tenant arrives with the same address, so address-only ownership let
them list, query and deregister each other's scans. Both halves must match — the same secret from a
different address does not carry ownership either. The node stores only its SHA-256. A client that
sends no header keeps the address-only behaviour, which is all a single-tenant deployment needs.

### A-12 — ActiveJ v7 server defaults *(implemented)*

> Added: 2026-08-09 | Source: feature `001-activej-v7-java25`

The server runs on the **ActiveJ v7.0.0 fork** (pin rationale → [platform](../platform/spec.md)
P-3). v7 ships hardening defaults that sit *underneath* this spec's own bounds. The rule is that
**Rhizome's explicit configuration governs and the v7 defaults are a strict superset** — a backstop
that catches anything a route forgets, never the primary bound.

| Concern | v7 default | What actually governs |
|---|---|---|
| Read/write inactivity timeout | 60 s | The explicit **30 s** `withReadWriteTimeout` on the HTTP server — A-7's slow-loris bound. Tighter than the default, so the default never fires first. |
| Request body ceiling | 100 MB | Per-route `loadBody(...)` caps, all far below 100 MB. No `withMaxBodySize` is set; the ceiling is a backstop for a route that ever ships without a cap. |
| Head parsing | strict RFC 7230 | Already the expected behaviour — malformed request heads answer **400**. |
| `ByteBufPool` retention | changed in v7 | Not reachable. `lib-persistence` abandoned pooled buffers deliberately (leak, audit F12) and uses plain allocations; the single explicit `ByteBuf.recycle()` in the repo is in test code. |

Consequences for the streaming routes, which are the timeout-sensitive ones — `GET /logs/stream`
(SSE), `GET /sync`, `GET /headers`:

- The SSE **15 s heartbeat** is what keeps `/logs/stream` alive across both the 30 s explicit
  timeout and the 60 s v7 default. It is a correctness requirement of the timeout interplay, not a
  cosmetic keep-alive — removing it closes idle streams.
- No `-Dactivej.*` system-property override is set anywhere, and no route relies on an implicit
  timeout or on the 100 MB ceiling as its only bound.

Two long-standing workaround comments were re-verified against v7 and still hold: the interned-header
lookup and the `Promise.ofBlocking` offload path (which swallows `RejectedExecutionException`) in
`NodeApi.java`.

## Known limits (accepted, not defects)

Deployment-shaped gaps that no code change inside the node closes. Stated here so an operator can
decide rather than discover.

- **The per-IP limiter is per *address*, not per user.** The key is the IPv4 address or the IPv6
  `/64`; an attacker holding a `/48` has 65 536 distinct keys, and NAT co-tenants share one. The
  bound that actually holds against a distributed flood is the **aggregate** gate (A-4), which is
  why every expensive route is charged to one before it decodes anything.
- **No `X-Forwarded-For` support.** Behind a reverse proxy every request carries the proxy's
  address, so all clients share one per-IP budget (a single client can exhaust it for everyone) and
  the address half of scan ownership collapses (A-11 — send `X-Scan-Owner`). Terminate rate
  limiting at the proxy, or bind the node where it sees real client addresses.
- **Reads are never token-gated.** `RHIZOME_API_TOKEN` covers state-changing/operator POSTs only;
  `/stats`, `/wallet?address=…`, `/logs/stream` and the rest stay readable by anything that reaches
  the port. Chain data is public by design — an SSE stream carries only on-chain events — but a
  *private* explorer is not something this node offers. Put it behind your own authenticating proxy.

## Invariants (must never regress)

- Every route that can trigger PoW hashing, VM gas, or block decoding is metered **before** the work
  — and, for `/submit` and `/add_transaction`, **before the body is decoded**.
- Read weights must match the blocks a handler actually decodes under the lock.
- The `Host` allowlist is the anti-rebinding control; the `X-Rhizome-Request` marker alone is not
  sufficient and must not be relied on as such.
- P2P protocol endpoints stay open under `RHIZOME_API_TOKEN`; only state-changing/operator routes
  are gated.
- Consensus work must be offloaded off the event loop where it can block; sync runs on its own
  thread in blocking I/O.
- `RHIZOME_PEER_TOKEN` is never logged; `/peers` withholds private seed URLs.
- Shutdown closes the HTTP listener before the stores.
- Bounded body sizes and bounded response sizes on every route.

## References

- `WHITEPAPER.md` §6.1 (transport), §7.3 (resource-exhaustion bounds), §7.4 (network security)
- `README.md` — the operator-facing configuration table
- [platform](../platform/spec.md) P-3 — why the ActiveJ pin is what it is
- Skill: `activej-http-patterns`
