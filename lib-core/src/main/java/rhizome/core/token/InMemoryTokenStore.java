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
 * maps keyed by the typed {@link TokenId} / {@link TokenBalanceKey} (whose byte layout is
 * the committed one — see {@link TokenBalanceKey#toBytes()}); the per-height undo journal
 * is kept in memory (the RocksDB store persists it).
 */
public final class InMemoryTokenStore implements TokenStore {

    private final Map<TokenId, TokenMeta> metas = new ConcurrentHashMap<>();
    private final Map<TokenBalanceKey, Long> balances = new ConcurrentHashMap<>();
    private final Map<Long, List<Undo>> journals = new ConcurrentSkipListMap<>();

    private sealed interface Undo {
        record Meta(TokenId key, TokenMeta prior) implements Undo {}
        record Balance(TokenBalanceKey key, long prior) implements Undo {}
    }

    @Override
    public TokenMeta getMeta(TokenId tokenId) {
        return metas.get(tokenId);
    }

    @Override
    public long getBalance(TokenBalanceKey key) {
        return balances.getOrDefault(key, 0L);
    }

    @Override
    public void applyBlock(long height, List<TokenOp> ops) {
        // Refuse a double-apply: re-applying a block would journal its own already-mutated state
        // as the "prior", so a later revert would restore the wrong values (audit F10). Checked
        // against whether a NON-EMPTY journal already exists at this height — mirroring the
        // durable store, which persists no journal (and so triggers no refusal next time) for a
        // mutation-less apply; see the guard below.
        List<Undo> existing = journals.get(height);
        if (existing != null && !existing.isEmpty()) {
            throw new IllegalStateException("token store already has a journal at height " + height);
        }
        List<Undo> journal = new ArrayList<>(ops.size());
        for (TokenOp op : ops) {
            if (op instanceof TokenOp.MetaSet m) {
                TokenId key = m.meta().id();
                journal.add(new Undo.Meta(key, metas.get(key)));
                metas.put(key, m.meta());
            } else if (op instanceof TokenOp.BalanceSet b) {
                TokenBalanceKey key = b.key();
                journal.add(new Undo.Balance(key, balances.getOrDefault(key, 0L)));
                if (b.amount() == 0) {
                    balances.remove(key);
                } else {
                    balances.put(key, b.amount());
                }
            }
        }
        // A mutation-less apply persists no journal: revertBlock maps a missing journal to
        // "nothing to undo", matching the durable store (audit: empty journals).
        if (!journal.isEmpty()) {
            journals.put(height, journal);
        }
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
    public List<TokenId> tokenIdsByMinter(byte[] minter, TokenId afterId, int limit) {
        List<TokenId> ids = new ArrayList<>();
        for (TokenMeta meta : metas.values()) {
            if (Arrays.equals(meta.minter().toBytes(), minter)) {
                ids.add(meta.id());
            }
        }
        return paginate(ids, afterId, limit);
    }

    @Override
    public List<TokenId> tokenIdsByHolder(byte[] address, TokenId afterId, int limit) {
        List<TokenId> ids = new ArrayList<>();
        for (Map.Entry<TokenBalanceKey, Long> e : balances.entrySet()) {
            if (e.getValue() > 0 && Arrays.equals(e.getKey().address().toBytes(), address)) {
                ids.add(e.getKey().tokenId());
            }
        }
        return paginate(ids, afterId, limit);
    }

    private static List<TokenId> paginate(List<TokenId> ids, TokenId afterId, int limit) {
        ids.sort((a, b) -> Arrays.compareUnsigned(a.toBytes(), b.toBytes()));
        List<TokenId> out = new ArrayList<>();
        for (TokenId id : ids) {
            if (afterId != null && Arrays.compareUnsigned(id.toBytes(), afterId.toBytes()) <= 0) {
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
        balances.forEach((key, amount) -> consumer.accept(key, amount));
    }
}
