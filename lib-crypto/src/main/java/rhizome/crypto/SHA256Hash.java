package rhizome.crypto;

import java.util.Arrays;

import static rhizome.crypto.Hex.bytesToHex;
import static rhizome.crypto.Hex.hexStringToByteArray;

public record SHA256Hash(byte[] hash) implements SimpleHashType, Comparable<SHA256Hash> {

    // Defensive copies on the way in AND out (audit): the record used to alias the caller's
    // array, so mutating the source bytes after wrapping silently changed the value — and with
    // it the equals/hashCode of every map keyed on it. That is live in Crypto.PufferfishCacheKey,
    // where the wrapped preimage is attacker-controlled bytes the caller retains.
    public SHA256Hash {
        hash = hash.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }

    public static SHA256Hash empty() {
        return new SHA256Hash(SimpleHashType.empty(SIZE));
    }

    public static SHA256Hash random() {
        return new SHA256Hash(SimpleHashType.random(SIZE));
    }

    public static SHA256Hash of(byte[] bytes) {
        return new SHA256Hash(bytes);
    }

    public static SHA256Hash of(String hexString) {
        // A hex-decoded hash of the wrong length is a malformed input, not a shorter hash —
        // reject it like PublicAddress.of(String) does instead of silently wrapping it. Note
        // of(byte[]) deliberately stays length-agnostic: Crypto.PUFFERFISH wraps arbitrary-length
        // PoW preimages in SHA256Hash for its cache key.
        if (hexString.length() != SIZE * 2) {
            throw new IllegalArgumentException("Invalid SHA256 hash string length. Expected "
                + (SIZE * 2) + " characters for a " + SIZE + "-byte hash.");
        }
        return SHA256Hash.of(hexStringToByteArray(hexString));
    }

    public String toHexString() {
        return bytesToHex(hash);
    }

    public byte[] toBytes() {
        return hash.clone();
    }

    /**
     * The backing array WITHOUT a defensive copy. Reserved for hot paths that consume the
     * bytes immediately and locally (arraycopy into a preimage, {@code MessageDigest.update},
     * a JNI call that copies before returning): <b>do not mutate, do not retain beyond the
     * call</b> — the array is shared with the record, so writing it corrupts every map keyed
     * on this value, and retaining it aliases state the owner may not expect to share. The
     * cloning {@link #hash()}/{@link #toBytes()} accessors stay the default API; anything that
     * stores the bytes in a structure or returns them to the outside must keep using those.
     */
    public byte[] raw() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SHA256Hash o)) {
            return false;
        }
        // Field access, not o.hash(): the accessor defensively clones, equality must not.
        return Arrays.equals(hash, o.hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    @Override
    public int compareTo(SHA256Hash o) {
        // Arrays.compare is total: it handles a length mismatch (shorter sorts first) instead of
        // indexing o.hash past its end. The old loop ran to this.hash.length and read o.hash[i],
        // throwing ArrayIndexOutOfBounds when o was shorter — SHA256Hash is also used in Crypto as a
        // wrapper over arbitrary-length PoW preimages, so equal length is not guaranteed (audit S9).
        // For the 32-byte consensus identities this is byte-for-byte the previous signed ordering.
        return java.util.Arrays.compare(this.hash, o.hash);
    }

    public static final int SIZE = 32;
    @Override
    public int getSize() {
        return SIZE;
    }
}
