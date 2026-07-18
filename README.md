# Rhizome

A Java port of [Pandanite](https://github.com/pandanite-crypto/pandanite) — a simple
proof-of-work blockchain, restarted as a clean chain with corrected consensus rules and
Pufferfish2 proof of work. See [`WHITEPAPER.md`](WHITEPAPER.md) for the design.

Status: functional, tested node core (166 tests). Requires JDK 21.

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
```
