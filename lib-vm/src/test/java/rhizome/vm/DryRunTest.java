package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ContractProcessor.ContractResult;
import rhizome.core.blockchain.Contracts;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

/**
 * Read-only dry-run: {@link WasmContractProcessor#dryRun} runs a CALL against committed
 * state and discards every write, so it can be called repeatedly to query state without
 * ever changing it — the mechanism behind {@code POST /call_readonly}.
 */
class DryRunTest {

    private static final byte[] COUNTER = load("/counter.wasm");
    private static final long GAS = 10_000_000L;

    private static byte[] load(String r) {
        try (var in = DryRunTest.class.getResourceAsStream(r)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (v >>> (8 * i));
        }
        return b;
    }

    @Test
    void dryRunQueriesWithoutPersisting() {
        InMemoryContractStore contracts = new InMemoryContractStore();
        WasmContractProcessor proc = new WasmContractProcessor(new WasmVm(), contracts);
        PublicAddress deployer = PublicAddress.random();
        PublicAddress caller = PublicAddress.random();

        proc.begin();
        proc.run(deployer, TransactionKind.DEPLOY, PublicAddress.empty(), COUNTER, 0, GAS, 0);
        proc.commit(1);
        PublicAddress contract = Contracts.deriveAddress(deployer, 0);
        assertNotNull(contracts.getCode(contract));

        // Two dry runs against the fresh counter: each returns 1 and persists nothing.
        ContractResult r1 = proc.dryRun(caller, contract, new byte[0], 0, GAS);
        assertTrue(r1.success());
        assertArrayEquals(le64(1), r1.output());
        assertNull(contracts.getStorage(contract, new byte[] {0}), "dry run must not write");
        ContractResult r2 = proc.dryRun(caller, contract, new byte[0], 0, GAS);
        assertArrayEquals(le64(1), r2.output(), "second dry run also sees the unchanged base state");

        // A real call persists the increment...
        proc.begin();
        proc.run(caller, TransactionKind.CALL, contract, new byte[0], 0, GAS, 1);
        proc.commit(2);
        assertArrayEquals(le64(1), contracts.getStorage(contract, new byte[] {0}));

        // ...and a subsequent dry run reads it (2) but still writes nothing.
        ContractResult r3 = proc.dryRun(caller, contract, new byte[0], 0, GAS);
        assertArrayEquals(le64(2), r3.output());
        assertArrayEquals(le64(1), contracts.getStorage(contract, new byte[] {0}), "dry run still must not write");
    }

    @Test
    void dryRunOnUnknownContractReverts() {
        InMemoryContractStore contracts = new InMemoryContractStore();
        WasmContractProcessor proc = new WasmContractProcessor(new WasmVm(), contracts);
        ContractResult r = proc.dryRun(PublicAddress.random(), PublicAddress.random(), new byte[0], 0, GAS);
        assertTrue(!r.success());
    }
}
