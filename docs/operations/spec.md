# Operations Specification

> Source of truth for running Rhizome node(s) — and the faucet that funds a public test
> network — as a real deployment: network profile selection, reverse-proxy/TLS hardening,
> telemetry surfaces, disk/pruning, key custody and backup posture, token rotation, and the
> upgrade procedure across a consensus activation height.
> Extracted from `README.md`, `NodeConfig.java`, `scripts/local-testnet/deploy/`,
> `scripts/local-testnet/profiles/`, `scripts/local-testnet/faucet/`, and source analysis.
> **Status**: Draft — needs review

## Overview

Every other domain spec in this tree describes what a *single* node does. This one describes
what an *operator* does with one or more of them: which network profile to boot, how to put a
node safely behind a reverse proxy, what to watch, what to back up, how to rotate a shared
secret across a fleet without splitting it, and how to carry the fleet across a consensus
activation height without forking it by accident. `scripts/local-testnet/deploy/` is the
reference deployment shape this spec documents — a single mainnet-faithful `staging` network
(chainId 4, [consensus](../consensus/spec.md) C-10/C-11 unchanged from mainnet, its own pinned
genesis allocation and its own difficulty floor) rehearsed on one development box before any
real multi-VM campaign exists. Where the harness could only exercise a property in a
single-box degraded form, this spec says so plainly (Known limits) rather than claiming
coverage the campaign never had.

## Scope

**Owns**

| Area | Source |
|---|---|
| Network profile selection & `RHIZOME_NETWORK` | [NetworkParameters.java](../../lib-core/src/main/java/rhizome/core/blockchain/NetworkParameters.java), [NodeConfig.java](../../app-node/src/main/java/rhizome/node/NodeConfig.java) |
| Deployment artifacts (systemd units, env file, reverse proxy) | [scripts/local-testnet/deploy/](../../scripts/local-testnet/deploy/) |
| Genesis identity verification | [verify-genesis.sh](../../scripts/local-testnet/deploy/verify-genesis.sh) |
| Telemetry surfaces (`/stats`, `/metrics`) | [DashboardApi.java](../../app-node/src/main/java/rhizome/node/DashboardApi.java) |
| Sync health accounting | [SyncDriver.java](../../app-node/src/main/java/rhizome/node/SyncDriver.java) |
| Disk retention (`RHIZOME_PRUNE`) | [NodeConfig.java](../../app-node/src/main/java/rhizome/node/NodeConfig.java) |
| Testnet faucet (a separate process, not a node route) | [scripts/local-testnet/faucet/](../../scripts/local-testnet/faucet/) |
| Local multi-node test harness & its five newest batteries | [scripts/local-testnet/](../../scripts/local-testnet/) |
| Step-by-step incident procedures | [runbooks.md](runbooks.md) |

**Does not own**

- What each route/field *means* — the node-facing behaviour behind every setting here belongs
  to the domain that implements it: token gating and CSRF/rebinding →
  [node-api](../node-api/spec.md); the SSRF/private-peer filter, peer tokens, ban scoring →
  [networking](../networking/spec.md); the reorg/finality window and activation-height
  mechanism itself → [consensus](../consensus/spec.md); RocksDB column families and undo
  journals → [persistence](../persistence/spec.md). This spec is the operator-facing *how*, not
  a restatement of those domains' *what*.
- The exploit-scenario catalogue → [adversarial](../adversarial/spec.md); this spec cites
  specific scenarios where a deployment choice exists *because* of one, but does not duplicate
  the catalogue.

## Features

### O-1 — Network profile selection *(implemented)*

`RHIZOME_NETWORK` selects one of exactly four profiles through
`NetworkParameters.byName(String)` — `mainnet` (default when unset), `testnet`, `devnet`, or
`staging`; any other value is a hard startup failure by design (a typo must never silently
start an operator on mainnet). `staging` (chainId 4) is the mainnet-faithful public network this
deployment shape targets: it derives from `cleanMainnet().toBuilder()` and changes only its
identity, its own pinned genesis allocation
(`lib-core/src/main/resources/genesis/rhizome-staging.json`, already compiled into the
jar/native image — no separate fetch or mount needed, and `RHIZOME_SNAPSHOT` should normally
stay unset for it) and its difficulty floor (`genesisDifficulty`/`minDifficulty` = 8, see O-9's
sibling note in Known limits) — every other consensus constant (PUFFERFISH2, 5 s block target,
120-block finality window, the emission curve's activation height, decay schedule and burn
share) is mainnet's, unchanged, because this profile exists to rehearse mainnet's tuning, not to
diverge from it.

### O-2 — Reverse-proxy TLS termination and `RHIZOME_TRUST_XFF` *(implemented)*

The documented posture: bind the node to loopback (`RHIZOME_BIND_ADDRESS=127.0.0.1`, the
default) and put [nginx-rhizome.conf.example](../../scripts/local-testnet/deploy/nginx-rhizome.conf.example)
in front for TLS termination — never bind the node's own port non-loopback with
`RHIZOME_TRUST_XFF=true`, which would let any client forge the header the node trusts to key
rate limits, push-strike tables and scan ownership (see [node-api](../node-api/spec.md)
Known limits and README's own words on the flag). The one rule the reference nginx config exists
to enforce: `X-Forwarded-For` must be **overwritten** with `$remote_addr`, never appended to
with `$proxy_add_x_forwarded_for` — nginx's common shorthand appends, which would still let an
attacker's own forged header value reach the node as the *first* hop `NodeApi.resolveXffHop`
reads, defeating `RHIZOME_TRUST_XFF` while looking safe. `RHIZOME_ALLOWED_HOSTS` must additionally
list the proxy's advertised hostname, or the DNS-rebinding `Host` allowlist
([node-api](../node-api/spec.md) A-3) refuses traffic the proxy itself forwards legitimately.

### O-3 — Deployment artifacts *(implemented, staging-shaped, unvalidated against a live systemd/nginx)*

[node.env.example](../../scripts/local-testnet/deploy/node.env.example) is the field-by-field
mirror of `NodeConfig`'s own defaults and validation — "if they and this file ever disagree,
they win" is stated in the file's own header. Copy it to `/etc/rhizome/node.env` (mode `0640`,
owned `root:rhizome`, since it can hold bearer tokens), fill in the values marked REQUIRED for a
publicly reachable node, and drive it with
[rhizome-node.service](../../scripts/local-testnet/deploy/rhizome-node.service) (systemd
`EnvironmentFile=`, no leading `-` — a missing env file is a misconfiguration that must refuse
to start, not a silent mainnet boot). Both the systemd unit and the nginx config were authored
and hand-checked against their respective directive syntax on a box with neither `systemctl`
nor `nginx` installed — neither has been run through `systemd-analyze verify` or `nginx -t`
against a live instance; run those yourself before trusting either in production (see each
file's own header comment for exactly what was and was not checked).

[verify-genesis.sh](../../scripts/local-testnet/deploy/verify-genesis.sh) checks that a
deployed node's `/info` (`chainId`, `network`) and block-1 hash (`/block?blockId=1`) match
caller-supplied expected values — never hard-coded in the script, so it verifies any of the
repo's pinned networks, not only `staging` — before an operator trusts it or adds it as a peer.

**A stale guess, flagged rather than silently propagated.**
[rhizome-faucet.service](../../scripts/local-testnet/deploy/rhizome-faucet.service)'s header
says it is a template authored *before* any `faucet.py` existed in this repository, and its
`ExecStart` guesses flag names accordingly. A real `faucet.py` now exists (O-8) and its actual
flags disagree with that guess: the unit's `--keyfile`/`--listen-port` must be corrected to
`--key-file`/`--port` (the real flags — see O-8 and
[faucet/README.md](../../scripts/local-testnet/faucet/README.md)) before this unit is used
as-is; it has not been updated to match.

### O-4 — Telemetry: `/stats` and `/metrics` *(implemented)*

`GET /stats` is the JSON operator surface: `height`, `tipHash`, `difficulty`, `peers`,
`mempool`, `avgBlockIntervalMs`, `reorgInProgress` (bool), `degraded` (string|null — see
README's "Node health signals" section, restated verbatim in
[runbooks.md](runbooks.md) RB-02 rather than re-defined), `syncRoundsWithoutProgress`,
`syncPeersBanned`, `prunedBelow`, plus the emission fragment
([node-api](../node-api/spec.md) A-16). `GET /metrics` is a pure OpenMetrics/Prometheus text
rendering of the **same** per-tip cache `/stats` reads (`DashboardApi.metrics`) — a scraper's
poll costs the read budget nothing beyond what `/stats` already costs, no new instrumentation,
no new lock. Same cost/guard classification as `/stats`
(`STATS_WINDOW` cost, `READ_BUDGET` gate, [node-api](../node-api/spec.md) A-14's reorg-window
503). Exported gauges: `rhizome_height`, `rhizome_difficulty`, `rhizome_total_work` (a `double`
— the exact `BigInteger` detail stays JSON-only on `/stats`), `rhizome_peers`,
`rhizome_mempool_size`, `rhizome_avg_block_interval_ms`, `rhizome_last_block_timestamp_seconds`,
`rhizome_reorg_in_progress` (0/1), `rhizome_degraded` (0/1 — the *string* is `/stats`-only;
`/metrics` collapses it to a boolean gauge, since OpenMetrics gauges carry no string payload),
`rhizome_sync_rounds_without_progress`, `rhizome_sync_peers_banned`, `rhizome_sync_eclipsed`
(0/1), `rhizome_pruned_below`, `rhizome_supply_base_units` (**omitted**, not `0`, on a chain
that has not committed a supply — the null-vs-value convention `/stats` already follows for
`degraded`/`stateRoot`), `rhizome_max_reorg_depth`.

A healthy node's `/metrics` scrape, in `monitor.sh`'s own convention (see
[monitor.sh](../../scripts/local-testnet/monitor.sh)): `rhizome_reorg_in_progress == 0`,
`rhizome_degraded == 0`, `rhizome_sync_eclipsed == 0`, `rhizome_sync_rounds_without_progress`
low and not climbing, `rhizome_height` advancing between scrapes at roughly the network's block
target, and — for the operator's own disk, which no gauge here reports — `df` on the
`RHIZOME_DATA` volume comfortably clear of capacity (see O-5; nothing under `/metrics` or
`/stats` reports free disk, so that check stays external to the node).

### O-5 — Disk retention and pruning *(implemented)*

`RHIZOME_PRUNE` (absent or `0` = archive, keep every body forever — the default, and by far the
largest driver of unbounded disk growth) sets how many recent block bodies a node retains.
`NodeConfig.parseKeepBlocks` enforces a documented safe floor rather than letting an operator
guess a value the engine cannot actually run on:

```
floor = max(maxReorgDepth, uncleMaxDepth, difficultyLookback, medianTimeWindow) + PRUNE_MARGIN(128)
```

(`NodeConfig.PRUNE_MARGIN`, package-private specifically so `TestnetProfileMirrorTest` and
`scripts/local-testnet/tools/ProfileDump.java` recompute the identical floor rather than each
hand-copying the constant). On mainnet/staging (`maxReorgDepth = 120`) that floor is **248**
blocks — the exact figure `profiles/staging.env`'s `PRUNE_FLOOR` mirrors. A `RHIZOME_PRUNE`
value below the floor for the running profile is a **startup refusal**
(`IllegalArgumentException` naming the floor), not a silently-corrupting prune of a body the
engine still needs mid-reorg. Reads below the resulting watermark answer `410 GONE` rather than
a generic error ([node-api](../node-api/spec.md) A-6), and `prunedBelow` is advertised on
`/info`.

### O-6 — Backup and key custody *(operational doctrine)*

**Chain data is disposable.** `RHIZOME_DATA` (RocksDB) is reconstructible from any honest peer
— full replay from genesis, or `RHIZOME_SYNC=snap` against a peer serving a recent state
snapshot — so it is not something this deployment shape backs up. The one operational cost of
losing it is resync time, not data loss.

**Key material is not.** The only artifacts that cannot be regenerated if lost are: any signing
key (a miner's `RHIZOME_MINER` key, the faucet's `--key-file`, any operator/wallet key), and any
shared bearer secret (`RHIZOME_API_TOKEN`, `RHIZOME_PEER_TOKEN`) that gates a running
deployment. None of these belong in `RHIZOME_DATA`, none belong in a snapshot, and — per O-8 —
the faucet's key must never be committed to this git repository. Back these up the way any
private-key material is backed up (offline, encrypted, access-logged); this spec does not
prescribe a specific mechanism, only the boundary: back up keys and tokens, never bother backing
up the chain data itself.

### O-7 — Faucet as a separate, unprivileged process *(implemented)*

[faucet.py](../../scripts/local-testnet/faucet/faucet.py) is deliberately **not** a node route
and **not** a dashboard page (its own docstring: a node route would ship a signing-key surface
into every mainnet binary forever; a dashboard page would ship the key to every browser that
loads it — the dashboard's whole design property is that neither the node nor any page it
serves ever sees a private key). It holds exactly one key, talks to a node the same way any
wallet does (shelling out to `app-wallet`'s `send`), and persists its own rate-limit state to a
JSON file so a restart cannot reset a cooldown or refill the daily budget. Its abuse controls —
per-address cooldown, per-IP daily cap, a hashcash-style proof-of-work challenge on `/drip` — are
stated in its own README as friction, not an identity system; the actual backstop is the global
daily budget (`--daily-budget-pdn`), which bounds the faucet's total loss regardless of how
requests were distributed, combined with the fact that this is test-network money with no value
outside the network it was minted on. `GET /status` reports `dailyBudgetBaseUnits`,
`dailyBudgetSpentBaseUnits`, `dailyBudgetRemainingBaseUnits`, `nodeReachable`, `faucetAddress`
and the current rate-limit constants — no secrets (no key path, wallet binary path, or
passphrase file path). Since it is a fully separate OS process from the node, stopping it never
touches node health — see [runbooks.md](runbooks.md) RB-06.

### O-8 — Upgrade procedure across a consensus activation height *(operational doctrine)*

A Rhizome upgrade that moves or introduces a consensus activation height (`powUpgradeHeight`,
`consensusV2Height`, `boxActivationHeight`, `tokenActivationHeight`,
`emissionCurveHeight`/the decay schedule's `decayStartHeight`, or a new one on the same
mechanism — see [consensus](../consensus/spec.md) C-10, WHITEPAPER §5 "coordinated activation
height") is **a binary swap, not a data migration**: these constants are compiled into
`NetworkParameters` per network profile, not read from an environment variable or a config file,
so upgrading means installing a new build of the node binary — `RHIZOME_DATA` is untouched and
needs no conversion.

The one thing that must happen **before** the chain reaches the activation height, on **every**
node whose vote or blocks the fleet must trust: agreement that they are all running a binary
whose `NetworkParameters` states the *same* height for the *same* rule. A fleet split between an
old binary (rule inactive at that height) and a new one (rule active) does not fail loudly —
each side keeps validating internally-consistent blocks under its own rule and the two branches
diverge exactly at the boundary height, the shape
[E2E-91](../adversarial/spec.md) exercises directly
(`E2EActivationSkewTest#nodesWithSkewedConsensusV2HeightsDivergeExactlyAtTheEarlierBoundaryAndTheStricterNodeNeverAdoptsTheRefusedBlock`).
The procedure is therefore: stage the new binary on every node ahead of the target height,
confirm via each node's own build/version (there is no `/version` route today — confirm by
binary checksum or deployment record) that the whole seed set is on the new build, *then* let
the chain progress past the activation height — never the reverse order.

### O-9 — Single-machine test harness scope *(implemented, single-box)*

`scripts/local-testnet/` is a real multi-node harness (up to 30 full RocksDB nodes, real HTTP,
real PoW at test-scaled difficulty) but it has run only on one shared development box, never
across real, separately-networked VMs. Five batteries were added specifically to close gaps a
single-box run cannot otherwise exercise, and each states its own remaining single-box gap in
its own header comment rather than claiming multi-VM coverage it does not have:

- **`suite-tls.sh`** — builds its own TLS-terminating relay (a Python byte relay,
  `tools/tls_proxy.py`, since none of nginx/caddy/socat/stunnel is installed on the authoring
  box) and its own `RHIZOME_TRUST_XFF`/peer-token nodes to exercise paths the main campaign
  skips entirely (it runs plaintext `http://`, no token — TLS and `RHIZOME_PEER_TOKEN` are
  explicitly out of that campaign's scope). Its own noted limit: certificates are disposable and
  generated on the fly (never committed, never rotated, no real public CA), and "another
  machine" for its XFF-03 case is simulated only by a second network interface on the *same*
  host — proving unreachability from a genuinely separate host needs a second physical/VM node.
- **`suite-dos.sh`** — floods a live node's `/submit` with PoW-free blocks from the *same*
  machine that also observes the result, so the measured throughput is a floor, not a ceiling: a
  real distributed attacker also evades the per-IP limiter and the per-client push-strike table
  that, here, end up throttling the test's own flood after a few dozen rejections.
- **`suite-clock.sh`** — the first battery to skew a real process's OS clock (via `LD_PRELOAD`
  `libfaketime`, never the shared machine's real clock) rather than a JVM-internal clock seam or
  a forged-but-honestly-clocked block; falls back to a documented `SKIP` verdict (never a false
  `PASS`) when neither a system `faketime` nor an offline-extractable one is available.
- **`suite-deep-reorg.sh`** — the only battery that has ever actually driven two camps past
  `maxReorgDepth` (120 blocks) from a common fork point, proving `REORG_TOO_DEEP` heals cleanly
  with **no** ban score post-fix. Its partition is PEX-seeding restriction only (`start.sh -p
  <lo>-<hi>`) — no real firewall — and it accelerates block cadence to make the depth reachable
  in minutes; a real multi-VM campaign would add a genuine network cut, asymmetric
  latency/bandwidth between camps, and the network's real 5 s cadence (~10–11 minutes per camp
  at nominal cadence, uncontended).
- **`suite-soak.sh`** — reduces the existing `monitor.sh` + `sim-tx.sh` + `sim-contract.sh` CSVs
  into first-class PASS/FAIL/METRIC verdicts over a **bounded, short** run (minutes, sized for a
  shared machine); it explicitly cannot surface a slow leak (heap, file descriptors, super-linear
  RocksDB growth) that only shows up after hours or days of continuous operation, and shares
  every other single-box caveat (`suite-net.sh`/`suite-pow.sh`/`suite-bootstrap.sh`'s: loopback
  latency is optimistic, no real cross-machine disk/CPU contention).

`scripts/local-testnet/profiles/*.env` (`devnet.env`, `staging.env`) are checked-in mirrors of
the live `NetworkParameters` constants for each profile, regenerated by
`scripts/local-testnet/tools/ProfileDump.java` and consumed by `TestnetProfileMirrorTest` so the
mirror cannot silently drift from the source of truth it mirrors.

## Invariants (must never regress)

- A node with `RHIZOME_TRUST_XFF=true` must never bind a non-loopback address directly reachable
  by anything other than its own trusted reverse proxy — the flag trusts the *first* hop of a
  client-controlled header, and a directly-reachable port lets any client forge it.
- The faucet's signing key must never appear in this git repository, in a build artifact, or in
  a log line — `faucet.py` checks its key file's permission bits at startup and warns (never
  refuses) on an over-permissive mode, but the boundary itself — key outside the working tree,
  outside version control — is an operator responsibility this spec states as absolute.
- `RHIZOME_PRUNE` is refused at startup below its computed safe floor; nothing in this
  deployment shape may bypass that check to save disk.
- A shared bearer token (`RHIZOME_API_TOKEN`/`RHIZOME_PEER_TOKEN`) rotation must never be
  verified as "done" from a single node's perspective — convergence only holds once **every**
  node in the trust set presents the same value, and the node config supports no dual-accept
  grace window ([runbooks.md](runbooks.md) RB-05).
- A consensus activation height is a compiled-in constant; upgrading across one requires the
  **whole seed set** to be running a binary that agrees on that height before the chain reaches
  it, never after.
- Chain data (`RHIZOME_DATA`) is always reconstructible from a peer and is never the thing this
  spec asks an operator to back up; key material and shared tokens are the only artifacts that
  are not reconstructible and are the only things this spec asks to be backed up.
- A single-box test-harness result is never represented as multi-VM/multi-host coverage; each
  battery in `scripts/local-testnet/` states its own remaining single-box gap rather than
  omitting it.

## Known limits (accepted, not defects)

- **No non-destructive `REORG_TOO_DEEP` recovery exists.** A node stranded past the reorg
  finality window on a confirmed minority branch has exactly one recovery path today: wipe
  `RHIZOME_DATA` and resync (full replay or `RHIZOME_SYNC=snap`) from a peer serving the
  canonical branch — see [runbooks.md](runbooks.md) RB-01. A non-destructive rewind-to-height
  feature would touch the consensus lock and reorg machinery days before a real launch; it is
  deliberately out of scope here rather than rushed in.
- **`staging()`'s `genesisDifficulty = 8` is a development-box placeholder, not a measurement of
  real campaign hardware.** It is derived from a `Pufferfish2Benchmark` run on the single shared
  development box this session used (measured single-thread ≈ 54.6 H/s, 16-thread aggregate ≈
  392.4 H/s at ~45% parallel efficiency — memory-hard PoW scales sub-linearly with cores), then
  margined against a generously estimated campaign aggregate hashrate. `genesisDifficulty` is
  part of the genesis block's hash preimage — it cannot change after this profile's genesis is
  published without forcing every operator to wipe their data directory. **Before any real
  public `staging` launch, `Pufferfish2Benchmark` must be re-run on the actual campaign VMs and
  this floor re-derived**, not inherited from a development box's numbers (see
  `NetworkParameters.staging()`'s own Javadoc for the full derivation).
- **This session's entire harness ran on one shared development box, never real, separately
  networked VMs.** Every "multi-node" property this spec or its runbooks describe as tested —
  TLS termination, `RHIZOME_TRUST_XFF` behind a proxy, a genuine network partition, disk/CPU
  contention between independently-loaded machines — was validated in a *single-box degraded
  form* (a Python byte-relay instead of a real TLS terminator; a PEX-seeding restriction instead
  of a firewall cut; every peer sharing one page cache, one disk, one clock). See each battery's
  own header comment (O-9) for exactly what it could and could not prove on this hardware, and
  treat this spec's "implemented" tags as "implemented and exercised in single-box degraded
  form," not "validated at multi-VM scale."

## References

- `README.md` — the authoritative `RHIZOME_*` configuration table and "Node health signals"
- `WHITEPAPER.md` §5 (coordinated activation height mechanism), §6 (networking)
- [consensus](../consensus/spec.md) C-7 (finality window), C-10/C-11 (activation heights, genesis
  pin)
- [node-api](../node-api/spec.md) A-2/A-3 (token gating, CSRF/rebinding), A-14 (reorg-window
  gate), Known limits (`X-Forwarded-For`)
- [networking](../networking/spec.md) P-8 (`RHIZOME_PEER_TOKEN` scoping), P-9 (SSRF/private-peer
  filter)
- [adversarial](../adversarial/spec.md) — `E2E-91` (activation-height skew), `POW-08`, `TIME-06`,
  `GENESIS-04`
- `scripts/local-testnet/TEST-PLAN.md` — the local campaign's own stated scope and out-of-scope
  list
