package rhizome.core.box;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.TransactionKind;

import static rhizome.core.mempool.ExecutionStatus.*;

/**
 * Reference {@link BoxProcessor}: validates box ops against a per-block session
 * overlaying a {@link BoxStore}, and flushes the session to the store atomically on
 * commit. The store persists both boxes and the undo journal, so box state is
 * restorable across a reorg (including one after a restart); receipts and events are
 * kept in memory to the reorg depth, exactly as the contract processor does.
 */
public final class DefaultBoxProcessor implements BoxProcessor {

    /** Marks a box deleted within the session (distinguished from "not in session"). */
    private static final Box TOMBSTONE = null;

    private final BoxStore store;
    private final NetworkParameters params;
    private final int retainDepth;

    /** Open block session: box id (hex) -> box, or a present key mapped to null for a delete. */
    private Map<String, Box> session;
    private List<BoxReceipt> currentReceipts = new ArrayList<>();
    private List<BoxEvent> currentEvents = new ArrayList<>();

    private final Map<Long, List<BoxReceipt>> receiptsByHeight = new ConcurrentHashMap<>();
    private final Map<Long, List<BoxEvent>> eventsByHeight = new ConcurrentHashMap<>();
    private long lastCommittedHeight = -1;

    public DefaultBoxProcessor(BoxStore store, NetworkParameters params) {
        this(store, params, params.maxReorgDepth());
    }

    public DefaultBoxProcessor(BoxStore store, NetworkParameters params, int retainDepth) {
        this.store = store;
        this.params = params;
        this.retainDepth = retainDepth;
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
        if (result.success()) {
            currentReceipts.add(new BoxReceipt(kind, result.debitFrom(), result.creditFrom()));
        }
        return result;
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
        return new BoxResult(SUCCESS, amount, 0, id);
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
        Box updated = box.updated(newValue, payload.registers(), height);
        BoxResult sizeCheck = checkSizeAndValue(updated, newValue);
        if (sizeCheck != null) {
            return sizeCheck;
        }
        sessionPut(updated);
        event(updated.owner(), "box.updated", updated.id());
        return new BoxResult(SUCCESS, amount, 0, updated.id());
    }

    private BoxResult spend(PublicAddress from, BoxPayload payload, long height) {
        Box box = sessionGet(payload.boxId());
        if (box == null) {
            return BoxResult.fail(BOX_NOT_FOUND);
        }
        if (!box.owner().equals(from)) {
            return BoxResult.fail(BOX_NOT_OWNER);
        }
        sessionDelete(box.id());
        event(box.owner(), "box.spent", box.id());
        return new BoxResult(SUCCESS, 0, box.value(), box.id());
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
        long rent = params.storageFeeFactor() * size;
        long floor = size * params.minValuePerByte();
        if (box.value() - rent < floor) {
            // Cannot pay the rent and stay above the dust floor: collect the whole box.
            long collected = box.value();
            sessionDelete(box.id());
            event(box.owner(), "box.collected", box.id());
            return new BoxResult(SUCCESS, 0, collected, box.id());
        }
        Box charged = box.afterRent(box.value() - rent, height);
        sessionPut(charged);
        event(box.owner(), "box.collected", box.id());
        return new BoxResult(SUCCESS, 0, rent, box.id());
    }

    /** Enforces the box-size cap and the min-value (anti-dust) floor. Null when valid. */
    private BoxResult checkSizeAndValue(Box box, long value) {
        if (box.serializedSize() > params.maxBoxSizeBytes()) {
            return BoxResult.fail(BOX_PAYLOAD_INVALID);
        }
        if (value < (long) box.serializedSize() * params.minValuePerByte()) {
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
        if (session != null) {
            List<BoxStore.BoxMutation> mutations = new ArrayList<>(session.size());
            for (Map.Entry<String, Box> e : session.entrySet()) {
                if (e.getValue() == TOMBSTONE) {
                    mutations.add(BoxStore.BoxMutation.delete(unhex(e.getKey())));
                } else {
                    mutations.add(BoxStore.BoxMutation.write(e.getValue()));
                }
            }
            store.applyBlock(blockHeight, mutations);
            session = null;
        }
        if (!currentReceipts.isEmpty()) {
            receiptsByHeight.put(blockHeight, currentReceipts);
        }
        if (!currentEvents.isEmpty()) {
            eventsByHeight.put(blockHeight, currentEvents);
        }
        currentReceipts = new ArrayList<>();
        currentEvents = new ArrayList<>();
        lastCommittedHeight = Math.max(lastCommittedHeight, blockHeight);
        pruneOld();
    }

    @Override
    public void discard() {
        session = null;
        currentReceipts = new ArrayList<>();
        currentEvents = new ArrayList<>();
    }

    @Override
    public void revertBlock(long blockHeight) {
        receiptsByHeight.remove(blockHeight);
        eventsByHeight.remove(blockHeight);
        store.revertBlock(blockHeight);
    }

    @Override
    public List<BoxReceipt> receipts(long blockHeight) {
        return receiptsByHeight.getOrDefault(blockHeight, List.of());
    }

    @Override
    public List<BoxEvent> events(long blockHeight) {
        return eventsByHeight.getOrDefault(blockHeight, List.of());
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

    private void pruneOld() {
        long cutoff = lastCommittedHeight - retainDepth;
        if (cutoff > 0) {
            receiptsByHeight.keySet().removeIf(h -> h < cutoff);
            eventsByHeight.keySet().removeIf(h -> h < cutoff);
            store.pruneJournals(cutoff);
        }
    }

    private static String hex(byte[] b) {
        return rhizome.core.common.Utils.bytesToHex(b);
    }

    private static byte[] unhex(String s) {
        return rhizome.core.common.Utils.hexStringToByteArray(s);
    }
}
