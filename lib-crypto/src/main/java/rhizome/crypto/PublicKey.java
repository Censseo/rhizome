package rhizome.crypto;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

import static rhizome.crypto.Hex.bytesToHex;
import static rhizome.crypto.Hex.hexStringToByteArray;

import java.util.Arrays;
import java.util.Optional;

// A final class rather than a record: records cannot hold per-instance memo state, and the
// canonical 32-byte encoding is memoised here (see encoded()) so equals/hashCode stop paying
// an Ed25519PublicKeyParameters.getEncoded() clone per comparison (audit). The BouncyCastle
// parameter is an implementation detail: the public surface is bytes and hex, so no caller
// — and no module — reaches a BC type through this class (constat 43c).
public final class PublicKey {

    public static final int SIZE = 32;

    private final Optional<Ed25519PublicKeyParameters> key;

    // Lazy memo of the canonical encoding. volatile + benign race: every racer computes the
    // identical 32 bytes, so a duplicate computation is harmless. Never exposed without a
    // clone (see toBytes()).
    private volatile byte[] encoded;

    private PublicKey(Optional<Ed25519PublicKeyParameters> key) {
        this.key = key;
    }

    public static PublicKey empty() {
        return new PublicKey(Optional.empty());
    }

    public static PublicKey of(AsymmetricKeyParameter keyParameter) {
        if (keyParameter == null) {
            return empty();
        }
        if (keyParameter instanceof Ed25519PublicKeyParameters) {
            return new PublicKey(Optional.of((Ed25519PublicKeyParameters) keyParameter));
        }
        return empty();
    }

    public static PublicKey of(byte[] bytes) {
        if (bytes == null || bytes.length != SIZE || isZeroFilled(bytes)) {
            return empty();
        }
        // Strictly validate the encoding before it becomes a signer: the y coordinate must be
        // canonical (< 2^255-19), the point must lie on the curve, and it must not be one of the
        // known small-order encodings. A small-order key (order 1/2/4/8) admits "signatures" that
        // verify under ANY message, so accepting one lets an attacker forge the signer binding of
        // a transaction (audit F1). Fail closed to empty(), identically to the all-zero key, so
        // checkSignature returns false rather than throwing on attacker-controlled wire bytes.
        if (!Ed25519.validatePublicKeyPartial(bytes, 0) || isSmallOrder(bytes)) {
            return empty();
        }
        return new PublicKey(Optional.of(new Ed25519PublicKeyParameters(bytes, 0)));
    }

    public static PublicKey of(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            return empty();
        }
        if (hexString.length() != 64) {
            throw new IllegalArgumentException("Invalid public key string length. Expected 64 characters for a 32-byte key.");
        }
        // Route through of(byte[]) so the all-zero key maps to empty() identically for JSON and
        // binary decoding. Otherwise the same wire bytes derive two different `from` addresses
        // depending on the codec, splitting the signer-binding check (audit).
        return of(hexStringToByteArray(hexString));
    }

    private byte[] encoded() {
        byte[] e = encoded;
        if (e == null) {
            e = key.map(Ed25519PublicKeyParameters::getEncoded).orElseGet(() -> new byte[SIZE]);
            encoded = e;
        }
        return e;
    }

    public String toHexString() {
        return key.isPresent() ? bytesToHex(encoded()) : "";
    }

    public byte[] toBytes() {
        // Defensive copy off the memo: callers must not be able to mutate the encoding that
        // equals/hashCode rely on.
        return encoded().clone();
    }

    /** Whether this key is the empty (absent) value, e.g. a decoded all-zero signing key. */
    public boolean isEmpty() {
        return key.isEmpty();
    }

    /** Whether this key carries a real Ed25519 point. */
    public boolean isPresent() {
        return key.isPresent();
    }

    // Ed25519PublicKeyParameters implements neither equals nor hashCode, so compare the
    // canonical 32-byte encodings — via the memo, so no clone per comparison.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PublicKey other)) {
            return false;
        }
        return Arrays.equals(encoded(), other.encoded());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encoded());
    }

    private static boolean isZeroFilled(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The seven remaining canonical small-order Ed25519 encodings (orders 1, 2, 4 and 8) from the
     * RFC 8032 / libsodium blocklist; the eighth (all-zero) is already mapped to {@link #empty()}
     * above. BouncyCastle's partial validation already rejects these, but the denylist is kept
     * explicit so the security property does not depend on a library's internal checks (audit F1).
     */
    private static final byte[][] SMALL_ORDER_DENYLIST = {
        hexStringToByteArray("0100000000000000000000000000000000000000000000000000000000000000"),
        hexStringToByteArray("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05"),
        hexStringToByteArray("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85"),
        hexStringToByteArray("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a"),
        hexStringToByteArray("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa"),
        hexStringToByteArray("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
        hexStringToByteArray("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
    };

    private static boolean isSmallOrder(byte[] bytes) {
        for (byte[] denied : SMALL_ORDER_DENYLIST) {
            if (java.util.Arrays.equals(bytes, denied)) {
                return true;
            }
        }
        return false;
    }
}