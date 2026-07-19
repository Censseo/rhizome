# Rhizome

A Java proof-of-work blockchain with **WebAssembly smart contracts** (via the pure-Java
[Chicory](https://github.com/dylibso/chicory) runtime), descended from
[Pandanite](https://github.com/pandanite-crypto/pandanite) and restarted as a clean chain
with corrected consensus rules and Pufferfish2 proof of work — built for cheap token
launches and autonomous agents. See [`WHITEPAPER.md`](WHITEPAPER.md) for the design.

Status: functional, tested node with a smart-contract VM (199 tests). Requires JDK 21.

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

```bash
RHIZOME_NETWORK=testnet RHIZOME_MINER=<address> ./gradlew :app-node:run
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
