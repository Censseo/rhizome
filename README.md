# Rhizome

A Java proof-of-work blockchain with **WebAssembly smart contracts** (via the pure-Java
[Chicory](https://github.com/dylibso/chicory) runtime), descended from
[Pandanite](https://github.com/pandanite-crypto/pandanite) and restarted as a clean chain
with corrected consensus rules and Pufferfish2 proof of work — built for cheap token
launches and autonomous agents. See [`WHITEPAPER.md`](WHITEPAPER.md) for the design.

Status: functional, tested node with a smart-contract VM (799 tests). Requires JDK 25 (Gradle
9.6.1 toolchain, source/target 25).

## Build & test

```bash
./gradlew build
./gradlew test
```

## Run a node

Configured via environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `RHIZOME_NETWORK` | `mainnet` | `mainnet` (supply-targeted emission curve active from height 1 — genesis pays no coinbase, so height 2 is the first paid block and it's already curve-governed), `testnet` (low difficulty, 90 s target, curve never active — the legacy geometric schedule governs every block; built for tests driving controlled clocks) or `devnet` (low difficulty on mainnet's real 5 s target, curve active from height 1 like mainnet — use this for a local node you actually run and watch). Anything else is **refused at startup**: a typo must not silently start you on mainnet |
| `RHIZOME_PORT` | `3000` | HTTP API port |
| `RHIZOME_BIND_ADDRESS` | `127.0.0.1` | HTTP API bind address. Loopback by default; binding a public address additionally requires `RHIZOME_API_TOKEN`, or `RHIZOME_ALLOW_OPEN_API=true` to accept that `/add_peer` and the other operator routes are open to the network |
| `RHIZOME_API_TOKEN` | — | when set, state-changing/operator routes (`/add_peer`, `/scan/register`, `/scan/deregister`, `/add_transaction`, `/submit`, `/call_readonly`) require `Authorization: Bearer <token>`; P2P protocol endpoints stay open. Note: with a token set, gossip peers must also present it on `/submit` and `/add_transaction` — set `RHIZOME_PEER_TOKEN` on every node of the deployment so outbound peer traffic authenticates |
| `RHIZOME_PEER_TOKEN` | — | bearer token for *outbound* peer-to-peer requests (gossip `/submit` & `/add_transaction`, PEX fetch/announce, sync GETs), sent **only to the peers of `RHIZOME_PEERS` and only over `https://`** — the peer registry is fed by unauthenticated `/add_peer`/PEX, so gossip-learned or cleartext-http peers never receive the secret. Required when your nodes gate ingest with `RHIZOME_API_TOKEN` (configure those peers with `https://` URLs), otherwise cross-node pushes are refused (401) and gossip stops converging. Never logged |
| `RHIZOME_DATA` | `./data` | RocksDB data directory |
| `RHIZOME_SNAPSHOT` | per-network (below) | path to a `LedgerSnapshot` JSON file seeding the genesis; overrides the network's default when set. Unset default is per network: mainnet loads its shipped, provisional allocation artifact (a pinned, non-zero genesis supply `S₀`, checked against whatever snapshot is loaded); testnet and devnet default to an empty snapshot, unchanged. Devnet's emission curve is calibrated against mainnet's `S₀` (both activate the curve from height 1), so a devnet run with the default empty genesis mints against an uncalibrated `S₀ = 0`; point `RHIZOME_SNAPSHOT` at the mainnet allocation artifact for mainnet-faithful reward figures |
| `RHIZOME_MINER` | — | reward address (enables block production) |
| `RHIZOME_PEERS` | — | comma-separated initial peers |
| `RHIZOME_ADVERTISE` | — | public URL advertised to peers; must be an `http(s)` URL with a host (a malformed value used to silently break self-pairing refusal, PEX and the `Host` allowlist) |
| `RHIZOME_PROTECT_READS` | `false` | with `RHIZOME_API_TOKEN`, extends the bearer gate to **every** route (reads included) — the private-node/private-explorer switch. The static SPA/docs shell (`/`, `/dashboard/*`, `/docs/*`) stays open so a browser can load the explorer; the SPA's own API fetches carry the token. Peering then requires every peer to present the token (see `RHIZOME_PEER_TOKEN`), so this suits private clusters, not public relays |
| `RHIZOME_TRUST_XFF` | `false` | key rate limits, push-strike tables and scan ownership on the first `X-Forwarded-For` hop instead of the socket address — required behind a reverse proxy. The hop is accepted only as an IP literal (parsed without any DNS lookup, so a spoofed header cannot stall the event loop). **Dangerous when the port is directly reachable** — clients could spoof the header to evade per-IP limits; enable only when the socket can solely be reached from the trusted proxy |
| `RHIZOME_ALLOWED_HOSTS` | loopback + advertised + LAN addresses | comma-separated extra `Host` authorities for the DNS-rebinding guard (e.g. a reverse proxy's public name or a Docker/NAT address, each as `name` or `name:port`); the literal value `off` disables the Host allowlist entirely (only the Origin/marker CSRF guard remains — not recommended) |
| `RHIZOME_BLOCK_INTERVAL_MS` | block target | producer pacing override (local devnets). Pacing only — it does **not** move the retarget target, so pacing far below the network's `desiredBlockTimeSec` makes every window look too fast and difficulty runs away until the chain stalls. Use `RHIZOME_NETWORK=devnet`, whose target already is 5 s, instead of pacing a testnet at 5 s |

```bash
RHIZOME_NETWORK=devnet RHIZOME_MINER=<address> ./gradlew :app-node:run
```

### Node health signals

`GET /stats` exposes two operator fields:

- `reorgInProgress` (`bool`) — a reorg window is open (pop → body-apply → restore, up to
  one header window of 20 000 blocks). While it is open the node keeps serving the pre-reorg
  tip, rejects incoming tip blocks with `IS_SYNCING`, and **block production pauses** — a
  miner that stops producing during a deep resync is usually in this state, not broken.
- `degraded` (`string|null`) — set when chain integrity is suspect: a reorg restore *failed*,
  or a peripheral store failed to revert during a pop. This is a hard barrier, not a hint:
  the node refuses every new-tip block (including direct extension) and pauses production,
  so **it stops progressing entirely**. A restore failure heals automatically once a full
  restore succeeds; a torn pop only heals via an operator restart (boot recovery rewinds the
  peripheral). `null` means healthy — alert on non-null, otherwise a frozen node goes
  unnoticed.

Snapshot spools (`rhizome-snapshot-*.chunks`) live under `$RHIZOME_DATA/snapshots` — not the
OS temp dir, which is commonly a tmpfs — and stale spools from an unclean shutdown are swept
at startup.

## Dashboard

Every node embeds a web dashboard, served on the API port — open
`http://localhost:3000/` in a browser. No build step, no external assets, works
offline against your own node:

- **Dashboard** — live network stats (height, difficulty, block time, mempool,
  peers, reward) and a live contract-event feed (SSE).
- **Explorer** — browse blocks, transactions and addresses; search by height,
  txid or address.
- **Wallet** — keys are generated and stored **in the browser** (localStorage)
  and transactions are signed locally in JS (Ed25519); the node never sees a
  private key.
- **Contrats** — bundled contract templates (token, AMM, launchpad, agent
  wallet…) with their Rust sources, one-click deploy, typed call builder, and
  read-only queries (`POST /contract/query`) that execute the VM against a
  throwaway state overlay.
- **Agents IA** — deploy and manage agent wallets: init/exec, grant/revoke
  capped session keys, inspect sessions, watch grant/spend events live.
- **Boxes** — browse, create, update, spend and rent-collect data boxes with
  typed registers; the minimum locked value is computed client-side from the
  node's `minValuePerByte`. Native tokens (mint/transfer/burn, holdings) live
  in the Wallet page. Both activate from the `GET /features` flags, so a node
  built without those layers keeps the pages dormant.
- **Docs** — this README, the whitepaper and every module spec, bundled into the
  binary at build time and rendered in the browser, with cross-document links and
  full-text search across the whole corpus. A node documents itself offline.

A quick local playground:

```bash
RHIZOME_NETWORK=devnet RHIZOME_MINER=<address> ./gradlew :app-node:run
# then open http://localhost:3000/
```

## Wallet

```bash
./gradlew :app-wallet:run --args="keygen  <keyfile>"
./gradlew :app-wallet:run --args="address <keyfile>"
./gradlew :app-wallet:run --args="balance <nodeUrl> <address>"
./gradlew :app-wallet:run --args="send    <nodeUrl> <keyfile> <to> <amount> [fee]"
./gradlew :app-wallet:run --args="deploy  <nodeUrl> <keyfile> <wasmfile> [gasLimit] [gasPrice]"
./gradlew :app-wallet:run --args="call    <nodeUrl> <keyfile> <contract> <hexInput> [gasLimit] [gasPrice]"
```
