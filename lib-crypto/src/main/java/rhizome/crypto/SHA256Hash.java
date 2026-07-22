package rhizome.crypto;

import java.util.Arrays;

import static rhizome.crypto.Hex.bytesToHex;
import static rhizome.crypto.Hex.hexStringToByteArray;

public record SHA256Hash(byte[] hash) implements SimpleHashType, Comparable<SHA256Hash> {

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
        return SHA256Hash.of(hexStringToByteArray(hexString));
    }

    public String toHexString() {
        return bytesToHex(hash);
    }

    public byte[] toBytes() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SHA256Hash)) {
            return false;
        }
        return Arrays.equals(hash, ((SHA256Hash) other).hash());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    @Override
    public int compareTo(SHA256Hash o) {
        for (int i = 0; i < this.hash.length; i++) {
            int compare = Byte.compare(this.hash[i], o.hash[i]);
            if (compare != 0) {
                return compare;
            }
        }
        return 0;
    }

    public static final int SIZE = 32;
    @Override
    public int getSize() {
        return SIZE;
    }
}
