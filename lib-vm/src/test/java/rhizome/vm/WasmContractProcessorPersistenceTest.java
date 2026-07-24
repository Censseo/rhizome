package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ContractProcessor;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

/**
 * Tests for the block-commit hardening in {@link WasmContractProcessor}:
 *
 * <ul>
 *   <li><b>Atomic commit</b> — a block's mutations and undo journal must ride the store's
 *       {@code applyBlock}/{@code revertBlock} API, not per-op writes (audit store F1).</li>
 *   <li><b>F3</b> — contract receipts persist alongside the journal and are reloaded on a RAM
 *       miss, so a reorg after a restart can reverse the block's ledger effects instead of the
 *       executor crashing mid-rollback on an empty receipt list.</li>
 *   <li><b>F6</b> — deploying over an existing (derived-address) contract is rejected.</li>
 *   <li><b>F10</b> — journal/receipt pruning keeps EXACTLY {@code retainDepth} heights.</li>
 * </ul>
 *
 * The store stub plays both roles: an in-memory contract store plus the durable journal/receipt
 * columns a RocksDB store would provide, with call counters to pin which API the processor uses.
 */
class WasmContractProcessorPersistenceTest {

    private static final byte[] COUNTER = load("/counter.wasm");
    private static final long GAS_LIMIT = 10_000_000;

    private static byte[] load(String r) {
        try (var in = WasmContractProcessorPersistenceTest.class.getResourceAsStream(r)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** In-memory contract state plus durable journal/receipt columns and API call tracking. */
    private static final class DurableTestStore implements ContractStore {
        private final InMemoryContractStore delegate = new InMemoryContractStore();
        final Map<Long, byte[]> journals = new HashMap<>();
        final Map<Long, byte[]> receipts = new HashMap<>();
        int applyBlockCalls;
        int revertBlockCalls;
        int directWrites; // per-op mutations that bypassed the atomic block API

        @Override public byte[] getCode(PublicAddress contract) {
            return delegate.getCode(contract);
        }

        @Override public void putCode(PublicAddress contract, byte[] code) {
            directWrites++;
            delegate.putCode(contract, code);
        }

        @Override public void deleteCode(PublicAddress contract) {
            directWrites++;
            delegate.deleteCode(contract);
        }

        @Override public byte[] getStorage(PublicAddress contract, byte[] key) {
            return delegate.getStorage(contract, key);
        }

        @Override public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
            directWrites++;
            delegate.putStorage(contract, key, value);
        }

        @Override public void deleteStorage(PublicAddress contract, byte[] key) {
            directWrites++;
            delegate.deleteStorage(contract, key);
        }

        @Override public void putJournal(long height, byte[] serialized) {
            journals.put(height, serialized);
        }

        @Override public byte[] getJournal(long height) {
            return journals.get(height);
        }

        @Override public void deleteJournal(long height) {
            journals.remove(height);
        }

        @Override public void putReceipts(long height, byte[] encoded) {
            receipts.put(height, encoded);
        }

        @Override public byte[] getReceipts(long height) {
            return receipts.get(height);
        }

        @Override public void deleteReceipts(long height) {
            receipts.remove(height);
        }

        @Override public void applyBlock(long height, List<StorageChange> changes, byte[] journal) {
            applyBlockCalls++;
            // Apply to the delegate directly so the directWrites counter sees only REAL bypasses.
            for (StorageChange change : changes) {
                change.applyTo(delegate);
            }
            if (journal != null) {
                putJournal(height, journal);
            }
        }

        @Override public void revertBlock(long height, List<StorageChange> restores) {
            revertBlockCalls++;
            for (StorageChange restore : restores) {
                restore.applyTo(delegate);
            }
            deleteJournal(height);
        }
    }

    @Test
    void commitRidesTheAtomicApplyBlockNotPerOpWrites() {
        DurableTestStore store = new DurableTestStore();
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), store);
        processor.begin();
        var result = processor.run(PublicAddress.random(), TransactionKind.DEPLOY,
            PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
        assertTrue(result.success());
        processor.commit(1);

        assertEquals(1, store.applyBlockCalls, "the block commit must use applyBlock");
        assertEquals(0, store.directWrites, "no per-op writes may bypass the atomic commit");
        assertNotNull(store.getJournal(1), "the undo journal is persisted with the block");
        assertNotNull(store.getReceipts(1), "the receipts are persisted with the block (F3)");
        assertNotNull(store.getCode(result.contractAddress()), "the deploy is committed");
    }

    @Test
    void revertAfterARestartRestoresStateFromTheDurableJournal() {
        DurableTestStore store = new DurableTestStore();
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), store);
        processor.begin();
        var result = processor.run(PublicAddress.random(), TransactionKind.DEPLOY,
            PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
        processor.commit(1);
        PublicAddress address = result.contractAddress();
        assertNotNull(store.getCode(address));

        // A fresh processor over the same store = a restarted node: its RAM journals/receipts are
        // empty, so the revert must come from the durable copies — atomically (audit F3 + M9).
        WasmContractProcessor restarted = new WasmContractProcessor(new WasmVm(), store);
        restarted.revertBlock(1);

        assertEquals(1, store.revertBlockCalls, "the revert must use revertBlock");
        assertEquals(0, store.directWrites, "no per-op writes may bypass the atomic revert");
        assertNull(store.getCode(address), "the deploy is undone from the durable journal");
        assertNull(store.getJournal(1), "the journal is dropped with the revert");
        assertNull(store.getReceipts(1), "the receipts are dropped with the revert (F3)");
    }

    @Test
    void receiptsSurviveARestart() {
        // The executor's rollbackBlock indexes receipts(height) mid-reorg; RAM-only receipts made
        // that an IndexOutOfBounds after a restart, corrupting the ledger mid-rollback (audit F3).
        // A fresh processor must serve the same receipts from the durable store.
        DurableTestStore store = new DurableTestStore();
        PublicAddress deployer = PublicAddress.random();
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), store);
        processor.begin();
        var result = processor.run(deployer, TransactionKind.DEPLOY,
            PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
        processor.commit(7);

        WasmContractProcessor restarted = new WasmContractProcessor(new WasmVm(), store);
        List<ContractProcessor.ContractReceipt> receipts = restarted.receipts(7);
        assertEquals(1, receipts.size());
        assertEquals(result.gasUsed(), receipts.get(0).gasUsed());
        assertEquals(result.success(), receipts.get(0).success());
        assertEquals(result.transfers(), receipts.get(0).transfers(),
            "transfer_value payouts must round-trip so rollback can reverse them");
    }

    @Test
    void pruningKeepsExactlyRetainDepthHeights() {
        // retainDepth 3, commits at heights 1..6: exactly the last 3 heights' journals and
        // receipts survive (the old strict-less-than cutoff retained one extra — audit F10).
        DurableTestStore store = new DurableTestStore();
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), store, 3);
        for (long h = 1; h <= 6; h++) {
            processor.begin();
            processor.commit(h);
        }
        assertEquals(Set.of(4L, 5L, 6L), store.journals.keySet(),
            "exactly retainDepth journals are kept");
        assertEquals(Set.of(4L, 5L, 6L), store.receipts.keySet(),
            "receipts prune on the journal schedule (F3)");
        assertEquals(List.of(), processor.receipts(3), "a pruned height serves no receipts");
    }

    @Test
    void deployOverAnExistingContractIsRejected() {
        // Same deployer + nonce derives the same address: the second deploy must revert instead
        // of silently overwriting live code (audit F6).
        DurableTestStore store = new DurableTestStore();
        PublicAddress deployer = PublicAddress.random();
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), store);
        processor.begin();
        var first = processor.run(deployer, TransactionKind.DEPLOY,
            PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
        assertTrue(first.success());
        var second = processor.run(deployer, TransactionKind.DEPLOY,
            PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
        assertFalse(second.success());
        assertTrue(second.error().contains("collision"), second.error());
    }
}
