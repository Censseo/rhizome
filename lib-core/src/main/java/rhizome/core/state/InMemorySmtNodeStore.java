package rhizome.core.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import rhizome.core.common.Utils;

/** In-memory {@link SmtNodeStore} for tests and light nodes. */
public final class InMemorySmtNodeStore implements SmtNodeStore {

    private final Map<String, byte[]> nodes = new ConcurrentHashMap<>();

    @Override
    public byte[] get(byte[] hash) {
        byte[] v = nodes.get(Utils.bytesToHex(hash));
        return v == null ? null : v.clone();
    }

    @Override
    public void put(byte[] hash, byte[] node) {
        nodes.put(Utils.bytesToHex(hash), node.clone());
    }
}
