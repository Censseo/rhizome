package rhizome.core.token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.state.HeightRetainedIndex;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

import static rhizome.core.mempool.ExecutionStatus.*;

/**
 * Reference {@link TokenProcessor}: validates token ops against a per-block session
 * overlaying a {@link TokenStore}, and flushes the session as atomic store ops on commit.
 * Mirrors {@link rhizome.core.box.DefaultBoxProcessor}; token balances survive a reorg via
 * the store's persisted journal, and token ops move no PDN so no receipts are needed.
 */
public final class DefaultTokenProcessor implements TokenProcessor {

    private final TokenStore store;
    private final NetworkParameters params;
    private final int retainDepth;

    private Map<String, TokenMeta> sessionMeta;
    private Map<String, Long> sessionBalance;
    private List<TokenEvent> currentEvents = new ArrayList<>();
    /**
     * Per-height retention for reorg reversal, bounded by heights AND bytes — see
     * {@link HeightRetainedIndex}, which the three domain processors now share instead of each
     * keeping its own copy of the same machinery.
     */
    private final HeightRetainedIndex<TokenEvent> eventsByHeight;
    private final HeightRetainedIndex<TokenStore.TokenOp> changesByHeight;

    /** Budgets on the two retained maps. Height count alone is not a memory bound: at the
     *  production retainDepth these hold every token op and event of 120 blocks, capped only by
     *  the block size limit (audit: RAM retention). */
    static final long MAX_RETAINED_EVENT_BYTES = 16L * 1024 * 1024;
    static final long MAX_RETAINED_CHANGE_BYTES = 16L * 1024 * 1024;

    /** Fixed part of a serialized {@link TokenMeta}: id, minter, decimals, supply, height, 2 lengths. */
    private static final long TOKEN_META_FIXED_BYTES = 32L + PublicAddress.SIZE + 1 + 8 + 8 + 1 + 1;

    /** Blocks between amortized durable interval prunes ({@code pruneJournals}); see pruneToChainTip. */
    static final long PRUNE_INTERVAL = 32;
    private long lastIntervalPruneCutoff;

    public DefaultTokenProcessor(TokenStore store, NetworkParameters params) {
        this(store, params, params.maxReorgDepth());
    }

    public DefaultTokenProcessor(TokenStore store, NetworkParameters params, int retainDepth) {
        this.store = store;
        this.params = params;
        this.retainDepth = retainDepth;
        this.eventsByHeight = new HeightRetainedIndex<>(retainDepth, MAX_RETAINED_EVENT_BYTES,
            DefaultTokenProcessor::eventBytes);
        this.changesByHeight = new HeightRetainedIndex<>(retainDepth, MAX_RETAINED_CHANGE_BYTES,
            DefaultTokenProcessor::changeBytes);
    }

    @Override
    public void begin() {
        sessionMeta = new LinkedHashMap<>();
        sessionBalance = new LinkedHashMap<>();
        currentEvents = new ArrayList<>();
    }

    @Override
    public TokenResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                           long nonce, byte[] data, long height) {
        if (sessionMeta == null) {
            begin();
        }
        TokenPayload payload;
        try {
            payload = TokenPayload.decode(kind, data, params.maxTokenSymbolBytes(),
                params.maxTokenNameBytes(), params.maxTokenDecimals());
        } catch (IllegalArgumentException e) {
            return TokenResult.fail(TOKEN_PAYLOAD_INVALID);
        }
        return switch (kind) {
            case TOKEN_MINT -> mint(from, to, nonce, payload, height);
            case TOKEN_TRANSFER -> transfer(from, to, payload);
            case TOKEN_BURN -> burn(from, payload);
            default -> TokenResult.fail(TOKEN_PAYLOAD_INVALID);
        };
    }

    private TokenResult mint(PublicAddress from, PublicAddress to, long nonce,
                             TokenPayload payload, long height) {
        byte[] id = TokenMeta.deriveId(from, nonce);
        if (getMeta(id) != null) {
            return TokenResult.fail(TOKEN_ALREADY_EXISTS);
        }
        TokenMeta meta = new TokenMeta(id, from, payload.symbol(), payload.name(),
            payload.decimals(), payload.amount(), height);
        putMeta(meta);
        setBalance(id, to, payload.amount()); // fresh token: recipient balance is the full mint
        event(from, "token.minted", id);
        return new TokenResult(SUCCESS, id);
    }

    private TokenResult transfer(PublicAddress from, PublicAddress to, TokenPayload payload) {
        byte[] id = payload.tokenId();
        if (getMeta(id) == null) {
            return TokenResult.fail(TOKEN_NOT_FOUND);
        }
        long fromBal = getBalance(id, from);
        if (fromBal < payload.amount()) {
            return TokenResult.fail(TOKEN_INSUFFICIENT_BALANCE);
        }
        // A self-transfer is a no-op. Handling it explicitly is not just an optimisation: the
        // debit-then-credit sequence below shares one balance key when from == to, and computing
        // the credit from a pre-debit read would blind-overwrite the debit and MINT the amount
        // (final balance = B + amount). See audit: token self-transfer counterfeiting.
        if (from.equals(to)) {
            return new TokenResult(SUCCESS, id);
        }
        // Compute the recipient's new balance (with its overflow check) BEFORE staging any write,
        // so a failing transfer leaves the session completely untouched. This atomicity is
        // load-bearing: the executor soft-reverts a failed token op and keeps the block valid
        // (Ethereum-style), which would silently commit a partial debit if run() staged before
        // failing (audit: mempool-poisoning block-production halt). from != to here, so the debit
        // and credit keys differ and reading the recipient pre-debit is safe.
        long toBal;
        try {
            toBal = Math.addExact(getBalance(id, to), payload.amount());
        } catch (ArithmeticException e) {
            return TokenResult.fail(INVALID_TRANSACTION_AMOUNT);
        }
        setBalance(id, from, fromBal - payload.amount());
        setBalance(id, to, toBal);
        event(from, "token.transferred", id);
        return new TokenResult(SUCCESS, id);
    }

    private TokenResult burn(PublicAddress from, TokenPayload payload) {
        byte[] id = payload.tokenId();
        TokenMeta meta = getMeta(id);
        if (meta == null) {
            return TokenResult.fail(TOKEN_NOT_FOUND);
        }
        long fromBal = getBalance(id, from);
        if (fromBal < payload.amount()) {
            return TokenResult.fail(TOKEN_INSUFFICIENT_BALANCE);
        }
        setBalance(id, from, fromBal - payload.amount());
        putMeta(meta.withSupply(meta.totalSupply() - payload.amount()));
        event(from, "token.burned", id);
        return new TokenResult(SUCCESS, id);
    }

    // ---- session ----

    private TokenMeta getMeta(byte[] id) {
        String key = hex(id);
        if (sessionMeta.containsKey(key)) {
            return sessionMeta.get(key);
        }
        return store.getMeta(id);
    }

    private void putMeta(TokenMeta meta) {
        sessionMeta.put(hex(meta.id()), meta);
    }

    private long getBalance(byte[] id, PublicAddress addr) {
        String key = balanceKey(id, addr);
        if (sessionBalance.containsKey(key)) {
            return sessionBalance.get(key);
        }
        return store.getBalance(id, addr.toBytes());
    }

    private void setBalance(byte[] id, PublicAddress addr, long amount) {
        sessionBalance.put(balanceKey(id, addr), amount);
    }

    private void event(PublicAddress actor, String type, byte[] id) {
        currentEvents.add(new TokenEvent(actor, type, id.clone()));
    }

    @Override
    public void commit(long blockHeight) {
        if (sessionMeta != null) {
            List<TokenStore.TokenOp> ops = new ArrayList<>();
            for (TokenMeta meta : sessionMeta.values()) {
                ops.add(new TokenStore.TokenOp.MetaSet(meta));
            }
            sessionBalance.forEach((key, amount) ->
                ops.add(new TokenStore.TokenOp.BalanceSet(tokenIdOf(key), addressOf(key), amount)));
            // A block with NO token ops persists no journal: every revert path maps a missing
            // journal to "nothing to undo" (see RocksDbTokenStore.revertBlock's early return),
            // so the 4-byte empty-journal row was a synced write per block for nothing (audit:
            // empty-journal fsyncs).
            if (!ops.isEmpty()) {
                store.applyBlock(blockHeight, ops);
                changesByHeight.retain(blockHeight, ops);
            }
            sessionMeta = null;
            sessionBalance = null;
        }
        if (!currentEvents.isEmpty()) {
            eventsByHeight.retain(blockHeight, currentEvents);
        }
        currentEvents = new ArrayList<>();
        // Deliberately NO retention prune here: this commit may still be reverted within the
        // caller's critical section (the stampStateRoot dry run, an addBlock state-root
        // rejection), so pruning must key on the appended chain tip — the engine drives it
        // post-append via {@link #pruneToChainTip}.
    }

    @Override
    public void pruneToChainTip(long chainTip) {
        eventsByHeight.pruneThrough(chainTip, null);
        changesByHeight.pruneThrough(chainTip, null);
        // Amortized durable interval prune: the deleteRange fsync is the backstop for rows
        // committed before a restart, not worth paying every block (audit perf). It only ever lags
        // the exact per-height schedule, so the reorg depth stays fully covered.
        long cutoff = chainTip - retainDepth;
        if (cutoff > 0 && cutoff - lastIntervalPruneCutoff >= PRUNE_INTERVAL) {
            store.pruneJournals(cutoff);
            lastIntervalPruneCutoff = cutoff;
        }
    }

    /** Retained size of one height's events: actor address, type string, token id. */
    private static long eventBytes(List<TokenEvent> events) {
        long bytes = 0;
        for (TokenEvent e : events) {
            bytes += PublicAddress.SIZE
                + (e.type() == null ? 0 : e.type().length() * 2L)
                + (e.tokenId() == null ? 0 : e.tokenId().length);
        }
        return bytes;
    }

    /** Retained size of one height's ops: metadata as serialized, balances as their key plus amount. */
    private static long changeBytes(List<TokenStore.TokenOp> changes) {
        long bytes = 0;
        for (TokenStore.TokenOp op : changes) {
            bytes += switch (op) {
                case TokenStore.TokenOp.MetaSet ms -> TOKEN_META_FIXED_BYTES
                    + (ms.meta() == null ? 0
                        : ms.meta().symbol().length() * 2L + ms.meta().name().length() * 2L);
                case TokenStore.TokenOp.BalanceSet bs ->
                    (bs.tokenId() == null ? 0 : bs.tokenId().length)
                    + (bs.address() == null ? 0 : bs.address().length) + 8L;
            };
        }
        return bytes;
    }

    @Override
    public void discard() {
        sessionMeta = null;
        sessionBalance = null;
        currentEvents = new ArrayList<>();
    }

    @Override
    public void revertBlock(long blockHeight) {
        eventsByHeight.forget(blockHeight);
        changesByHeight.forget(blockHeight);
        store.revertBlock(blockHeight);
    }

    @Override
    public List<TokenEvent> events(long blockHeight) {
        return eventsByHeight.get(blockHeight);
    }

    @Override
    public List<TokenStore.TokenOp> changes(long blockHeight) {
        return changesByHeight.get(blockHeight);
    }

    @Override
    public TokenMeta meta(byte[] tokenId) {
        return store.getMeta(tokenId);
    }

    @Override
    public long balance(byte[] tokenId, byte[] address) {
        return store.getBalance(tokenId, address);
    }

    @Override
    public List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit) {
        return store.tokenIdsByMinter(minter, afterId, limit);
    }

    @Override
    public List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit) {
        return store.tokenIdsByHolder(address, afterId, limit);
    }

    // ---- key helpers: balance key = tokenIdHex(64) + addressHex(50) ----

    private static String balanceKey(byte[] tokenId, PublicAddress addr) {
        return hex(tokenId) + hex(addr.toBytes());
    }

    private static byte[] tokenIdOf(String key) {
        return unhex(key.substring(0, 64));
    }

    private static byte[] addressOf(String key) {
        return unhex(key.substring(64));
    }

    private static String hex(byte[] b) {
        return rhizome.core.common.Utils.bytesToHex(b);
    }

    private static byte[] unhex(String s) {
        return rhizome.core.common.Utils.hexStringToByteArray(s);
    }
}
