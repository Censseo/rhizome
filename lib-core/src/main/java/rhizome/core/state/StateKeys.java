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

    private StateKeys() {}

    /** The SMT key for {@code rawKey} in {@code domain}: {@code SHA-256(domain ‖ rawKey)}. */
    public static byte[] key(byte domain, byte[] rawKey) {
        MessageDigest sha = sha256();
        sha.update(domain);
        sha.update(rawKey);
        return sha.digest();
    }

    /** The leaf value hash for {@code value}: {@code SHA-256(value)}. */
    public static byte[] valueHash(byte[] value) {
        return sha256().digest(value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
