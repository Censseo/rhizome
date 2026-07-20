package rhizome.vm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import rhizome.core.blockchain.ContractProcessor;
import rhizome.core.blockchain.ContractProcessor.ContractLog;
import rhizome.core.blockchain.Contracts;
import rhizome.core.ledger.PublicAddress;
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
    private SessionContractStore session;
    private List<ContractReceipt> currentReceipts = new java.util.ArrayList<>();
    private List<ContractLog> currentLogs = new java.util.ArrayList<>();

    /** Undo journals of recently committed blocks, keyed by height, for reorg reversal. */
    private final Map<Long, List<ContractUndo>> journals = new ConcurrentHashMap<>();
    private final Map<Long, List<ContractReceipt>> receiptsByHeight = new ConcurrentHashMap<>();
    private final Map<Long, List<ContractLog>> logsByHeight = new ConcurrentHashMap<>();
    private long lastCommittedHeight = -1;

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
    }

    /**
     * Wires the box reader so contracts can {@code box_read} data boxes (Ergo-style
     * data inputs). Set once at node assembly, after the box processor exists.
     */
    public void setBoxReader(BoxReader boxReader) {
        this.boxReader = boxReader;
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
        };
        currentReceipts.add(new ContractReceipt(result.gasUsed(), result.success()));
        currentLogs.addAll(result.logs());
        return result;
    }

    private ContractResult deploy(PublicAddress deployer, byte[] code, long nonce, long gasLimit) {
        PublicAddress address = Contracts.deriveAddress(deployer, nonce);
        long gasUsed = GasSchedule.DEPLOY_BASE + (long) code.length * GasSchedule.DEPLOY_PER_CODE_BYTE;
        if (gasUsed > gasLimit) {
            return ContractResult.reverted(gasLimit, "out of gas for deploy");
        }
        session.putCode(address, code);
        return ContractResult.ok(gasUsed, new byte[0], address);
    }

    /** Maximum nesting of contract-to-contract calls (the top-level call is depth 1). */
    static final int MAX_CALL_DEPTH = 8;

    private ContractResult call(PublicAddress caller, PublicAddress contract, byte[] input,
                                long value, long gasLimit) {
        GasMeter meter = new GasMeter(gasLimit);
        CallOutcome outcome = runCall(caller.toBytes(), contract, input, value, meter,
            session, new java.util.ArrayDeque<>());
        if (outcome.success()) {
            return ContractResult.ok(meter.used(), outcome.output(), null, outcome.logs());
        }
        return ContractResult.reverted(meter.used(), outcome.error());
    }

    /** Result of one call frame: callee output and the logs that survived (both empty on failure). */
    private record CallOutcome(boolean success, byte[] output, List<ContractLog> logs, String error) {
        static CallOutcome fail(String error) {
            return new CallOutcome(false, new byte[0], List.of(), error);
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
                                long value, GasMeter meter, ContractStore parent,
                                java.util.Deque<PublicAddress> stack) {
        if (stack.size() >= MAX_CALL_DEPTH) {
            return CallOutcome.fail("call depth limit");
        }
        if (stack.contains(contract)) {
            return CallOutcome.fail("reentrant call");
        }
        byte[] code = parent.getCode(contract);
        if (code == null) {
            return CallOutcome.fail("no contract at address");
        }

        SessionContractStore frame = new SessionContractStore(parent);
        PersistentHostState host =
            new PersistentHostState(frame, contract, callerBytes, input, value, boxReader);
        List<ContractLog> collected = new java.util.ArrayList<>();

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
                    calleeInput, 0, meter, frame, stack);
                if (!sub.success()) {
                    return null;
                }
                collected.addAll(sub.logs());
                return sub.output();
            });
        } finally {
            stack.pop();
        }

        if (!result.succeeded()) {
            return CallOutcome.fail(result.message()); // frame discarded: no writes, no logs
        }
        host.commit();                 // this call's own writes into its frame...
        frame.flushWithJournal();      // ...and the frame (incl. sub-calls) into the parent
        for (LogEntry log : result.logs()) {
            collected.add(new ContractLog(contract, log.topic(), log.data()));
        }
        return new CallOutcome(true, result.output(), collected, null);
    }

    @Override
    public void commit(long blockHeight) {
        if (session != null) {
            journals.put(blockHeight, session.flushWithJournal());
            session = null;
        }
        receiptsByHeight.put(blockHeight, currentReceipts);
        if (!currentLogs.isEmpty()) {
            logsByHeight.put(blockHeight, currentLogs);
        }
        currentReceipts = new java.util.ArrayList<>();
        currentLogs = new java.util.ArrayList<>();
        lastCommittedHeight = Math.max(lastCommittedHeight, blockHeight);
        pruneOldJournals();
    }

    @Override
    public void discard() {
        session = null;
        currentReceipts = new java.util.ArrayList<>();
        currentLogs = new java.util.ArrayList<>();
    }

    @Override
    public List<ContractReceipt> receipts(long blockHeight) {
        return receiptsByHeight.getOrDefault(blockHeight, List.of());
    }

    @Override
    public List<ContractLog> logs(long blockHeight) {
        return logsByHeight.getOrDefault(blockHeight, List.of());
    }

    @Override
    public void revertBlock(long blockHeight) {
        receiptsByHeight.remove(blockHeight);
        logsByHeight.remove(blockHeight);
        List<ContractUndo> journal = journals.remove(blockHeight);
        if (journal == null) {
            return; // nothing was committed for this height (e.g. a transfer-only block)
        }
        // Apply in reverse so repeated writes to the same key restore the earliest prior.
        for (int i = journal.size() - 1; i >= 0; i--) {
            ContractUndo u = journal.get(i);
            if (u.isCode()) {
                if (u.prior() == null) {
                    baseStore.deleteCode(u.contract());
                } else {
                    baseStore.putCode(u.contract(), u.prior());
                }
            } else if (u.prior() == null) {
                baseStore.deleteStorage(u.contract(), u.key());
            } else {
                baseStore.putStorage(u.contract(), u.key(), u.prior());
            }
        }
    }

    /** Drops journals buried deeper than the retention depth (unreachable by any reorg). */
    private void pruneOldJournals() {
        long cutoff = lastCommittedHeight - retainDepth;
        if (cutoff > 0) {
            journals.keySet().removeIf(h -> h < cutoff);
            receiptsByHeight.keySet().removeIf(h -> h < cutoff);
            logsByHeight.keySet().removeIf(h -> h < cutoff);
        }
    }
}

