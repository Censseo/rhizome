package rhizome.core.token;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import rhizome.core.ledger.PublicAddress;

/**
 * Immutable-except-supply metadata of a native token: its unique id, minter,
 * human-readable symbol/name, decimals, and current total supply. A native token is
 * a protocol-level fungible asset — minted, transferred and burned by dedicated
 * transaction kinds, with no contract and no gas — so a token launch is one
 * transaction (the "cheap token launch" goal).
 *
 * <p>The id is derived like a box id or contract address:
 * {@code SHA-256(minter ‖ nonce ‖ "rztoken")}, globally unique because the account
 * nonce never repeats.
 */
public final class TokenMeta {

    private static final byte[] ID_DOMAIN = {'r', 'z', 't', 'o', 'k', 'e', 'n'};

    private final byte[] id;
    private final PublicAddress minter;
    private final String symbol;
    private final String name;
    private final int decimals;
    private final long totalSupply;
    private final long createdHeight;

    public TokenMeta(byte[] id, PublicAddress minter, String symbol, String name,
                     int decimals, long totalSupply, long createdHeight) {
        if (id == null || id.length != 32) {
            throw new IllegalArgumentException("token id must be 32 bytes");
        }
        this.id = id.clone();
        this.minter = minter;
        this.symbol = symbol;
        this.name = name;
        this.decimals = decimals;
        this.totalSupply = totalSupply;
        this.createdHeight = createdHeight;
    }

    /** Deterministic token id: {@code SHA-256(minter ‖ nonce ‖ "rztoken")}. */
    public static byte[] deriveId(PublicAddress minter, long nonce) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(minter.toBytes());
            sha.update(ByteBuffer.allocate(Long.BYTES).putLong(nonce).array());
            sha.update(ID_DOMAIN);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public byte[] id() {
        return id.clone();
    }

    public PublicAddress minter() {
        return minter;
    }

    public String symbol() {
        return symbol;
    }

    public String name() {
        return name;
    }

    public int decimals() {
        return decimals;
    }

    public long totalSupply() {
        return totalSupply;
    }

    public long createdHeight() {
        return createdHeight;
    }

    /** A copy with a new total supply (a burn reduces it; nothing else changes). */
    public TokenMeta withSupply(long newSupply) {
        return new TokenMeta(id, minter, symbol, name, decimals, newSupply, createdHeight);
    }

    public byte[] serialize() {
        byte[] symbolBytes = symbol.getBytes(StandardCharsets.UTF_8);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(32 + PublicAddress.SIZE + 1 + 8 + 8
            + 1 + symbolBytes.length + 1 + nameBytes.length);
        buffer.put(id);
        buffer.put(minter.toBytes());
        buffer.put((byte) decimals);
        buffer.putLong(totalSupply);
        buffer.putLong(createdHeight);
        buffer.put((byte) symbolBytes.length);
        buffer.put(symbolBytes);
        buffer.put((byte) nameBytes.length);
        buffer.put(nameBytes);
        return buffer.array();
    }

    public static TokenMeta deserialize(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try {
            byte[] id = new byte[32];
            buffer.get(id);
            byte[] minter = new byte[PublicAddress.SIZE];
            buffer.get(minter);
            int decimals = buffer.get() & 0xFF;
            long totalSupply = buffer.getLong();
            long createdHeight = buffer.getLong();
            String symbol = readString(buffer);
            String name = readString(buffer);
            return new TokenMeta(id, PublicAddress.of(minter), symbol, name, decimals, totalSupply, createdHeight);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated token meta", e);
        }
    }

    private static String readString(ByteBuffer buffer) {
        int len = buffer.get() & 0xFF;
        byte[] s = new byte[len];
        buffer.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TokenMeta other
            && Arrays.equals(id, other.id)
            && decimals == other.decimals
            && totalSupply == other.totalSupply
            && createdHeight == other.createdHeight
            && minter.equals(other.minter)
            && symbol.equals(other.symbol)
            && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(id);
    }

    @Override
    public String toString() {
        return "TokenMeta[" + symbol + ", supply=" + totalSupply + ", decimals=" + decimals + "]";
    }
}
