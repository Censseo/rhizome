package rhizome.vm;

import java.util.List;

import rhizome.core.blockchain.ContractProcessor;
import rhizome.core.blockchain.ContractStateSource.ContractChange;
import rhizome.core.blockchain.ContractApi.ContractLog;
import rhizome.core.blockchain.Contracts;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.state.HeightRetainedIndex;
import rhizome.core.state.PruneCadence;
import rhizome.core.transaction.TransactionKind;

/**
 * {@link ContractProcessor} backed by the WASM VM and a persistent contract store.
 * Consensus (the Executor) calls this through the interface, so the consensus core
 * never depends on the WASM runtime.
 *
 * <p>State is staged twice: each call buffers its own writes ({@link PersistentHostState})
 * and, if it succeeds, flushes them into the block {@link SessionContractStore}; the
 * executor then flushes the whole session to the base store on {@link #commit()} or
 * drops it on {@link #discard()}. A reverted/out-of-gas call contributes no writes.
 */
public final class WasmContractProcessor implements ContractProcessor {

    private final WasmVm vm;
    private final ContractStore baseStore;
    private final int retainDepth;
    private volatile BoxReader boxReader;
    private volatile ContractProcessor.NativeBalance nativeBalance;
    private SessionContractStore session;
    private List<ContractReceipt> currentReceipts = new java.util.ArrayList<>();
    private List<ContractLog> currentLogs = new java.util.ArrayList<>();

    /**
     * Per-height retention for reorg reversal, bounded by heights AND bytes — see
     * {@link HeightRetainedIndex}, shared with the box and token processors. Four independent
     * index instances, one per domain: byte accounting serializes on each index's own monitor,
     * and reads ({@link #logs}/{@link #receipts}/{@link #changes} queries) run lock-free on the
     * height-keyed maps inside. Mutating one domain never blocks another. Safe because every
     * writer of these indexes runs under the engine lock and the only off-lock reader is
     * {@code NodeService.logsAt} — a reader of {@link #logsByHeight} alone, so it contends with
     * nothing the consensus path mutates concurrently.
     */
    private final HeightRetainedIndex<ContractUndo> journals;
    private final HeightRetainedIndex<ContractReceipt> receiptsByHeight;
    private final HeightRetainedIndex<ContractLog> logsByHeight;
    private final HeightRetainedIndex<ContractChange> changesByHeight;

    /**
     * Bounds on the RAM {@link #logsByHeight} retains. Event logs are a best-effort query
     * service, NOT consensus: they never feed the state root, so dropping old ones changes no
     * validation outcome. Before these caps, a block could emit gasPrice-0 spam logs and the
     * processor retained 600 heights of them unbounded (~hundreds of MB) — a memory-growth DoS
     * (audit: log retention). We cap both the entries kept per height and the total bytes kept
     * across heights; past the byte budget the OLDEST heights are dropped first (LRU by height).
     */
    static final int MAX_LOGS_PER_HEIGHT = 4_096;
    static final long MAX_RETAINED_LOG_BYTES = 64L * 1024 * 1024;

    /**
     * Byte budgets on the RAM the per-height journals / receipts / forward-changes retain
     * (audit: journal/receipt retention — the same unbounded-growth class the log budget closed,
     * but for 600 retained heights of attacker-inflated block state). Eviction past a budget
     * drops only the RAM copy and is consensus-safe for all three maps:
     * <ul>
     *   <li><b>journals</b> — {@link #revertBlock} falls through to the durable copy
     *       ({@code baseStore.getJournal}), present on RocksDB and on the in-memory store's
     *       journal hooks; the height-scheduled prune still owns durable deletion.</li>
     *   <li><b>receipts</b> — {@link #receipts} likewise loads through the durable copy.</li>
     *   <li><b>changes</b> — consumed only around the commit height ({@code
     *       ChainEngine.collectStateChanges}) and re-derived on any re-apply, so an old evicted
     *       entry is never read.</li>
     * </ul>
     */
    static final long MAX_RETAINED_JOURNAL_BYTES = 64L * 1024 * 1024;
    static final long MAX_RETAINED_RECEIPT_BYTES = 16L * 1024 * 1024;
    static final long MAX_RETAINED_CHANGE_BYTES = 64L * 1024 * 1024;

    /**
     * Amortized cadence of the durable interval prune ({@code pruneThrough} range tombstones) —
     * see {@link PruneCadence}: the per-height deletes run on every appended block, the interval
     * deleteRange is only the backstop for rows committed before a restart.
     */
    private final PruneCadence durablePrune = new PruneCadence();

    /** Uses a default retention depth; fine when reorgs are shallow. */
    public WasmContractProcessor(WasmVm vm, ContractStore baseStore) {
        this(vm, baseStore, 600);
    }

    /**
     * @param retainDepth how many recent blocks' undo journals to keep — must be at
     *                    least the chain's max reorg depth so any reversible block can
     *                    be undone; older journals are pruned to bound memory.
     */
    public WasmContractProcessor(WasmVm vm, ContractStore baseStore, int retainDepth) {
        this.vm = vm;
        this.baseStore = baseStore;
        this.retainDepth = retainDepth;
        this.journals = new HeightRetainedIndex<>(retainDepth, MAX_RETAINED_JOURNAL_BYTES,
            WasmContractProcessor::journalBytes);
        this.receiptsByHeight = new HeightRetainedIndex<>(retainDepth, MAX_RETAINED_RECEIPT_BYTES,
            WasmContractProcessor::receiptBytes);
        this.logsByHeight = new HeightRetainedIndex<>(retainDepth, MAX_RETAINED_LOG_BYTES,
            WasmContractProcessor::logBytes);
        this.changesByHeight = new HeightRetainedIndex<>(retainDepth, MAX_RETAINED_CHANGE_BYTES,
            WasmContractProcessor::changeBytes);
    }

    /**
     * Wires the box reader so contracts can {@code box_read} data boxes (Ergo-style
     * data inputs). Set once at node assembly, after the box processor exists.
     */
    public void setBoxReader(BoxReader boxReader) {
        this.boxReader = boxReader;
    }

    @Override
    public void useNativeBalance(ContractProcessor.NativeBalance source) {
        this.nativeBalance = source;
    }

    @Override
    public void begin() {
        session = new SessionContractStore(baseStore);
        currentReceipts = new java.util.ArrayList<>();
        currentLogs = new java.util.ArrayList<>();
    }

    @Override
    public ContractResult run(PublicAddress from, TransactionKind kind, PublicAddress to,
                              byte[] data, long value, long gasLimit, long nonce) {
        if (session == null) {
            begin();
        }
        ContractResult result = switch (kind) {
            case DEPLOY -> deploy(from, data, nonce, gasLimit);
            case CALL -> call(from, to, data, value, gasLimit);
            default -> ContractResult.reverted(0, "not a contract transaction");
        };        currentReceipts.add(new ContractReceipt(result.gasUsed(), result.success(), result.transfers()));
        currentLogs.addAll(result.logs());
        return result;
    }

    private ContractResult deploy(PublicAddress deployer, byte[] code, long nonce, long gasLimit) {
        PublicAddress address = Contracts.deriveAddress(deployer, nonce);
        long gasUsed = GasSchedule.DEPLOY_BASE + (long) code.length * GasSchedule.DEPLOY_PER_CODE_BYTE;
        if (gasUsed > gasLimit) {
            return ContractResult.outOfGas(gasLimit, "out of gas for deploy");
        }
        // Reject oversized, malformed, or non-deterministic (float/SIMD) code at deploy so it
        // never enters on-chain state, rather than only discovering it on every later call.
        // Only RuntimeException becomes a verdict: an Error here (StackOverflowError in
        // Parser.parse at a JIT/-Xss-dependent depth, OutOfMemoryError on a small-heap node)
        // is a HOST-local condition, and normalizing it to "invalid contract code" would make
        // the deploy verdict node-dependent and FORK consensus — the same class of fork the
        // CALL path refuses to normalize (see WasmVm). A crash is preferable to a fork, so
        // Errors propagate to the block executor's fatal catch-all.
        try {
            // Validation runs on the fixed-stack worker, exactly like CALL execution: the
            // parser's recursion depth on the engine thread is JIT/-Xss-dependent, so the
            // bounded worker's fixed stack keeps even the fatal path a network constant.
            // RuntimeException verdicts propagate unchanged through onBoundedStack.
            WasmVm.onBoundedStack(() -> {
                WasmVm.validateCode(code);
                return null;
            });
        } catch (RuntimeException e) {
            return ContractResult.reverted(gasUsed, "invalid contract code: " + e.getMessage());
        }
        // Never deploy over live code: the address is derived deterministically from
        // (deployer, nonce), so a collision would silently overwrite an existing contract
        // (audit F6). Reads through the session, so a deploy earlier in this same block counts.
        if (session.getCode(address) != null) {
            return ContractResult.reverted(gasUsed, "contract address collision");
        }
        session.putCode(address, code);
        // Record the deployer under the reserved empty storage key so get_deployer can return it and
        // a template can gate its init to the deployer (audit T1). This rides the normal contract-
        // storage commit path, so it is covered by the state root, snapshot export/import and the
        // reorg undo journal with no extra plumbing; contracts cannot overwrite it (storage_write
        // rejects a zero-length key).
        session.putStorage(address, PersistentHostState.DEPLOYER_KEY, deployer.toBytes());
        return ContractResult.ok(gasUsed, new byte[0], address);
    }

    /** Maximum nesting of contract-to-contract calls (the top-level call is depth 1). */
    static final int MAX_CALL_DEPTH = 8;

    private ContractResult call(PublicAddress caller, PublicAddress contract, byte[] input,
                                long value, long gasLimit) {
        GasMeter meter = new GasMeter(gasLimit);
        // Intrinsic CALL cost, charged whatever the outcome: a call that fails before metering
        // anything (unknown contract, or a gasLimit too small to cover even the module-parse
        // charge) still costs the node real work — a fixed-stack execution thread, a code-store
        // lookup, the enclosing transaction's signature check and block space. Without this a
        // missing-contract call returned gasUsed=0 and paid NO fee at all (audit H2), letting a
        // miner fill blocks with zero-cost executions. DEPLOY already charges its base even on
        // failure; this is the symmetric CALL charge. Edge case: gasLimit=0 still pays nothing
        // (charge saturates at used=limit=0 — there is no budget to take a fee FROM), which is
        // harmless: no execution happens and the sender gains nothing. Changing that would
        // alter historical fees — a consensus change, not a fix.
        try {
            meter.charge(GasSchedule.CALL_BASE);
        } catch (OutOfGasException e) {
            return ContractResult.outOfGas(meter.used(), "out of gas for call");
        }
        // Native transfers a contract makes (transfer_value) accumulate here across the call tree;
        // a reverted frame truncates its own entries (see runCall), so only surviving transfers
        // remain. The executor applies them from the contracts' balances on success.
        List<ContractProcessor.NativeTransfer> transfers = new java.util.ArrayList<>();
        java.util.Map<PublicAddress, Long> reservedByContract = new java.util.HashMap<>();
        // The whole call tree runs on a fixed-stack thread so recursion depth is bounded by a
        // network constant, not the host JVM's -Xss (see WasmVm.onBoundedStack).
        CallOutcome outcome = WasmVm.onBoundedStack(() -> runCall(caller.toBytes(), contract, input,
            value, meter, session, new java.util.ArrayDeque<>(), transfers, reservedByContract));
        if (outcome.succeeded()) {
            return ContractResult.ok(meter.used(), outcome.output(), null, outcome.logs(), transfers);
        }
        // The frame's own status rides through: a call that exhausted the budget stays
        // OUT_OF_GAS, not a revert — the distinction the boundary type now carries.
        return new ContractResult(outcome.status(), meter.used(), new byte[0], null,
            outcome.error(), List.of(), List.of());
    }

    @Override
    public ContractResult dryRun(PublicAddress from, PublicAddress to, byte[] input,
                                 long value, long gasLimit) {
        // Run against a throwaway session over the committed base store. runCall flushes
        // its frame into this local session on success; we never flush the local session
        // to the base store, so nothing persists and the block session is untouched.
        GasMeter meter = new GasMeter(gasLimit);
        // Same intrinsic charge as call() so a dry-run's gasUsed reflects the real cost of the
        // execution it reports (and the /call_readonly gas budget accounts for it).
        try {
            meter.charge(GasSchedule.CALL_BASE);
        } catch (OutOfGasException e) {
            return ContractResult.outOfGas(meter.used(), "out of gas for call");
        }
        SessionContractStore scratch = new SessionContractStore(baseStore);
        // dryRun is read-only: transfers are collected for bounds-checking but never applied.
        List<ContractProcessor.NativeTransfer> transfers = new java.util.ArrayList<>();
        java.util.Map<PublicAddress, Long> reservedByContract = new java.util.HashMap<>();
        CallOutcome outcome = WasmVm.onBoundedStackDryRun(() -> runCall(from.toBytes(), to, input, value,
            meter, scratch, new java.util.ArrayDeque<>(), transfers, reservedByContract));
        if (outcome.succeeded()) {
            return ContractResult.ok(meter.used(), outcome.output(), null, outcome.logs());
        }
        return new ContractResult(outcome.status(), meter.used(), new byte[0], null,
            outcome.error(), List.of(), List.of());
    }

    /** Result of one call frame: the VM status, the callee output and the logs that survived
     *  (both empty on failure), and the error message (null on success). */
    private record CallOutcome(ContractResult.Status status, byte[] output,
                               List<ContractLog> logs, String error) {
        static CallOutcome ok(byte[] output, List<ContractLog> logs) {
            return new CallOutcome(ContractResult.Status.OK, output, logs, null);
        }

        static CallOutcome fail(ContractResult.Status status, String error) {
            return new CallOutcome(status, new byte[0], List.of(), error);
        }

        boolean succeeded() {
            return status == ContractResult.Status.OK;
        }
    }

    /**
     * Runs one call frame. Each frame executes against its own overlay
     * ({@link SessionContractStore}) over the parent's store, flushed into the parent
     * only on success — so a failed sub-call leaves no trace, and a caller that fails
     * after a successful sub-call discards the sub-call's writes with its own (the
     * savepoint semantics that make nested calls atomic with the top-level call).
     * The gas meter is shared across frames (forwarded gas). {@code stack} holds the
     * contracts currently executing: a callee already on it is reentrancy and is
     * refused, as is a chain deeper than {@link #MAX_CALL_DEPTH}.
     */
    private CallOutcome runCall(byte[] callerBytes, PublicAddress contract, byte[] input,
                                long value, GasMeter meter, ContractState parent,
                                java.util.Deque<PublicAddress> stack,
                                List<ContractProcessor.NativeTransfer> transfers,
                                java.util.Map<PublicAddress, Long> reservedByContract) {
        if (stack.size() >= MAX_CALL_DEPTH) {
            return CallOutcome.fail(ContractResult.Status.REVERTED, "call depth limit");
        }
        if (stack.contains(contract)) {
            return CallOutcome.fail(ContractResult.Status.REVERTED, "reentrant call");
        }
        byte[] code;
        try {
            code = parent.getCode(contract);
        } catch (Throwable t) {
            // Node-local store failure — a fatal HostFault, never a contract verdict: for a
            // nested call this throws inside the parent's vm.execute, whose catch-all would
            // otherwise normalize it to a deterministic-looking revert and fork consensus.
            throw HostFault.wrap("contract code read failed", t);
        }
        if (code == null) {
            return CallOutcome.fail(ContractResult.Status.REVERTED, "no contract at address");
        }

        SessionContractStore frame = new SessionContractStore(parent);
        // transfer_value handler: the contract may pay out native coin up to its committed balance
        // minus what earlier (still-live) transfers in this tree already reserved for it. The intent
        // is recorded here; the executor moves the value on success. Truncated below on a revert so
        // a failed frame's payouts vanish along with its writes (audit T4).
        NativeTransferHandler xfer = (toBytes, amount) -> {
            ContractProcessor.NativeBalance nb = nativeBalance;
            if (nb == null || amount <= 0 || toBytes.length != PublicAddress.SIZE) {
                return -1;
            }
            // Coin this contract already reserved for still-live transfers earlier in the tree. Kept as
            // a running per-contract total (mirrored against `transfers` — incremented on each add here,
            // decremented on a frame revert below) so it is O(1) rather than an O(n) rescan of the whole
            // shared list per call, which made n transfers cost O(n^2) work for O(n) gas (audit: transfer_value
            // reserved-scan). The value is identical to summing `transfers` where from == contract.
            long reserved = reservedByContract.getOrDefault(contract, 0L);
            long spendable;
            try {
                spendable = nb.balanceOf(contract) - reserved;
            } catch (Throwable t) {
                // Node-local ledger read failure — a fatal HostFault, never a contract verdict:
                // this handler runs inside vm.execute's catch-all (see getCode above).
                throw HostFault.wrap("native balance read failed", t);
            }
            if (amount > spendable) {
                return -1;
            }
            transfers.add(new ContractProcessor.NativeTransfer(contract, PublicAddress.of(toBytes), amount));
            reservedByContract.merge(contract, amount, Long::sum);
            return 0;
        };
        PersistentHostState host =
            new PersistentHostState(frame, contract, callerBytes, input, value, boxReader, xfer);
        List<ContractLog> collected = new java.util.ArrayList<>();
        // Logs are collected in EMISSION order: this frame's own logs flow through the live
        // sink as the contract emits them, and a nested call's logs are spliced in at the exact
        // point of the call below. Buffering this frame's logs and appending them after the
        // execution put every parent log behind every nested log regardless of the real
        // interleaving (audit: inter-contract log ordering).
        host.setLogSink((topic, data) -> collected.add(new ContractLog(contract, topic, data)));
        int transferMark = transfers.size();

        stack.push(contract);
        ExecResult result;
        try {
            // Nested calls: the running contract is the caller, its frame is the parent
            // store, value transfer is not forwarded (no ledger access from the VM).
            result = vm.execute(code, host, meter, (calleeAddr, calleeInput) -> {
                if (calleeAddr.length != PublicAddress.SIZE) {
                    return null;
                }
                CallOutcome sub = runCall(contract.toBytes(), PublicAddress.of(calleeAddr),
                    calleeInput, 0, meter, frame, stack, transfers, reservedByContract);
                if (!sub.succeeded()) {
                    return null;
                }
                collected.addAll(sub.logs());
                return sub.output();
            });
        } finally {
            stack.pop();
        }

        if (!result.succeeded()) {
            // Frame discarded: drop this frame's (and its subtree's) native-transfer intents too,
            // and unwind their contribution to the running reserved totals so the map stays a mirror
            // of the surviving `transfers` (the reserved sum is what the next transfer_value checks).
            List<ContractProcessor.NativeTransfer> discarded = transfers.subList(transferMark, transfers.size());
            for (ContractProcessor.NativeTransfer t : discarded) {
                // remaining is 0+ by construction (every addition was bounded by spendable) — the
                // <= clamp makes that invariant self-defending: a negative entry would INFLATE
                // spendable beyond the balance (audit: reserved-mirror guard).
                long remaining = reservedByContract.getOrDefault(t.from(), 0L) - t.amount();
                if (remaining <= 0) {
                    reservedByContract.remove(t.from());
                } else {
                    reservedByContract.put(t.from(), remaining);
                }
            }
            discarded.clear();
            // Frame discarded: no writes, no logs. The VM's own status rides through — a
            // sub-frame that exhausted the shared meter stays OUT_OF_GAS, not a revert.
            return CallOutcome.fail(result.status(), result.message());
        }
        host.commit();                 // this call's own writes into its frame...
        frame.flushWithJournal();      // ...and the frame (incl. sub-calls) into the parent
        // No end-of-call log splice: this frame's logs already reached `collected` via the live
        // sink, in emission order (audit: inter-contract log ordering).
        return CallOutcome.ok(result.output(), collected);
    }

    /** Deployed code at {@code contract} in the committed state, or {@code null}. */
    @Override
    public byte[] codeAt(PublicAddress contract) {
        return baseStore.getCode(contract);
    }

    @Override
    public void commit(long blockHeight) {
        // Persist the receipts too (durable stores only), alongside the journal: the executor's
        // rollback consumes them to reverse each contract tx's gas fee, value transfer and
        // transfer_value payouts, and RAM-only receipts made a reorg after a restart crash
        // mid-rollback and corrupt the ledger (audit F3). Empty blocks persist NOTHING: the
        // 4-byte encoding of an empty receipt list would otherwise cost every contract-free
        // block a receipts write in the synced batch (audit review; receipts() maps a missing
        // blob back to List.of()).
        byte[] encodedReceipts = currentReceipts.isEmpty() ? null : encodeReceipts(currentReceipts);
        if (session != null) {
            List<ContractChange> changes = session.forwardChanges();
            // A block that touched no contract state persists NO journal either: the 4-byte
            // empty journal row cost every contract-free block a synced write, and every revert
            // path already maps a MISSING journal to "nothing to undo at this height" (see
            // revertBlock; audit: empty-journal fsyncs). applyBlock itself is skipped when the
            // block carries neither mutations nor receipts — an empty synced batch is a wasted
            // fsync. A receipts-only block (e.g. a reverting CALL) still rides applyBlock, so
            // the receipts stay in the one atomic batch.
            if (!changes.isEmpty() || encodedReceipts != null) {
                // Commit the block's mutations AND its undo journal AND its receipts as ONE
                // atomic unit where the store supports it (RocksDB: a single synced WriteBatch),
                // so a crash mid-flush cannot leave storage half-applied with no journal to
                // rewind it (audit store F1) and the receipts cost no second fsync (audit perf).
                // The store GENERATES the journal itself (each key's prior, like the box and
                // token stores — audit: one undo protocol); it doubles as the durable reorg-undo
                // after a restart (M9). The byte-budgeted RAM copy below is a cache over it.
                baseStore.applyBlock(blockHeight, session.pendingChanges(), encodedReceipts);
                encodedReceipts = null;
                byte[] persisted = baseStore.getJournal(blockHeight);
                if (persisted != null) {
                    journals.retain(blockHeight, ContractJournalCodec.decode(persisted));
                }
            }
            if (!changes.isEmpty()) {
                retainChanges(blockHeight, changes);
            }
            session = null;
        }
        if (encodedReceipts != null) {
            // No contract writes this block, but receipts may exist (e.g. a reverting CALL with
            // no session): persist them standalone (the separate-put fallback of the receipts
            // overload).
            baseStore.putReceipts(blockHeight, encodedReceipts);
        }
        // Deliberately unconditional, unlike the box processor's isEmpty() guard: a receipt-free
        // block still retains (and prunes, and reverts) an empty entry, byte-accounted at zero —
        // preserving the exact call sequence the persistence tests pin.
        receiptsByHeight.retain(blockHeight, currentReceipts);
        if (!currentLogs.isEmpty()) {
            retainLogs(blockHeight, currentLogs);
        }
        currentReceipts = new java.util.ArrayList<>();
        currentLogs = new java.util.ArrayList<>();
        // Deliberately NO retention prune here: this commit may still be reverted within the
        // caller's critical section (the stampStateRoot dry run, an addBlock state-root
        // rejection), and pruning against an uncommitted height deletes the oldest in-window
        // journals/receipts a max-depth reorg still needs. The engine prunes post-append via
        // {@link #pruneToChainTip}.
    }

    @Override
    public void discard() {
        session = null;
        currentReceipts = new java.util.ArrayList<>();
        currentLogs = new java.util.ArrayList<>();
    }

    @Override
    public List<ContractReceipt> receipts(long blockHeight) {
        // has(), not a null test: the index's get() returns List.of() on a miss, so a null
        // check would treat an absent height as a cache hit and skip the durable fallback.
        if (receiptsByHeight.has(blockHeight)) {
            return receiptsByHeight.get(blockHeight);
        }
        // RAM miss (evicted past the byte budget, or this process restarted after the block
        // committed): fall back to the durable copy so a reorg can still reverse the block's
        // ledger effects exactly, instead of the executor crashing mid-rollback on an empty
        // list (audit F3). The re-retained copy is byte-accounted like any commit's.
        byte[] persisted = baseStore.getReceipts(blockHeight);
        if (persisted == null) {
            return List.of();
        }
        List<ContractReceipt> decoded = decodeReceipts(persisted);
        receiptsByHeight.retain(blockHeight, decoded);
        return decoded;
    }

    @Override
    public List<ContractLog> logs(long blockHeight) {
        return logsByHeight.get(blockHeight);
    }

    /**
     * Retains one height's logs under the {@link #MAX_LOGS_PER_HEIGHT} / {@link
     * #MAX_RETAINED_LOG_BYTES} budget, evicting the oldest heights (LRU by height) once the byte
     * budget is exceeded. The per-height entry cap lives here — the shared index has no
     * equivalent — and the index owns the byte budget and the eviction. Logs are a best-effort
     * service, not consensus, so truncation and eviction never affect validation.
     * Package-private so tests can drive the retention path without executing 64 MiB of real logs.
     */
    void retainLogs(long blockHeight, List<ContractLog> logs) {
        List<ContractLog> kept = logs.size() > MAX_LOGS_PER_HEIGHT
            ? List.copyOf(logs.subList(0, MAX_LOGS_PER_HEIGHT))
            : logs;
        logsByHeight.retain(blockHeight, kept);
    }

    /** Approximate retained size of one height's logs: address + topic + data per entry. */
    private static long logBytes(List<ContractLog> logs) {
        long bytes = 0;
        for (ContractLog log : logs) {
            bytes += PublicAddress.SIZE + log.topic().length + log.data().length;
        }
        return bytes;
    }

    // ---- byte-budgeted retention for journals / receipts / changes (audit: RAM retention) ----
    // The shared HeightRetainedIndex enforces a fixed byte cap per map, evicting the OLDEST
    // heights past it. Eviction is RAM-only — the durable copies (journal/receipt hooks) are
    // deleted solely by the height-scheduled prune, so every read path keeps its store-level
    // fallback. The index never evicts the height being retained: the state-root collection
    // reads it back immediately after commit.

    /**
     * Retains one height's forward changes under the {@link #MAX_RETAINED_CHANGE_BYTES} budget.
     * Package-private so tests can drive the retention path directly, like {@link #retainLogs}:
     * a block over the budget is unreachable through the VM (the block size limit and the gas
     * schedule keep one block's changes under it), but the index's eviction guard — the retained
     * height is never its own victim — is consensus-critical and must stay assertable.
     */
    void retainChanges(long blockHeight, List<ContractChange> changes) {
        changesByHeight.retain(blockHeight, changes);
    }

    /** Approximate retained size of one height's journal: tag + address + key + prior per entry. */
    private static long journalBytes(List<ContractUndo> journal) {
        long bytes = 0;
        for (ContractUndo u : journal) {
            bytes += 1 + PublicAddress.SIZE
                + (u.key() == null ? 0 : u.key().length)
                + (u.prior() == null ? 0 : u.prior().length);
        }
        return bytes;
    }

    /** Approximate retained size of one height's receipts: the fixed record plus its transfers. */
    private static long receiptBytes(List<ContractReceipt> receipts) {
        long bytes = 0;
        for (ContractReceipt r : receipts) {
            bytes += MIN_RECEIPT_RECORD_BYTES
                + (long) r.transfers().size() * MIN_TRANSFER_RECORD_BYTES;
        }
        return bytes;
    }

    /** Approximate retained size of one height's forward changes: tag + address + key + value. */
    private static long changeBytes(List<ContractChange> changes) {
        long bytes = 0;
        for (ContractChange c : changes) {
            bytes += 1 + PublicAddress.SIZE
                + (c.key() == null ? 0 : c.key().length)
                + (c.value() == null ? 0 : c.value().length);
        }
        return bytes;
    }

    @Override
    public List<ContractChange> changes(long blockHeight) {
        return changesByHeight.get(blockHeight);
    }

    @Override
    public void revertBlock(long blockHeight) {
        receiptsByHeight.forget(blockHeight);
        changesByHeight.forget(blockHeight);
        journals.forget(blockHeight);
        logsByHeight.forget(blockHeight);
        // The STORE decodes its own journal and drops it, the receipts AND the restores as one
        // atomic unit (audit: who decodes the journal). The processor's RAM copy above is a
        // byte-budgeted cache only — the durable store always has the journal when the cache
        // does (both are pruned by the same height schedule), so delegating the revert is safe
        // even after an eviction or a restart (audit M9).
        baseStore.revertBlock(blockHeight);
    }

    // ---- persistent journal codec (audit M9) ----
    // The journal's wire format lives in ContractJournalCodec: the processor ENCODES what the
    // sessions produce, and every store DECODES the same bytes on revert — one codec, one
    // layout, so a journal written by this processor is reversible by any store.

    /** Smallest possible serialized receipt: gasUsed(8) + success(1) + transferCount(4). */
    private static final int MIN_RECEIPT_RECORD_BYTES = Long.BYTES + 1 + Integer.BYTES;

    /** Smallest possible serialized transfer: from(25) + to(25) + amount(8). */
    private static final int MIN_TRANSFER_RECORD_BYTES =
        2 * rhizome.core.ledger.PublicAddress.SIZE + Long.BYTES;

    // ---- persistent receipt codec (audit F3) ----
    // Fixed, compact record per receipt, in the journal codec's style:
    // count(4) then per receipt: gasUsed(8) | success(1) | transferCount(4)
    //                          | per transfer: from(25) | to(25) | amount(8).
    // Transfers are included because the executor's rollback reverses transfer_value payouts
    // from them — dropping them would unrevert a contract's native payouts on a reorg.

    private static byte[] encodeReceipts(List<ContractReceipt> receipts) {
        int size = Integer.BYTES;
        for (ContractReceipt r : receipts) {
            size += Long.BYTES + 1 + Integer.BYTES
                + r.transfers().size() * (2 * rhizome.core.ledger.PublicAddress.SIZE + Long.BYTES);
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(size);
        b.putInt(receipts.size());
        for (ContractReceipt r : receipts) {
            b.putLong(r.gasUsed());
            b.put((byte) (r.success() ? 1 : 0));
            b.putInt(r.transfers().size());
            for (NativeTransfer t : r.transfers()) {
                b.put(t.from().toBytes());
                b.put(t.to().toBytes());
                b.putLong(t.amount());
            }
        }
        return b.array();
    }

    static List<ContractReceipt> decodeReceipts(byte[] bytes) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes);
        int count = b.getInt();
        // Same store-read bound as decodeJournal: a corrupt count must fail cleanly, never
        // pre-dimension a giant list (audit: unbounded decode allocations).
        if (count < 0 || count > b.remaining() / MIN_RECEIPT_RECORD_BYTES) {
            throw new IllegalArgumentException("corrupt receipts: count " + count
                + " exceeds buffer (" + b.remaining() + " bytes)");
        }
        List<ContractReceipt> receipts = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long gasUsed = b.getLong();
            boolean success = b.get() != 0;
            int transferCount = b.getInt();
            if (transferCount < 0 || transferCount > b.remaining() / MIN_TRANSFER_RECORD_BYTES) {
                throw new IllegalArgumentException("corrupt receipts: transfer count "
                    + transferCount + " exceeds buffer (" + b.remaining() + " bytes)");
            }
            List<NativeTransfer> transfers = new java.util.ArrayList<>(transferCount);
            for (int t = 0; t < transferCount; t++) {
                byte[] from = new byte[rhizome.core.ledger.PublicAddress.SIZE];
                b.get(from);
                byte[] to = new byte[rhizome.core.ledger.PublicAddress.SIZE];
                b.get(to);
                long amount = b.getLong();
                transfers.add(new NativeTransfer(
                    rhizome.core.ledger.PublicAddress.of(from),
                    rhizome.core.ledger.PublicAddress.of(to), amount));
            }
            receipts.add(new ContractReceipt(gasUsed, success, transfers));
        }
        return receipts;
    }

    /**
     * Drops journals buried deeper than the retention depth (unreachable by any reorg). Keeps
     * EXACTLY {@code retainDepth} heights — {@code (chainTip - retainDepth, chainTip]}
     * — so {@code retainDepth} remains the single source of truth for "how many blocks can be
     * reverted"; the previous strict-less-than comparison retained one extra height (audit F10).
     * The safe direction is keeping more, and this never drops below it: retainDepth must be at
     * least the chain's max reorg depth (see the constructor), and exactly retainDepth are kept.
     */
    @Override
    public void pruneToChainTip(long chainTip) {
        // headMap(cutoff, true) instead of a full-map removeIf: the index's key order makes the
        // expired prefix the head, so the prune visits only the heights it drops.
        journals.pruneThrough(chainTip, baseStore::deleteJournal); // drop the durable copy too
        receiptsByHeight.pruneThrough(chainTip, baseStore::deleteReceipts); // receipts prune on the journal schedule (F3)
        changesByHeight.pruneThrough(chainTip, null);
        logsByHeight.pruneThrough(chainTip, null);
        // Interval prune of the durable rows, AMORTIZED via PruneCadence: the per-height deletes
        // above only reach heights still present in the RAM maps, which are EMPTY after a restart
        // — without an interval prune, every journal/receipt written before it would stay on
        // disk forever.
        if (durablePrune.due(chainTip - retainDepth)) {
            baseStore.pruneThrough(chainTip - retainDepth);
        }
    }
}

