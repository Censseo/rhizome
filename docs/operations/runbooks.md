# Operations Runbooks

> Companion to [operations](spec.md): step-by-step procedures for the incidents an operator of a
> Rhizome deployment actually hits. Each runbook follows the same shape — **Symptom → Confirm →
> Act → Verify → Escalate** — so it stays scannable under pressure. Field names below (`/stats`,
> `/metrics`) are exact and cross-checked against
> [DashboardApi.java](../../app-node/src/main/java/rhizome/node/DashboardApi.java) and
> [SyncDriver.java](../../app-node/src/main/java/rhizome/node/SyncDriver.java); do not rename them
> when acting on this document.

## RB-01 — Node stuck past the reorg horizon (`REORG_TOO_DEEP`) / stranded on a minority branch

**Symptom**

- Height is stalled; `/stats`' `syncRoundsWithoutProgress` (or `/metrics`'
  `rhizome_sync_rounds_without_progress`) is climbing, past
  `SyncDriver.PROGRESS_WARN_ROUNDS` (6 consecutive rounds) worth of WARN log lines.
- The node's logs contain `"is past the reorg horizon (finality); nothing to adopt"` — the exact
  message `SyncDriver` logs for a peer whose chain the engine judged `REORG_TOO_DEEP`
  ([consensus](spec.md) C-7's finality window, `maxReorgDepth`).
- Crucially: **`syncPeersBanned` (`/stats`) / `rhizome_sync_peers_banned` (`/metrics`) stays
  `0`.** This is deliberate, not a bug to chase — `REORG_TOO_DEEP` carries **no** ban score by
  design (`SyncDriver.PENALTY_INVALID`'s own comment: a branch past the reorg horizon is not
  misbehaviour, the peer cannot help how deep its fork is; scoring it locked two forked mining
  camps into a mutual renewed-hourly ban in a past campaign replay). If you see ban counts
  climbing alongside this symptom, you are looking at a **different** condition — re-check
  against RB-02/RB-03 before following this runbook further.

**Confirm**

1. This is a **network** split, not a fault local to this one node. Query `/stats` (or `/info`)
   on **at least two other, independently operated peers** and compare `height`, `tipHash`, and
   the chain's cumulative work (`/total_work`) against this node's own. A genuine
   `REORG_TOO_DEEP` situation shows two (or more) camps that each internally agree with their own
   peers but disagree with the other camp by more than `maxReorgDepth` (120 blocks on
   mainnet/staging) since their last common ancestor.
2. Confirm this node's own chain integrity is intact: `/stats`' `degraded` must be `null`. If
   `degraded` is non-null, this is RB-02, not RB-01 — a `REORG_TOO_DEEP` peer response is a
   perfectly healthy outcome for a node whose own state is sound.
3. Determine which side of the split this node is on by **cumulative work**
   ([consensus](spec.md) C-7: work is compared base-only, no uncle term, strictly-greater to
   adopt) — not by height alone, and not by asking the node itself, which cannot see the other
   branch's true weight past its own reorg window. Corroborate with multiple independent peers,
   not just one.

**Act**

- **Do not "fix" a healthy node that is honestly on the minority side of a fork race.** A
  structurally valid but lighter branch is a lost fork race, not a protocol violation — the node
  stays connected to its peers and keeps mining on its own view exactly as designed. Forcing a
  change here on the basis of a single alarming metric, without confirming the split first, risks
  discarding a node that is actually the *majority* side while chasing a peer's honest divergence.
- If the split is **transient** and within the finality window (most partitions in this repo's
  own local-campaign history healed on their own — see `scripts/local-testnet/TEST-PLAN.md` S7),
  wait: sync rounds retry every `syncPeriodMs`, and a reconnection under 120 blocks of divergence
  heals with a normal `REORGED` result, no operator action needed.
- If the split is **confirmed genuinely past the finality window** (step 3 above corroborated
  across multiple independent peers) and this node's branch is the confirmed loser by cumulative
  work, the only recovery today is destructive, and deliberately so — see
  [spec.md](spec.md) Known limits: wipe `RHIZOME_DATA` and
  resync from a peer on the canonical branch (`RHIZOME_SYNC=snap` for a fast bootstrap, or full
  replay). There is no non-destructive rewind-to-height recovery; do not attempt to hand-edit
  `RHIZOME_DATA`.

**Verify**

- After resync: `/info` reports the expected `chainId`/`network`; height converges with the
  confirmed-canonical peers within one sync round; `syncRoundsWithoutProgress` resets to `0`;
  `degraded` stays `null` throughout.

**Escalate**

- If **multiple** nodes across the fleet independently report `REORG_TOO_DEEP` against each
  other — a real, sustained split between two clusters of peers, not one node's isolated
  divergence — escalate to whoever owns fleet-wide network health. This is a partition or
  connectivity event, not a per-node fault: resyncing minority nodes one at a time treats the
  symptom, and if the underlying partition (firewall, routing, a `RHIZOME_PEERS`
  misconfiguration splitting the seed set into two disjoint groups) is not fixed, freshly-resynced
  nodes can re-diverge.

## RB-02 — `degraded` is non-null

**Symptom**: `/stats`' `degraded` field (or `/metrics`' `rhizome_degraded == 1`) is a non-null
string.

**Confirm**

- Read the string itself, and treat README.md's ["Node health signals"](../../README.md)
  section as the authoritative definition of what `degraded` means — do not invent a different
  reading of it here. In short: it means a reorg **restore** failed, or a peripheral store failed
  to revert during a pop. This is a **hard barrier**, not a hint — the node refuses every new-tip
  block (including a direct extension of its own chain) and pauses production, so it stops
  progressing entirely until addressed.
- Distinguish this from `reorgInProgress == true` (RB-01-adjacent): a reorg window simply being
  *open* is normal and self-clears; `degraded` non-null means a reorg **failed partway through**.

**Act**

- Restart the node process. Per README: a restore-failure form of `degraded` heals automatically
  once a subsequent full restore later succeeds; a torn-pop form only heals via an operator
  restart, because boot recovery specifically rewinds the peripheral state that was left
  inconsistent. A restart is the only remedy this deployment shape has today.

**Verify**

- After restart: `/stats`' `degraded` is `null`; height advances again; if `RHIZOME_MINER` is
  configured, block production resumes.

**Escalate**

- **Recurrence itself is the signal worth escalating, not the individual event.** A restart heals
  one occurrence; a node that goes `degraded` repeatedly is telling you the restart is masking an
  underlying problem the restart does not fix — a flaky disk under RocksDB, resource exhaustion
  mid-pop, or a genuine reorg-reversal defect. Treat a second occurrence within a short window as
  paging-worthy rather than another routine restart.

## RB-03 — Eclipsed / low peer count

**Symptom**: `/stats`' `syncEclipsed` (or `/metrics`' `rhizome_sync_eclipsed == 1`) is true, peer
count is near zero, and logs carry `SyncDriver`'s `"sync eclipsed: no usable sync source this
round"` WARN.

**Confirm**

1. **Is the SSRF/private-peer filter refusing peers this deployment legitimately intends to use?**
   Added peers must resolve to routable IPs by default
   ([networking](../networking/spec.md) P-9); a private/local-network deployment (RFC1918
   addresses among peers) needs `RHIZOME_ALLOW_PRIVATE_PEERS=true` or every such peer is silently
   refused at admission. Check whether the peers this node is missing are private-range
   addresses.
2. **Is a reverse proxy or firewall blocking inbound peer traffic?** Note that P2P protocol
   routes (`/sync`, `/headers`, `/peers`, `/block_count`, `/total_work`, …) intentionally bypass
   the browser-facing CSRF/`Host`-allowlist guards
   ([node-api](../node-api/spec.md) A-3's `isPeerProtocolRequest` fails open for these), so a
   misconfigured `RHIZOME_ALLOWED_HOSTS` is an unlikely cause of a peering failure specifically —
   look instead at the proxy's own upstream rules, or a firewall/security-group not admitting
   inbound connections on `RHIZOME_PORT` from other nodes at all.
3. **Has this node's outbound address been banned by its peers?** Check whether the registry
   emptied through evictions rather than staying full of banned entries — `SyncDriver`'s own
   comment on `peersSkippedBanned` notes evictions are the common shape, so an empty peer list is
   consistent with "every known peer's ban already expired the entry," not necessarily a live
   attack.

**Act**

- If private peers are legitimately needed (a local/private deployment): set
  `RHIZOME_ALLOW_PRIVATE_PEERS=true` and restart. **Local/private deployments only** — leave this
  unset on any node reachable from the public internet (`node.env.example`'s own guidance).
- If behind a reverse proxy: confirm `nginx-rhizome.conf.example`'s `X-Forwarded-For` handling
  ([spec.md](spec.md) O-2)
  is actually in place, and that `RHIZOME_ALLOWED_HOSTS` includes the proxy's advertised hostname.
- Seed the node with known-good peers: `RHIZOME_PEERS` (requires a restart) or `POST /add_peer`
  if the node is reachable and the request carries the bearer token when
  `RHIZOME_API_TOKEN` is set ([node-api](../node-api/spec.md) A-2). Configured seed peers bypass
  the private-IP filter regardless of `RHIZOME_ALLOW_PRIVATE_PEERS`.

**Verify**

- `/stats`' peer count rises above zero; `syncEclipsed` returns to false; height resumes
  advancing (either by push or by the next periodic sync round).

**Escalate**

- If the eclipse persists after confirming the SSRF/`Host` settings are correct and seed peers
  are independently reachable (e.g. `curl` a peer's `/block_count` from the same host), escalate
  — this points at a network-level block (firewall, DNS, routing) outside anything `NodeConfig`
  or the reverse-proxy template controls.

## RB-04 — Disk filling up / enabling pruning

**Symptom**: disk usage on the `RHIZOME_DATA` volume approaching capacity.

**Confirm**

- `df` the filesystem `RHIZOME_DATA` lives on.
- Check whether the node is running in **archive mode** — `RHIZOME_PRUNE` unset or `0` keeps
  every block body forever, and is the default; this is by far the largest driver of open-ended
  growth for a long-lived node.

**Act**

- Set `RHIZOME_PRUNE` to a positive number of blocks and restart. The value is **not** a free
  choice: `NodeConfig.parseKeepBlocks` enforces a safe floor derived from the network's own
  consensus windows —

  ```
  floor = max(maxReorgDepth, uncleMaxDepth, difficultyLookback, medianTimeWindow) + PRUNE_MARGIN(128)
  ```

  — which on mainnet/staging (`maxReorgDepth = 120`) is **248** blocks (the exact figure
  `scripts/local-testnet/profiles/staging.env`'s `PRUNE_FLOOR` mirrors). **The node refuses to
  start** with a `RHIZOME_PRUNE` value below that floor for its profile, naming the floor in the
  startup exception, rather than silently pruning a body the engine still needs mid-reorg — do
  not iterate downward by trial and error; read the refusal message, it already names the exact
  number to use as a minimum.

**Verify**

- Node boots successfully; `/info`'s `prunedBelow` reflects the configured retention; explorer
  reads below the watermark answer `410 GONE` with the watermark
  ([node-api](../node-api/spec.md) A-6) instead of erroring generically or serving stale data.

**Escalate**

- Pruning bodies is a **partial** mitigation, not a hard bound on total disk use — state stores,
  indexes and periodic snapshots (`RHIZOME_SNAPSHOT_EVERY`) still grow. If disk pressure continues
  after pruning is correctly configured, escalate rather than lowering `RHIZOME_PRUNE` further
  (it is already floored) or disabling snapshot materialisation blindly (other nodes may depend
  on this node's snapshots for `RHIZOME_SYNC=snap` bootstrapping).

## RB-05 — Rotating `RHIZOME_API_TOKEN` / `RHIZOME_PEER_TOKEN`

The two variables are, in a token-gated deployment, **the same shared secret**: `RHIZOME_PEER_TOKEN`
is what a node presents on outbound pushes, and `RHIZOME_API_TOKEN` is what a peer's inbound
gate compares an incoming bearer against — so a fleet gating ingest with `RHIZOME_API_TOKEN` sets
every node's `RHIZOME_PEER_TOKEN` to that same value ([node-api](../node-api/spec.md) A-2,
[networking](../networking/spec.md) P-8). The node's token check
(`NodeApi.bearerMatches`) compares against exactly **one** configured value — there is **no
dual-accept grace window** for "old token OR new token" during a rotation. Rotation must
therefore be treated as an explicit, ordered procedure, not a per-node rolling restart done
casually.

**What does and does not break mid-rotation.** Only routes carrying `RoutePolicy.Guard.TOKEN` —
`/submit`, `/add_transaction`, `/add_transaction_json`, `/add_peer`, `/scan/register`,
`/scan/deregister`, `/call_readonly` — are gated by the bearer token. The pure P2P protocol
routes (`/sync`, `/headers`, `/block_count`, `/total_work`, `/peers`, `/blocks`, `/block`,
`/info`, `/state/snapshot/*`) carry `Guard.PEER_PROTOCOL` instead and **stay open regardless of
token state** ([node-api](../node-api/spec.md) A-2). Practically: during a rotation window where
two nodes disagree on the token, their **pushed** blocks/transactions to each other are refused
with `401` (silently dropped, not banned — a token mismatch is not a protocol violation), but
their periodic **pull**-based sync rounds keep working and eventually catch each side up to the
other. Convergence degrades to the sync-round period during the window; it does not stop outright
— but do not rely on that as a substitute for finishing the rotation quickly.

**Procedure**

1. Generate the new shared token value once (e.g. `openssl rand -hex 32`, per
   `node.env.example`'s own guidance for the initial value).
2. For each node, in turn, update **both** `RHIZOME_API_TOKEN` and `RHIZOME_PEER_TOKEN` to the new
   value **in the same edit and the same restart**. Never split them across two restarts — a node
   whose `RHIZOME_PEER_TOKEN` is rotated before its `RHIZOME_API_TOKEN` (or vice versa) briefly
   presents a token its peers do not expect while also rejecting the token they still send it,
   which only widens the mismatch window without buying anything.
3. Restart nodes one at a time (a rolling restart), not all simultaneously — the P2P protocol
   routes staying open (see above) is exactly what makes a rolling restart safe rather than an
   all-at-once cutover strictly necessary. Do not stretch the rollout out unnecessarily long,
   though: every node still on the old token is, for the duration, isolated from every
   already-rotated node's pushes.
4. After the **last** node is rotated, confirm the fleet has re-converged: check each node's logs
   for `401`s on push routes settling out, and `/stats` sync rounds returning `EXTENDED`/`REORGED`
   again rather than relying on stale pull-cached state (`NO_CHANGE` from a peer that is actually
   behind is the symptom of a still-mismatched pair).
5. Discard the old token value once every node confirms the new one. There is no grace window to
   maintain deliberately — the rolling restart itself *is* the whole bridge, carried by
   pull-sync while it is in progress.

**Verify**

- Every node's `/stats` shows peer counts and sync progress consistent with pre-rotation; no
  node's logs show ongoing `401` responses from peers that have already been rotated.

**Escalate**

- If a node cannot be reached to complete its rotation step (offline, unreachable operator),
  escalate before leaving the fleet split for an extended period — the longer the split persists,
  the longer push-based propagation stays degraded to pull-sync latency for every node still on
  the old value.

## RB-06 — Faucet drained or abused

**Symptom**: the faucet's global daily budget is exhausted unusually fast, or `/drip` requests
show a pattern consistent with abuse (many distinct addresses funded in a short window, a single
IP retrying rapidly despite the per-IP cap).

**Confirm**

- `GET /status` on the faucet (not the node — this is the faucet's own route, default
  `http://127.0.0.1:8085/status` unless `--host`/`--port` were overridden) reports
  `dailyBudgetBaseUnits`, `dailyBudgetSpentBaseUnits`, `dailyBudgetRemainingBaseUnits`,
  `nodeReachable`, `dripBaseUnits`, `cooldownSeconds`, `ipDailyCap` and `powDifficultyBits` — no
  secrets are exposed (no key path, wallet binary path, or passphrase file path). Confirm the
  daily budget is genuinely exhausted (`dailyBudgetRemainingBaseUnits == 0`) rather than the node
  itself being unreachable (`nodeReachable == false`, a different, node-side problem — check the
  node, not the faucet, in that case).
- Per [faucet/README.md](../../scripts/local-testnet/faucet/README.md)'s own honest limitation:
  **none of the faucet's per-request controls (proof-of-work, per-address cooldown, per-IP daily
  cap) stop a determined attacker with many IP addresses and some CPU.** The real backstop is
  always the global daily budget, combined with the fact that this is worthless test-network
  money. A drained budget from a distributed source is the control working as designed, not a
  gap to patch reactively.

**Act — options, from least to most disruptive**

1. **Tighten per-address/per-IP limits without stopping the service**: restart the faucet process
   with a lower `--ip-daily-cap`/`RHIZOME_FAUCET_IP_DAILY_CAP`, a longer
   `--cooldown-seconds`/`RHIZOME_FAUCET_COOLDOWN_SECONDS`, a lower
   `--drip-pdn`/`RHIZOME_FAUCET_DRIP_PDN`, a lower
   `--daily-budget-pdn`/`RHIZOME_FAUCET_DAILY_BUDGET_PDN`, or a higher
   `--pow-difficulty-bits`/`RHIZOME_FAUCET_POW_DIFFICULTY_BITS` (raises the CPU cost of each
   `/drip` attempt). All of these are process-startup flags/env vars, not runtime-mutable — a
   restart is required to change any of them (state such as spent-budget and cooldowns is
   persisted to `--state-file` across the restart, so this does **not** reset the current day's
   spend or any address's cooldown).
2. **Pause the faucet entirely without affecting the node**: since `faucet.py` is a fully
   separate OS process from `rhizome-node` — it holds its own key, listens on its own port, and
   only ever talks to the node the way any external client does — simply stopping it (`Ctrl-C`,
   `systemctl stop rhizome-faucet` if deployed as a unit, or `kill` the process) removes the
   funding surface with **zero** effect on node health, peering, or block production. This is the
   correct choice for a suspected active drain in progress: stop first, tune limits second, then
   restart.

**Verify**

- Faucet `GET /status` reflects the new configuration (`ipDailyCap`, `cooldownSeconds`,
  `dailyBudgetBaseUnits`, `powDifficultyBits` match what was set); if paused, the faucet's port no
  longer accepts connections while the node's own `/stats`/`/metrics` are unaffected (confirming
  the two processes really are independent, as designed).

**Escalate**

- If tightened limits do not slow the drain (consistent with the README's own acknowledged
  limitation — a distributed attacker is not meaningfully rate-limited by per-IP controls),
  escalate the decision to pause the faucet for an extended period, or to fund it more
  conservatively per top-up, to whoever owns the staging deployment's operating budget — this is
  a policy call about acceptable test-network spend, not something a limit tweak alone resolves.
