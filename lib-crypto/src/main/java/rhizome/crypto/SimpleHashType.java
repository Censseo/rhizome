package rhizome.crypto;

import java.security.SecureRandom;

public interface SimpleHashType {

    /** One shared, thread-safe CSPRNG rather than a fresh {@code new SecureRandom()} per call (which
     *  can trigger a reseed). Held on a nested holder so the interface stays without static fields. */
    final class Rng {
        private Rng() {}
        static final SecureRandom INSTANCE = new SecureRandom();
    }

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
        Rng.INSTANCE.nextBytes(random);
        return random;
    }
}
