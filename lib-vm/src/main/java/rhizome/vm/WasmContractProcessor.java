package rhizome.vm;

import rhizome.core.blockchain.ContractProcessor;
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
    private SessionContractStore session;

    public WasmContractProcessor(WasmVm vm, ContractStore baseStore) {
        this.vm = vm;
        this.baseStore = baseStore;
    }

    @Override
    public void begin() {
        session = new SessionContractStore(baseStore);
    }

    @Override
    public ContractResult run(PublicAddress from, TransactionKind kind, PublicAddress to,
                              byte[] data, long value, long gasLimit, long nonce) {
        if (session == null) {
            begin();
        }
        return switch (kind) {
            case DEPLOY -> deploy(from, data, nonce, gasLimit);
            case CALL -> call(from, to, data, value, gasLimit);
            case TRANSFER -> ContractResult.reverted(0, "not a contract transaction");
        };
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
    public void commit() {
        if (session != null) {
            session.flush();
            session = null;
        }
    }

    @Override
    public void discard() {
        session = null;
    }
}
