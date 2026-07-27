package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;

/**
 * Tests for {@link PersistentHostState}'s array-ownership invariant: {@code storageWrite}
 * defensively copies the key and value arrays (the key lives inside a {@code ByteKey} map
 * key, where caller-side mutation would make the entry unreachable and silently lose the
 * write), while {@code storageRead} hands out the buffered array zero-copy for the VM to
 * copy into contract memory.
 */
class PersistentHostStateTest {

    private static final PublicAddress CONTRACT = PublicAddress.random();

    private final InMemoryContractStore base = new InMemoryContractStore();

    private PersistentHostState host() {
        return new PersistentHostState(base, CONTRACT,
            new byte[PublicAddress.SIZE], new byte[0], 0);
    }

    @Test
    void mutatingTheCallersArraysAfterStorageWriteDoesNotChangePendingState() {
        PersistentHostState host = host();
        byte[] key = {1};
        byte[] value = {7};
        host.storageWrite(key, value);
        key[0] = 99;
        value[0] = 99;
        assertArrayEquals(new byte[] {7}, host.storageRead(new byte[] {1}),
            "the pending write must not alias the caller's value array");
        assertNull(host.storageRead(new byte[] {99}),
            "the pending write must not move when the caller's key array is mutated");
    }

    @Test
    void commitPublishesTheCopiedArraysNotTheCallers() {
        PersistentHostState host = host();
        byte[] key = {1};
        byte[] value = {7};
        host.storageWrite(key, value);
        key[0] = 99;
        value[0] = 99;
        host.commit();
        assertArrayEquals(new byte[] {7}, base.getStorage(CONTRACT, new byte[] {1}),
            "commit flushes the entry under the key/value as they were at storageWrite time");
        assertNull(base.getStorage(CONTRACT, new byte[] {99}));
    }

    @Test
    void consecutiveReadsOfAPendingWriteAreCoherentAcrossAnOverwrite() {
        PersistentHostState host = host();
        host.storageWrite(new byte[] {1}, new byte[] {1});
        byte[] first = host.storageRead(new byte[] {1});
        assertArrayEquals(new byte[] {1}, first);
        assertArrayEquals(first, host.storageRead(new byte[] {1}),
            "repeated reads of an untouched pending write agree");
        host.storageWrite(new byte[] {1}, new byte[] {2});
        assertArrayEquals(new byte[] {2}, host.storageRead(new byte[] {1}),
            "an overwrite is reflected by the next read");
        assertArrayEquals(new byte[] {1}, first,
            "an overwrite REPLACES the entry — the array returned before it is unchanged");
    }
}
