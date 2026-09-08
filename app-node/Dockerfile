# syntax=docker/dockerfile:1.7
#
# Rhizome node — reproducible two-stage image (builder → slim glibc runtime).
#
# Build context MUST be the REPOSITORY ROOT, not app-node/: this is a multi-module Gradle
# build (settings.gradle at the root pulls in lib-crypto/lib-core/lib-net/lib-vm/
# lib-persistence/app-wallet/app-node), so the builder stage needs the whole tree. Build from
# the repo root with:
#
#   docker build -f app-node/Dockerfile -t rhizome-node:staging .
#
# This file has been reviewed for syntax and checked with `docker build --check` (a
# lint-only dry run — see the "NOT BUILT OR RUN" note near the bottom) but deliberately NOT
# actually built or run in the environment it was authored in (shared devbox).

# ================================================================================================
# Stage 1 — builder: compile the node and link it into a GraalVM native binary.
#
# Deliberately runs the *actual* `nativeImage` Gradle task (app-node/build.gradle, around
# lines 226-260) instead of re-deriving its own `native-image` invocation — that task already
# owns the exact flags (`--no-fallback -O2 -H:Name=rhizome-node`) and picks up the
# reachability metadata shipped inside the installed jars under
# META-INF/native-image/rhizome/rhizome-node/ automatically. Reinventing the invocation here
# would be a second copy of those flags that could silently drift from the one Gradle task
# actually maintained (the same "single source of truth" concern CLAUDE.md raises about the
# lib-vm contract .rs/.wasm pair).
# ================================================================================================

# JDK 25 because the root build.gradle toolchain enforces it (JavaLanguageVersion.of(25)) and
# pins options.release = 25 — see CLAUDE.md "Requires JDK 25". Gradle 9.6.1 (the wrapper's
# pinned version, gradle/wrapper/gradle-wrapper.properties) runs ON JDK 25 directly, so one
# JDK-25-with-native-image toolchain is sufficient for the whole build (no separate JDK needed
# to run Gradle itself, per app-node/build.gradle's own nativeImage task comment).
#
# IMAGE TAG: confirmed to exist (not a guess) via `docker buildx imagetools inspect
# ghcr.io/graalvm/graalvm-community:25`, which resolved a real multi-arch manifest list
# (linux/amd64 + linux/arm64, digest sha256:7eeb8043...) against the live registry. What was
# NOT verified — this task's constraints rule out `docker run` on this shared box — is the
# exact `java -version` feature/patch string inside the image; the tag matches this project's
# documented convention (JDK major version as the tag) and CLAUDE.md's "Requires JDK 25", but
# a maintainer should run `docker run --rm ghcr.io/graalvm/graalvm-community:25 java -version`
# once, off this box, before relying on it for a real release build. Deliberately an ARG (not
# baked into a bare FROM) so a maintainer can repoint it in one place if that check disagrees:
#   docker build --build-arg GRAALVM_IMAGE=<confirmed-jdk25-tag> -f app-node/Dockerfile .
ARG GRAALVM_IMAGE=ghcr.io/graalvm/graalvm-community:25

FROM ${GRAALVM_IMAGE} AS builder

# native-image ships as a separate GraalVM component on some base images and preinstalled on
# others; this no-ops harmlessly when it is already on PATH (mirrors the "one JDK suffices"
# assumption app-node/build.gradle's nativeImage task doc makes — it just resolves
# native-image off PATH, same as here).
RUN native-image --version || gu install native-image

WORKDIR /src

# Whole repo: see the module-layout note above for why this can't be scoped to app-node/ alone.
# .dockerignore (repo root) trims it to what the Gradle build actually reads — build/.gradle
# caches, the local-testnet harness, specs/ideas/.claude working directories, etc.
COPY . .

RUN chmod +x gradlew

# --no-daemon: a lingering Gradle daemon process serves no purpose in a build stage that is
# discarded after this RUN completes, and would otherwise hold the layer open.
RUN ./gradlew --no-daemon :app-node:nativeImage

# ================================================================================================
# Stage 2 — runtime: slim glibc userland, native binary only, nothing else.
#
# debian-slim, deliberately NOT alpine: RocksDB's JNI library (org.rocksdb:rocksdbjni, pulled
# in by lib-persistence — see the rocksdb-leveldb-patterns skill) ships prebuilt against
# glibc/libstdc++ and cannot load under musl. The GraalVM native image produced above links
# against glibc by default too (the nativeImage task above does not pass any musl/static
# toolchain flag), so the runtime base must supply it.
# ================================================================================================
FROM debian:bookworm-slim AS runtime

# ca-certificates: the node makes outbound HTTPS requests to configured peers when
# RHIZOME_PEER_TOKEN and/or https:// peer URLs are set (lib-net's HttpPeerSource) — without a
# trust store those TLS handshakes fail. libstdc++6 is not installed explicitly here because
# bookworm-slim's base image already carries it as an essential-package transitive dependency;
# that assumption was NOT verified against a running container in this sandbox (no `docker
# run` was performed — see the note at the bottom of this file). If `rhizome-node` fails to
# start with a shared-library error, `ldd /usr/local/bin/rhizome-node` inside this stage is
# the first thing to check, and `apt-get install -y libstdc++6` the likely fix.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged user: the node never needs root once the binary and data directory are in
# place. --system gives it a fixed, non-interactive UID with no login shell.
RUN useradd --system --create-home --home-dir /home/rhizome --shell /usr/sbin/nologin rhizome

COPY --from=builder /src/app-node/build/native/rhizome-node /usr/local/bin/rhizome-node
RUN chmod 0755 /usr/local/bin/rhizome-node

# RHIZOME_DATA (NodeConfig.dataDir, default ./data) — the RocksDB directory. Declared as a
# volume so the ledger survives container recreation; an operator MUST attach a named volume
# or bind mount here in any real deployment — an anonymous volume (or none) loses the chain
# the moment the container is removed. The genesis allocation itself does NOT need to be
# mounted in separately: lib-core/src/main/resources/genesis/rhizome-staging.json (and the
# other network profiles) are compiled into the jars this image links, per NodeConfig's
# per-network RHIZOME_SNAPSHOT default.
ENV RHIZOME_DATA=/data
RUN mkdir -p /data && chown rhizome:rhizome /data
VOLUME ["/data"]

# RHIZOME_PORT default (see README.md's env-var table); override with `-e RHIZOME_PORT=...`
# and adjust the published port mapping to match if changed.
EXPOSE 3000

USER rhizome
WORKDIR /home/rhizome

# No entrypoint shell script: NodeConfig.fromEnv() reads System::getenv directly, so there is
# nothing for a wrapper script to translate — RHIZOME_* variables reach the process unchanged
# whether set via `docker run -e`, an env-file, or (see rhizome-node.service) systemd's
# EnvironmentFile. Exec form, not shell form: the binary runs as PID 1 and receives SIGTERM
# directly, which is what RhizomeNode's shutdown hook (flushes RocksDB, closes stores) needs —
# shell form would interpose /bin/sh as PID 1 and that shell does not forward the signal to a
# child process by default.
ENTRYPOINT ["/usr/local/bin/rhizome-node"]

# ------------------------------------------------------------------------------------------------
# NOT BUILT OR RUN: per this task's constraints, a real `docker build`/`docker run` was not
# executed against this file on the shared devbox it was authored on (the Docker daemon there
# already runs several unrelated containers belonging to other sessions; a native-image link
# is CPU/memory-heavy and a `docker run` would bind a port / consume resources without those
# owners' knowledge). This file was reviewed manually for Dockerfile syntax and additionally
# passed `docker build --check -f app-node/Dockerfile .` (buildx's lint-only dry run — it
# resolves base-image metadata and evaluates the build definition but runs no RUN/COPY step):
# result was "Check complete, no warnings found." It has not been proven to actually produce a
# working image.
# ------------------------------------------------------------------------------------------------
