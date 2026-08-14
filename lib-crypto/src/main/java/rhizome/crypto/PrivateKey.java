package rhizome.crypto;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

import static rhizome.crypto.Hex.bytesToHex;
import static rhizome.crypto.Hex.hexStringToByteArray;

/**
 * An Ed25519 private key.
 *
 * <p>The BouncyCastle parameter is an implementation detail: the public surface is bytes, hex and
 * {@link #publicKey()}. It used to be a {@code record} whose accessor handed the raw
 * {@code Ed25519PrivateKeyParameters} to anyone who asked, which made the library type part of
 * this project's API — the same leak {@link PublicKey} closed, and the last one keeping the
 * cryptographic extension point (constat 43) confined to the decoder. Every caller that unwrapped
 * a key wanted one thing, its public half, and that now goes through the scheme's algorithm like
 * signing and verification already do.
 */
public final class PrivateKey {

    public static final int SIZE = 32;

    private final Ed25519PrivateKeyParameters key;

    private PrivateKey(Ed25519PrivateKeyParameters key) {
        this.key = key;
    }

    public static PrivateKey of(byte[] bytes) {
        if (bytes == null || bytes.length != SIZE) {
            throw new IllegalArgumentException(
                "Invalid private key length: expected " + SIZE + " bytes, got "
                    + (bytes == null ? "null" : bytes.length));
        }
        return new PrivateKey(new Ed25519PrivateKeyParameters(bytes, 0));
    }

    /**
     * Package-private: the only caller is {@link Crypto#generateKeyPairTyped}, adapting the
     * generator's raw pair. Exposing it would put a BouncyCastle type back in this module's API
     * one factory below the field this class just made private.
     */
    static PrivateKey of(AsymmetricKeyParameter keyParameter) {
        if (keyParameter instanceof Ed25519PrivateKeyParameters ed25519) {
            return new PrivateKey(ed25519);
        }
        return null;
    }

    // Residual exposure: the hex String (and the String returned by toHexString) stays on the
    // heap until GC — seed-handling callers should prefer of(byte[]) and wipe their own copy.
    // No destroy() is offered: Ed25519PrivateKeyParameters keeps a private internal copy that
    // BouncyCastle 1.78 exposes no wipe for, so only the mutable buffers created here are zeroed.
    public static PrivateKey of(String hexString) {
        if ("".equals(hexString)) {
            return null;
        }
        if (hexString.length() != 64) {
            throw new IllegalArgumentException("Invalid private key string length. Expected 64 characters for a 32-byte key.");
        }
        byte[] seed = hexStringToByteArray(hexString);
        try {
            return new PrivateKey(new Ed25519PrivateKeyParameters(seed, 0));
        } finally {
            java.util.Arrays.fill(seed, (byte) 0);
        }
    }

    /**
     * The public key for this private key, derived through the scheme's algorithm table rather
     * than by unwrapping into a BouncyCastle parameter (constat 43). The bytes are the ones
     * {@code key().generatePublicKey()} used to return.
     */
    public PublicKey publicKey() {
        return PublicKey.of(SignatureScheme.ED25519.algorithm().derivePublicKey(toBytes()));
    }

    public String toHexString() {
        if (key == null) {
            return "";
        }
        byte[] encoded = key.getEncoded();
        try {
            return encoded == null ? "" : bytesToHex(encoded);
        } finally {
            if (encoded != null) {
                java.util.Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    /** A fresh copy of the 32-byte seed; the caller owns it and should wipe it when done. */
    public byte[] toBytes() {
        return key.getEncoded();
    }

    /**
     * Value equality, in constant time.
     *
     * <p>The record this replaced inherited {@code Ed25519PrivateKeyParameters.equals}, which
     * BouncyCastle does not override — so two keys built from the SAME seed compared unequal, and
     * any caller pairing that with a bytewise equals broke the equals/hashCode contract (as
     * {@code UserImpl} did: equal users hashed differently, so a HashSet kept duplicates).
     *
     * <p>{@link java.security.MessageDigest#isEqual} rather than {@code Arrays.equals}: the
     * latter short-circuits on the first differing byte, leaking through timing how many leading
     * bytes of a candidate secret matched.
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof PrivateKey other)) {
            return false;
        }
        if (key == null || other.key == null) {
            return key == other.key;
        }
        return java.security.MessageDigest.isEqual(toBytes(), other.toBytes());
    }

    @Override
    public int hashCode() {
        return key == null ? 0 : java.util.Arrays.hashCode(toBytes());
    }

    // Never leak key material into logs/stack traces: the record default would defer to
    // Ed25519PrivateKeyParameters.toString(), whose output is a library implementation detail
    // that must never become a secret-disclosure channel. Redact unconditionally (audit).
    @Override
    public String toString() {
        return "PrivateKey[REDACTED]";
    }
}
