package rhizome.core.token;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import rhizome.core.transaction.TransactionKind;

/**
 * Codec for the {@code data} payload of a native-token transaction:
 * <ul>
 *   <li>{@code TOKEN_MINT}: {@code amount(8) || decimals(1) || symLen(1) || symbol || nameLen(1) || name}
 *       — the token id is derived from the minter and nonce, not carried.</li>
 *   <li>{@code TOKEN_TRANSFER} / {@code TOKEN_BURN}: {@code tokenId(32) || amount(8)}.</li>
 * </ul>
 * Decoding is strict: it rejects over-long symbol/name, out-of-range decimals, a
 * non-positive mint amount, and any trailing bytes.
 */
public final class TokenPayload {

    private final byte[] tokenId;   // null for MINT
    private final long amount;
    private final int decimals;     // MINT only
    private final String symbol;    // MINT only
    private final String name;      // MINT only

    private TokenPayload(byte[] tokenId, long amount, int decimals, String symbol, String name) {
        this.tokenId = tokenId;
        this.amount = amount;
        this.decimals = decimals;
        this.symbol = symbol;
        this.name = name;
    }

    public byte[] tokenId() {
        return tokenId == null ? null : tokenId.clone();
    }

    public long amount() {
        return amount;
    }

    public int decimals() {
        return decimals;
    }

    public String symbol() {
        return symbol;
    }

    public String name() {
        return name;
    }

    // ---- encoding ----

    public static byte[] encodeMint(long amount, int decimals, String symbol, String name) {
        byte[] sym = symbol.getBytes(StandardCharsets.UTF_8);
        byte[] nm = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + 1 + 1 + sym.length + 1 + nm.length);
        buffer.putLong(amount);
        buffer.put((byte) decimals);
        buffer.put((byte) sym.length);
        buffer.put(sym);
        buffer.put((byte) nm.length);
        buffer.put(nm);
        return buffer.array();
    }

    public static byte[] encodeAmount(byte[] tokenId, long amount) {
        ByteBuffer buffer = ByteBuffer.allocate(32 + 8);
        buffer.put(tokenId);
        buffer.putLong(amount);
        return buffer.array();
    }

    // ---- decoding ----

    /**
     * Parses the payload for {@code kind}. {@code maxSymbol}/{@code maxName} bound the
     * strings; {@code maxDecimals} bounds decimals. Throws {@link IllegalArgumentException}
     * on any malformation (the caller maps that to {@code TOKEN_PAYLOAD_INVALID}).
     */
    public static TokenPayload decode(TransactionKind kind, byte[] data,
                                      int maxSymbol, int maxName, int maxDecimals) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        try {
            TokenPayload payload = switch (kind) {
                case TOKEN_MINT -> decodeMint(buffer, maxSymbol, maxName, maxDecimals);
                case TOKEN_TRANSFER, TOKEN_BURN -> decodeAmount(buffer);
                default -> throw new IllegalArgumentException("not a token kind: " + kind);
            };
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("trailing bytes in token payload");
            }
            return payload;
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated token payload", e);
        }
    }

    private static TokenPayload decodeMint(ByteBuffer buffer, int maxSymbol, int maxName, int maxDecimals) {
        long amount = buffer.getLong();
        if (amount <= 0) {
            throw new IllegalArgumentException("mint amount must be positive");
        }
        int decimals = buffer.get() & 0xFF;
        if (decimals > maxDecimals) {
            throw new IllegalArgumentException("decimals out of range: " + decimals);
        }
        String symbol = readString(buffer, maxSymbol, "symbol");
        String name = readString(buffer, maxName, "name");
        return new TokenPayload(null, amount, decimals, symbol, name);
    }

    private static TokenPayload decodeAmount(ByteBuffer buffer) {
        byte[] tokenId = new byte[32];
        buffer.get(tokenId);
        long amount = buffer.getLong();
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return new TokenPayload(tokenId, amount, 0, null, null);
    }

    private static String readString(ByteBuffer buffer, int max, String what) {
        int len = buffer.get() & 0xFF;
        if (len > max) {
            throw new IllegalArgumentException(what + " too long: " + len);
        }
        byte[] s = new byte[len];
        buffer.get(s);
        String value = new String(s, StandardCharsets.UTF_8);
        if (value.getBytes(StandardCharsets.UTF_8).length != len) {
            throw new IllegalArgumentException(what + " is not valid UTF-8");
        }
        return value;
    }
}
