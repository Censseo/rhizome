# Platform Specification

> Source of truth for the build toolchain, language level, dependency pins and their rationale,
> and the native-image path.
> Extracted from `build.gradle`, `README.md`, `CLAUDE.md` and feature `001-activej-v7-java25`.
> **Status**: Draft — needs review

## Overview

Rhizome is a Gradle multi-module project built and run entirely on **Java 25**, wired with explicit
constructors — no DI container, no reflection — so the dependency graph stays legible and the whole
node remains GraalVM-native-friendly. That constraint is what makes this a domain rather than
incidental build config: **every pin here is load-bearing**, and each one carries a reason that a
future reader would otherwise have to rediscover by breaking the build.

Two rules govern everything below:

- **A pin is a decision, not a default.** No version in `build.gradle` is "whatever was latest".
  Each has a comment stating what breaks below it. Bumping is fine; silently downgrading is not.
- **Declared, not inherited.** Nothing is injected globally into modules. Each module declares
  exactly what it uses, and nebula lint's `all-dependency` rule enforces it.

## Scope

**Owns**

| Area | Source |
|---|---|
| Language level, toolchain, compile flags | [build.gradle](../../build.gradle) (`subprojects` block) |
| Shared dependency versions and their rationale | [build.gradle](../../build.gradle) (`ext` block) |
| Gradle wrapper version | [gradle/wrapper/gradle-wrapper.properties](../../gradle/wrapper/gradle-wrapper.properties) |
| Build plugin lines (lint, Lombok) | [build.gradle](../../build.gradle) (`plugins` block) |
| Native-image build | [app-node/build.gradle](../../app-node/build.gradle) (`nativeImage` task) |
| Reachability metadata | [app-node/src/main/resources/META-INF/native-image/](../../app-node/src/main/resources/META-INF/native-image/) |
| Benchmark harness plumbing | [build.gradle](../../build.gradle) (`bench*` system-property forwarding) |
| Local testnet prerequisite checks | [scripts/local-testnet/common.sh](../../scripts/local-testnet/common.sh) |
| CI toolchain setup | [.github/ci-workflow.yml.example](../../.github/ci-workflow.yml.example) |

**Does not own**

- What the ActiveJ HTTP server is configured to *do* (timeouts, body caps, route metering) →
  [node-api](../node-api/spec.md)
- Why HTTP was chosen as the transport → [networking](../networking/spec.md)
- The WASM contract toolchain (`no_std` Rust → `.wasm` fixtures) → [contracts](../contracts/spec.md)
- Test conventions and where integration tests live → skill `junit5-conventions`

## Features

### P-1 — Java 25 end to end *(implemented)*

The toolchain, the source level, the target level and the compiled API surface are all **25**.

| Setting | Value | Where |
|---|---|---|
| Toolchain | `JavaLanguageVersion.of(25)` | `build.gradle` `subprojects.java.toolchain` |
| Compiled API surface | `options.release = 25` | `tasks.withType(JavaCompile).configureEach` |
| Resulting bytecode | class file major version **69** | verifiable with `javap -verbose` |

`options.release` is set in addition to the toolchain and is not redundant. A toolchain alone fixes
*which compiler runs*; `release` also fixes *which API surface is visible*. When the two diverge,
code compiles against a newer JDK's methods and then fails at runtime with `NoSuchMethodError` on
an older one. Pinning both closes that class of escape. The ActiveJ fork made the same move for the
same reason.

Gradle auto-detects sdkman-installed JDKs for toolchain resolution, so no `JAVA_HOME` override is
needed when a 25 JDK is the current sdkman candidate.

### P-2 — Gradle 9.6.1 wrapper *(implemented)*

Gradle **9.6.1**, from `gradle/wrapper/gradle-wrapper.properties`.

The floor is hard: **Gradle ≥ 9.1.0** is required both to *run on* Java 25 and to *target* a Java 25
toolchain. The previous 8.14.3 wrapper could do neither (it runs on Java 24 at most). This has a
useful consequence for the native-image path — see P-5.

Two Gradle 9 behaviour changes the build had to absorb:

- The JUnit Platform **launcher is no longer auto-detected**; `testRuntimeOnly
  'org.junit.platform:junit-platform-launcher'` is now declared explicitly in `subprojects`.
- Plugin lines had to move with it: nebula lint and freefair-lombok (P-4).

### P-3 — Dependency pins *(implemented)*

Shared versions live in the root `ext` block. Each is annotated in place; the reason matters more
than the number.

| Dependency | Pin | Why this floor |
|---|---|---|
| ActiveJ (fork) | `com.github.Censseo.activej` `v7.0.0` | Java 25-baseline fork, served from JitPack. Upstream ActiveJ has been unmaintained since 2026-01. **Do not "downgrade to stable" 5.x or upstream 6.x** — that is a breaking API change. v7 hardening deltas are documented in [node-api](../node-api/spec.md) A-12. |
| BouncyCastle | `1.85.2` | Java 25 runtime support begins at **1.81**. 1.76 is vulnerable to **CVE-2024-30172** — that floor is preserved. 1.85 is the security-focus release (parser hardening, crypto fixes). |
| logback | `1.5.38` | Runtime-only logging backend. **1.5.13** fixed CVE-2024-12798 / CVE-2024-12801 (config-time expression/DTD injection). Do not drop below 1.5.13. |
| slf4j | `2.0.12` | Declared floor; conflict resolution may lift it (the ActiveJ fork pulls 2.0.16). |
| org.json | `20240303` | — |
| JUnit Jupiter | `5.9.1` | Runs on Java 25 as-is. No `io.activej.test` rules are used anywhere, so ActiveJ test-module churn does not reach the suite. |

**ASM: deliberately unpinned.** A legacy `resolutionStrategy` force of ASM 9.7 was removed. Neither
the v6 nor the v7 ActiveJ resolved graph contains an `org.ow2.asm` artifact — verified empirically
against the resolved graph, not assumed. The force was inert config that misled readers into
thinking ASM was in play. If an ASM artifact ever *does* reappear in a resolution, re-add the force
at **≥ 9.8** — that is the first version that can read class file V25/69 bytecode.

### P-4 — Build plugins *(implemented)*

| Plugin | Version | Role |
|---|---|---|
| `nebula.lint` | `21.2.1` | Dependency hygiene gate (see P-6). 21.x is the Gradle 9 line. |
| `io.freefair.lombok` | `9.5.0` | Lombok for all modules. 9.x is the Gradle 9 line. |

Lombok is the **one permitted code generator** in this project. Java 25 support landed in Lombok
1.18.40+; freefair 9.5.0 bundles a current one. If a future freefair release ever lags behind a JDK
bump, pin the Lombok version explicitly (`lombok { version = '1.18.42' }`) rather than pinning the
plugin backwards.

### P-5 — GraalVM native image *(implemented)*

`./gradlew :app-node:nativeImage` produces a native node binary. Reachability metadata is checked in
under `app-node/src/main/resources/META-INF/native-image/`.

Because Gradle 9.6.1 runs on JDK 25, **one JDK now suffices** — the previous two-JDK dance (an older
JDK to run Gradle, a GraalVM to run `native-image`) is gone. Use a GraalVM as the current SDK
(e.g. `sdk use java 25.0.2-graal`) and `native-image` resolves from `PATH`.

The no-DI/no-reflection architecture is what keeps this viable: none of ActiveJ's runtime-codegen
modules (DI/inject, serializer, codegen, specializer, datastream, launchers, servicegraph, config,
RPC, triggers, workers) are used anywhere in the repo. ActiveJ usage is confined to
**`app-node`** (http, eventloop, promise, csp, bytebuf) and **`lib-persistence`** (bytebuf, one call
site). Outbound HTTP — p2p, wallet, discovery — uses the JDK's own `java.net.http` and is unaffected
by the fork entirely.

### P-6 — Module boundaries and the lint gate *(implemented)*

Nebula lint runs with the **`all-dependency`** rule on every build. Each module must declare exactly
what it uses:

- `api` **only** when the type appears in a public signature;
- `implementation` otherwise.

`undeclared-dependency` is in `excludedRules`; the rest of the rule set is active. This is a quality
gate, not advice — `./gradlew build` fails on violation. It is the mechanical half of the
interface-driven decoupling described in [CLAUDE.md](../../CLAUDE.md): `lib-core` defines
`ContractProcessor`, `PeerSource`, `ChainStore`/`NonceStore`/`BoxStore`, and the outward modules
implement them, so consensus never compiles against a transport or a WASM runtime.

`ContractStore` is deliberately **not** in that list: it lives in `lib-vm`, next to the contract
logic that drives it, under the same ownership rule the rest of the tree follows — a port belongs
with the domain logic that uses it, not with whoever happens to hold a reference. `lib-core` reaches
contract state through `ContractProcessor` and never names a store type. It is also no longer one
interface: `19ff5b9` split it into `ContractState`, `ContractSnapshotStore` and
`ContractJournalStore` for the three audiences that were sharing it.

### P-7 — Benchmark harness *(implemented)*

JVM system properties whose name starts with `bench` are forwarded from the Gradle invocation into
the forked test JVM:

```bash
./gradlew :lib-core:test -Dbench=true --tests "rhizome.ValidationBenchmark"
```

Benchmarks are ordinary JUnit classes that no-op unless the property is present, so they stay inside
the normal suite without slowing it. `ValidationBenchmark` is the consensus throughput probe; the
**warm-cache regression floor is ≈140 blocks/s** at 2000 tx/block. `Ed25519ScalingBenchmark`
(lib-core) and `Pufferfish2Benchmark` (lib-crypto) probe signature scaling and PoW throughput.

Benchmarks write `bench.txt` into the module directory; it is gitignored.

## Verified state (as of feature 001-activej-v7-java25)

Measured on Oracle GraalVM 25.0.2 LTS, Gradle 9.6.1, the pins above:

| Gate | Result |
|---|---|
| Full build | `./gradlew build` green — lint + compile + suite, across 5 consecutive `--rerun-tasks` runs |
| Test suite | **801 tests / 130 classes, 0 skipped, 0 failures** |
| Bytecode level | class major **69** verified on all 7 modules |
| Consensus benchmark | 438 863 verif/s → **219.4 blocks/s** @2000tx (floor ≈140) |
| Native image | `:app-node:nativeImage` green, binary in ~1m 9s |

Two **build-observability** issues were seen during that validation and remain open. Neither is a
scenario failure — every complete run was green — but both would be invisible in CI:

- **Flaky lib-core test.** One `:lib-core:test --rerun-tasks` run reported 2 failures out of 380
  and could not be reproduced in 25+ subsequent runs (~4% observed, then zero). The failing tests
  were overwritten before capture. The only timing-sensitive candidate in `lib-core` is
  `BlockProducerTest`. Needs a repeat-run CI job to pin down.
- **Test task under-run in full-build context.** `:lib-core:test` (during `clean build`) and
  `:lib-crypto:test` (during 1 of 3 `build --rerun-tasks`) once executed **only** their `*Benchmark`
  class — 1 result XML instead of 60/8. Direct per-module runs always execute the full set. The
  build cache is disabled, so this is not a cache-restore artifact. Risk: **CI could silently
  under-run tests and still report green.** Verify with a `--rerun-tasks` check once CI is active.

## Invariants (must never regress)

- **Toolchain, source, target and `release` stay aligned at the same version.** Setting the
  toolchain without `options.release` reopens the `NoSuchMethodError` gap.
- **No pin is downgraded without reading its comment.** The security floors (BouncyCastle 1.76 for
  CVE-2024-30172, logback 1.5.13 for CVE-2024-12798/12801) are hard minimums.
- **ActiveJ stays on the Censseo fork.** Upstream 6.x and 5.x are not drop-in; 5.x is a breaking API
  change.
- **No DI container, no reflection-based wiring, no runtime codegen.** Node wiring is explicit
  constructors. This is what keeps the native image buildable — it is an architectural constraint,
  not a stylistic one.
- **Each module declares exactly what it uses**, `api` only for public signatures. The
  `all-dependency` lint rule is a build gate.
- **Zero skipped and zero `@Disabled` tests** in a green build.
- **If an ASM force is ever reintroduced, it must be ≥ 9.8** — earlier versions cannot read V25/69
  bytecode.

## References

- [CLAUDE.md](../../CLAUDE.md) — working conventions, module map, pin rationale
- [README.md](../../README.md) — build, run and configure
- [node-api](../node-api/spec.md) A-12 — the ActiveJ v7 runtime defaults as they affect the HTTP surface
- Skills: `java-gradle-patterns`, `activej-http-patterns`, `junit5-conventions`
