package rhizome.core.blockchain;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ObjLongConsumer;

import rhizome.core.ledger.PublicAddress;

/**
 * In-memory {@link NonceStore} — the default for tests and non-persistent nodes.
 * Not durable, so an engine built on it reconstructs nonces from block bodies at
 * boot (harmless: such a node keeps every body anyway).
 */
public final class InMemoryNonceStore implements NonceStore {

    private final Map<PublicAddress, Long> nonces = new HashMap<>();
    private long syncedThroughHeight = 0;

    @Override
    public long next(PublicAddress sender) {
        return nonces.getOrDefault(sender, 0L);
    }

    @Override
    public void set(PublicAddress sender, long next) {
        if (next <= 0) {
            nonces.remove(sender);
        } else {
            nonces.put(sender, next);
        }
    }

    @Override
    public long syncedThroughHeight() {
        return syncedThroughHeight; // resets to 0 each construction: an in-memory store rebuilds at boot
    }

    @Override
    public void markSyncedThrough(long height) {
        this.syncedThroughHeight = height;
    }

    @Override
    public void forEach(ObjLongConsumer<PublicAddress> consumer) {
        nonces.forEach(consumer::accept);
    }
}
