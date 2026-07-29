# Cryptography Specification

> Source of truth for the cryptographic primitives — Ed25519, Pufferfish2 proof of work, and hashes.
> Extracted from `WHITEPAPER.md` §3.2, §7 and source analysis.
> **Status**: Draft — needs review

## Overview

`lib-crypto` holds the primitives with **BouncyCastle as its only dependency**. It sits at the
bottom of the dependency graph: `lib-core`, `lib-vm`, `lib-net`, `app-node` and `app-wallet` all
depend on it, and it depends on none of them.

Two things here are consensus-critical: **Pufferfish2**, ported in pure Java and validated
bit-for-bit against a C reference, and **Ed25519 key/signature validation**, whose malleability was
one of the Pandanite defects this chain corrects.

## Scope

**Owns**

| Area | Source |
|---|---|
| Signing, verification, work arithmetic | [Crypto.java](../../lib-crypto/src/main/java/rhizome/crypto/Crypto.java) |
| Ed25519 keys and address derivation | [PublicKey.java](../../lib-crypto/src/main/java/rhizome/crypto/PublicKey.java), [PrivateKey.java](../../lib-crypto/src/main/java/rhizome/crypto/PrivateKey.java) |
| Pufferfish2 PoW | [Pufferfish2.java](../../lib-crypto/src/main/java/rhizome/crypto/Pufferfish2.java), [PufferfishAlgorithm.java](../../lib-crypto/src/main/java/rhizome/crypto/PufferfishAlgorithm.java), [PufferfishConstants.java](../../lib-crypto/src/main/java/rhizome/crypto/PufferfishConstants.java) |
| PoW cost parameters and cache | [PowCosts.java](../../lib-crypto/src/main/java/rhizome/crypto/PowCosts.java), [PowAlgorithm.java](../../lib-crypto/src/main/java/rhizome/crypto/PowAlgorithm.java) |
| Hashes and hex | [SHA256Hash.java](../../lib-crypto/src/main/java/rhizome/crypto/SHA256Hash.java), [RIPEMD160Hash.java](../../lib-crypto/src/main/java/rhizome/crypto/RIPEMD160Hash.java), [SimpleHashType.java](../../lib-crypto/src/main/java/rhizome/crypto/SimpleHashType.java), [Hex.java](../../lib-crypto/src/main/java/rhizome/crypto/Hex.java) |

**Does not own**

- Where in the header the PoW is checked, and in what order → [consensus](../consensus/spec.md) C-2
- The block hash preimage → [consensus](../consensus/spec.md) C-1
- Key storage, keystore encryption → [wallet](../wallet/spec.md)
- The in-browser Ed25519 reimplementation (`RzCrypto`) → [dashboard](../dashboard/spec.md)

## Public surface

| Class | Methods |
|---|---|
| `Crypto` | `generateKeyPair`, `signMessage`, `signWithPrivateKey`, `verifyMessage`, `checkSignature`, `verifyHash`, `checkLeadingZeroBits`, `addWork` |
| `PublicKey` | `of`, `empty` |
| `PrivateKey` | `of` |
| `PufferfishAlgorithm` | `compute` |

## Features

### K-1 — Pufferfish2 proof of work *(implemented)*

A memory-hard key-derivation function (`$PF2$`, `cost_t = 0`, `cost_m = 8`, all-zero salt →
deterministic), ported in **pure Java over HMAC-SHA512** (BouncyCastle) and **validated bit-for-bit
against a C reference through golden vectors**.

- `verifyNonce` recomputes the PoW hash and requires `difficulty` leading zero bits
  (`checkLeadingZeroBits`).
- Because difficulty is **bounded at 255**, a block claiming an absurd difficulty is rejected in
  **constant time with no costly allocation**.
- Pufferfish2 is used **from genesis** — no SHA-256 phase, no algorithm switch. Pandanite chose its
  PoW algorithm from an *uncommitted* `id` field (§4.5); here there is a single algorithm and
  nothing to switch on.
- The Pufferfish cache is **bounded** (Pandanite's was unbounded — a memory DoS).

`NetworkParameters` retains `powUpgradeHeight` / `powCostTAfter` / `powCostMAfter` fields for a
future cost change, currently inert (`powUpgradeHeight = 0`, the "after" costs `-1`).

### K-2 — Ed25519 signing and verification *(implemented)*

- `signMessage` / `signWithPrivateKey` / `verifyMessage` / `checkSignature` over BouncyCastle
  Ed25519.
- **Small-order and non-canonical keys are rejected** — a hostile key that verifies under multiple
  public keys would break the sender-binding invariant.
- **Domain-separated message signing** keeps signatures over different message classes disjoint.
- Malleability is neutralised at the *identity* layer rather than the signature layer: a
  transaction's identity is its **signature-free content hash**, so a malleated signature yields the
  same id and cannot double-execute (§4.6). See [transactions](../transactions/spec.md) T-2.

### K-3 — Address derivation *(implemented)*

`PublicAddress.of(publicKey)` — a 25-byte address over SHA-256/RIPEMD-160 with a checksum.
`PublicKey.empty()` is the sentinel sender for the block-minted `BOX_COLLECT`, pinned by
[transactions](../transactions/spec.md) invariants.

### K-4 — Cumulative work arithmetic *(implemented)*

`addWork` accumulates `2^difficulty` as `BigInteger`. Pandanite's getters read `BigInt` work without
a lock, producing torn reads (§4.9); here work arithmetic is pure and the chain state that holds it
is behind the engine lock.

### K-5 — Hashes and hex *(implemented)*

`SHA256Hash`, `RIPEMD160Hash`, `SimpleHashType`, `Hex`. A reused `ThreadLocal` digest and reused
codec buffers were introduced as **byte-identical** performance residual fixes — any change here
must preserve exact output.

## Invariants (must never regress)

- Pufferfish2 output must remain **bit-identical to the C reference golden vectors**. Any change is
  a hard fork.
- Difficulty is bounded at 255 and the bound is checked **before** any allocation, so an absurd
  claim costs constant time.
- One PoW algorithm from genesis; the algorithm is never selected from an uncommitted field.
- Ed25519 small-order and non-canonical keys are rejected.
- Message signing is domain-separated.
- Transaction identity is the signature-free content hash — never include a signature in an
  anti-replay key.
- All caches (Pufferfish included) are bounded.
- Performance work in this module must be byte-identical to what it replaces.
- BouncyCastle is pinned to a patched release (≥ 1.78, CVE-2024-30172) and is the module's **only**
  dependency.
- No runtime code generation (GraalVM native constraint).

## References

- `WHITEPAPER.md` §3.2 (Pufferfish2), §4.5–4.6 (the C++ defects corrected here), §7.1 (invariants),
  §7.4 (Ed25519 hardening)
