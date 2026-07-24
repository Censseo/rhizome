package rhizome.persistence.leveldb;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LevelDBDataStoreTest {

    private static final String TEST_DB_PATH = "./test-data/tmpdb-datastore";
    private LevelDBDataStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new LevelDBDataStore();
        store.init(TEST_DB_PATH);
    }

    @AfterEach
    void tearDown() throws IOException {
        store.deleteDB();
    }

    @Test
    void intCodecIsSymmetricRawBigEndian() throws IOException {
        // Regression (audit F6): set(String, int) used to write ASCII Integer.toString while
        // get(..., Integer.class) read raw 4-byte big-endian — written ints could never be read
        // back. Both sides are now the raw 4-byte form.
        store.set("n", 42);
        assertEquals(42, store.get("n", Integer.class));

        byte[] raw = store.rawDb().get("n".getBytes(UTF_8));
        assertArrayEquals(new byte[] {0, 0, 0, 42}, raw, "on-disk form must be 4-byte big-endian");

        store.set("neg", -1);
        assertEquals(-1, store.get("neg", Integer.class));
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
            store.rawDb().get("neg".getBytes(UTF_8)));
    }

    @Test
    void clearRemovesBinaryKeysWithoutUtf8Corruption() throws IOException {
        // Regression (audit F5): clear() used to String-encode keys, so binary keys that are not
        // valid UTF-8 survived the "clear". Raw-key deletion removes everything.
        byte[] binaryKey = new byte[] {(byte) 0x80, (byte) 0x81, 0x00, (byte) 0xFF};
        store.set(binaryKey, new byte[] {1, 2, 3});
        store.set("plain", 7);
        store.clear();
        assertTrue(store.rawDb().get(binaryKey) == null);
        assertTrue(store.rawDb().get("plain".getBytes(UTF_8)) == null);
    }
}
