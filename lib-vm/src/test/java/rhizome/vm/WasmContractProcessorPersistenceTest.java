package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        @Override public void applyBlock(long height, List<StorageChange> changes) {
            applyBlockCalls++;
            // The store generates the journal itself (each prior, in change order — audit: one
            // undo protocol). Apply to the delegate directly so the directWrites counter sees
            // only REAL bypasses.
            List<ContractUndo> journal = new java.util.ArrayList<>(changes.size());
            for (StorageChange change : changes) {
                byte[] prior = change.isCode()
                    ? delegate.getCode(change.contract())
                    : delegate.getStorage(change.contract(), change.key());
                journal.add(new ContractUndo(change.isCode(), change.contract(),
                    change.isCode() ? null : change.key(), prior));
                change.applyTo(delegate);
            }
            if (!journal.isEmpty()) {
                putJournal(height, ContractJournalCodec.encode(journal));
            }
        }

        @Override public void revertBlock(long height) {
            // The store decodes its own journal — the revert no longer takes caller-supplied
            // restores (audit: who decodes the journal). Nothing committed at the height is a
            // strict no-op, like the durable stores (boot recovery replays empty reverts).
            byte[] journal = journals.get(height);
            if (journal == null && !receipts.containsKey(height)) {
                return;
            }
            revertBlockCalls++;
            if (journal != null) {
                for (StorageChange restore : ContractJournalCodec.restores(journal)) {
                    restore.applyTo(delegate);
                }
            }
            deleteJournal(height);
            // The store-level contract: receipts ride the SAME atomic unit as the restore and
            // the journal drop (audit: revert-path tear) — the processor no longer deletes them
            // separately.
            deleteReceipts(height);
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
        // Each height carries a deploy so it actually HAS receipts to prune.
        DurableTestStore store = new DurableTestStore();
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), store, 3);
        for (long h = 1; h <= 6; h++) {
            processor.begin();
            processor.run(PublicAddress.random(), TransactionKind.DEPLOY,
                PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
            processor.commit(h);
            processor.pruneToChainTip(h); // the engine prunes post-append, not at commit
        }
        assertEquals(Set.of(4L, 5L, 6L), store.journals.keySet(),
            "exactly retainDepth journals are kept");
        assertEquals(Set.of(4L, 5L, 6L), store.receipts.keySet(),
            "receipts prune on the journal schedule (F3)");
        assertEquals(List.of(), processor.receipts(3), "a pruned height serves no receipts");
    }

    @Test
    void aRevertedCommitPrunesNothing() {
        // The stampStateRoot dry run commits a candidate at tip+1 and reverts it within the same
        // engine critical section, so a prune keyed on commit heights runs one ahead of the chain
        // and deletes the oldest in-window height's journal AND durable receipts — the max-depth
        // reorg that then needs them dies on rollbackBlock's receipt guard, and a restart cannot
        // heal it (the durable copy went with the RAM one). Retention must track appended tips.
        DurableTestStore store = new DurableTestStore();
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), store, 3);
        for (long h = 1; h <= 6; h++) {
            processor.begin();
            processor.run(PublicAddress.random(), TransactionKind.DEPLOY,
                PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
            processor.commit(h);
            processor.pruneToChainTip(h);
        }
        assertEquals(Set.of(4L, 5L, 6L), store.receipts.keySet());

        // The dry run at 7: commit (the rollback walk and the state-root collection read the
        // staged state), then revert. No pruneToChainTip runs — the candidate never appended.
        processor.begin();
        processor.run(PublicAddress.random(), TransactionKind.DEPLOY,
            PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
        processor.commit(7);
        processor.revertBlock(7);

        assertEquals(Set.of(4L, 5L, 6L), store.journals.keySet(),
            "a reverted phantom commit must not prune the reorg window");
        assertEquals(Set.of(4L, 5L, 6L), store.receipts.keySet(),
            "a reverted phantom commit must not prune the window's durable receipts");
        assertEquals(1, processor.receipts(4).size(), "the oldest in-window receipts still serve");
        assertNull(store.getJournal(7), "the phantom height leaves no journal");
        assertNull(store.getReceipts(7), "the phantom height leaves no receipts");

        // Once a block at 7 really appends, the window slides forward exactly one height.
        processor.begin();
        processor.run(PublicAddress.random(), TransactionKind.DEPLOY,
            PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
        processor.commit(7);
        processor.pruneToChainTip(7);
        assertEquals(Set.of(5L, 6L, 7L), store.receipts.keySet());
    }

    @Test
    void aReceiptFreeBlockPersistsNoReceiptBlob() {
        // Empty blocks skip the receipts write entirely: a 4-byte empty blob per block would
        // otherwise ride every synced batch for nothing (audit review). A RAM-miss read of such
        // a height still serves an empty list.
        DurableTestStore store = new DurableTestStore();
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), store);
        processor.begin();
        processor.commit(1);
        assertNull(store.getReceipts(1), "no blob is written for a receipt-free block");

        WasmContractProcessor restarted = new WasmContractProcessor(new WasmVm(), store);
        assertEquals(List.of(), restarted.receipts(1), "a missing blob reads back as no receipts");
    }

    @Test
    void revertDropsReceiptsWhenNoJournalExistsForTheHeight() {
        // Receipts WITHOUT a journal — the shape a receipts-only block commits (a reverting CALL
        // that touched no storage), or a tear that lost the journal but kept the receipts.
        // revertBlock must still route through the store's atomic revert unit to drop them:
        // returning early stranded the receipts, and the executor's rollback guard then aborted
        // every later reorg attempt over this height (audit 17th pass: this branch of
        // WasmContractProcessor.revertBlock was exercised by nothing).
        DurableTestStore store = new DurableTestStore();
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), store);
        processor.begin();
        var result = processor.run(PublicAddress.random(), TransactionKind.DEPLOY,
            PublicAddress.empty(), COUNTER, 0, GAS_LIMIT, 0);
        assertTrue(result.success());
        processor.commit(1);
        assertNotNull(store.getReceipts(1));
        assertNotNull(store.getJournal(1));

        // Remove only the journal, then revert through a FRESH processor (a restarted node: no
        // RAM maps) so the durable columns decide the path.
        store.deleteJournal(1);
        WasmContractProcessor restarted = new WasmContractProcessor(new WasmVm(), store);
        restarted.revertBlock(1);

        assertEquals(1, store.revertBlockCalls,
            "the receipts drop rides the store's atomic revert unit, not a separate delete");
        assertNull(store.getReceipts(1), "receipts without a journal are still dropped");
        assertNotNull(store.getCode(result.contractAddress()),
            "with no journal there is nothing to restore — state is left as committed");

        // Idempotent: a second revert over the same height (boot recovery replaying the sweep)
        // is a no-op, not an error.
        restarted.revertBlock(1);
        assertEquals(1, store.revertBlockCalls, "nothing left at the height: no second revert unit");
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

    // ---- bounded event-log retention (audit: RAM retention of logs) ----

    @Test
    void logsPerHeightAreCapped() {
        // A log-spamming block must not retain unbounded entries: logs are a best-effort query
        // service, not consensus, so the excess is truncated to MAX_LOGS_PER_HEIGHT.
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), new DurableTestStore());
        List<ContractProcessor.ContractLog> spam = new java.util.ArrayList<>();
        for (int i = 0; i < WasmContractProcessor.MAX_LOGS_PER_HEIGHT + 1_000; i++) {
            spam.add(new ContractProcessor.ContractLog(PublicAddress.random(),
                new byte[] {1}, new byte[] {2}));
        }
        processor.retainLogs(1, spam);
        assertEquals(WasmContractProcessor.MAX_LOGS_PER_HEIGHT, processor.logs(1).size(),
            "per-height log retention is capped");
    }

    @Test
    void retainedLogBytesEvictTheOldestHeightsFirst() {
        // Past MAX_RETAINED_LOG_BYTES the oldest heights are dropped (LRU by height): retention
        // stays a fixed network constant no matter how much log spam the retained window holds.
        WasmContractProcessor processor = new WasmContractProcessor(new WasmVm(), new DurableTestStore());
        byte[] payload = new byte[1024 * 1024];
        long perHeight = PublicAddress.SIZE + 1L + payload.length;
        int expectedKept = (int) (WasmContractProcessor.MAX_RETAINED_LOG_BYTES / perHeight);
        long heights = expectedKept + 3;
        for (long h = 1; h <= heights; h++) {
            processor.retainLogs(h, List.of(new ContractProcessor.ContractLog(
                PublicAddress.random(), new byte[] {1}, payload)));
        }
        int retained = 0;
        for (long h = 1; h <= heights; h++) {
            if (!processor.logs(h).isEmpty()) {
                retained++;
            }
        }
        assertEquals(expectedKept, retained, "retention is bounded by the byte budget");
        assertTrue(processor.logs(1).isEmpty(), "the oldest height is evicted first");
        assertTrue(processor.logs(2).isEmpty(), "eviction proceeds in height order");
        assertFalse(processor.logs(heights).isEmpty(), "the most recent height is always kept");
    }

    // ---- bounded journal/receipt decoding (audit: unbounded decode allocations) ----

    @Test
    void aCorruptJournalCountFailsCleanlyWithoutAGiantAllocation() {
        // count = Integer.MAX_VALUE in a 4-byte buffer: the old code pre-dimensioned a 2-billion-
        // entry ArrayList before touching any record. The count must be bounded by the bytes
        // present and rejected with a clean exception.
        byte[] corrupt = java.nio.ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array();
        assertThrows(IllegalStateException.class, () -> ContractJournalCodec.decode(corrupt));
    }

    @Test
    void aCorruptJournalEntryLengthFailsCleanly() {
        // A plausible single-record header followed by a huge key length: the length must be
        // bounded by the remaining bytes, not allocated verbatim.
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(64);
        b.putInt(1);                 // count
        b.put((byte) 1);             // isCode
        b.put(new byte[25]);         // contract address
        b.putInt(Integer.MAX_VALUE); // keyLen — absurd
        assertThrows(IllegalStateException.class,
            () -> ContractJournalCodec.decode(b.array()));
    }

    @Test
    void aCorruptReceiptCountFailsCleanlyWithoutAGiantAllocation() {
        byte[] corrupt = java.nio.ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array();
        assertThrows(IllegalArgumentException.class, () -> WasmContractProcessor.decodeReceipts(corrupt));
    }

    @Test
    void aCorruptReceiptTransferCountFailsCleanly() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(32);
        b.putInt(1);                 // one receipt
        b.putLong(0);                // gasUsed
        b.put((byte) 1);             // success
        b.putInt(Integer.MAX_VALUE); // transferCount — absurd
        assertThrows(IllegalArgumentException.class,
            () -> WasmContractProcessor.decodeReceipts(b.array()));
    }

    @Test
    void aTruncatedJournalFailsCleanly() {
        // Fewer bytes than even the count field: must surface as a clean exception, never a
        // giant allocation or a silent half-decode.
        assertThrows(RuntimeException.class, () -> ContractJournalCodec.decode(new byte[2]));
        assertThrows(RuntimeException.class, () -> WasmContractProcessor.decodeReceipts(new byte[2]));
    }
}
