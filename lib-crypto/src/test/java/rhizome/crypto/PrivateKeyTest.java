package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Hex round-trip of {@link PrivateKey}: zeroing the mutable decode/encode buffers must not corrupt the key. */
class PrivateKeyTest {

    @Test
    void hexRoundTripPreservesTheKey() {
        PrivateKey privateKey = PrivateKey.of(Crypto.generateKeyPair().getPrivate());
        PrivateKey decoded = PrivateKey.of(privateKey.toHexString());
        assertEquals(privateKey.toHexString(), decoded.toHexString());
        byte[] signature = Crypto.signWithPrivateKey("hello", decoded);
        assertTrue(Crypto.checkSignature("hello", signature, PublicKey.of(decoded.key().generatePublicKey())));
    }
}
