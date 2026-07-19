package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.persistence.rocksdb.RocksDbContractStore;

class RocksDbContractStoreTest {

    @Test
    void persistsCodeAndStorageAcrossReopen(@TempDir Path dir) throws Exception {
        PublicAddress contract = PublicAddress.random();
        byte[] code = {0x00, 0x61, 0x73, 0x6d, 1, 2, 3};
        byte[] key = {0};
        byte[] value = {9, 8, 7, 6};

        try (var store = new RocksDbContractStore(dir.toString())) {
            store.putCode(contract, code);
            store.putStorage(contract, key, value);
            assertArrayEquals(code, store.getCode(contract));
            assertArrayEquals(value, store.getStorage(contract, key));
            assertNull(store.getStorage(contract, new byte[] {1}));
            assertNull(store.getCode(PublicAddress.random()));
        }

        // Reopen: state survived (it is on disk, not in memory).
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertArrayEquals(code, store.getCode(contract));
            assertArrayEquals(value, store.getStorage(contract, key));
        }
    }

    @Test
    void storageIsIsolatedPerContract(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbContractStore(dir.toString())) {
            PublicAddress a = PublicAddress.random();
            PublicAddress b = PublicAddress.random();
            byte[] key = {0};
            store.putStorage(a, key, new byte[] {1});
            store.putStorage(b, key, new byte[] {2});
            assertArrayEquals(new byte[] {1}, store.getStorage(a, key));
            assertArrayEquals(new byte[] {2}, store.getStorage(b, key));
        }
    }

    @Test
    void unusedKeysAreNull(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertNull(store.getStorage(PublicAddress.random(), new byte[] {42}));
            assertEquals(null, store.getCode(PublicAddress.random()));
        }
    }
}
