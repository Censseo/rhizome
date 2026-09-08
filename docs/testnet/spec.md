# Testnet Specification

> Source of truth for the `staging` network profile and the operator path onto it: what makes
> `staging` different from `mainnet`/`testnet`/`devnet`, and the concrete steps from a bare host
> to a verified, funded node.
> Extracted from `NetworkParameters.staging()`'s own javadoc, `README.md`'s "Join the public
> staging testnet" section, `scripts/local-testnet/deploy/`, and `scripts/local-testnet/faucet/`.
> **Status**: Draft — needs review

## Overview

`staging` (chain id 4, network name `rhizome-staging`) is a **mainnet-faithful public rehearsal
network** — chantier 0, phase 1 of exercising a real launch before mainnet itself exists.
`NetworkParameters.staging()` derives from `cleanMainnet().toBuilder()` and changes only
identity, allocation and the difficulty floor, so every other constant (PUFFERFISH2 from
genesis, the 5 s target, the fee floor, the emission curve's activation height, the decay
schedule, the burn share) is mainnet's, bit-for-bit — the whole point is that this network tests
mainnet's actual tuning, not a scaled-down stand-in for it. That is also what makes `staging`
different in kind from the two other non-mainnet profiles this repository ships: `testnet` and
`devnet` both deliberately swap in `SHA256` for PoW ("the memory-hard Pufferfish2 would only
make local devnets/CI slow" — `NetworkParameters.devnet()`'s own javadoc), so neither one ever
exercises what real mining costs. `staging` is the first profile besides `mainnet` itself to run
real, memory-hard Pufferfish2.

**No public staging seed host is reachable yet.** This is stated plainly in `README.md`'s "Join
the public staging testnet" section and repeated here rather than assumed away: the deployment
artifacts, the genesis-verification tool and the faucet described below are all real and already
work, but there is currently nothing to put in `RHIZOME_PEERS`. Following the steps in this
document today produces a node that boots on the correct chain, is independently verifiable
against its own pinned genesis, and is one `RHIZOME_PEERS`/`RHIZOME_ADVERTISE` edit away from
joining the network the moment a seed host is published — it does not, and cannot yet, produce a
node that is talking to anyone else. Treat `staging` itself as disposable: its genesis
difficulty floor is a development-box placeholder (see O-2 below) pending re-measurement on real
campaign hardware, and once a real campaign's genesis is published it can never be retuned
without every operator wiping their data directory.

Steps O-1 through O-7 below are the whole walk-through, in order. Building the native binary
aside (a `./gradlew` invocation whose duration depends on the host), it is well under fifteen
minutes of operator time from a bare host to a chain-verified, request-funded node.

## Scope

**Owns**

| Area | Source |
|---|---|
| The `staging` network profile itself | [NetworkParameters.java](../../lib-core/src/main/java/rhizome/core/blockchain/NetworkParameters.java) (`staging()`, `byName()`) |
| Pinned staging genesis allocation | [genesis/rhizome-staging.json](../../lib-core/src/main/resources/genesis/rhizome-staging.json) |
| Deployment artifacts (systemd unit, env template, nginx template, container build) | [scripts/local-testnet/deploy/](../../scripts/local-testnet/deploy/) |
| Genesis/chain-identity verification tool | [verify-genesis.sh](../../scripts/local-testnet/deploy/verify-genesis.sh) |
| Test-fund faucet | [scripts/local-testnet/faucet/](../../scripts/local-testnet/faucet/) |

**Does not own**

- The consensus rules `staging` inherits unchanged from `cleanMainnet()` (PoW, timing, emission
  curve, decay schedule, burn share) → [consensus](../consensus/spec.md)
- The full semantics of every `RHIZOME_*` environment variable → [node-api](../node-api/spec.md)
  A-1, and `README.md`'s "Run a node" table
- Pufferfish2 itself (the algorithm, its cost parameters, its golden-vector validation) →
  [crypto](../crypto/spec.md) K-1
- The local, single-machine development/CI harness (`devnet`/`testnet` profiles, the
  `suite-*.sh` batteries, `TEST-PLAN.md`) → [scripts/local-testnet/README.md](../../scripts/local-testnet/README.md).
  That harness exists to develop and regression-test the node; this document exists to run one
  for real. `staging`'s own mirror file in that harness
  (`scripts/local-testnet/profiles/staging.env`) is explicitly **not** meant to be loaded by a
  local battery — see its header comment — it exists only so `TestnetProfileMirrorTest` covers
  the profile too.

## Features

### O-1 — Get the binary *(implemented)*

Two supported paths, both already real and checked into this repository — pick one:

- **Native binary** (what the systemd unit in O-4 assumes): `./gradlew :app-node:nativeImage`
  produces `app-node/build/native/rhizome-node`, a self-contained GraalVM binary with no JVM to
  install alongside it.
- **Container image**: `docker build -f app-node/Dockerfile -t rhizome-node:staging .` from the
  **repository root** (the build context must be the root — the multi-module Gradle build needs
  the whole tree; see the header comment of
  [app-node/Dockerfile](../../app-node/Dockerfile) for why).

Either way you are building the same node; `RHIZOME_NETWORK=staging` (O-3) is what selects the
profile at boot, not a separate binary.

### O-2 — Minimum host requirements (informative — not a measured campaign figure)

`NetworkParameters.staging()`'s own javadoc is explicit that its difficulty floor
(`genesisDifficulty`/`minDifficulty` = 8, versus mainnet's 16) is a **development-box placeholder**,
calibrated from a single benchmark run (`Pufferfish2Benchmark`, 2026-09-06, on a shared 16-core
devbox): single-thread Pufferfish2 hashing measured **18.331 ms/hash ≈ 54.6 H/s**, and 16 threads
together reached only **2.548 ms/hash aggregate ≈ 392.4 H/s** — a ~7.2× speedup over 16×
(~45% efficiency), sub-linear because Pufferfish2 is memory-hard. The javadoc draws one load-bearing
conclusion from that number that matters for host sizing: **mining is single-threaded per node
process** (`BlockProducer` runs `Miner.mineNonce` on one dedicated thread), so a node's own
mining hashrate is bounded by single-core performance, not by how many cores its host has —
adding vCPUs to a miner does not make it mine faster, it only gives the rest of the process
(HTTP/API event loop, peer sync, RocksDB) room to run without contending with the mining thread.

This repository does not have a measured figure for "minimum viable host" and the javadoc says
so explicitly (re-measurement on real campaign VMs is called out as required before any real
public launch) — do not treat what follows as one either. What can be stated with the above as
grounding, not as a substitute for that re-measurement:

- **At least 2 vCPUs** for a mining node: one that can sustain the dedicated mining thread near
  its ceiling, and at least one more so the HTTP surface, sync, and RocksDB are not starved by
  it. A relay-only node (no `RHIZOME_MINER`) never runs that thread at all and is far less
  CPU-sensitive.
- Anyone whose intuition comes from running this repository's own local harness
  (`scripts/local-testnet/`) should recalibrate it: `devnet` and `testnet` both deliberately run
  `SHA256` instead of Pufferfish2 specifically because the memory-hard KDF "would only make
  local devnets/CI slow" (`NetworkParameters.devnet()`'s own javadoc) — so a laptop that mines
  a local devnet block instantaneously is not evidence about what mining `staging`'s real
  Pufferfish2 costs.
- Memory footprint per hash is small in absolute terms — Pufferfish2's cost parameter
  `cost_m = 8` allocates `2^(cost_m+10)` = 256 KiB of s-box working set per hash
  (`PowCosts`'s own javadoc) — so it is the *access pattern*, not the footprint, that taxes CPU
  (cache misses, memory bandwidth) and produces the millisecond-scale per-hash cost above; RAM
  sizing should instead follow the same guidance any RocksDB-backed node needs (JVM heap or, for
  the native binary, its much smaller RSS, plus the block cache) — see
  [persistence](../persistence/spec.md) for that guidance, unrelated to Pufferfish2.

### O-3 — A copy-pasteable `node.env` *(implemented)*

Start from [node.env.example](../../scripts/local-testnet/deploy/node.env.example) — it is
already written for this profile (its first uncommented line is `RHIZOME_NETWORK=staging`) and
every field carries its own comment explaining when to change it; this document does not
duplicate that content. The two things worth calling out before you copy it:

- `RHIZOME_SNAPSHOT` should stay **unset**: the `staging` profile's pinned genesis allocation
  (`genesis/rhizome-staging.json`) is already compiled into the jar/native image, so the node
  loads it automatically.
- `RHIZOME_PEERS` and `RHIZOME_ADVERTISE` are left blank in the example on purpose (O-1's "no
  seed host yet" caveat) — leave them unset for now and fill them in once a seed is published,
  or point them at a peer you are standing up yourself.

Copy it to `/etc/rhizome/node.env`, mode `0640`, owned `root:rhizome` (it can hold bearer
tokens) — the exact target O-4's systemd unit reads from.

### O-4 — systemd install *(implemented)*

[rhizome-node.service](../../scripts/local-testnet/deploy/rhizome-node.service) is the real unit
this session shipped. The six lines below are its own header comment's install procedure,
reproduced verbatim (not a different one); the line above them is not literally in that comment
but follows directly from it — the header states `ExecStart` assumes the binary lands at
`/usr/local/bin/rhizome-node`, "the same absolute path `app-node/Dockerfile`'s runtime stage
COPYs it to", without spelling out the copy itself:

```bash
# Not from the unit file's own comment, but the path it documents (O-1's binary):
install -o root -g root -m 0755 app-node/build/native/rhizome-node /usr/local/bin/rhizome-node

# Verbatim from rhizome-node.service's own header comment:
install -o root -g root -m 0644 rhizome-node.service /etc/systemd/system/rhizome-node.service
useradd --system --no-create-home --shell /usr/sbin/nologin rhizome   # once, if absent
install -d -o root -g root -m 0750 /etc/rhizome
install -o root -g rhizome -m 0640 node.env.example /etc/rhizome/node.env   # then edit it
systemctl daemon-reload
systemctl enable --now rhizome-node.service
```

Run the last five (all but the binary copy) from inside `scripts/local-testnet/deploy/`, where
`rhizome-node.service` and `node.env.example` live. The unit itself is worth reading
before enabling it — it documents its own shutdown budget (`TimeoutStopSec=180s`, sized against
`RhizomeNode.close()`'s real ~110 s worst-case ordered unwind), its `StateDirectory=rhizome-node`
(materialises `/var/lib/rhizome-node`, matching the `RHIZOME_DATA` line `node.env.example` tells
you to uncomment for a systemd deployment), and its hardening posture. It was authored and
reviewed by hand against `systemd.service(5)`/`systemd.exec(5)`/`systemd.kill(5)` syntax but
**not run through `systemd-analyze verify`** on a live system, and no unit here has actually been
enabled or started — see [Known limits](#known-limits-accepted-not-defects) below.

For a container deployment instead, use `app-node/Dockerfile` (O-1) with `--env-file` pointed at
the same `node.env`; see `README.md`'s "Join the public staging testnet" section for the pointer
to `nginx-rhizome.conf.example` if you are terminating TLS in front of either path.

### O-5 — Verify you're on the right chain *(implemented)*

Two independent checks, both against the freshly booted node's own HTTP surface — do both, they
catch different mistakes:

1. **`GET /info`** and read `chainId` and `network` yourself: `staging` is `chainId = 4`,
   `network = "rhizome-staging"`. A node that booted with a typo'd `RHIZOME_NETWORK` or an
   accidentally-inherited `mainnet` default will show a different pair immediately — the node
   already refuses to boot on an unrecognised network name (`byName` in
   [NetworkParameters.java](../../lib-core/src/main/java/rhizome/core/blockchain/NetworkParameters.java)),
   so what you are really checking here is that it booted the network **you** intended.
2. **[verify-genesis.sh](../../scripts/local-testnet/deploy/verify-genesis.sh)** — the same check,
   scripted, plus an optional third check against the genesis block's actual hash:

   ```bash
   scripts/local-testnet/deploy/verify-genesis.sh http://127.0.0.1:3000 \
     --chain-id 4 --network rhizome-staging
   ```

   Because no seed host exists yet (O-1), there is no third party's `--genesis-hash` to compare
   against today; run it anyway to pin your own node's reported values, and hand that command
   line (or its `--genesis-hash` extension, once you know what your node reports at
   `/block?blockId=1`) to the next operator who wants to verify *your* node before peering with
   it — that is the tool's actual intended use once a network of more than one node exists. It
   takes no dependency beyond `bash` and `curl` deliberately, so it runs on the same deployment
   box as `rhizome-node.service` without pulling in this repository's Python tooling.

### O-6 — Request test funds from the faucet *(implemented)*

[scripts/local-testnet/faucet/faucet.py](../../scripts/local-testnet/faucet/faucet.py) is a
standalone, stdlib-only Python service — not a node route, not a dashboard page — that hands out
`staging`-network coins by shelling out to the `app-wallet` CLI. Its real routes, as implemented:

- **`GET /challenge`** issues a fresh, single-use hashcash-style proof-of-work puzzle:
  `{"nonce", "difficultyBits", "expiresAt"}`. Solving it means finding a `solution` such that
  `sha256(nonce + ":" + solution)` has at least `difficultyBits` leading zero bits — the faucet's
  own `GET /` page does this in the browser with the Web Crypto API, no third-party script.
- **`POST /drip`** with `{"address", "nonce", "solution"}` — malformed input, or a
  bad/expired/already-consumed challenge, is rejected before the wallet CLI is ever invoked.
  Responses: `200` with `{"status": "SUCCESS", "amountBaseUnits", ...}`, `400` malformed
  request or failed PoW, `429` the address is in cooldown or the source IP hit its daily cap,
  `503` the daily faucet budget is exhausted or the node/wallet CLI could not complete the
  transfer.
- **`GET /status`** reports `{"nodeReachable", "faucetAddress", "dailyBudget*",
  "dripBaseUnits", ...}` — no secrets.

Whoever operates a `staging` faucet publishes its base URL alongside the seed peers this document
cannot yet name; there is no faucet running against a real `staging` network today either, for
the same reason. See [faucet/README.md](../../scripts/local-testnet/faucet/README.md) for the
full flag/env-var table, key custody, and — importantly — its own honest statement of what the
proof-of-work and per-IP controls do and do not defend against.

### O-7 — Where to report problems, and what "degraded" means *(implemented)*

Before filing anything, check the node's own two operator-facing health fields on `GET /stats`
(also on `GET /metrics` in OpenMetrics form) — see `README.md`'s
["Node health signals"](../../README.md#node-health-signals) section, which is the source of
truth for both and is not re-derived here to avoid the two drifting apart:

- `reorgInProgress` — a reorg window is open; block production pauses and the node keeps serving
  its pre-reorg tip. Expected, self-resolving behaviour during a deep resync, not a fault.
- `degraded` — non-`null` means chain integrity is suspect (a reorg restore failed, or a
  peripheral store failed to revert). This is a hard barrier: the node stops progressing
  entirely until the underlying cause clears or, for a torn pop specifically, until an operator
  restart. `null` is healthy; alert on anything else.

`journalctl -u rhizome-node` is where the systemd unit's stdout/stderr lands (`Type=simple`, no
separate log file configured). Because `staging` has no seed hosts and no operating body yet
(O-1), there is no shared issue tracker or chat channel to name here; report against whatever
this repository's own contribution path is at the time you are reading this, and include the
`/info` and `/stats` output from O-5/this section — both are safe to paste, since neither is
gated by `RHIZOME_API_TOKEN` on a default (non-`RHIZOME_PROTECT_READS`) node.

## Known limits (accepted, not defects)

Deployment-shaped gaps in the `staging` story specifically — narrower than
[node-api](../node-api/spec.md)'s own "Known limits", which cover the HTTP surface generally.

- **No public seed host exists yet.** Every step above produces a correct, verifiable,
  standing-alone node; none of them can currently connect it to a wider `staging` network,
  because there is not yet one to connect to. `RHIZOME_PEERS`/`RHIZOME_ADVERTISE` in O-3 stay
  blank until one is published.
- **The deployment artifacts are unvalidated against live infrastructure.** The systemd unit
  (O-4) and the nginx template `README.md` points at were authored and reviewed by hand on a box
  with no running `systemd`/`nginx` to check them against — their own header comments say so —
  and neither has been enabled/started/reloaded against a real service manager. Validate on your
  own infrastructure before depending on either.
- **The genesis difficulty floor is an admitted placeholder.** `genesisDifficulty`/
  `minDifficulty = 8` was sized from a single-machine benchmark (O-2), explicitly not a
  measurement of real campaign hardware, and is load-bearing forever once published (it is part
  of the genesis block's hash preimage) — a real public launch re-derives it first.
- **No canonical published genesis hash to check against.** O-5's `verify-genesis.sh` supports
  a `--genesis-hash` check, but with no seed host there is no publisher of that value yet; using
  the tool today only pins your own node's self-reported values for later comparison.
- **No `staging` faucet is running.** O-6 describes real, working code with no live deployment
  behind it yet, for the same reason as the network itself.

## References

- `README.md` — "Run a node" (the `RHIZOME_NETWORK` table entry for `staging`) and
  ["Join the public staging testnet"](../../README.md#join-the-public-staging-testnet)
- [NetworkParameters.java](../../lib-core/src/main/java/rhizome/core/blockchain/NetworkParameters.java)
  — `staging()`'s own javadoc is the primary source for O-1/O-2/Known limits
- [node-api](../node-api/spec.md) A-1 (environment configuration), A-13 (fail-fast genesis boot)
- [crypto](../crypto/spec.md) K-1 — Pufferfish2 itself
- [scripts/local-testnet/README.md](../../scripts/local-testnet/README.md) — the local
  single-machine harness this document is deliberately not
- `WHITEPAPER.md` §3.2 (Pufferfish2)
