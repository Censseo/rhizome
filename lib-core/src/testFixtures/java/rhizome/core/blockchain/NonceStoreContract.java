package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;

/** The behaviour every {@link NonceStore} owes its callers, run against each implementation. */
public interface NonceStoreContract {

    /** A fresh, empty store. */
    NonceStore newNonceStore() throws Exception;

    @Test
    default void nextIsZeroForAnUnknownSender() throws Exception {
        NonceStore store = newNonceStore();
        assertEquals(0L, store.next(PublicAddress.random()));
    }

    @Test
    default void setThenNextRoundTripsAndNonPositiveClears() throws Exception {
        NonceStore store = newNonceStore();
        PublicAddress sender = PublicAddress.random();
        store.set(sender, 3L);
        assertEquals(3L, store.next(sender));

        store.set(sender, 0L); // <= 0 removes the entry
        assertEquals(0L, store.next(sender));
    }

    @Test
    default void markSyncedThroughRoundTrips() throws Exception {
        NonceStore store = newNonceStore();
        store.markSyncedThrough(42L);
        assertEquals(42L, store.syncedThroughHeight());
    }

    @Test
    default void forEachVisitsEveryEntrySet() throws Exception {
        NonceStore store = newNonceStore();
        PublicAddress a = PublicAddress.random();
        PublicAddress b = PublicAddress.random();
        store.set(a, 1L);
        store.set(b, 2L);

        Map<PublicAddress, Long> seen = new HashMap<>();
        store.forEach(seen::put);
        assertEquals(Map.of(a, 1L, b, 2L), seen);
    }
}
