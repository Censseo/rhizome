# Contract sources

`#![no_std]` Rust, compiled to `wasm32-unknown-unknown`. Each `.rs` here has exactly one
compiled twin in `lib-vm/src/test/resources/`, and both are checked in.

Both halves are checked in on purpose: the ordinary build, the test suite and the native image
must not require a Rust toolchain to exist. The cost of that choice is drift — the two halves can
disagree — so the pair is governed instead of trusted:

| | task | needs Rust | runs |
|---|---|---|---|
| regenerate the binaries | `:lib-vm:compileContracts` | yes | opt-in, `-Pcontracts=rebuild` |
| refuse a stale pair | `:lib-vm:verifyContractFixtures` | no | every `check` |

```bash
./gradlew :lib-vm:compileContracts -Pcontracts=rebuild   # after editing any .rs
```

`contracts.lock` records the sha256 of every pair. Editing a `.rs` without rebuilding its `.wasm`
now fails the build and names the command to run. It had gone unnoticed three times before this
existed — once leaving the dashboard serving a `token.rs` without its `is_deployer`
front-running guard while the `token.wasm` beside it was already the fixed, post-audit binary.

## The pinned invocation

```bash
cd lib-vm/contracts
rustc --target wasm32-unknown-unknown --crate-type=cdylib \
      -C opt-level=3 -C strip=debuginfo -o ../src/test/resources/<name>.wasm <name>.rs
```

The compiler is pinned by `rust-toolchain.toml`, so a bare `rustc` from this directory is already
the right one.

Three details of that command line are load-bearing, and all three were recovered from the
binaries rather than assumed:

- **`rustc` directly, not Cargo.** rustc names the wasm module after the `-o` *basename*, and the
  checked-in binaries carry `counter.wasm`, `pair.wasm`, … as their module name — the file name,
  extension included, which a Cargo build does not produce.
- **The working directory must be `contracts/`.** rustc embeds the panic-location path exactly as
  written on the command line. Compiling `pair.rs` from here puts `pair.rs` in `.rodata`;
  compiling `/abs/path/to/pair.rs` puts the absolute path there, and the output stops being
  reproducible across machines. On `pair.wasm` that is 41 bytes of difference.
- **`strip=debuginfo`.** Contracts that reach any `core` panic path (`amm`, `pair`,
  `agent_wallet`, `launchpad`) otherwise drag in `core`'s DWARF — 556 KB of it on `pair`.

Verification is not a claim in this file: `compileContracts` reports whether anything changed, and
on an unmodified tree it prints *"every .wasm was already byte-identical to its source"*.

## Two binaries that had already drifted

Recovering the invocation showed the checked-in set was not internally consistent — they had been
produced by at least three different builds:

- **`token.wasm`** still carried 945 bytes of `.debug_*` sections: built without
  `strip=debuginfo`, unlike every other contract.
- **`logtree.wasm`** was built by **rustc 1.96.0**, while the other eight record **1.94.1** in
  their `producers` section.

Both were rebuilt on the pinned toolchain and the single invocation above. Neither changes
behaviour — `:lib-vm:test` passes unchanged, `LogOrderingTest` and `TokenContractTest` (which owns
the `is_deployer` regression) included — but their bytes moved, which is precisely the kind of
change that should happen once, deliberately, rather than never and then invisibly.

`logtree.rs` is a `LogOrderingTest` fixture, not a dashboard template; the staging task in
`app-node/build.gradle` excludes it by name, and that exclusion is why leaving a *real* contract
out of `dashboard/templates/manifest.json` still fails the build.
