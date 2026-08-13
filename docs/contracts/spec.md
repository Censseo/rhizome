# Contracts Specification

> Source of truth for the WASM smart-contract VM, host ABI, gas, and contract state.
> Extracted from `WHITEPAPER.md` §5.4, §7.2, §7.5 and source analysis.
> **Status**: Draft — needs review

## Overview

Contracts are **WebAssembly**, executed on the pure-Java [Chicory](https://github.com/dylibso/chicory)
runtime — no JNI, no native dependency, deterministic across nodes because every node runs the same
interpreter. A contract imports a small host ABI from module `env` and exports a `call` entry point;
the WASM sandbox denies it any other I/O.

The consensus core never depends on the WASM runtime: the `Executor` invokes a `ContractProcessor`
**interface** declared in `lib-core` and implemented by `WasmContractProcessor` in `lib-vm`, so
`lib-core` stays free of Chicory.

The largest residual fork risk in a metered-VM chain is **node-local nondeterminism**, so most of
this spec is about making execution byte-identical everywhere.

## Scope

**Owns**

| Area | Source |
|---|---|
| VM, host ABI, deploy-time validation | [WasmVm.java](../../lib-vm/src/main/java/rhizome/vm/WasmVm.java) |
| Consensus dispatch (`ContractProcessor` impl) | [WasmContractProcessor.java](../../lib-vm/src/main/java/rhizome/vm/WasmContractProcessor.java) |
| Gas metering & schedule | [GasMeter.java](../../lib-vm/src/main/java/rhizome/vm/GasMeter.java), [GasSchedule.java](../../lib-vm/src/main/java/rhizome/vm/GasSchedule.java) |
| Contract storage, sessions, undo journals | [ContractStore.java](../../lib-vm/src/main/java/rhizome/vm/ContractStore.java), [SessionContractStore.java](../../lib-vm/src/main/java/rhizome/vm/SessionContractStore.java), [ContractUndo.java](../../lib-vm/src/main/java/rhizome/vm/ContractUndo.java) |
| Host state, call frames, native transfers | [HostState.java](../../lib-vm/src/main/java/rhizome/vm/HostState.java), [PersistentHostState.java](../../lib-vm/src/main/java/rhizome/vm/PersistentHostState.java), [NativeTransferHandler.java](../../lib-vm/src/main/java/rhizome/vm/NativeTransferHandler.java) |
| Depth/locals budgets | [DepthLimitedInterpreterMachine.java](../../lib-vm/src/main/java/rhizome/vm/DepthLimitedInterpreterMachine.java), [WasmCallDepthExceeded.java](../../lib-vm/src/main/java/rhizome/vm/WasmCallDepthExceeded.java), [WasmLocalsBudgetExceeded.java](../../lib-vm/src/main/java/rhizome/vm/WasmLocalsBudgetExceeded.java) |
| Event logs | [LogEntry.java](../../lib-vm/src/main/java/rhizome/vm/LogEntry.java) |
| Contract sources (`#![no_std]` Rust) | [lib-vm/contracts/](../../lib-vm/contracts/) |
| Bundled dashboard templates | [dashboard/templates/](../../app-node/src/main/resources/dashboard/templates/) |
| Interface declaration | [ContractProcessor.java](../../lib-core/src/main/java/rhizome/core/blockchain/ContractProcessor.java) |

**Does not own**

- The `DEPLOY`/`CALL` transaction envelope, fees, nonces → [transactions](../transactions/spec.md)
- Data boxes read via `box_read` → [boxes](../boxes/spec.md)
- Persisted contract state on disk → [persistence](../persistence/spec.md)
- Contract code/storage state-root domains → [state](../state/spec.md)
- `/contract`, `/call_readonly`, `/logs` HTTP surface → [node-api](../node-api/spec.md)

## Features

### V-1 — Host ABI *(implemented)*

Module `"env"`, exported entry point `call`:

| Import | Purpose |
|---|---|
| `storage_read` / `storage_write` | contract key/value storage |
| `set_output` | return value |
| `emit_log(topic, data)` | event emission |
| `get_caller` / `get_self` / `get_value` / `get_input` / `get_deployer` | call context, host-supplied per frame and unspoofable |
| `call_contract(addr, input) -> output \| -1` | cross-contract call |
| `box_read(id)` | read a data box (not consumed) |
| `transfer_value` | native value transfer out of a contract |

### V-2 — Deterministic gas metering *(implemented)*

Every executed instruction is charged via the interpreter's execution listener; every host call is
charged on top. A contract that loops forever is aborted deterministically at the same step on
every node. Out-of-gas is a clean, identical failure everywhere.

Schedule ([GasSchedule.java](../../lib-vm/src/main/java/rhizome/vm/GasSchedule.java)):

| Cost | Value |
|---|---|
| `PER_INSTRUCTION` | 1 |
| `MEMORY_PER_PAGE` | 128 |
| `STORAGE_READ_BASE` | 50 |
| `STORAGE_WRITE_BASE` / `_PER_BYTE` | 200 / 256 |
| `OUTPUT_BASE` / `PER_BYTE` | 5 / 1 |
| `LOG_BASE` | 100 |
| `CALL_BASE` | 500 |
| `DEPLOY_BASE` / `_PER_CODE_BYTE` | 500 / 10 |
| `MODULE_PARSE_BASE` / `_PER_BYTE` | 500 / 2 |
| `BOX_READ_BASE` | 100 |

`CALL_BASE` is charged as an **intrinsic before dispatch** on every call *and* every read-only dry
run, so a call that fails before metering begins (unknown contract, gas limit too small for one
instruction) still costs the validator and can never be free repeatable load. The module-parse cost
is levied on **every** call, cache hit or miss, so a warm/cold module cache cannot change `gasUsed`.

### V-3 — Cross-contract calls *(implemented)*

`call_contract(addr, input)` lets a contract drive another; the callee sees the **calling contract**
as its caller.

- Each call frame runs against its **own store overlay**, flushed into the parent only on success —
  a failed sub-call leaves no trace; a caller that reverts after a successful sub-call discards the
  sub-call's writes with its own. Nested state is atomic with the top-level call.
- Gas is **shared across frames** (true forwarded gas — a sub-call cannot resurrect a spent budget).
- Call depth is bounded (**8**).
- **Reentrancy is refused outright** — a callee already on the call stack returns failure instead of
  executing, closing the classic DeFi exploit class by construction.
- Logs from sub-frames survive only if every enclosing frame succeeds, each stamped with its
  emitting contract.

### V-4 — Deploy, address derivation, and call *(implemented)*

`DEPLOY` installs code at a deterministic address `SHA-256(deployer ‖ nonce)[:25]` — predictable
and collision-free. `CALL` runs the target's `call` export with the transaction's input and any
attached value.

**Fees and atomicity**: the caller must afford `value + gasLimit × gasPrice`. It always pays
`gasUsed × gasPrice` to the miner — **even on revert or out-of-gas**, the work was done
(Ethereum-style) — while the value transfer and storage writes apply only on success. A reverted
call is still *included* in its block and pays gas but does not invalidate the block; only an
unaffordable or malformed contract transaction fails the block.

### V-5 — Per-block sessions, undo journals, receipts *(implemented)*

Contract storage is staged in a per-block **session** that flushes to the store only when the block
is accepted. A per-block **undo journal** (each written key's prior value) plus **receipts** (gas
used, success) let a reorg reverse both contract state and the contract-transaction ledger effects
**exactly** when a block is popped. Receipts are **persisted**, so a reorg after a restart reverses
cleanly instead of crashing mid-rollback.

### V-6 — Event logs and live feed *(implemented)*

`emit_log(topic, data)` records an event during a call — the channel autonomous agents watch. Logs
are gas-metered, kept only when the call succeeds, and never read back by contract code, so they
carry no consensus weight beyond the gas paid. The processor collects each block's logs and drops
them exactly on a reorg, like contract state.

Exposed three ways (see [node-api](../node-api/spec.md)): `GET /logs?height=N`, a height-cursor
scan `GET /logs?fromHeight=N` whose `toHeight` is the next cursor, and **SSE push** at
`GET /logs/stream` — one heartbeat comment per applied block (a natural keepalive at the 5 s
cadence, whatever path the block arrived by) and one `data:` event per log, block height as the SSE
event id. A subscriber that cannot keep up is disconnected rather than buffered without bound, and
resumes exactly via the `fromHeight` cursor — **push for liveness, cursor for correctness**.

### V-7 — Reference contracts *(implemented)*

`#![no_std]` Rust in [lib-vm/contracts/](../../lib-vm/contracts/), compiled to `.wasm` fixtures in
`lib-vm/src/test/resources/`. Both are the single source: the `stageContractTemplates` Gradle task
(app-node/build.gradle) stages them into
[dashboard/templates/](../../app-node/src/main/resources/dashboard/templates/) at build time,
validated against the editorial list in `dashboard/templates/manifest.json` — no hand-maintained
copy exists to drift. All are driven through consensus in the tests.

| Contract | What it proves |
|---|---|
| `token.rs` | fungible token — mint, transfer, allowances (`approve`/`transfer_from`), events; `transfer_from` **traps** on insufficient allowance/balance so composers observe the failure |
| `amm.rs` | self-contained constant-product AMM, `x*y=k`, 0.3% fee, exact integer math verified against the same formula in the test |
| `pair.rs` | token-backed AMM pair — pulls and pays two *real* token contracts via `call_contract`; an unauthorised swap unwinds both legs |
| `launchpad.rs` | fair-launch fixed-price sale; reverts when it cannot deliver, so the buyer's coin only moves when tokens do |
| `router.rs` | drives the token through consensus to prove composition |
| `agent_wallet.rs` | account abstraction for agents — owner-driven `exec`, plus revocable **session keys** capped per-token and decremented per spend |
| `counter.rs`, `emitter.rs` | minimal ABI fixtures |

> If you edit a `.rs` template, the corresponding checked-in `.wasm` in `lib-vm/src/test/resources/`
> **must** be rebuilt to match — this is not yet enforced by the build (no Rust toolchain is
> wired into Gradle), only by `DashboardTemplatesTest` pinning the served binary's `is_deployer`
> import and by review.

### V-8 — Agent wallet / account abstraction *(implemented)*

A contract account whose owner drives arbitrary calls through it (`exec` — the wallet is the
callee's caller, so it owns tokens and positions), and can grant **session keys**: other addresses
allowed to move at most a capped amount of one token out of the wallet, decremented per spend and
revocable at any time. An AI agent operates with its own key inside a hard budget; it never holds
the treasury's keys.

## Invariants (must never regress)

**Determinism is the whole game.** All of the following are load-bearing against forks:

- Contract execution is deterministic: gas-metered interpreter only, **no host time or randomness**.
- Scalar float, `V128`, and vector-float lanes are **rejected at deploy**, matched anywhere in the
  opcode name so integer↔float conversions cannot slip a prefix filter.
- The **WASM GC proposal is rejected wholesale at validation** — reference/array/struct types and
  their function-body, global-init and element-segment encodings alike. Chicory 1.7.5's GC opcodes
  allocate on the JVM heap outside every gas and memory budget: `array.new_default(len)` was a
  one-gas node-heap-dependent OOM, `array.copy`/`fill` an O(n) memcpy for one gas.
- Gas is integer/saturating. The call-depth cap, per-instance **and tree-wide** linear-memory caps,
  table count/aggregate caps, and a **tree-wide live-locals** reservation are fixed *network
  constants* — not `-Xmx`/`-Xss`-dependent — and are reserved *before* the runtime allocates.
- Declared locals **and function parameters** are bounded straight from the raw code bytes *before*
  the parser can eagerly expand them, so neither a many-group parse-time blow-up nor a locals×depth
  recursion can OOM one node while another reverts.
- `OutOfMemoryError` / `StackOverflowError` normalise to a deterministic **full-gas out-of-gas**.
- A `memory.grow` that overshoots the instance cap reserves **zero** tree-wide pages, not the full
  requested amount (WASM grow is all-or-nothing), so a failed grow cannot strand phantom pages that
  revert a later legitimate grow.
- Call context (`caller`, `self`, `value`, `deployer`) is host-supplied per frame and unspoofable.
- Contract state must move atomically with its block and reverse exactly on reorg.
- Consensus VM work is bounded by `maxTxGas` (per transaction) and `maxBlockGas` (per block declared
  total), checked in `executeBlock`'s structural pass **before any instruction runs** — otherwise a
  miner could seal a `gasPrice = 0` block with a 10¹¹-gas `loop … br 0` call, clear PoW, and force
  every node to run those instructions under the consensus lock.
- Host functions charge their gas **before** touching guest memory (`box_read` included).

## Open items

- No known open items in the VM itself; the sandbox and determinism surfaces were the subject of
  review passes 6–11 (§7.6).

## References

- `WHITEPAPER.md` §5.4 (smart contracts), §7.2 (determinism and fork resistance), §7.5 (contract
  sandbox), §7.3 (block gas ceiling), §9 (agent primitives)
- Skill: `chicory-wasm-patterns`
