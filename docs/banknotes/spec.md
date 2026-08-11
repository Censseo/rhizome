# Banknotes Specification

> Bearer notes — physical paper whose value is escrowed on-chain, redeemed by presenting a
> signature from a key that lives inside the note's NFC chip, co-signed by a certified machine.
> **Status**: Design — nothing implemented. This document fixes the protocol before any code.

## Overview

A banknote is a **paper bearer instrument backed by an irrevocable on-chain escrow**. It is closer
to a certified cheque than to a private key printed on paper: the issuer locks the face value in
the chain and can never take it back, so a holder can verify — without trusting the issuer, the
seller, or anyone else — that the paper in their hand is covered.

The design goal is that a note changes hands like cash: hand it over, the recipient taps it with a
phone, sees `50 RZM · active · issued by X`, and accepts. Three properties make that safe:

1. **A photograph of the note is worthless.** Everything visible on an intact note is public by
   construction. There is no secret to photograph.
2. **A hostile NFC tap cannot spend the note.** The chip is a dumb signer, so redemption requires a
   second, independent signature from a certified machine that has the note physically present.
3. **The value never depends on any single machine, issuer, or company staying alive.** Two
   independent escape hatches (§7, §8) guarantee redemption even if the machine network disappears
   entirely.

Issuance is **permissionless** — anyone may lock value and mint a note. The certified-machine
registry (§6) gates *redemption attestation* only. That split is what lets "anyone can print notes"
coexist with "a hostile tap cannot steal one".

## Scope

**Owns**

| Area | Planned location |
|---|---|
| Note record, machine record, payload codecs | `lib-core/src/main/java/rhizome/core/banknote/` |
| Lifecycle execution | `BanknoteProcessor` / `DefaultBanknoteProcessor` (same shape as `DefaultBoxProcessor`) |
| Receipts (reorg reversal) | `BanknoteReceiptCodec` |
| Store abstraction + persisted store | `BanknoteStore`, `InMemoryBanknoteStore`, `RocksDbBanknoteStore` |
| HTTP read surface | `BanknoteApi` in `app-node` |

**Does not own**

- The `BANKNOTE_*` transaction envelope → [transactions](../transactions/spec.md)
- The `BANKNOTE` / `BANKNOTE_MACHINE` state-root domains → [state](../state/spec.md)
- Physical note manufacturing (seal rolls, printers) — out of protocol scope, summarised in §3

---

## 1. Why a native primitive, not a WASM contract

The obvious first instinct is to write this as a contract. It cannot work, for three reasons that
are each independently fatal:

- **No signature verification in the host ABI.** The whitelist in
  [WasmVm.java:934-936](../../lib-vm/src/main/java/rhizome/vm/WasmVm.java#L934-L936) is exactly
  twelve names (`storage_read`, `storage_write`, `set_output`, `emit_log`, `get_caller`,
  `get_input`, `get_value`, `get_self`, `get_deployer`, `transfer_value`, `call_contract`,
  `box_read`). There is no Ed25519 verify and no hash function, and
  [`rejectNonWhitelistedAbi`](../../lib-vm/src/main/java/rhizome/vm/WasmVm.java#L944) rejects at
  deploy time any module importing anything else. A banknote must verify signatures from keys that
  are *not* the transaction sender — precisely what the VM cannot do.
- **No block height.** `ContractProcessor.run(from, kind, to, data, value, gasLimit, nonce)`
  ([ContractProcessor.java:32-33](../../lib-core/src/main/java/rhizome/core/blockchain/ContractProcessor.java#L32-L33))
  deliberately carries no height — contracts get no time oracle. The fallback claim window (§7)
  and the degraded-mode trigger (§8) are both height-based, so they are unimplementable in a
  contract.
- **Cost.** Verifying Ed25519 inside WASM would burn six figures of gas per redemption, on an
  instrument whose whole point is to behave like small change.

Banknotes therefore follow the **native primitive** pattern already established by data boxes and
native tokens: deterministic, no VM, no gas, paid by the ordinary fee. Value locking, receipts,
undo journals and state-root domains all have working precedent to mirror ([boxes](../boxes/spec.md),
[tokens](../tokens/spec.md)).

Extending the host ABI with `verify_ed25519` + `get_block_height` remains a legitimate *separate*
proposal — it would benefit many contracts — but it is a consensus-level VM change and is not a
prerequisite for this feature.

## 2. Threat model

Every mechanism below exists to answer one of these. Attacks are listed with the countermeasure
that defeats them, and the residual risk stated honestly where one remains.

| Attack | Countermeasure | Residual |
|---|---|---|
| Photograph a note in circulation, redeem from the photo | Nothing secret is printed on an intact note; the chip key never leaves the chip | None while the seal is intact |
| Walk past with an NFC reader, harvest a signature, redeem it | Redemption needs a machine attestation (§5) the attacker cannot produce; verification taps are domain-separated (§4.4) so a harvested verify signature is not a redeem signature | Fails only in degraded mode (§8), which requires the machine network to be provably dead |
| Clone the printed front (its QR is public) onto blank paper | The seal is a manufactured physical object — chip + bubble tag + serialised hologram; the chip answers a challenge, a photocopy does not | Physical-security arms race, not a cryptographic guarantee. See §3 |
| Issuer keeps a copy of the note's key | The key is generated *inside* the chip and never exists outside it | A compromised chip fab. Mitigated by a bond and reputation on the issuing machine (§6) |
| The machine that issued my note goes offline | Attestation is accepted from *any* certified machine, never the issuing one | None |
| The whole machine network disappears | Fallback claim (§7) redeems with the printed key alone; degraded mode (§8) accepts chip-only | Both trade tap-resistance for liveness, by design |
| Someone tears the seal, photographs the fallback key, hands the note back | Torn seal is visible to the eye *and* electrically latched in the chip; the claim delay (§7) lets the physical holder win the race with a nominal redeem | Once the seal is torn, the fallback key is shared. The holder must redeem promptly |
| A corrupt machine operator co-signs a redemption for a note they do not hold | Machine bond, slashing, on-chain reputation counters (§6) | Real. A corrupt machine plus a harvested chip signature is the sharpest remaining attack; economic, not cryptographic, defence |
| Spam the mempool with bogus redemptions | Admission verifies the chip signature and note state before accepting (§9) | Bounded by the number of live notes |

## 3. Physical anatomy (informative)

Not consensus, but the protocol assumes it.

- **Stock**: ordinary secure paper, pre-printed offset (guilloche, UV ink, optically variable ink).
  Blank stock carries no value — a stolen ream is worthless, because value only exists once an
  escrow references a seal.
- **Seal roll**: manufactured, sequentially serialised seals, each carrying an NFC chip whose
  Ed25519 keypair is generated inside the secure element, a bubble tag / serialised hologram for
  offline optical authentication, and a tamper loop.
- **Machine-printed**: denomination, serial, issuing machine, and the public verification QR —
  all public.
- **Under a pressure seal**: the fallback key in the clear (§7), a single printed QR. The chip's
  tamper loop runs through the fold, so tearing the seal latches "opened" permanently in the chip
  and is reported on every subsequent tap.

The laser prints *information*; the factory prints *authentication*. Toner colour is a readability
aid and carries no security weight.

## 4. On-chain model

### 4.1 The note record

Derived id, following the box/token convention
([`Box` id derivation](../boxes/spec.md), `SHA-256(creator ‖ nonce ‖ "rzbox")`):

```
noteId = SHA-256(issuer ‖ nonce ‖ "rznote")     // 32 bytes, unforgeable, non-repeating
```

| Field | Type | Notes |
|---|---|---|
| `noteId` | 32 B | primary key |
| `denomination` | i64 | base units, must be in `banknoteDenominations` |
| `chipPubKey` | 32 B | Ed25519, generated inside the seal's secure element |
| `fallbackPubKey` | 32 B | Ed25519, printed under the pressure seal, then forgotten by the machine |
| `sealId` | 32 B | hash of the optical fingerprint (bubble tag / hologram serial), for cross-checking the physical seal against the chip |
| `issuer` | 25 B | `PublicAddress` that issued — reputation only, carries no authority |
| `issuedHeight` | i64 | |
| `status` | u8 | `ACTIVE` \| `CLAIM_PENDING` \| `REDEEMED` |
| `claimDest`, `claimHeight` | 25 B, i64 | set only while `CLAIM_PENDING` |

A note is ~200 bytes. **Redeemed notes are not deleted** — the record is retained with
`status = REDEEMED` so that a tap on a spent note reports "already redeemed" rather than "unknown",
which is the difference between a clear answer and a scary one at the point of sale.

### 4.2 The machine record

| Field | Type | Notes |
|---|---|---|
| `machine` | 25 B | `PublicAddress`, primary key |
| `attestPubKey` | 32 B | secure-element key used for co-signatures |
| `bond` | i64 | locked at registration, slashable, returned on clean retirement |
| `registeredHeight`, `lastSeenHeight` | i64 | `lastSeenHeight` advances on any attestation or heartbeat |
| `status` | u8 | `ACTIVE` \| `RETIRED` \| `SLASHED` |
| `notesIssued`, `notesAttested` | i64 | public reputation counters |

### 4.3 Transaction kinds

`TransactionKind` ordinals are the wire codes and are **append-only** — never insert
([TransactionKind.java:15-64](../../lib-core/src/main/java/rhizome/core/transaction/TransactionKind.java#L15-L64)).
Appending after `TOKEN_BURN(9)`:

| Kind | Code | Signed by envelope | Effect |
|---|---|---|---|
| `BANKNOTE_ISSUE` | 10 | issuer | locks `denomination` out of the ledger into a new note |
| `BANKNOTE_REDEEM` | 11 | anyone (fee payer) | nominal path: chip signature + machine attestation → pays `claimDest` |
| `BANKNOTE_CLAIM` | 12 | anyone | fallback path: announces intent with the printed key |
| `BANKNOTE_SETTLE` | 13 | anyone | executes a matured claim |
| `BANKNOTE_MACHINE` | 14 | machine | sub-op in payload: `REGISTER` \| `HEARTBEAT` \| `RETIRE` |

All five need `hasPayload() == true` and a new `isBanknote()` predicate, dispatched in
`Executor` pass 1/pass 2/rollback
([Executor.java:222-260, 328-341, 843-850](../../lib-core/src/main/java/rhizome/core/blockchain/Executor.java#L222-L260))
and in mempool admission
([MemPool.java:149-198](../../lib-core/src/main/java/rhizome/core/mempool/MemPool.java#L149-L198)).

### 4.4 Signature preimages — domain separation is load-bearing

The transaction envelope has exactly one signature slot
([TransactionImpl.java:60-64](../../lib-core/src/main/java/rhizome/core/transaction/TransactionImpl.java#L60-L64)),
so the chip and machine signatures travel **inside the payload** and are verified by the processor,
not by the batch verifier. Each is `Crypto.checkSignature`
([Crypto.java:62-74](../../lib-crypto/src/main/java/rhizome/crypto/Crypto.java#L62-L74)) over a
domain-tagged preimage, following the existing `RHIZOME_MSG\0` convention
([Crypto.java:88](../../lib-crypto/src/main/java/rhizome/crypto/Crypto.java#L88)):

```
redeem   : "RZNOTE_REDEEM\0" ‖ chainId(4) ‖ noteId(32) ‖ dest(25)        — chip key
attest   : "RZNOTE_ATTEST\0" ‖ chainId(4) ‖ noteId(32) ‖ dest(25) ‖ machine(25)  — machine key
claim    : "RZNOTE_CLAIM\0"  ‖ chainId(4) ‖ noteId(32) ‖ dest(25)        — fallback key
verify   : "RZNOTE_VERIFY\0" ‖ challenge(32)                             — chip key, NEVER valid on-chain
```

Two consequences worth stating plainly:

- **Binding `dest` into every preimage kills front-running.** A signature seen in the mempool pays
  only the address it already names. Observers gain nothing.
- **The `verify` domain is what makes public tap-verification safe.** Anyone may challenge a chip
  and get a signed response — that is the acceptance gesture — and no such response can ever be
  replayed as a redemption, because the domain tag differs. Without this separation, verifying a
  note would be equivalent to spending it.

Including `chainId` ([NetworkParameters.java:36](../../lib-core/src/main/java/rhizome/core/blockchain/NetworkParameters.java#L36),
mainnet 1 / testnet 2 / devnet 3) prevents cross-network replay.

## 5. Nominal lifecycle

**Issue.** `BANKNOTE_ISSUE` debits `denomination + fee` from the issuer and writes the note record.
The value leaves the ledger exactly as `BOX_CREATE` moves value into a box
([DefaultBoxProcessor.java:127-141](../../lib-core/src/main/java/rhizome/core/box/DefaultBoxProcessor.java#L127-L141),
ledger movement in [Executor.java:572-587](../../lib-core/src/main/java/rhizome/core/blockchain/Executor.java#L572-L587)).
The chain's total money becomes account balances + box values + **live note values**.

Issuance is **irrevocable**: there is no cancel, no expiry, no refund path. This is the property
that makes a note acceptable from a stranger — the issuer cannot pull the provision. A lost note is
burned value, exactly like a lost paper banknote.

**Verify** (off-chain, free). Tap → chip signs a `RZNOTE_VERIFY` challenge → read
`GET /banknote?id=…` from any node → face value, status, issuer, seal id. Optically, the bubble tag
cross-checks that the chip has not been transplanted onto forged paper.

**Redeem.** The machine authenticates the note physically, obtains the chip's `redeem` signature
over the beneficiary's address, adds its own `attest` signature, and submits `BANKNOTE_REDEEM`.
The processor requires, in order: note `ACTIVE`; machine `ACTIVE` in the registry; both signatures
valid over their preimages with the same `noteId` and `dest`. It then marks the note `REDEEMED`,
credits `dest`, and advances the machine's `lastSeenHeight` and `notesAttested`.

**Fees come out of the escrow, not the sender.** `credit(dest) = denomination − fee`, capped so a
fee can never exceed the face value (the same capping discipline as box rent). This is not a detail:
a person whose only asset is a banknote has no balance to pay a fee with, and a note that cannot be
redeemed by a first-time user is not cash. It also means the redeem transaction's `from` may be an
empty account, which the processor must tolerate.

## 6. Machine registry

`BANKNOTE_MACHINE/REGISTER` locks `banknoteMachineBond` and records the attestation key. A new
machine is admitted by **M-of-N endorsement** from currently-active machines, with the initial set
seeded in `NetworkParameters` at activation. Retirement returns the bond after a cooldown; proven
misbehaviour slashes it.

The registry exists to make attestation *accountable*, not to make it exclusive: it is permanently
open to new entrants, so the network can renew itself indefinitely and no operator can hold
redemption hostage. Note that reputation counters and bond size are readable at
`GET /banknote/machines`, so a person deciding whether to accept a note can see who stands behind
its issuance.

**This is the least-settled part of the design** — see §12.

## 7. Fallback claim (seal-protected escape hatch)

If the chip is dead, or no machine is reachable, the holder tears the pressure seal and uses the
printed fallback key from an ordinary phone:

1. `BANKNOTE_CLAIM` — valid `claim` signature over `(noteId, dest)`. The note moves to
   `CLAIM_PENDING`, recording `claimDest` and `claimHeight`. **No value moves.**
2. After `banknoteFallbackDelayBlocks`, `BANKNOTE_SETTLE` pays `claimDest`.
3. **A nominal `BANKNOTE_REDEEM` during the window supersedes the claim and cancels it.**

That third rule is the whole point. If someone tears the seal, photographs the fallback key and
hands the note back, the victim sees the broken seal (visually, and latched in the chip on the next
tap) and has the full window to redeem normally at a machine — *the party physically holding the
note beats the party holding only a photograph*. A later claim announcement re-arms the window with
a fresh `claimHeight`, so griefing by repeated announcement gains nothing.

The delay costs nothing in the honest cases (a dead chip is not urgent). Suggested value: 48 h,
which at `desiredBlockTimeSec = 5`
([NetworkParameters.java:462](../../lib-core/src/main/java/rhizome/core/blockchain/NetworkParameters.java#L462))
is **34 560 blocks**.

## 8. Degraded mode (network-death escape hatch)

Machines advance `lastSeenHeight` on every attestation and, when idle, via
`BANKNOTE_MACHINE/HEARTBEAT`. If **no** registered machine has been seen for
`banknoteDegradedAfterBlocks`, the processor accepts a `BANKNOTE_REDEEM` carrying the **chip
signature alone**, and the note becomes redeemable from any NFC phone.

This deliberately trades tap-resistance for liveness, and only in a world where the machine network
is already provably dead — where the priority is recovering value, not circulating notes. The
switch is a pure function of chain state, so it is deterministic and every node agrees on it.

Combined, §7 and §8 give the guarantee that matters: **in the last resort the value depends only on
the chain and on the paper in your hand.**

## 9. Consensus integration

| Concern | Requirement | Precedent |
|---|---|---|
| Activation | `banknoteActivationHeight`, enforced in **both** `Executor` and `MemPool` — an unconditionally-admitted pre-activation tx halts block production network-wide | [Executor.java:223](../../lib-core/src/main/java/rhizome/core/blockchain/Executor.java#L223), [MemPool.java:191-197](../../lib-core/src/main/java/rhizome/core/mempool/MemPool.java#L191-L197) |
| Gas | must be zero on all `BANKNOTE_*` kinds | box/token gate, `Executor.java:222-260` |
| Height | passed as a trailing `long` to `BanknoteProcessor.run(...)`, never a context object | [BoxProcessor.java:36-37](../../lib-core/src/main/java/rhizome/core/box/BoxProcessor.java#L36-L37) |
| Receipts | **required** — value crosses the ledger boundary, so banknotes follow the box pattern (one receipt per banknote tx, *even on soft-revert*), not the receipt-free token pattern | [DefaultBoxProcessor.java:89-97](../../lib-core/src/main/java/rhizome/core/box/DefaultBoxProcessor.java#L89-L97), [BoxReceiptCodec.java:33-83](../../lib-core/src/main/java/rhizome/core/box/BoxReceiptCodec.java#L33-L83) |
| Undo journal | per-mutation prior-image in the same atomic `WriteBatch` as the record write and index maintenance | [RocksDbBoxStore.java:102-166](../../lib-persistence/src/main/java/rhizome/persistence/rocksdb/RocksDbBoxStore.java#L102-L166) |
| State root | new domain bytes `BANKNOTE = 0x08`, `BANKNOTE_MACHINE = 0x09`; folded in `ChainEngine.collectStateChanges` | [StateKeys.java:15-27](../../lib-core/src/main/java/rhizome/core/state/StateKeys.java#L15-L27), [ChainEngine.java:1303-1361](../../lib-core/src/main/java/rhizome/core/blockchain/ChainEngine.java#L1303-L1361) |
| Payload codec | strict: reject unknown tags, truncation, **and trailing bytes** | [BoxPayload.java:101-103](../../lib-core/src/main/java/rhizome/core/box/BoxPayload.java#L101-L103) |
| Mempool anti-spam | admission verifies note existence, status, and the chip signature before accepting — one Ed25519 verify, bounded by live-note count. The second signature is checked in the processor, never in the batch verifier (which only knows the envelope's single key) | [Executor.java:80-84](../../lib-core/src/main/java/rhizome/core/blockchain/Executor.java#L80-L84) |
| Feature flag | `banknotesAvailable()` → `banknoteProcessor != null`, surfaced in `GET /features` | [DashboardApi.java:97-108](../../app-node/src/main/java/rhizome/node/DashboardApi.java#L97-L108) |

**Reorg correctness is the hard part.** A redemption that reverses must restore `status`,
`claimDest`/`claimHeight`, the ledger credit, and the machine's counters — exactly. The rollback
walk consumes one receipt per banknote transaction and must fail fast before any mutation if the
count is short, mirroring
[RollbackReceiptGuardTest](../../lib-core/src/test/java/rhizome/core/blockchain/RollbackReceiptGuardTest.java).

### State growth

Note records are permanent and never expire. Boxes solve this with storage rent — **banknotes
deliberately do not**, because a note whose value decays is not a banknote; its face value must
hold indefinitely or the instrument is worthless as cash. The bound is therefore economic: a
`minBanknoteDenomination` set high enough that the permanent state cost is negligible against the
face value. Small change stays digital.

### Parameters

`banknoteActivationHeight`, `banknoteDenominations`, `minBanknoteDenomination`,
`banknoteFallbackDelayBlocks` (34 560), `banknoteMachineBond`, `banknoteMachineQuorum` (M-of-N),
`banknoteHeartbeatIntervalBlocks`, `banknoteDegradedAfterBlocks` — all added to
`NetworkParameters` with per-profile values for mainnet/testnet/devnet
([NetworkParameters.java:435-576](../../lib-core/src/main/java/rhizome/core/blockchain/NetworkParameters.java#L435-L576)).

## 10. Node surface

Read-only, following `BoxApi`/`TokenApi` shape, registered in `NodeApi.servlet` and offloaded off
the event loop ([NodeApi.java:229-245](../../app-node/src/main/java/rhizome/node/NodeApi.java#L229-L245)):

- `GET /banknote?id=<hex>` — face value, status, issuer, seal id, claim state
- `GET /banknotes?issuer=<addr>` — notes by issuer
- `GET /banknote/machines` — registry with bonds, counters, last-seen heights

Transactions go through the existing `POST /add_transaction`. **One genuinely new surface is
needed**: an endpoint on a machine (not on a plain node) that co-signs a redemption — there is no
precedent for it, since `BoxApi`/`TokenApi` are pure reads.

## 11. Phasing

1. **Protocol core** — note record, `BANKNOTE_ISSUE`/`REDEEM`, receipts, journal, state-root
   domain, in-memory + RocksDB stores. Testable end to end with software-simulated chips.
2. **Escape hatches** — `CLAIM`/`SETTLE` and the delay rule; degraded mode.
3. **Machine registry** — bond, endorsement quorum, heartbeats, slashing.
4. **Dashboard** — issue/verify/redeem pages; a phone with NFC is already a verifier.
5. **Kiosk** — Raspberry Pi + laser printer + seal applicator + pressure-seal folder, running the
   dashboard in kiosk mode against a local node.

Phase 1 is independently useful and carries no physical dependency. Phases 3–5 are where the
unsettled questions live.

## 12. Open questions

- **Machine certification governance.** M-of-N endorsement by incumbents is the sketch, but it is
  cartel-prone: incumbents can refuse entrants. Alternatives — bond-only permissionless
  registration (weaker accountability), miner voting (the chain already has voted economic
  parameters), or a hybrid. **Unresolved, and it gates phase 3.**
- **Slashing evidence.** What constitutes on-chain proof that a machine attested for a note it did
  not hold? Physical possession is unobservable to the chain. Possibly unfalsifiable — in which
  case the bond deters only through the *threat* of governance action, not automatic slashing.
- **Second signature in the payload vs. the envelope.** Payload keeps `TransactionDto` unchanged
  and matches box/token precedent. An envelope-level multi-signature slot would be cleaner for
  future primitives but is a wire-breaking change with no existing precedent.
- **Light-path persistence.** Boxes and tokens have RocksDB + in-memory stores but **no pure-Java
  store implementation** (the LevelDB cluster was dropped; only the dumper that reads a foreign
  Pandanite ledger still uses that dependency). Banknotes inherit that gap; whether to fix it here
  or accept it is a scoping decision.
- **Chip supply chain.** Everything rests on keys being generated inside the secure element and
  never leaving. That is a manufacturing assurance question, entirely outside the protocol.

## Cross-domain invariants this feature must respect

- **Atomicity with the block** — note and machine state moves with its block and reverses exactly
  on reorg (receipts + journal + atomic `WriteBatch`).
- **Cheapest-first validation** — cheap state checks (existence, status, activation) before the
  Ed25519 verifications, which are the expensive part of a banknote transaction.
- **Derived, never cached** — degraded-mode status is recomputed from `lastSeenHeight` in chain
  state on every evaluation, never memoised across a `popBlock`.
- **Single writer** — all of this runs under the existing `ChainEngine` lock; the processor adds no
  locking of its own.
- **Append-only wire** — `TransactionKind` ordinals 10–14 are appended, never inserted; the payload
  codec rejects trailing bytes.
