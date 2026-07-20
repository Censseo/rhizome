package rhizome.core.token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import rhizome.core.blockchain.NetworkParameters;
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
    private final Map<Long, List<TokenEvent>> eventsByHeight = new ConcurrentHashMap<>();
    private final Map<Long, List<TokenStore.TokenOp>> changesByHeight = new ConcurrentHashMap<>();
    private long lastCommittedHeight = -1;

    public DefaultTokenProcessor(TokenStore store, NetworkParameters params) {
        this(store, params, params.maxReorgDepth());
    }

    public DefaultTokenProcessor(TokenStore store, NetworkParameters params, int retainDepth) {
        this.store = store;
        this.params = params;
        this.retainDepth = retainDepth;
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
            store.applyBlock(blockHeight, ops);
            if (!ops.isEmpty()) {
                changesByHeight.put(blockHeight, ops);
            }
            sessionMeta = null;
            sessionBalance = null;
        }
        if (!currentEvents.isEmpty()) {
            eventsByHeight.put(blockHeight, currentEvents);
        }
        currentEvents = new ArrayList<>();
        lastCommittedHeight = Math.max(lastCommittedHeight, blockHeight);
        long cutoff = lastCommittedHeight - retainDepth;
        if (cutoff > 0) {
            eventsByHeight.keySet().removeIf(h -> h < cutoff);
            changesByHeight.keySet().removeIf(h -> h < cutoff);
            store.pruneJournals(cutoff);
        }
    }

    @Override
    public void discard() {
        sessionMeta = null;
        sessionBalance = null;
        currentEvents = new ArrayList<>();
    }

    @Override
    public void revertBlock(long blockHeight) {
        eventsByHeight.remove(blockHeight);
        changesByHeight.remove(blockHeight);
        store.revertBlock(blockHeight);
    }

    @Override
    public List<TokenEvent> events(long blockHeight) {
        return eventsByHeight.getOrDefault(blockHeight, List.of());
    }

    @Override
    public List<TokenStore.TokenOp> changes(long blockHeight) {
        return changesByHeight.getOrDefault(blockHeight, List.of());
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
