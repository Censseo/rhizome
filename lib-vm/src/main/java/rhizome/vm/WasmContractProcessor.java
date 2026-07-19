package rhizome.vm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import rhizome.core.blockchain.ContractProcessor;
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
    private SessionContractStore session;
    private List<ContractReceipt> currentReceipts = new java.util.ArrayList<>();

    /** Undo journals of recently committed blocks, keyed by height, for reorg reversal. */
    private final Map<Long, List<ContractUndo>> journals = new ConcurrentHashMap<>();
    private final Map<Long, List<ContractReceipt>> receiptsByHeight = new ConcurrentHashMap<>();
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

    @Override
    public void begin() {
        session = new SessionContractStore(baseStore);
        currentReceipts = new java.util.ArrayList<>();
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
            case TRANSFER -> ContractResult.reverted(0, "not a contract transaction");
        };
        currentReceipts.add(new ContractReceipt(result.gasUsed(), result.success()));
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

    private ContractResult call(PublicAddress caller, PublicAddress contract, byte[] input,
                                long value, long gasLimit) {
        byte[] code = session.getCode(contract);
        if (code == null) {
            return ContractResult.reverted(0, "no contract at address");
        }
        PersistentHostState host = new PersistentHostState(session, contract, caller.toBytes(), input, value);
        ExecResult result = vm.execute(code, host, new GasMeter(gasLimit));
        if (result.succeeded()) {
            host.commit(); // flush this call's writes into the block session
            return ContractResult.ok(result.gasUsed(), result.output(), null);
        }
        return ContractResult.reverted(result.gasUsed(), result.message());
    }

    @Override
    public void commit(long blockHeight) {
        if (session != null) {
            journals.put(blockHeight, session.flushWithJournal());
            session = null;
        }
        receiptsByHeight.put(blockHeight, currentReceipts);
        currentReceipts = new java.util.ArrayList<>();
        lastCommittedHeight = Math.max(lastCommittedHeight, blockHeight);
        pruneOldJournals();
    }

    @Override
    public void discard() {
        session = null;
        currentReceipts = new java.util.ArrayList<>();
    }

    @Override
    public List<ContractReceipt> receipts(long blockHeight) {
        return receiptsByHeight.getOrDefault(blockHeight, List.of());
    }

    @Override
    public void revertBlock(long blockHeight) {
        receiptsByHeight.remove(blockHeight);
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
        }
    }
}

