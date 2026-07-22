package rhizome.crypto;

import java.util.Arrays;

public record RIPEMD160Hash(byte[] hash) implements SimpleHashType {
    public static RIPEMD160Hash empty() {
        return new RIPEMD160Hash(SimpleHashType.empty(SIZE));
    }

    public static RIPEMD160Hash random() {
        return new RIPEMD160Hash(SimpleHashType.random(SIZE));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RIPEMD160Hash)) {
            return false;
        }
        return Arrays.equals(hash, ((RIPEMD160Hash) other).hash());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    public static final int SIZE = 20;
    @Override
    public int getSize() {
        return SIZE;
    }

    @Override
    public byte[] toBytes() {
        return hash;
    }
}
