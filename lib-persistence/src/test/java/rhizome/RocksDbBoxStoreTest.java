package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.box.Box;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.BoxStore;
import rhizome.core.box.BoxStoreContract;
import rhizome.core.ledger.PublicAddress;
import rhizome.persistence.rocksdb.RocksDbBoxStore;

/**
 * RocksDB-specific behaviour beyond {@link BoxStoreContract}: persistence across a reopen, which
 * an in-memory store has no equivalent of — see {@code InMemoryBoxStoreTest} and {@code
 * BoxStoreContract} for the properties (including the double-apply refusal) both backends share.
 */
class RocksDbBoxStoreTest implements BoxStoreContract {

    @TempDir
    Path dir;

    private RocksDbBoxStore opened;

    @Override
    public BoxStore newBoxStore() throws Exception {
        opened = new RocksDbBoxStore(dir.toString());
        return opened;
    }

    @AfterEach
    void tearDown() {
        if (opened != null) {
            opened.close();
        }
    }

    private static Box box(PublicAddress owner, long nonce, long value, long rentPaidHeight) {
        return new Box(Box.deriveId(owner, nonce), owner, value, 1, rentPaidHeight,
            List.of(BoxRegister.string("m" + nonce)));
    }

    @Test
    void persistsBoxesAcrossReopen() throws Exception {
        PublicAddress owner = PublicAddress.random();
        Box a = box(owner, 0, 1000, 5);
        try (var store = new RocksDbBoxStore(dir.toString())) {
            store.applyBlock(2, List.of(BoxStore.BoxMutation.write(a)));
            assertEquals(a, store.get(a.id()));
            assertNull(store.get(Box.deriveId(owner, 99)));
        }
        try (var store = new RocksDbBoxStore(dir.toString())) {
            assertEquals(a, store.get(a.id())); // survived on disk
        }
    }
}
