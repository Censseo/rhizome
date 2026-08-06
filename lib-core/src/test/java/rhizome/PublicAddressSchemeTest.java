package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SignatureScheme;

/**
 * Address-format invariants for signature-scheme agility: the version byte is a real discriminant,
 * it is covered by the checksum, and a post-quantum commitment is bound into the address body.
 */
class PublicAddressSchemeTest {

    private static PublicKey key() {
        return PublicKey.of(generateKeyPair().getPublic());
    }

    private static byte[] commitment(byte fill) {
        byte[] c = new byte[SignatureScheme.COMMITMENT_SIZE];
        java.util.Arrays.fill(c, fill);
        return c;
    }

    /**
     * The load-bearing fix: the checksum covers the version byte. If it covered only the body, an
     * address could be re-labelled with another scheme's version byte and still validate — a
     * downgrade primitive that would let a post-quantum-committed address be presented as a plain
     * Ed25519 one. Every flipped version byte must be detectable.
     */
    @Test
    void checksumCoversTheVersionByte() {
        PublicAddress address = PublicAddress.of(key());
        assertTrue(address.isValidChecksum());

        for (int version = 1; version < 256; version++) {
            byte[] tampered = address.toBytes();
            tampered[0] = (byte) version;
            assertFalse(PublicAddress.of(tampered).isValidChecksum(),
                "version byte 0x" + Integer.toHexString(version) + " must break the checksum");
        }
    }

    @Test
    void checksumStillCatchesBodyTypos() {
        PublicAddress address = PublicAddress.of(key());
        for (int i = 1; i <= 20; i++) {
            byte[] tampered = address.toBytes();
            tampered[i] ^= 0x01;
            assertFalse(PublicAddress.of(tampered).isValidChecksum(), "body byte " + i);
        }
    }

    @Test
    void versionByteNamesTheScheme() {
        PublicKey pk = key();
        assertEquals(SignatureScheme.ED25519.code(),
            PublicAddress.of(pk, SignatureScheme.ED25519, null).version());
        assertEquals(SignatureScheme.ED25519_PQC.code(),
            PublicAddress.of(pk, SignatureScheme.ED25519_PQC, commitment((byte) 0x5a)).version());
    }

    /**
     * The same Ed25519 key must derive a different address under each scheme, otherwise the version
     * byte would be decoration and two schemes would share a balance.
     */
    @Test
    void schemesDeriveDistinctAddressesFromTheSameKey() {
        PublicKey pk = key();
        PublicAddress classical = PublicAddress.of(pk, SignatureScheme.ED25519, null);
        PublicAddress committed = PublicAddress.of(pk, SignatureScheme.ED25519_PQC, commitment((byte) 0x5a));
        assertNotEquals(classical, committed);
        // Distinct in the body, not merely in the version byte: the commitment is hashed in.
        assertNotEquals(classical.toHexString().substring(2, 42), committed.toHexString().substring(2, 42));
    }

    /** Different post-quantum keys must give different addresses, or the commitment binds nothing. */
    @Test
    void differentCommitmentsGiveDifferentAddresses() {
        PublicKey pk = key();
        assertNotEquals(
            PublicAddress.of(pk, SignatureScheme.ED25519_PQC, commitment((byte) 0x01)),
            PublicAddress.of(pk, SignatureScheme.ED25519_PQC, commitment((byte) 0x02)));
    }

    @Test
    void derivationIsDeterministic() {
        PublicKey pk = key();
        byte[] c = commitment((byte) 0x5a);
        assertArrayEquals(
            PublicAddress.of(pk, SignatureScheme.ED25519_PQC, c).toBytes(),
            PublicAddress.of(pk, SignatureScheme.ED25519_PQC, c.clone()).toBytes());
    }

    /**
     * A wrong-length commitment must fail loudly rather than being padded or ignored: silently
     * deriving a different address would surface only as an unspendable balance.
     */
    @Test
    void commitmentWidthMustMatchTheScheme() {
        PublicKey pk = key();
        assertThrows(IllegalArgumentException.class,
            () -> PublicAddress.of(pk, SignatureScheme.ED25519_PQC, null));
        assertThrows(IllegalArgumentException.class,
            () -> PublicAddress.of(pk, SignatureScheme.ED25519_PQC, new byte[31]));
        assertThrows(IllegalArgumentException.class,
            () -> PublicAddress.of(pk, SignatureScheme.ED25519, commitment((byte) 0x5a)));
    }

    @Test
    void defaultFactoryIsTheClassicalScheme() {
        PublicKey pk = key();
        assertEquals(PublicAddress.of(pk, SignatureScheme.ED25519, null), PublicAddress.of(pk));
    }

    @Test
    void absentKeyStillDerivesTheEmptyAddress() {
        // Fail-closed path: callers compare the result against a transaction's `from`, and empty()
        // matches nothing key-derived.
        assertEquals(PublicAddress.empty(), PublicAddress.of(PublicKey.empty()));
        assertEquals(PublicAddress.empty(),
            PublicAddress.of(PublicKey.empty(), SignatureScheme.ED25519_PQC, commitment((byte) 0x5a)));
    }

    @Test
    void isKeyDerivedRecognisesBothSchemesAndRejectsHashDerived() {
        PublicKey pk = key();
        assertTrue(PublicAddress.of(pk).isKeyDerived());
        assertTrue(PublicAddress.of(pk, SignatureScheme.ED25519_PQC, commitment((byte) 0x5a)).isKeyDerived());

        // A contract address: hash-derived, so its version byte is an artefact and its trailing
        // bytes are not a checksum.
        PublicAddress contract = rhizome.core.blockchain.Contracts.deriveAddress(PublicAddress.of(pk), 1);
        assertFalse(contract.isKeyDerived() && contract.isValidChecksum() && contract.version() == 0);
    }
}
