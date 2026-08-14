package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Hex round-trip of {@link PrivateKey}: zeroing the mutable decode/encode buffers must not corrupt the key. */
class PrivateKeyTest {

    @Test
    void hexRoundTripPreservesTheKey() {
        PrivateKey privateKey = Crypto.generateKeyPairTyped().privateKey();
        PrivateKey decoded = PrivateKey.of(privateKey.toHexString());
        assertEquals(privateKey.toHexString(), decoded.toHexString());
        byte[] signature = Crypto.signWithPrivateKey("hello", decoded);
        assertTrue(Crypto.checkSignature("hello", signature, decoded.publicKey()));
    }

    @Test
    void derivedPublicKeyMatchesTheGeneratedPair() {
        // publicKey() goes through the scheme's algorithm instead of unwrapping the key into a
        // BouncyCastle parameter; it must land on exactly the public half the generator produced.
        Crypto.KeyPair pair = Crypto.generateKeyPairTyped();
        assertArrayEquals(pair.publicKey().toBytes(), pair.privateKey().publicKey().toBytes());
    }

    @Test
    void twoKeysFromTheSameSeedAreEqualAndHashAlike() {
        // The record this replaced inherited BouncyCastle's identity equals, so this was FALSE:
        // two keys over the same seed compared unequal while callers compared their bytes, which
        // broke the equals/hashCode contract downstream (UserImpl kept duplicates in a HashSet).
        byte[] seed = Crypto.randomBytes(PrivateKey.SIZE);
        PrivateKey a = PrivateKey.of(seed);
        PrivateKey b = PrivateKey.of(seed.clone());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode(), "equal keys must hash alike");
        // The contract that actually broke downstream: hashing must agree with equality, or a
        // hash container keeps both. (Set.of would REJECT the duplicate rather than fold it.)
        assertEquals(1, new HashSet<>(List.of(a, b)).size(), "a hash set must not keep both");
    }

    @Test
    void keysOverDifferentSeedsAreNotEqual() {
        assertNotEquals(PrivateKey.of(Crypto.randomBytes(PrivateKey.SIZE)),
            PrivateKey.of(Crypto.randomBytes(PrivateKey.SIZE)));
    }

    @Test
    void toStringNeverRevealsKeyMaterial() {
        PrivateKey privateKey = Crypto.generateKeyPairTyped().privateKey();
        assertEquals("PrivateKey[REDACTED]", privateKey.toString());
        // The class is no longer a record, so this is a written override rather than a default
        // the compiler happens to generate — worth pinning that it survived the conversion.
        assertTrue(!privateKey.toString().contains(privateKey.toHexString().substring(0, 8)));
    }
}
