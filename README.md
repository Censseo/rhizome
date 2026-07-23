# Rhizome

A Java proof-of-work blockchain with **WebAssembly smart contracts** (via the pure-Java
[Chicory](https://github.com/dylibso/chicory) runtime), descended from
[Pandanite](https://github.com/pandanite-crypto/pandanite) and restarted as a clean chain
with corrected consensus rules and Pufferfish2 proof of work — built for cheap token
launches and autonomous agents. See [`WHITEPAPER.md`](WHITEPAPER.md) for the design.

Status: functional, tested node with a smart-contract VM (446 tests). Requires JDK 21.

## Build & test

```bash
./gradlew build
./gradlew test
```

## Run a node

Configured via environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `RHIZOME_NETWORK` | `mainnet` | `mainnet` or `testnet` (low difficulty) |
| `RHIZOME_PORT` | `3000` | HTTP API port |
| `RHIZOME_DATA` | `./data` | RocksDB data directory |
| `RHIZOME_SNAPSHOT` | — | snapshot file seeding the genesis |
| `RHIZOME_MINER` | — | reward address (enables block production) |
| `RHIZOME_PEERS` | — | comma-separated initial peers |
| `RHIZOME_ADVERTISE` | — | public URL advertised to peers |
| `RHIZOME_BLOCK_INTERVAL_MS` | block target | producer pacing override (local devnets) |

```bash
RHIZOME_NETWORK=testnet RHIZOME_MINER=<address> ./gradlew :app-node:run
```

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

A quick local playground:

```bash
RHIZOME_NETWORK=testnet RHIZOME_MINER=<address> \
RHIZOME_BLOCK_INTERVAL_MS=1000 ./gradlew :app-node:run
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
