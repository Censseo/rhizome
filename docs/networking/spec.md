# Networking Specification

> Source of truth for the HTTP p2p transport, peer discovery, gossip, sync, and network hardening.
> Extracted from `WHITEPAPER.md` §6.1, §6.3, §6.4, §7.4 and source analysis.
> **Status**: Draft — needs review

## Overview

Transport is **HTTP** (parity with Pandanite, simpler to reason about, GraalVM-native-friendly),
with synchronisation running on its own thread in blocking I/O **off the event loop**. HTTP is the
right fit for request/response, cache-friendly, observable node APIs; the ActiveJ RPC forks tried
upstream were non-functional and depended on runtime codegen.

`lib-net` implements `lib-core`'s `PeerSource` abstraction over HTTP, so sync logic never depends on
a transport. The node binds `0.0.0.0` with an unauthenticated `/add_peer`, so **peer handling is
secure-by-default** — most of this spec is the hardening that assumption forces.

## Scope

**Owns**

| Area | Source |
|---|---|
| `PeerSource` over HTTP | [HttpPeerSource.java](../../lib-net/src/main/java/rhizome/net/HttpPeerSource.java), [NodeHttpClient.java](../../lib-net/src/main/java/rhizome/net/NodeHttpClient.java) |
| Gossip broadcast | [PeerBroadcaster.java](../../lib-net/src/main/java/rhizome/net/PeerBroadcaster.java) |
| PEX discovery | [PeerDiscovery.java](../../lib-net/src/main/java/rhizome/net/PeerDiscovery.java) |
| Registry / ban list / rate limiting | [PeerRegistry.java](../../lib-net/src/main/java/rhizome/net/PeerRegistry.java), [PeerBanList.java](../../lib-net/src/main/java/rhizome/net/PeerBanList.java), [RateLimiter.java](../../lib-net/src/main/java/rhizome/net/RateLimiter.java) |
| Outbound auth & URL validation | [PeerTokenPolicy.java](../../lib-net/src/main/java/rhizome/net/PeerTokenPolicy.java), [PeerAuth.java](../../lib-net/src/main/java/rhizome/net/PeerAuth.java), [PeerUrls.java](../../lib-net/src/main/java/rhizome/net/PeerUrls.java), [PeerHosts.java](../../lib-net/src/main/java/rhizome/net/PeerHosts.java) |
| Wire JSON, read deadlines | [PeerJson.java](../../lib-net/src/main/java/rhizome/net/PeerJson.java), [BodyReadDeadline.java](../../lib-net/src/main/java/rhizome/net/BodyReadDeadline.java) |
| Sync drivers | [ChainSynchronizer.java](../../lib-core/src/main/java/rhizome/core/blockchain/ChainSynchronizer.java), [HeaderSynchronizer.java](../../lib-core/src/main/java/rhizome/core/blockchain/HeaderSynchronizer.java) |
| Interface declaration | [PeerSource.java](../../lib-core/src/main/java/rhizome/core/blockchain/PeerSource.java) |

**Does not own**

- The work-comparison / fork-choice rules the sync gate applies → [consensus](../consensus/spec.md) C-7
- Snapshot content and root verification → [state](../state/spec.md)
- The inbound HTTP servlet, CSRF/Host gates, aggregate budgets → [node-api](../node-api/spec.md)

## Features

### P-1 — Active gossip *(implemented)*

Accepted blocks and transactions are re-broadcast to peers. Loops terminate because a peer that
already has an item rejects it.

### P-2 — Peer discovery (PEX) *(implemented)*

Every node serves `/peers`, accepts announcements, and periodically polls its peers, so the network
self-organises from a few seeds. Repeatedly unreachable peers are pruned. The PEX round is
**bounded-parallel** with a bounded pool, capped, subnet-bucketed and ban-scored against
Sybil/eclipse; `/peers` withholds private seed URLs.

### P-3 — Ban-score banning *(implemented)*

A peer accrues points for protocol violations; over a threshold it is banned for a window, and the
score decays over time. The key is the **host** — a peer cannot dodge a ban by rotating port or
path.

- Serving an invalid chain bans on the **first strike**; a too-deep reorg or wrong network costs
  less.
- The peer registry is the **single admission choke point**: a banned host cannot be reintroduced
  via config, `/add_peer`, or PEX.
- The ban list is **fail-closed**.

### P-4 — Rate and size limits *(implemented)*

- Each client is limited by a fixed window over a **bounded** client table (fixing Pandanite #52's
  memory leak).
- Each POST body is capped.
- The sync client **bounds peer response sizes** — a giant `/total_work` string would be an O(n²)
  `BigInteger`-parse CPU DoS.
- Every outbound peer read is size-bounded, **the PEX `/peers` body included**, so a hostile peer
  cannot OOM the node with a giant response on the automatic discovery round.
- A 64 MiB aggregate cap per sync window.
- A **wall-clock deadline over a whole peer exchange**, closing a slow-drip liveness drain.
- A peer that throws or returns garbage mid-probe is **dropped rather than crashing the sync pass**.

### P-5 — Headers-first sync *(implemented)*

Initial sync validates the header chain **before downloading any body**, turning the anti-DoS work
gate from a full-block download into a ~150 B/header one.

`HeaderSynchronizer` (subsuming the older block-based one) finds the common ancestor on headers,
downloads the contested range, and validates statelessly via `HeaderChain`: id continuity, hash
chaining, per-header PoW, difficulty recomputed from header timestamps, median-time-past, the future
bound, and structural uncle limits. It returns the branch's **base-only work**
(`Σ 2^difficulty`, deliberately *excluding* committed uncle difficulties — see
[consensus](../consensus/spec.md) C-7).

Only when base-only proven work strictly exceeds the local branch does it enter **body sync**: fetch
bodies in batches, **verify each against its already-validated header** (hash equality) before
execution, with the same restore-on-failure and orphan-registration a reorg always had.

**The payoff**: a peer that merely *claims* huge total work now costs a bounded header download
(capped per round), after which the work gate refuses it without a single body fetched. A peer
predating `/headers` (404) transparently falls back to the old full-block path — the change is
additive, no wire format changed.

`GET /headers?start=&end=` serves a self-framing binary stream, ~150 B per header versus up to
4 MiB for a full block.

### P-6 — Pruned nodes *(implemented)*

With the body dependency gone (difficulty, median-time, uncle work and vote tallies read only
`headerAt(h)`; nonces are persisted), a node configured with `RHIZOME_PRUNE=N` keeps only the most
recent `N` block bodies plus genesis, all headers, and the transaction index — discarding each body
as it falls out of the window, an amortised O(1) delete in the same write batch as the append.

`N` is **floored at boot** to at least the deepest history the engine can read (reorg window, uncle
depth, difficulty and median-time windows) plus a margin, so pruning can never remove a body a reorg
still needs.

A pruned node mines, validates, serves every header and its recent bodies, and restarts correctly.
`/sync` for a discarded range answers **410 Gone** with the prune watermark (advertised as
`prunedBelow` on `/info`), and the synchroniser routes deep body requests to an **archive peer**
instead of penalising the pruned one.

### P-7 — Snapshot bootstrap transport *(implemented)*

`RHIZOME_SYNC=snap`. Servers materialise a consistent snapshot periodically
(`RHIZOME_SNAPSHOT_EVERY` blocks) under a point-in-time lock and advertise
`(pivotHeight, stateRoot, chunkCount)` on `GET /state/snapshot/info`, serving chunks by index via
`GET /state/snapshot/chunk`. Header validation during bootstrap is **incremental, in bounded
windows**, each chained from the already-validated prefix, stopping at `pivot + maxReorgDepth` —
never the peer's untrusted advertised height. A peer serving cheap invalid headers is rejected after
a single window rather than by first buffering its whole advertised span (an allocation a hostile
seed could otherwise turn into an OOM before any check ran).

Content and root verification: see [state](../state/spec.md).

### P-8 — Outbound peer-token scoping *(implemented)*

`RHIZOME_PEER_TOKEN` is the bearer token for **outbound** peer requests (gossip `/submit` and
`/add_transaction`, PEX fetch/announce, sync GETs). It is sent **only to the peers named in
`RHIZOME_PEERS`, and only over `https://`** — the peer registry is fed by unauthenticated
`/add_peer` and PEX, so gossip-learned or cleartext-http peers never receive the secret. It is
**never logged**.

Required when nodes gate ingest with `RHIZOME_API_TOKEN`; otherwise cross-node pushes are refused
(401) and gossip stops converging.

### P-9 — SSRF / rebinding defence on outbound connections *(implemented)*

- Added peers must resolve to **routable IPs** (SSRF/rebinding filter on by default, opt-out only
  via `RHIZOME_ALLOW_PRIVATE_PEERS`); 6to4/Teredo are rejected.
- The connect target is **re-pinned to the validated literal on every send**.
- **Redirects are refused.**
- Outbound resolution is DNS-cached with a short TTL **over the already-validated address**, so the
  pin's "connect to a validated IP" property is preserved. The resolver cache is an **access-order
  LRU** — an attacker-influenced hostname key with no cap, reachable before the peer-table capacity
  checks, could otherwise accumulate one permanent entry per distinct resolvable name.
- DNS verdicts are computed at **admission time, off the request path**; per-subnet admission
  accounting bounds Sybil.

### P-10 — Sync-round scheduling *(implemented)*

Reshaped without touching consensus order:

- one shared `HttpClient` across rounds;
- a **wall-clock-bounded and fairly-rotated** sync round, so a slow-peer tail cannot starve the
  schedule;
- a body fetch/apply **pipeline** that prefetches the next window while the current one is applied.

The apply stays **strictly serial and in order under the single engine lock**, so the block sequence
and state root are identical. Application is deliberately never parallelised across peers — that
would only waste work and mislabel honest peers `PEER_INVALID`.

## Invariants (must never regress)

- Sync logic depends on `PeerSource`, never on a concrete transport.
- The peer registry is the single admission choke point; a banned **host** cannot re-enter via any
  path.
- Every outbound read is size-bounded and deadline-bounded; a misbehaving peer is dropped, not
  propagated as an exception through the sync pass.
- `RHIZOME_PEER_TOKEN` goes only to configured peers, only over `https://`, and is never logged.
- The connect target is re-pinned to a validated IP literal on every send; redirects refused.
- The work gate ranks branches by **base-only** own-block PoW, consistently at both prefilter and
  adopt (see [consensus](../consensus/spec.md) C-7).
- Block/body application under sync is serial and in-order under the engine lock — never
  parallelised.
- Sync runs off the event loop, in blocking I/O on its own thread.

## Configuration

| Variable | Purpose |
|---|---|
| `RHIZOME_PEERS` | comma-separated initial peers |
| `RHIZOME_PEER_TOKEN` | outbound bearer token (configured peers, https only) |
| `RHIZOME_ADVERTISE` | public URL advertised to peers |
| `RHIZOME_ALLOW_PRIVATE_PEERS` | opt out of the SSRF/private-IP filter |
| `RHIZOME_PRUNE` | keep only the most recent N block bodies |
| `RHIZOME_SYNC` | `snap` to bootstrap from a snapshot pivot |
| `RHIZOME_SNAPSHOT_EVERY` | snapshot materialisation interval (blocks) |

## Open items

Three relay upgrades, in decreasing order of impact — none implemented, all prerequisites for a
sub-5 s target (§6.3):

1. **Compact-block relay (BIP-152 style)** — announce a header plus short transaction IDs; the
   receiver reconstructs from its mempool and requests only missing transactions, so the block on
   the wire shrinks to a few kilobytes regardless of fullness.
2. **Header-first, announce-then-pull** — relay and PoW-check the header immediately and forward it,
   then fetch/reconstruct the body; peers pull a body only if they lack it (`inv`/`getdata`), so
   each node receives a block once rather than from every peer.
3. **Persistent streaming connections** — long-lived links (WebSocket or raw binary) so a header can
   be pushed the instant it is produced, with no per-block TCP/TLS handshake.

The current relay pushes each accepted block **in full** over one-shot HTTP and periodically pulls
ranges over `/sync`.

## References

- `WHITEPAPER.md` §6.1 (transport), §6.3 (propagation and the block-time floor), §6.4 (headers-first
  sync, pruning, snap-sync), §7.3 (resource bounds), §7.4 (network security)
- Pandanite issue #52 (rate-limiter leak)
