package rhizome.core.token;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import rhizome.core.common.Utils;

/**
 * In-memory {@link TokenStore} for tests and light nodes. Metadata and balances live in
 * maps; the per-height undo journal is kept in memory (the RocksDB store persists it).
 */
public final class InMemoryTokenStore implements TokenStore {

    private final Map<String, TokenMeta> metas = new ConcurrentHashMap<>();
    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Map<Long, List<Undo>> journals = new ConcurrentSkipListMap<>();

    private sealed interface Undo {
        record Meta(String key, TokenMeta prior) implements Undo {}
        record Balance(String key, long prior) implements Undo {}
    }

    @Override
    public TokenMeta getMeta(byte[] tokenId) {
        return metas.get(hex(tokenId));
    }

    @Override
    public long getBalance(byte[] tokenId, byte[] address) {
        return balances.getOrDefault(balanceKey(tokenId, address), 0L);
    }

    @Override
    public void applyBlock(long height, List<TokenOp> ops) {
        List<Undo> journal = new ArrayList<>(ops.size());
        for (TokenOp op : ops) {
            if (op instanceof TokenOp.MetaSet m) {
                String key = hex(m.meta().id());
                journal.add(new Undo.Meta(key, metas.get(key)));
                metas.put(key, m.meta());
            } else if (op instanceof TokenOp.BalanceSet b) {
                String key = balanceKey(b.tokenId(), b.address());
                journal.add(new Undo.Balance(key, balances.getOrDefault(key, 0L)));
                if (b.amount() == 0) {
                    balances.remove(key);
                } else {
                    balances.put(key, b.amount());
                }
            }
        }
        journals.put(height, journal);
    }

    @Override
    public void revertBlock(long height) {
        List<Undo> journal = journals.remove(height);
        if (journal == null) {
            return;
        }
        for (int i = journal.size() - 1; i >= 0; i--) {
            Undo u = journal.get(i);
            if (u instanceof Undo.Meta m) {
                if (m.prior() == null) {
                    metas.remove(m.key());
                } else {
                    metas.put(m.key(), m.prior());
                }
            } else if (u instanceof Undo.Balance b) {
                if (b.prior() == 0) {
                    balances.remove(b.key());
                } else {
                    balances.put(b.key(), b.prior());
                }
            }
        }
    }

    @Override
    public void pruneJournals(long minHeight) {
        journals.keySet().removeIf(h -> h < minHeight);
    }

    @Override
    public List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit) {
        List<byte[]> ids = new ArrayList<>();
        for (TokenMeta meta : metas.values()) {
            if (Arrays.equals(meta.minter().toBytes(), minter)) {
                ids.add(meta.id());
            }
        }
        return paginate(ids, afterId, limit);
    }

    @Override
    public List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit) {
        String addrHex = hex(address);
        List<byte[]> ids = new ArrayList<>();
        for (Map.Entry<String, Long> e : balances.entrySet()) {
            // key = tokenIdHex(64) + addressHex; match by the address suffix.
            if (e.getValue() > 0 && e.getKey().endsWith(addrHex)) {
                ids.add(Utils.hexStringToByteArray(e.getKey().substring(0, 64)));
            }
        }
        return paginate(ids, afterId, limit);
    }

    private static List<byte[]> paginate(List<byte[]> ids, byte[] afterId, int limit) {
        ids.sort((a, b) -> Arrays.compareUnsigned(a, b));
        List<byte[]> out = new ArrayList<>();
        for (byte[] id : ids) {
            if (afterId != null && Arrays.compareUnsigned(id, afterId) <= 0) {
                continue;
            }
            if (out.size() >= limit) {
                break;
            }
            out.add(id);
        }
        return out;
    }

    @Override
    public void forEachMeta(java.util.function.Consumer<TokenMeta> consumer) {
        metas.values().forEach(consumer);
    }

    @Override
    public void forEachBalance(BalanceConsumer consumer) {
        // Balance keys are hex(tokenId(32)) + hex(address(25)): 64 + 50 hex chars.
        balances.forEach((key, amount) -> consumer.accept(
            Utils.hexStringToByteArray(key.substring(0, 64)),
            Utils.hexStringToByteArray(key.substring(64)),
            amount));
    }

    private static String balanceKey(byte[] tokenId, byte[] address) {
        return hex(tokenId) + hex(address);
    }

    private static String hex(byte[] b) {
        return Utils.bytesToHex(b);
    }
}
