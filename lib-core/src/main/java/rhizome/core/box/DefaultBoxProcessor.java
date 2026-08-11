package rhizome.core.box;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.TransactionKind;

import static rhizome.core.mempool.ExecutionStatus.*;

/**
 * Reference {@link BoxProcessor}: validates box ops against a per-block session
 * overlaying a {@link BoxStore}, and flushes the session to the store atomically on
 * commit. The store persists both boxes and the undo journal, so box state is
 * restorable across a reorg (including one after a restart). Receipts are likewise
 * persisted through the store's receipt hooks ({@link BoxStore#putReceipts}) and
 * served from a write-through RAM cache, so {@code Executor.rollbackBlock} — which
 * consumes exactly one receipt per box transaction — still reverses a box-carrying
 * block exactly after a restart (audit F7); events stay RAM-only to the reorg depth.
 */
public final class DefaultBoxProcessor implements BoxProcessor {

    /** Marks a box deleted within the session (distinguished from "not in session"). */
    private static final Box TOMBSTONE = null;

    private final BoxStore store;
    private final NetworkParameters params;
    private final int retainDepth;
    /** The miner-votable box params, read at execution time (seeded from the network defaults). */
    private final rhizome.core.blockchain.VoteableParams voteable;

    /** Open block session: box id (hex) -> box, or a present key mapped to null for a delete. */
    private Map<String, Box> session;
    private List<BoxReceipt> currentReceipts = new ArrayList<>();
    private List<BoxEvent> currentEvents = new ArrayList<>();

    /**
     * Per-height retention for reorg reversal. {@link ConcurrentSkipListMap}, not a hash map:
     * keys are block heights, so ascending order IS the eviction order and the byte-budget
     * eviction below takes its victim in O(log n) rather than scanning the key set.
     */
    private final ConcurrentNavigableMap<Long, List<BoxReceipt>> receiptsByHeight = new ConcurrentSkipListMap<>();
    private final ConcurrentNavigableMap<Long, List<BoxEvent>> eventsByHeight = new ConcurrentSkipListMap<>();
    private final ConcurrentNavigableMap<Long, List<BoxStore.BoxMutation>> changesByHeight =
        new ConcurrentSkipListMap<>();

    /**
     * Byte budgets on the three retained maps, mirroring {@code WasmContractProcessor}'s.
     * Height-count retention alone is not a memory bound: at the production
     * {@code retainDepth = maxReorgDepth = 120}, {@link #changesByHeight} holds the FULL
     * serialized box payload of every mutation in 120 blocks, which is bounded only by the block
     * size limit times 120 — the same unbounded-RAM shape the contract processor's budgets were
     * added to close (audit: RAM retention), left open here because the retention code was
     * copied without them.
     */
    static final long MAX_RETAINED_RECEIPT_BYTES = 16L * 1024 * 1024;
    static final long MAX_RETAINED_EVENT_BYTES = 16L * 1024 * 1024;
    static final long MAX_RETAINED_CHANGE_BYTES = 64L * 1024 * 1024;

    /** Fixed retained size of one {@link BoxReceipt}: kind tag plus three longs. */
    private static final long RECEIPT_RECORD_BYTES = 1 + 8L + 8L + 8L;

    private long retainedReceiptBytes;
    private long retainedEventBytes;
    private long retainedChangeBytes;

    /** Serializes every counter mutation (retain / revert / prune / load-through / evict) so a
     *  counter never drifts from its map when these run on different threads. Reads stay
     *  lock-free on the concurrent maps. */
    private final Object retentionLock = new Object();

    public DefaultBoxProcessor(BoxStore store, NetworkParameters params) {
        this(store, params, params.maxReorgDepth());
    }

    public DefaultBoxProcessor(BoxStore store, NetworkParameters params, int retainDepth) {
        this.store = store;
        this.params = params;
        this.retainDepth = retainDepth;
        this.voteable = rhizome.core.blockchain.VoteableParams.fromDefaults(params);
    }

    @Override
    public rhizome.core.blockchain.VoteableParams voteableParams() {
        return voteable;
    }

    @Override
    public void begin() {
        session = new LinkedHashMap<>();
        currentReceipts = new ArrayList<>();
        currentEvents = new ArrayList<>();
    }

    @Override
    public BoxResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                         long amount, long nonce, byte[] data, long height) {
        if (session == null) {
            begin();
        }
        BoxPayload payload;
        try {
            payload = BoxPayload.decode(kind, data, params.maxBoxRegisters());
        } catch (IllegalArgumentException e) {
            return BoxResult.fail(BOX_PAYLOAD_INVALID);
        }
        BoxResult result = switch (kind) {
            case BOX_CREATE -> create(from, to, amount, nonce, payload, height);
            case BOX_UPDATE -> update(from, amount, payload, height);
            case BOX_SPEND -> spend(from, payload, height);
            case BOX_COLLECT -> collect(payload, height);
            default -> BoxResult.fail(BOX_PAYLOAD_INVALID);
        };
        // Emit one receipt per box transaction, even for a soft-reverted failure (debit 0,
        // credit 0). The executor keeps a failed box op in the block (Ethereum-style soft revert,
        // to defeat the mempool-poisoning halt) and rollbackBlock consumes exactly one box receipt
        // per box transaction in reverse; a receipt only on success would misalign that walk and
        // corrupt a reorg (audit: mempool-poisoning halt / C2-class rollback aliasing).
        currentReceipts.add(result.success()
            ? new BoxReceipt(kind, result.debitFrom(), result.creditFrom(), result.rentToMiner())
            : new BoxReceipt(kind, 0, 0, 0));
        return result;
    }

    /**
     * The storage rent accrued by {@code box} up to {@code height}: one {@code storageFeeFactor ×
     * serializedSize} per FULL elapsed storage period since {@code rentPaidHeight}. Saturates to
     * {@code Long.MAX_VALUE} on overflow (the box's value can never cover that, so the charge
     * degrades to taking the whole box — the same outcome as a BOX_COLLECT of an overdrawn box).
     *
     * <p>Charged on every op that touches the box (audit M7): before this, an UPDATE with
     * {@code amount=0} reset the rent clock for free, so an active owner could dodge rent forever
     * with one zero-cost transaction per period and store state permanently for nothing.
     */
    private long accruedRent(Box box, long height) {
        long period = params.storagePeriodBlocks();
        if (period <= 0) {
            return 0;
        }
        long elapsed = height - box.rentPaidHeight();
        if (elapsed <= 0) {
            return 0;
        }
        try {
            return Math.multiplyExact(Math.multiplyExact(elapsed / period, voteable.storageFeeFactor()),
                box.serializedSize());
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private BoxResult create(PublicAddress from, PublicAddress owner, long amount, long nonce,
                             BoxPayload payload, long height) {
        byte[] id = Box.deriveId(from, nonce);
        if (sessionGet(id) != null) {
            return BoxResult.fail(BOX_ALREADY_EXISTS);
        }
        Box box = new Box(id, owner, amount, height, height, payload.registers());
        BoxResult sizeCheck = checkSizeAndValue(box, amount);
        if (sizeCheck != null) {
            return sizeCheck;
        }
        sessionPut(box);
        event(box.owner(), "box.created", id);
        return new BoxResult(SUCCESS, amount, 0, 0, id);
    }

    private BoxResult update(PublicAddress from, long amount, BoxPayload payload, long height) {
        Box box = sessionGet(payload.boxId());
        if (box == null) {
            return BoxResult.fail(BOX_NOT_FOUND);
        }
        if (!box.owner().equals(from)) {
            return BoxResult.fail(BOX_NOT_OWNER);
        }
        long newValue;
        try {
            newValue = Math.addExact(box.value(), amount);
        } catch (ArithmeticException e) {
            return BoxResult.fail(INVALID_TRANSACTION_AMOUNT);
        }
        // Re-arming the rent clock costs the rent accrued since rentPaidHeight (audit M7): the
        // charge comes out of the box's locked value and goes to the block miner — it is NOT
        // returned to the owner, so cycling zero-amount updates can no longer keep a box alive
        // for free. The box must stay above the dust floor after the charge; an owner whose box
        // cannot cover the accrued rent can still SPEND it (paying what it holds) or abandon it
        // to collectors.
        long rent = accruedRent(box, height);
        long chargedValue = newValue - rent; // cannot underflow: checked against the floor below
        Box updated = box.updated(chargedValue, payload.registers(), height);
        BoxResult sizeCheck = checkSizeAndValue(updated, chargedValue);
        if (sizeCheck != null) {
            return sizeCheck;
        }
        sessionPut(updated);
        event(updated.owner(), "box.updated", updated.id());
        return new BoxResult(SUCCESS, amount, 0, rent, updated.id());
    }

    private BoxResult spend(PublicAddress from, BoxPayload payload, long height) {
        Box box = sessionGet(payload.boxId());
        if (box == null) {
            return BoxResult.fail(BOX_NOT_FOUND);
        }
        if (!box.owner().equals(from)) {
            return BoxResult.fail(BOX_NOT_OWNER);
        }
        // Charge the accrued rent before releasing the locked value (audit M7): without this an
        // owner could let a box sit for years, dodging collectors, then spend it and recover the
        // full value. The charge is capped at what the box holds, so a deeply overdrawn box is
        // simply surrendered to the miner — it always remains spendable by its owner.
        long rent = Math.min(accruedRent(box, height), box.value());
        long released = box.value() - rent;
        sessionDelete(box.id());
        event(box.owner(), "box.spent", box.id());
        return new BoxResult(SUCCESS, 0, released, rent, box.id());
    }

    private BoxResult collect(BoxPayload payload, long height) {
        Box box = sessionGet(payload.boxId());
        if (box == null) {
            return BoxResult.fail(BOX_NOT_FOUND);
        }
        if (height - box.rentPaidHeight() < params.storagePeriodBlocks()) {
            return BoxResult.fail(BOX_NOT_EXPIRED);
        }
        long size = box.serializedSize();
        // Charge EVERY elapsed period's rent, not just one (audit M7 residual): charging a single
        // period per collect let a deeply overdue box shed its debt one period at a time.
        long rent = accruedRent(box, height);
        long floor = size * voteable.minValuePerByte();
        if (box.value() - rent < floor) {
            // Cannot pay the rent and stay above the dust floor: collect the whole box.
            long collected = box.value();
            sessionDelete(box.id());
            event(box.owner(), "box.collected", box.id());
            return new BoxResult(SUCCESS, 0, collected, 0, box.id());
        }
        Box charged = box.afterRent(box.value() - rent, height);
        sessionPut(charged);
        event(box.owner(), "box.collected", box.id());
        return new BoxResult(SUCCESS, 0, rent, 0, box.id());
    }

    /** Enforces the box-size cap and the min-value (anti-dust) floor. Null when valid. */
    private BoxResult checkSizeAndValue(Box box, long value) {
        if (box.serializedSize() > params.maxBoxSizeBytes()) {
            return BoxResult.fail(BOX_PAYLOAD_INVALID);
        }
        if (value < (long) box.serializedSize() * voteable.minValuePerByte()) {
            return BoxResult.fail(BOX_VALUE_TOO_LOW);
        }
        return null;
    }

    // ---- session ----

    private Box sessionGet(byte[] id) {
        String key = hex(id);
        if (session.containsKey(key)) {
            return session.get(key); // may be null (tombstone)
        }
        return store.get(id);
    }

    private void sessionPut(Box box) {
        session.put(hex(box.id()), box);
    }

    private void sessionDelete(byte[] id) {
        session.put(hex(id), TOMBSTONE);
    }

    private void event(PublicAddress owner, String type, byte[] id) {
        currentEvents.add(new BoxEvent(owner, type, id.clone()));
    }

    @Override
    public void commit(long blockHeight) {
        byte[] encodedReceipts = currentReceipts.isEmpty() ? null : BoxReceiptCodec.encode(currentReceipts);
        if (session != null) {
            List<BoxStore.BoxMutation> mutations = new ArrayList<>(session.size());
            for (Map.Entry<String, Box> e : session.entrySet()) {
                if (e.getValue() == TOMBSTONE) {
                    mutations.add(BoxStore.BoxMutation.delete(unhex(e.getKey())));
                } else {
                    mutations.add(BoxStore.BoxMutation.write(e.getValue()));
                }
            }
            // A block with NO box mutations (an empty block, or only soft-reverted box txs)
            // persists no journal: every revert path maps a missing journal to "nothing to undo"
            // (see RocksDbBoxStore.revertBlock's early return), so the 4-byte empty-journal row
            // was a synced write per block for nothing (audit: empty-journal fsyncs). Receipts
            // still persist — a soft-reverted box tx has one that rollbackBlock consumes.
            if (!mutations.isEmpty()) {
                // Mutations + journal + receipts as ONE atomic batch where the store supports it
                // (RocksDB: no second fsync for the receipts, audit perf).
                store.applyBlock(blockHeight, mutations, encodedReceipts);
                encodedReceipts = null; // already persisted with the batch
                retainChanges(blockHeight, mutations);
            }
            session = null;
        }
        if (encodedReceipts != null) {
            store.putReceipts(blockHeight, encodedReceipts);
        }
        if (!currentReceipts.isEmpty()) {
            // Write-through (audit F7): the RAM copy serves the hot reorg path; the store copy
            // survives a restart, so a later pop/restore of this block finds its receipts.
            retainReceipts(blockHeight, currentReceipts);
        }
        if (!currentEvents.isEmpty()) {
            retainEvents(blockHeight, currentEvents);
        }
        currentReceipts = new ArrayList<>();
        currentEvents = new ArrayList<>();
        // Deliberately NO retention prune here: this commit may still be reverted within the
        // caller's critical section (the stampStateRoot dry run, an addBlock state-root
        // rejection), and pruning against an uncommitted height deletes the oldest in-window
        // receipts a max-depth reorg still needs. The engine prunes post-append via
        // {@link #pruneToChainTip}.
    }

    @Override
    public void discard() {
        session = null;
        currentReceipts = new ArrayList<>();
        currentEvents = new ArrayList<>();
    }

    @Override
    public void revertBlock(long blockHeight) {
        synchronized (retentionLock) {
            List<BoxReceipt> receipts = receiptsByHeight.remove(blockHeight);
            if (receipts != null) {
                retainedReceiptBytes -= receiptBytes(receipts);
            }
            List<BoxEvent> events = eventsByHeight.remove(blockHeight);
            if (events != null) {
                retainedEventBytes -= eventBytes(events);
            }
            List<BoxStore.BoxMutation> changes = changesByHeight.remove(blockHeight);
            if (changes != null) {
                retainedChangeBytes -= changeBytes(changes);
            }
        }
        // The store drops the block's receipts in the SAME atomic unit as the journal-driven
        // restore (audit: revert-path tear) — no separate deleteReceipts call here.
        store.revertBlock(blockHeight);
    }

    @Override
    public List<BoxReceipt> receipts(long blockHeight) {
        List<BoxReceipt> cached = receiptsByHeight.get(blockHeight);
        if (cached != null) {
            return cached;
        }
        // Durable load-through (audit F7): after a restart the RAM cache is empty; recover the
        // committed receipts from the store so a reorg's ledger reversal stays exact. Stores
        // without the receipt hooks return null and the pre-F7 RAM-only behaviour remains.
        byte[] encoded = store.getReceipts(blockHeight);
        if (encoded == null) {
            return List.of();
        }
        List<BoxReceipt> decoded = BoxReceiptCodec.decode(encoded);
        retainReceipts(blockHeight, decoded); // re-accounted, so the counter tracks the load-through
        return decoded;
    }

    @Override
    public List<BoxEvent> events(long blockHeight) {
        return eventsByHeight.getOrDefault(blockHeight, List.of());
    }

    @Override
    public List<BoxStore.BoxMutation> changes(long blockHeight) {
        return changesByHeight.getOrDefault(blockHeight, List.of());
    }

    @Override
    public Box get(byte[] boxId) {
        if (session != null) {
            String key = hex(boxId);
            if (session.containsKey(key)) {
                return session.get(key);
            }
        }
        return store.get(boxId);
    }

    @Override
    public Box getCommitted(byte[] boxId) {
        return store.get(boxId);
    }

    @Override
    public List<byte[]> collectableBoxIds(long height, int limit) {
        return store.collectableBoxIds(height, params.storagePeriodBlocks(), limit);
    }

    @Override
    public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
        return store.boxIdsByOwner(owner, afterId, limit);
    }

    @Override
    public ScanPage scan(ScanPredicate predicate, byte[] afterId, int limit, int window) {
        byte[] owner = predicate.ownerAnchor();
        List<byte[]> candidates = owner != null
            ? store.boxIdsByOwner(owner, afterId, window)
            : store.boxIdsFrom(afterId, window);

        List<Box> matches = new ArrayList<>();
        byte[] lastExamined = null;
        boolean brokeAtLimit = false;
        for (byte[] id : candidates) {
            lastExamined = id;
            Box box = store.get(id);
            if (box != null && predicate.test(box)) {
                matches.add(box);
                if (matches.size() >= limit) {
                    brokeAtLimit = true;
                    break;
                }
            }
        }
        // A cursor is returned when more candidates may remain: we stopped at the match
        // limit mid-window, or we consumed a full window (there could be more beyond it).
        byte[] cursor = brokeAtLimit || candidates.size() == window ? lastExamined : null;
        return new ScanPage(matches, cursor);
    }

    /**
     * Cadence of the amortized durable interval prune ({@code pruneJournals} range tombstones).
     * Per-height receipt deletes still run on every appended block; the interval deleteRange is
     * only the backstop for rows committed before a restart (the RAM maps are empty then), so it
     * is paid every PRUNE_INTERVAL blocks instead of ~2 synced tombstone fsyncs per block (audit
     * perf). It only ever lags the exact per-height schedule — the reorg window stays covered.
     */
    static final long PRUNE_INTERVAL = 32;
    private long lastIntervalPruneCutoff;

    @Override
    public void pruneToChainTip(long chainTip) {
        long cutoff = chainTip - retainDepth;
        if (cutoff <= 0) {
            return;
        }
        synchronized (retentionLock) {
            // `<= cutoff`, not `< cutoff`: keep EXACTLY retainDepth heights, (cutoff, chainTip].
            // The strict-less-than form retained one height too many — the same off-by-one the
            // contract processor fixed in audit F10 and this copy never picked up.
            for (Long h : receiptsByHeight.headMap(cutoff, true).keySet()) {
                store.deleteReceipts(h); // receipts prune on the journal schedule (audit F7)
            }
            dropThrough(receiptsByHeight, cutoff, DefaultBoxProcessor::receiptBytes,
                bytes -> retainedReceiptBytes -= bytes);
            dropThrough(eventsByHeight, cutoff, DefaultBoxProcessor::eventBytes,
                bytes -> retainedEventBytes -= bytes);
            dropThrough(changesByHeight, cutoff, DefaultBoxProcessor::changeBytes,
                bytes -> retainedChangeBytes -= bytes);
            if (cutoff - lastIntervalPruneCutoff >= PRUNE_INTERVAL) {
                store.pruneJournals(cutoff);
                lastIntervalPruneCutoff = cutoff;
            }
        }
    }

    /** Removes every height at or below {@code cutoff}, crediting the freed bytes back. */
    private static <T> void dropThrough(ConcurrentNavigableMap<Long, List<T>> map, long cutoff,
                                        java.util.function.ToLongFunction<List<T>> sizer,
                                        java.util.function.LongConsumer credit) {
        var expired = map.headMap(cutoff, true);
        for (List<T> value : expired.values()) {
            credit.accept(sizer.applyAsLong(value));
        }
        expired.clear();
    }

    // ---- byte-budgeted retention (audit: RAM retention) ---------------------------------------
    // Eviction is RAM-ONLY: the durable copies are deleted solely by the height-scheduled prune
    // above, so `receipts()` keeps its store-level fallback. Events and changes have no fallback
    // by design — events are non-consensus, and changes are consumed by
    // ChainEngine.collectStateChanges at the commit height and re-derived on re-apply.

    private void retainReceipts(long blockHeight, List<BoxReceipt> receipts) {
        synchronized (retentionLock) {
            List<BoxReceipt> previous = receiptsByHeight.put(blockHeight, receipts);
            if (previous != null) {
                retainedReceiptBytes -= receiptBytes(previous);
            }
            retainedReceiptBytes += receiptBytes(receipts);
            retainedReceiptBytes -= evictOldest(receiptsByHeight, blockHeight,
                retainedReceiptBytes, MAX_RETAINED_RECEIPT_BYTES, DefaultBoxProcessor::receiptBytes);
        }
    }

    private void retainEvents(long blockHeight, List<BoxEvent> events) {
        synchronized (retentionLock) {
            List<BoxEvent> previous = eventsByHeight.put(blockHeight, events);
            if (previous != null) {
                retainedEventBytes -= eventBytes(previous);
            }
            retainedEventBytes += eventBytes(events);
            retainedEventBytes -= evictOldest(eventsByHeight, blockHeight,
                retainedEventBytes, MAX_RETAINED_EVENT_BYTES, DefaultBoxProcessor::eventBytes);
        }
    }

    private void retainChanges(long blockHeight, List<BoxStore.BoxMutation> changes) {
        synchronized (retentionLock) {
            List<BoxStore.BoxMutation> previous = changesByHeight.put(blockHeight, changes);
            if (previous != null) {
                retainedChangeBytes -= changeBytes(previous);
            }
            retainedChangeBytes += changeBytes(changes);
            retainedChangeBytes -= evictOldest(changesByHeight, blockHeight,
                retainedChangeBytes, MAX_RETAINED_CHANGE_BYTES, DefaultBoxProcessor::changeBytes);
        }
    }

    /**
     * Drops oldest-first until the map fits {@code budget}, returning the bytes freed.
     *
     * <p>Never evicts {@code keepHeight}, the height just retained: {@code changes(height)} is read
     * by {@code ChainEngine.collectStateChanges} immediately after commit, and dropping it would
     * silently omit a domain from the state root rather than merely lose a cache entry. A single
     * block cannot exceed the budget in practice — the block size limit bounds it — so the guard
     * only ever fires on a misconfiguration, where a fatter-than-budget block is the right thing
     * to keep.
     */
    private static <T> long evictOldest(ConcurrentNavigableMap<Long, List<T>> map, long keepHeight,
                                        long retained, long budget,
                                        java.util.function.ToLongFunction<List<T>> sizer) {
        long freed = 0;
        while (retained - freed > budget) {
            Map.Entry<Long, List<T>> oldest = map.firstEntry();
            if (oldest == null || oldest.getKey() == keepHeight) {
                break;
            }
            map.remove(oldest.getKey());
            freed += sizer.applyAsLong(oldest.getValue());
        }
        return freed;
    }

    /** Retained size of one height's receipts: a fixed record each, no variable-length payload. */
    private static long receiptBytes(List<BoxReceipt> receipts) {
        return receipts.size() * RECEIPT_RECORD_BYTES;
    }

    /** Retained size of one height's events: owner address, type string, box id. */
    private static long eventBytes(List<BoxEvent> events) {
        long bytes = 0;
        for (BoxEvent e : events) {
            bytes += PublicAddress.SIZE
                + (e.type() == null ? 0 : e.type().length() * 2L)
                + (e.boxId() == null ? 0 : e.boxId().length);
        }
        return bytes;
    }

    /** Retained size of one height's mutations: the id plus the full serialized box payload. */
    private static long changeBytes(List<BoxStore.BoxMutation> changes) {
        long bytes = 0;
        for (BoxStore.BoxMutation m : changes) {
            bytes += (m.id() == null ? 0 : m.id().length)
                + (m.box() == null ? 0 : m.box().serializedSize());
        }
        return bytes;
    }

    private static String hex(byte[] b) {
        return rhizome.core.common.Utils.bytesToHex(b);
    }

    private static byte[] unhex(String s) {
        return rhizome.core.common.Utils.hexStringToByteArray(s);
    }
}
