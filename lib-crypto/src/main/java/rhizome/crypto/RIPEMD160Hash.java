package rhizome.crypto;

import java.util.Arrays;

import static rhizome.crypto.Hex.hexStringToByteArray;

public record RIPEMD160Hash(byte[] hash) {

    // Defensive copies on the way in AND out, exactly like SHA256Hash (audit): aliasing the
    // caller's array lets post-wrap mutation corrupt equals/hashCode of every map keyed on it.
    public RIPEMD160Hash {
        hash = hash.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }

    public static RIPEMD160Hash empty() {
        return new RIPEMD160Hash(Crypto.emptyBytes(SIZE));
    }

    public static RIPEMD160Hash random() {
        return new RIPEMD160Hash(Crypto.randomBytes(SIZE));
    }

    public static RIPEMD160Hash of(byte[] bytes) {
        return new RIPEMD160Hash(bytes);
    }

    public static RIPEMD160Hash of(String hexString) {
        // Same contract as SHA256Hash.of(String): a wrong-length hex string is malformed input,
        // not a shorter hash.
        if (hexString.length() != SIZE * 2) {
            throw new IllegalArgumentException("Invalid RIPEMD160 hash string length. Expected "
                + (SIZE * 2) + " characters for a " + SIZE + "-byte hash.");
        }
        return RIPEMD160Hash.of(hexStringToByteArray(hexString));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RIPEMD160Hash o)) {
            return false;
        }
        // Field access, not o.hash(): the accessor defensively clones, equality must not.
        return Arrays.equals(hash, o.hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    public static final int SIZE = 20;

    public byte[] toBytes() {
        return hash.clone();
    }
}
