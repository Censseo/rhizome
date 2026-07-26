package rhizome.persistence.leveldb;

import org.apache.commons.io.FileUtils;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.MemSize;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.iq80.leveldb.DBIterator;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;
import static java.nio.charset.StandardCharsets.UTF_8;
import static rhizome.core.common.Utils.intToBytes;

@Getter @Setter @Slf4j
public class LevelDBDataStore {
    private DB db;
    private String path;

    public LevelDBDataStore() {
        this.db = null;
        this.path = "";
    }

    public void init(String path) throws IOException {
        if (this.db != null) {
            this.closeDB();
        }
        this.path = path;
        Options options = new Options();
        options.createIfMissing(true);
        this.db = factory.open(new File(path), options);
    }

    public void deleteDB() throws IOException {
        this.closeDB();
        factory.destroy(new File(path), new Options());
        File directory = new File(path);
        try {
            FileUtils.deleteDirectory(directory);
        } catch (IOException e) {
            throw new LevelDBException("Could not clear path " + path, e);
        }
    }

    public void closeDB() {
        if (this.db != null) {
            try {
                this.db.close();
            } catch (IOException e) {
                throw new LevelDBException("Could not close DataStore db", e);
            }
            this.db = null;
        }
    }

    public void clear() {
        // Delete by the RAW keys: a UTF-8 String round-trip corrupts any key that is not valid
        // UTF-8 (block/wallet index keys are arbitrary binary), silently failing to delete them
        // (audit F5). Stream the iterator and delete in bounded synced chunks: materialising every
        // key into a list first OOM'd on a large database (audit: clear() heap). The iterator is a
        // consistent snapshot, so batch-deleting already-seen keys mid-scan is safe.
        final int chunkSize = 4096;
        try (DBIterator iterator = db.iterator()) {
            WriteBatch batch = db.createWriteBatch();
            try {
                int pending = 0;
                for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
                    batch.delete(iterator.peekNext().getKey());
                    if (++pending >= chunkSize) {
                        db.write(batch, new WriteOptions().sync(true));
                        batch.close();
                        batch = db.createWriteBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    db.write(batch, new WriteOptions().sync(true));
                }
            } finally {
                batch.close(); // IOException propagates to the catch below
            }
        } catch (IOException e) {
            throw new LevelDBException("Could not clear data store", e);
        }
    }

    public String getPath() {
        return this.path;
    }

    /** Test hook: raw access to the underlying DB for on-disk assertions. */
    DB rawDb() {
        return db;
    }

    protected void set(String key, String value) {
        set(key.getBytes(UTF_8), value.getBytes(UTF_8));
    }
    protected void set(String key, int value) {
        // Raw 4-byte big-endian, symmetric with the Integer read path in get() below — the old
        // ASCII Integer.toString encoding could never be read back (audit F6).
        set(key.getBytes(UTF_8), intToBytes(value));
    }
    protected void set(String key, long value) {
        // Plain allocation for short-lived internal keys: the previous ByteBufPool buffers were
        // never recycled, leaking pooled memory (audit F12).
        set(key.getBytes(UTF_8), ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }
    protected void set(String key, BigInteger value) {
        set(key.getBytes(UTF_8), value.toByteArray());
    }
    protected void set(int key, byte[] value) {
        set(intToBytes(key), value);
    }
    protected void set(byte[] key, byte[] value) {
        db.put(key, value, new WriteOptions().sync(true));
    }

    protected Object get(String key, Class<?> type) {
        return get(key.getBytes(UTF_8), type);
    }
    protected Object get(int key, Class<?> type) {
        return get(intToBytes(key), type);
    }
    protected Object get(byte[] key, Class<?> type) {
        var value = db.get(key, new ReadOptions());
        if (value == null) {
            throw new LevelDBException("Empty key: " + key);
        }

        if(type == String.class) {
            return new String(value, UTF_8);
        } else if (type == Integer.class) {
            return ByteBuffer.wrap(value).getInt();
        } else if (type == Long.class && value.length == Long.BYTES) {
            return ByteBuffer.wrap(value).getLong();
        } else if (type == BigInteger.class) {
            return new BigInteger(value);
        } else if (type == byte[].class) {
            return value;
        } else if (type == ByteBuf.class) {
            // Ownership: the returned pooled buffer ESCAPES to the caller, who must recycle it
            // (ByteBuf.recycle()) when done — the store cannot, it has no way to know (audit F12).
            var buff = ByteBufPool.allocate(MemSize.of(value.length));
            buff.put(value);

            if (!buff.canRead()) {
                throw new LevelDBException("Could not read value of record " + key + " from BlockStore db.");
            }

            return buff;
        } else {
            throw new LevelDBException("Unsupported type");
        }
    }

    protected boolean hasKey(String key) {
        try {
            return db.get(key.getBytes(UTF_8), new ReadOptions()) != null;
        } catch (DBException e) {
            // Propagate like every other method: swallowing the error and answering "false"
            // silently hid database corruption behind "key absent" (audit F11).
            throw new LevelDBException("Error checking key", e);
        }
    }

    protected boolean hasKey(int key) {
        try {
            return db.get(intToBytes(key)) != null;
        } catch (DBException e) {
            throw new LevelDBException("Error checking key", e);
        }
    }

    protected static byte[] composeKey(int key1, int key2) {
        // Plain allocation for short-lived internal keys (the pooled buffers were never
        // recycled — audit F12); big-endian, unchanged on-disk encoding.
        return ByteBuffer.allocate(2 * Integer.BYTES).putInt(key1).putInt(key2).array();
    }

    protected static byte[] composeKey(byte[] key1, byte[] key2) {
        byte[] compositeKey = new byte[key1.length + key2.length];
        System.arraycopy(key1, 0, compositeKey, 0, key1.length);
        System.arraycopy(key2, 0, compositeKey, key1.length, key2.length);
        return compositeKey;
    }
}
