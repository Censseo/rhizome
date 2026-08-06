package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.dto.TransactionDto;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SignatureScheme;

/**
 * Signature-scheme agility on the transaction wire form: the leading scheme byte selects field
 * widths, an unknown scheme is rejected rather than defaulted, and the scheme cannot be swapped on
 * a signed transaction because the signed preimage binds it through the sender's address.
 */
class TransactionSchemeAgilityTest {

    private static final byte[] COMMITMENT = commitment();

    private static byte[] commitment() {
        byte[] c = new byte[SignatureScheme.COMMITMENT_SIZE];
        java.util.Arrays.fill(c, (byte) 0x5a);
        return c;
    }

    private static TransactionImpl signed(AsymmetricCipherKeyPair pair, SignatureScheme scheme, byte[] pq) {
        PublicKey key = PublicKey.of(pair.getPublic());
        TransactionImpl tx = TransactionImpl.builder()
            .from(PublicAddress.of(key, scheme, pq))
            .to(PublicAddress.random())
            .amount(new TransactionAmount(1234))
            .fee(new TransactionAmount(7))
            .timestamp(1_750_000_000_000L)
            .chainId(3)
            .nonce(11)
            .signingKey(key)
            .scheme(scheme)
            .pqCommitment(pq)
            .build();
        tx.sign(new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate()));
        return tx;
    }

    @Test
    void postQuantumCommittedTransactionRoundTripsOnTheWire() {
        TransactionImpl tx = signed(generateKeyPair(), SignatureScheme.ED25519_PQC, COMMITMENT);
        byte[] bytes = tx.serialize().toBuffer();

        assertEquals(SignatureScheme.ED25519_PQC.code(), bytes[0]);
        assertEquals(TransactionDto.fixedSize(SignatureScheme.ED25519_PQC) + 1, bytes.length);

        TransactionImpl restored = (TransactionImpl) Transaction.of(
            BinarySerializable.fromBuffer(bytes, TransactionDto.class));
        assertEquals(SignatureScheme.ED25519_PQC, restored.scheme());
        assertArrayEquals(COMMITMENT, restored.pqCommitment());
        assertEquals(tx.from(), restored.from());
        assertTrue(restored.signatureValid());
        assertTrue(restored.senderBindingValid());
        assertArrayEquals(bytes, restored.serialize().toBuffer());
    }

    @Test
    void postQuantumCommittedTransactionRoundTripsThroughJson() {
        TransactionImpl tx = signed(generateKeyPair(), SignatureScheme.ED25519_PQC, COMMITMENT);
        TransactionImpl restored = (TransactionImpl) Transaction.of(tx.toJson());

        assertEquals(SignatureScheme.ED25519_PQC, restored.scheme());
        assertArrayEquals(COMMITMENT, restored.pqCommitment());
        assertEquals(tx.from(), restored.from());
        assertTrue(restored.signatureValid());
        assertTrue(restored.senderBindingValid());
    }

    /**
     * The security property the whole design rests on. A signature covers {@code hashContents},
     * which contains {@code from}, which is derived from (key, scheme, commitment) — so an attacker
     * who re-labels a signed transaction under another scheme keeps a <em>valid signature</em> but
     * breaks the sender binding, and both MemPool and Executor reject on exactly that check. If this
     * ever passes, the scheme byte is malleable and a post-quantum commitment can be stripped.
     */
    @Test
    void schemeDowngradeKeepsTheSignatureButBreaksTheSenderBinding() {
        AsymmetricCipherKeyPair pair = generateKeyPair();
        TransactionImpl original = signed(pair, SignatureScheme.ED25519_PQC, COMMITMENT);
        assertTrue(original.senderBindingValid());

        // Strip the commitment and re-label as classical Ed25519, keeping every signed field.
        TransactionImpl downgraded = TransactionImpl.builder()
            .from(original.from())
            .to(original.to())
            .amount(original.amount())
            .fee(original.fee())
            .timestamp(original.timestamp())
            .chainId(original.chainId())
            .nonce(original.nonce())
            .signingKey(original.signingKey())
            .signature(original.signature())
            .scheme(SignatureScheme.ED25519)
            .pqCommitment(new byte[0])
            .build();

        assertTrue(downgraded.signatureValid(), "the signature itself is untouched");
        assertFalse(downgraded.senderBindingValid(), "but the sender no longer derives to `from`");
    }

    /** The mirror case: claiming a commitment that was never signed for also breaks the binding. */
    @Test
    void forgingACommitmentBreaksTheSenderBinding() {
        AsymmetricCipherKeyPair pair = generateKeyPair();
        TransactionImpl original = signed(pair, SignatureScheme.ED25519, null);
        TransactionImpl upgraded = TransactionImpl.builder()
            .from(original.from())
            .to(original.to())
            .amount(original.amount())
            .fee(original.fee())
            .timestamp(original.timestamp())
            .chainId(original.chainId())
            .nonce(original.nonce())
            .signingKey(original.signingKey())
            .signature(original.signature())
            .scheme(SignatureScheme.ED25519_PQC)
            .pqCommitment(COMMITMENT)
            .build();

        assertTrue(upgraded.signatureValid());
        assertFalse(upgraded.senderBindingValid());
    }

    @Test
    void unknownSchemeByteIsRejectedOnDecode() {
        byte[] bytes = signed(generateKeyPair(), SignatureScheme.ED25519, null).serialize().toBuffer();
        for (int code : new int[] {0x02, 0x0F, 0x80, 0xFF}) {
            byte[] tampered = bytes.clone();
            tampered[0] = (byte) code;
            assertThrows(IllegalArgumentException.class,
                () -> BinarySerializable.fromBuffer(tampered, TransactionDto.class),
                "scheme 0x" + Integer.toHexString(code) + " must be rejected, not defaulted");
        }
    }

    /**
     * Coinbase and BOX_COLLECT carry no signature, so a non-default scheme would be pure
     * malleability — 32 free commitment bytes that change the wire form without changing meaning.
     */
    @Test
    void selfAuthorizedTransactionMustUseTheDefaultScheme() {
        byte[] bytes = signed(generateKeyPair(), SignatureScheme.ED25519_PQC, COMMITMENT)
            .serialize().toBuffer();
        // Flip isTransactionFee, whose offset follows the scheme-dependent authorisation fields.
        int feeFlagOffset = SignatureScheme.ED25519_PQC.wireAuthBytes()
            + Long.BYTES + PublicAddress.SIZE + Long.BYTES + Long.BYTES;
        assertEquals(0, bytes[feeFlagOffset], "expected the isTransactionFee flag at this offset");
        bytes[feeFlagOffset] = 1;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> BinarySerializable.fromBuffer(bytes, TransactionDto.class));
        assertTrue(e.getMessage().contains("self-authorized"), e.getMessage());
    }

    @Test
    void sizeBytesMatchesTheSerializedLengthForEveryScheme() {
        for (SignatureScheme scheme : SignatureScheme.values()) {
            byte[] pq = scheme.commitsToPostQuantumKey() ? COMMITMENT : null;
            TransactionImpl tx = signed(generateKeyPair(), scheme, pq);
            assertEquals(tx.serialize().getSize(), tx.sizeBytes(), scheme + " size mismatch");
            assertEquals(tx.serialize().toBuffer().length, tx.sizeBytes(), scheme + " size mismatch");
        }
    }

    /**
     * A classical transaction's JSON must be unchanged by scheme agility, so the dashboard, explorer
     * and wallet CLI keep working without learning a new field.
     */
    @Test
    void classicalTransactionJsonCarriesNoSchemeFields() {
        var json = signed(generateKeyPair(), SignatureScheme.ED25519, null).toJson();
        assertFalse(json.has("sigScheme"));
        assertFalse(json.has("pqCommitment"));
    }

    @Test
    void jsonRejectsUnknownSchemeAndMismatchedCommitmentWidth() {
        TransactionImpl tx = signed(generateKeyPair(), SignatureScheme.ED25519_PQC, COMMITMENT);

        var unknown = tx.toJson();
        unknown.put("sigScheme", "ML_DSA_44");
        assertThrows(IllegalArgumentException.class, () -> Transaction.of(unknown));

        var shortCommitment = tx.toJson();
        shortCommitment.put("pqCommitment", "5a5a5a5a");
        assertThrows(IllegalArgumentException.class, () -> Transaction.of(shortCommitment));

        var strayCommitment = signed(generateKeyPair(), SignatureScheme.ED25519, null).toJson();
        strayCommitment.put("pqCommitment", rhizome.core.common.Utils.bytesToHex(COMMITMENT));
        assertThrows(IllegalArgumentException.class, () -> Transaction.of(strayCommitment));
    }

    /**
     * An inconsistent in-memory transaction must fail the binding check rather than throw: consensus
     * paths call this inside a validation branch, and an exception there would abort a whole block
     * instead of rejecting one transaction.
     */
    @Test
    void inconsistentSchemeAndCommitmentFailClosedInsteadOfThrowing() {
        PublicKey key = PublicKey.of(generateKeyPair().getPublic());
        TransactionImpl tx = TransactionImpl.builder()
            .from(PublicAddress.of(key))
            .to(PublicAddress.random())
            .signingKey(key)
            .scheme(SignatureScheme.ED25519_PQC)
            .pqCommitment(new byte[7]) // wrong width for the declared scheme
            .build();

        assertEquals(PublicAddress.empty(), tx.derivedSenderAddress());
        assertFalse(tx.senderBindingValid());
    }
}
