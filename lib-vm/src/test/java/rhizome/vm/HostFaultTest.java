package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.Contracts;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

/**
 * Node-local infrastructure failures underneath a call must be fatal ({@link HostFault}),
 * never a contract verdict (audit: a store fault converted to a deterministic-looking revert
 * makes {@code gasUsed} and the state root depend on the node's transient faults — a silent
 * fork; a crash is preferable to a fork).
 */
class HostFaultTest {

    private static final byte[] COUNTER = load("/counter.wasm");
    private static final long GAS = 10_000_000L;

    private static byte[] load(String r) {
        try (var in = HostFaultTest.class.getResourceAsStream(r)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Delegates to an in-memory store, but fails storage reads with a simulated disk fault. */
    private static final class FailingStorageStore implements ContractStore {
        private final InMemoryContractStore delegate = new InMemoryContractStore();

        @Override public byte[] getCode(PublicAddress contract) {
            return delegate.getCode(contract);
        }

        @Override public void putCode(PublicAddress contract, byte[] code) {
            delegate.putCode(contract, code);
        }

        @Override public void deleteCode(PublicAddress contract) {
            delegate.deleteCode(contract);
        }

        @Override public byte[] getStorage(PublicAddress contract, byte[] key) {
            throw new IllegalStateException("simulated disk failure");
        }

        @Override public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
            delegate.putStorage(contract, key, value);
        }

        @Override public void deleteStorage(PublicAddress contract, byte[] key) {
            delegate.deleteStorage(contract, key);
        }
    }

    /** Delegates to an in-memory store, but fails code reads with a simulated disk fault. */
    private static final class FailingCodeStore implements ContractStore {
        private final InMemoryContractStore delegate = new InMemoryContractStore();

        @Override public byte[] getCode(PublicAddress contract) {
            throw new IllegalStateException("simulated disk failure");
        }

        @Override public void putCode(PublicAddress contract, byte[] code) {
            delegate.putCode(contract, code);
        }

        @Override public void deleteCode(PublicAddress contract) {
            delegate.deleteCode(contract);
        }

        @Override public byte[] getStorage(PublicAddress contract, byte[] key) {
            return delegate.getStorage(contract, key);
        }

        @Override public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
            delegate.putStorage(contract, key, value);
        }

        @Override public void deleteStorage(PublicAddress contract, byte[] key) {
            delegate.deleteStorage(contract, key);
        }
    }

    private static PublicAddress deployCounter(WasmContractProcessor proc, byte[] code, long gas) {
        PublicAddress deployer = PublicAddress.random();
        proc.begin();
        proc.run(deployer, TransactionKind.DEPLOY, PublicAddress.empty(), code, 0, gas, 0);
        proc.commit(1);
        return Contracts.deriveAddress(deployer, 0);
    }

    @Test
    void storageFailureDuringCallIsFatalNeverARevert() {
        // Deploy against a healthy store, then fail storage reads underneath the CALL: the
        // counter's storage_read host call must surface a HostFault out of run(), not a
        // deterministic-looking ContractResult.reverted that healthy nodes wouldn't produce.
        FailingStorageStore store = new FailingStorageStore();
        WasmContractProcessor proc = new WasmContractProcessor(new WasmVm(), store.delegate);
        PublicAddress contract = deployCounter(proc, COUNTER, GAS);

        WasmContractProcessor failingProc = new WasmContractProcessor(new WasmVm(), store);
        failingProc.begin();
        HostFault fault = assertThrows(HostFault.class, () ->
            failingProc.run(PublicAddress.random(), TransactionKind.CALL, contract,
                new byte[0], 0, GAS, 1));
        assertTrue(String.valueOf(fault.getMessage()).contains("storage"), fault.getMessage());
    }

    @Test
    void codeReadFailureDuringCallIsFatalNeverARevert() {
        FailingCodeStore store = new FailingCodeStore();
        WasmContractProcessor proc = new WasmContractProcessor(new WasmVm(), store.delegate);
        PublicAddress contract = deployCounter(proc, COUNTER, GAS);

        WasmContractProcessor failingProc = new WasmContractProcessor(new WasmVm(), store);
        failingProc.begin();
        HostFault fault = assertThrows(HostFault.class, () ->
            failingProc.run(PublicAddress.random(), TransactionKind.CALL, contract,
                new byte[0], 0, GAS, 1));
        assertTrue(String.valueOf(fault.getMessage()).contains("code"), fault.getMessage());
    }

    @Test
    void hostFaultSurvivesTheInterpreterAndWorkerThread() {
        // The fault crosses the Chicory interpreter (which may wrap host-function throwables)
        // and the bounded-stack worker: the cause chain must stay discoverable, and the type
        // escaping run() must be the HostFault Error itself — Errors are never a verdict.
        FailingStorageStore store = new FailingStorageStore();
        WasmContractProcessor proc = new WasmContractProcessor(new WasmVm(), store.delegate);
        PublicAddress contract = deployCounter(proc, COUNTER, GAS);

        WasmContractProcessor failingProc = new WasmContractProcessor(new WasmVm(), store);
        failingProc.begin();
        Throwable t = assertThrows(Throwable.class, () ->
            failingProc.run(PublicAddress.random(), TransactionKind.CALL, contract,
                new byte[0], 0, GAS, 1));
        assertTrue(t instanceof HostFault, "expected HostFault, got " + t.getClass());
        assertTrue(HostFault.of(t) != null);
    }
}
