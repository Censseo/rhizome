# AGENTS.md

Read `CLAUDE.md` first — it covers architecture, module boundaries, and consensus invariants.
Read `WHITEPAPER.md` before touching anything consensus-related (difficulty, PoW, uncles,
reorgs); each rule exists to fix a specific Pandanite bug.

## Commands (JDK 25 required, Gradle toolchain enforces it)

```bash
./gradlew build                                                   # build + lint + all tests
./gradlew :lib-core:test --tests "rhizome.ChainEngineTest.someTestMethod"   # one test
./gradlew :lib-core:test -Dbench=true --tests "rhizome.ValidationBenchmark" # benchmark
RHIZOME_NETWORK=testnet RHIZOME_MINER=<addr> RHIZOME_BLOCK_INTERVAL_MS=1000 \
  ./gradlew :app-node:run                                          # local node, UI on :3000
```

No CI is active — `.github/ci-workflow.yml.example` must be copied into
`.github/workflows/` by a maintainer. Verify changes locally with `./gradlew build`.

## Things agents get wrong

- Test sources live in **both** `src/test/java` and `src/java/test` (extra dir registered in
  the root `build.gradle` `sourceSets`). Search both before concluding a test doesn't exist.
- Editing a contract template in `lib-vm/contracts/*.rs` requires rebuilding the matching
  checked-in `.wasm` in `lib-vm/src/test/resources/` — tests and the dashboard use the
  compiled fixtures, not the Rust sources. The dashboard carries no copy of its own:
  `app-node`'s `stageContractTemplates` Gradle task stages the lib-vm files into
  `build/resources/main/dashboard/templates/` at build time, against the checked-in
  `app-node/src/main/resources/dashboard/templates/manifest.json` (the one editorial list
  of which contracts are templates — that directory holds only the manifest).
- Nebula lint runs with the `all-dependency` rule: every module's `build.gradle` must declare
  exactly what it uses. `./gradlew build` fails on unused/missing declarations.
- Dependency pins in root `build.gradle` are deliberate (ActiveJ v7.0.0, BouncyCastle 1.85.2,
  logback ≥1.5.13 — the legacy ASM force is gone, neither ActiveJ graph resolves one).
  Do not "upgrade/downgrade to stable" — the comments explain why.
- Node config is environment variables only (`RHIZOME_*`, table in README.md) — there is no
  config file. `RHIZOME_PEER_TOKEN` is sent only to explicitly configured `https://` peers.
- `addBlock` validates cheapest-first with PoW last; that ordering is DoS protection, not
  style. All public `ChainEngine` methods serialize on one lock — keep it that way.

## Verification

Always finish with `./gradlew build` (it is the lint + typecheck + test step combined).
For focused iteration, run a single module's tests (`./gradlew :lib-vm:test`) instead of the
full suite.
