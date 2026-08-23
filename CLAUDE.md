# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Rhizome is a Java proof-of-work blockchain with WebAssembly smart contracts (pure-Java
[Chicory](https://github.com/dylibso/chicory) runtime), descended from Pandanite but restarted as
a clean chain with corrected consensus rules and Pufferfish2 PoW. `WHITEPAPER.md` is the design
document — consult it before changing anything consensus-related; it explains *why* each rule
exists (usually a specific Pandanite bug being fixed).

## Commands

Requires JDK 25 (Gradle toolchain enforces it).

```bash
./gradlew build                 # build + lint + all tests
./gradlew test                  # all tests (~1180)
./gradlew :lib-core:test        # one module's tests
./gradlew :lib-core:test --tests "rhizome.ChainEngineTest"                    # one class
./gradlew :lib-core:test --tests "rhizome.ChainEngineTest.someTestMethod"     # one method
./gradlew adversarial           # protocol gate + the dedicated attack suites (incl. E2E, ~1 min)
```

`docs/adversarial/spec.md` is the adversarial test protocol: a catalogue of exploit scenarios, each
naming the test that runs it. It is machine-checked in both directions by `AdversarialProtocolTest` —
a cited proof that was renamed, deleted or `@Disabled` fails the build, and so does an attack suite
whose in-file `FAMILY-NN` labels disagree with the catalogue. Renaming an attack test therefore means
updating the catalogue. The suites live in `lib-core/src/test/java/rhizome/adversarial/` over the
fixtures in `lib-core/src/testFixtures/java/rhizome/adversarial/`, and the network layer in
`app-node/src/test/java/rhizome/adversarial/e2e/` (real `RhizomeNode` processes, real sockets, real
mining — see `TestNetwork`). Most scenarios point at the test that already covered them wherever it
lives, so `./gradlew adversarial` runs the gate and those suites, not the whole catalogue, which
`./gradlew test` covers. Every `@Test` in an adversarial package must open its javadoc with its
scenario id (`FAMILY-NN — …`); the gate rejects a label the catalogue does not corroborate.

Run a node (config is environment variables — see README table: `RHIZOME_NETWORK`, `RHIZOME_PORT`,
`RHIZOME_MINER`, `RHIZOME_PEERS`, `RHIZOME_API_TOKEN`, `RHIZOME_PEER_TOKEN`, …):

```bash
RHIZOME_NETWORK=testnet RHIZOME_MINER=<address> RHIZOME_BLOCK_INTERVAL_MS=1000 ./gradlew :app-node:run
# dashboard/explorer/wallet UI at http://localhost:3000/
```

Wallet CLI: `./gradlew :app-wallet:run --args="keygen <keyfile>"` (also `address`, `balance`,
`send`, `deploy`, `call`).

GraalVM native binary: `./gradlew :app-node:nativeImage` — Gradle 9.6.1 runs on JDK 25, so one
JDK suffices: use a GraalVM as the current SDK (e.g. `sdk use java 25.0.2-graal`) and
`native-image` is resolved from PATH. Reachability metadata lives in
`app-node/src/main/resources/META-INF/native-image/`.

Benchmarks: JVM properties starting with `bench` are forwarded to the test JVM
(e.g. `./gradlew :lib-core:test -Dbench=true --tests "rhizome.ValidationBenchmark"`).

## Architecture

Gradle multi-module; the node is wired with explicit constructors — no DI container, no
reflection — to keep the dependency graph legible and GraalVM-native-friendly.

```
app-node   — NodeApi/ExplorerApi/ContractApi/BoxApi/TokenApi (ActiveJ HTTP), BlockProducer wiring,
             ChainSynchronizer, SnapshotBootstrap, embedded dashboard (static JS, no build step)
app-wallet — WalletCli; talks to a node over HTTP
lib-net    — HTTP p2p transport: HttpPeerSource, PeerBroadcaster, PeerDiscovery/Registry/BanList,
             RateLimiter, PeerTokenPolicy (outbound bearer tokens, https-only)
lib-core   — consensus core: ChainEngine (addBlock/popBlock), Executor (transactional apply),
             MemPool, DifficultyAdjustment, MerkleTree, GHOST uncles, data boxes, native tokens,
             SparseMerkleTree state root, snapshots
lib-vm     — WASM contract VM: WasmVm (Chicory, gas-metered), WasmContractProcessor,
             ContractStore, undo journals for reorg reversal
lib-persistence — RocksDB stores (full node: one DB, atomic WriteBatch), plus
             PandaniteLedgerDumper (reads a Pandanite LevelDB ledger to seed genesis)
lib-crypto — Ed25519, Pufferfish2 PoW (pure Java, validated against C reference), hashes
```

The decoupling is interface-driven, defined in `lib-core` and implemented outward:

- `ContractProcessor` (lib-core) ← implemented by `WasmContractProcessor` (lib-vm), so consensus
  dispatches contract transactions without depending on the WASM runtime.
- `PeerSource` (lib-core) ← implemented by `HttpPeerSource` (lib-net), so sync logic never
  depends on a transport.
- `ChainStore`/`NonceStore`/`ContractStore`/`BoxStore` ← in-memory implementations in core/vm,
  RocksDB implementations in lib-persistence. The in-memory pair is the second real
  implementation these ports exist for; it is what the tests run against.

## Consensus invariants (do not casually break)

- `addBlock` validation is ordered cheapest-first, PoW last, uncle PoW after the block's own PoW —
  this ordering is DoS armor, not style (WHITEPAPER §3.5).
- All public `ChainEngine` methods serialize on a single lock; keep it that way (torn reads and
  lock-order deadlocks were Pandanite bugs).
- Difficulty is always recomputed from timestamp history, never cached across `popBlock`.
- The Merkle tree preserves transaction insertion order — never sort transactions.
- Contract/box/token state must move atomically with its block and reverse exactly on reorg
  (undo journals in lib-vm, atomic WriteBatch in lib-persistence).
- Contract execution must be deterministic: gas-metered interpreter only, no host
  time/randomness. Optional header fields (uncles, state root, vote) are folded into the block
  hash only when present so old blocks hash unchanged.

## Smart contracts

Contract sources are `#![no_std]` Rust in `lib-vm/contracts/*.rs`, compiled to the `.wasm`
fixtures in `lib-vm/src/test/resources/`. Both are the single source — the dashboard does not
carry its own copy; `app-node`'s `stageContractTemplates` Gradle task stages them into
`dashboard/templates/` at build time, checked against `dashboard/templates/manifest.json`, the one
editorial list of which contracts are templates. Contracts import a small host ABI (module
`"env"`: `storage_read`/`storage_write`/`set_output`, …) and export `call`. If you edit a `.rs`
template, the corresponding checked-in `.wasm` must be rebuilt to match.

## Dependency pins (deliberate — see comments in build.gradle)

- ActiveJ `v7.0.0` (Java 25-baseline fork; do not "downgrade to stable" 5.x or upstream 6.x).
- BouncyCastle 1.85.2 (CVE-2024-30172), logback ≥ 1.5.13 (CVE-2024-12798/12801).
- ASM: no force — neither ActiveJ graph resolves an `org.ow2.asm` artifact (verified
  empirically); re-add with ≥ 9.8 if a resolution ever proves otherwise.
- Nebula lint runs with the `all-dependency` rule: each module declares exactly what it uses
  (`api` only when the type appears in public signatures — existing build.gradle comments show
  the pattern).

## Conventions

- Lombok is available in all modules (io.freefair.lombok).
- Tests are plain JUnit 5 under `src/test/java`; heavier integration tests (multi-node sync,
  gossip, wallet-against-real-node) live in `app-node`/`app-wallet` test sources.
- The dashboard is dependency-free static HTML/JS served from resources — no npm, no bundler;
  wallet keys are generated and kept in the browser, the node never sees a private key.

## Recent Changes
- 003-genesis-allocation: pinned per-network genesis supply constant (`NetworkParameters`), a
  shipped mainnet allocation artifact, and a boot-time check that refuses a mismatched snapshot
  total. No new dependency or module; JDK 25 throughout (constitution v1.0.1 restatement).
