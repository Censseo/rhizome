package rhizome.crypto;

import java.security.SecureRandom;

public interface SimpleHashType {

    int getSize();
    byte[] toBytes();

    default void checkSize(byte[] bytes) {
        if (bytes.length != getSize()) {
            throw new IllegalArgumentException("Invalid address size");
        }
    }

    public static byte[] empty(int size) {
        return new byte[size];
    }

    public static byte[] random(int size) {
        var random = new byte[size];
        new SecureRandom().nextBytes(random);
        return random;
    }
}
