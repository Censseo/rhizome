package rhizome.core.state;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Maps a domain-tagged raw key to the 32-byte {@link SparseMerkleTree} key, and a value's
 * bytes to its leaf hash. The domain byte keeps the four committed state domains disjoint
 * in one tree; hashing the raw key also spreads keys uniformly (short, correlated raw keys
 * like sequential addresses would otherwise cluster).
 */
public final class StateKeys {

    /** Native PDN balance: raw key = address(25), value = balance(8, big-endian). */
    public static final byte LEDGER = 0x01;
    /** Data box: raw key = boxId(32), value = serialized box. */
    public static final byte BOX = 0x02;
    /** Token metadata: raw key = tokenId(32), value = serialized meta. */
    public static final byte TOKEN_META = 0x03;
    /** Token balance: raw key = tokenId(32) ‖ address(25), value = amount(8, big-endian). */
    public static final byte TOKEN_BALANCE = 0x04;
    /** Contract code: raw key = contract address(25), value = WASM code. */
    public static final byte CONTRACT_CODE = 0x05;
    /** Contract storage: raw key = contract(25) ‖ key, value = stored bytes. */
    public static final byte CONTRACT_STORAGE = 0x06;
    /** Account nonce: raw key = address(25), value = next-expected nonce(8, big-endian). */
    public static final byte ACCOUNT_NONCE = 0x07;

    /** Width of a token id ({@link #TOKEN_META}'s raw key, and the leading half of {@link #TOKEN_BALANCE}'s). */
    public static final int TOKEN_ID_BYTES = 32;

    private StateKeys() {}

    /**
     * {@code a ‖ b}. The composite raw-key layout for {@link #TOKEN_BALANCE} and
     * {@link #CONTRACT_STORAGE} — both committed-state encoders (block application in
     * {@code BlockStateChanges}, snapshot export/import in {@code DomainStateAdapter}) share
     * this one function rather than each concatenating by hand, since transposing the two
     * halves produces an equally well-formed key that silently forks the state root.
     */
    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** {@link #TOKEN_BALANCE} raw key: {@code tokenId ‖ address}. */
    public static byte[] tokenBalanceKey(byte[] tokenId, byte[] address) {
        return concat(tokenId, address);
    }

    /**
     * Splits a {@link #TOKEN_BALANCE} raw key back into {@code {tokenId, address}} — the exact
     * inverse of {@link #tokenBalanceKey}, so encode and decode cannot drift independently.
     */
    public static byte[][] splitTokenBalanceKey(byte[] key) {
        return new byte[][] {
            java.util.Arrays.copyOfRange(key, 0, TOKEN_ID_BYTES),
            java.util.Arrays.copyOfRange(key, TOKEN_ID_BYTES, key.length),
        };
    }

    /** The SMT key for {@code rawKey} in {@code domain}: {@code SHA-256(domain ‖ rawKey)}. */
    public static byte[] key(byte domain, byte[] rawKey) {
        MessageDigest sha = digest();
        sha.update(domain);
        sha.update(rawKey);
        return sha.digest();
    }

    /** The leaf value hash for {@code value}: {@code SHA-256(value)}. */
    public static byte[] valueHash(byte[] value) {
        return digest().digest(value);
    }

    // A SHA-256 instance per thread, reset and reused across every StateChange in a block apply.
    // Replaces MessageDigest.getInstance (a JCA provider lookup) per call on the consensus-critical
    // accumulate path with a reset(), mirroring SparseMerkleTree.DIGEST (audit P2). Per-thread so the
    // engine's single writer and concurrent API-thread proof reads never share one Digest. The digest
    // OUTPUT is byte-for-byte identical; only the allocation/lookup changes.
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    });

    private static MessageDigest digest() {
        MessageDigest md = DIGEST.get();
        md.reset();
        return md;
    }
}
