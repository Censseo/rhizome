package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionSignature;

/**
 * Attacks that try to move value without the owner's key (SIG family — see docs/adversarial/spec.md).
 *
 * <p>Ed25519 signatures are malleable: {@code (R, S)} and {@code (R, S + L)} (L the group order)
 * encode the same mathematical signature over the same message. Pandanite keyed its anti-replay
 * set on a hash that <em>included</em> the signature, so malleating S produced a second identity for
 * the same authorised spend and the transaction executed twice (WHITEPAPER §4.6, upstream #37).
 *
 * <p>Rhizome closes that with two independent properties, both pinned here: the signature is range-
 * checked so the malleated form does not verify at all, and — the load-bearing half — transaction
 * identity is the <em>signature-free</em> content hash, so even a signature variant that did verify
 * would be the same transaction and lose to deduplication rather than double-spend.
 */
class SignatureForgeryAttackTest {

    /** Ed25519's group order L = 2^252 + 27742317777372353535851937790883648493. */
    private static final BigInteger GROUP_ORDER = BigInteger.TWO.pow(252)
        .add(new BigInteger("27742317777372353535851937790883648493"));

    private AdversarialChain chain() {
        return AdversarialChain.testnet().fund("alice", 1_000_000L).build();
    }

    /**
     * Malleates the S half of an Ed25519 signature by adding the group order: the same signature
     * mathematically, a different 64-byte encoding. S is the second 32 bytes, little-endian.
     */
    private static TransactionSignature malleate(TransactionSignature original) {
        byte[] bytes = original.toBytes().clone();
        byte[] sLittleEndian = Arrays.copyOfRange(bytes, 32, 64);
        byte[] sBigEndian = new byte[32];
        for (int i = 0; i < 32; i++) {
            sBigEndian[i] = sLittleEndian[31 - i];
        }
        byte[] malleated = new BigInteger(1, sBigEndian).add(GROUP_ORDER).toByteArray();
        // Back to 32 bytes little-endian, dropping any sign byte the BigInteger encoding added.
        byte[] out = new byte[32];
        int copied = Math.min(32, malleated.length);
        for (int i = 0; i < copied; i++) {
            out[i] = malleated[malleated.length - 1 - i];
        }
        System.arraycopy(out, 0, bytes, 32, 32);
        return TransactionSignature.of(bytes);
    }

    private static Transaction withSignature(Transaction original, TransactionSignature signature) {
        TransactionImpl copy = (TransactionImpl) Transaction.of(original);
        copy.signature(signature);
        return copy;
    }

    /**
     * SIG-01 — the identity property that makes malleability structurally irrelevant: mutating the
     * signature does not produce a second transaction, because the content hash never covered it.
     */
    @Test
    void malleatingTheSignatureDoesNotProduceASecondTransactionIdentity() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        Transaction authorised = chain.account("alice").send(victim, 10_000, 0, 0);
        Transaction malleated = withSignature(authorised, malleate(authorised.signature()));

        assertNotEquals(authorised.signature(), malleated.signature(), "the wire form did change");
        assertEquals(authorised.hashContents(), malleated.hashContents(),
            "but identity is the signature-free content hash, so both are the SAME transaction");
    }

    /**
     * SIG-01 — and the malleated encoding does not even verify: S is range-checked against the
     * group order, so the second form is rejected outright rather than merely deduplicated.
     */
    @Test
    void theMalleatedEncodingDoesNotVerify() {
        AdversarialChain chain = chain();
        Transaction authorised = chain.account("alice").send(PublicAddress.random(), 10_000, 0, 0);

        assertTrue(authorised.signatureValid(), "the honest signature verifies");
        assertFalse(withSignature(authorised, malleate(authorised.signature())).signatureValid(),
            "S + L is out of range and must fail closed");
    }

    /**
     * SIG-02 — the double-spend the malleability defect actually bought: submit the authorised
     * transaction, then its malleated twin in a later block. It must be refused, and the victim's
     * balance must move exactly once.
     */
    @Test
    void theMalleatedTwinCannotSpendTheSameFundsASecondTime() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        Transaction authorised = chain.account("alice").send(victim, 10_000, 0, 0);
        Transaction twin = withSignature(authorised, malleate(authorised.signature()));

        assertEquals(ExecutionStatus.SUCCESS, chain.engine().addBlock(
            chain.forge().transaction(authorised).seal()));
        assertEquals(10_000L, chain.balanceOf(victim));

        Block replay = chain.forge().transaction(twin).seal();
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE, chain.engine().addBlock(replay),
            "the twin carries the spent nonce; the content-hash dedup stands behind that");
        assertEquals(10_000L, chain.balanceOf(victim), "the value moved exactly once");
    }

    /**
     * SIG-03 — sender spoofing: an attacker names the victim as {@code from} but signs with their
     * own key. The binding {@code PublicAddress.of(signingKey) == from} is checked at consensus,
     * not just at admission, so a miner cannot include it either.
     */
    @Test
    void namingAVictimAsSenderWhileSigningWithAnotherKeyIsRefused() {
        AdversarialChain chain = AdversarialChain.testnet()
            .fund("victim", 1_000_000L)
            .fund("attacker", 1_000L)
            .build();
        PublicAddress victim = chain.account("victim").address();
        PublicAddress pocket = PublicAddress.random();

        // from = victim, but the signing key (and therefore the signature) is the attacker's.
        Transaction spoofed = Transaction.of(victim, pocket,
            new rhizome.core.transaction.TransactionAmount(500_000L),
            chain.account("attacker").publicKey(),
            new rhizome.core.transaction.TransactionAmount(0L),
            chain.now(), chain.params().chainId(), 0L);
        spoofed.sign(chain.account("attacker").privateKey());

        assertTrue(spoofed.signatureValid(), "the signature itself is genuine — that is the point");
        assertFalse(spoofed.senderBindingValid(), "but it does not bind the named sender");
        assertEquals(ExecutionStatus.WALLET_SIGNATURE_MISMATCH,
            chain.engine().addBlock(chain.forge().transaction(spoofed).seal()));
        assertEquals(1_000_000L, chain.balanceOf(victim), "no value moved");
        assertEquals(0L, chain.balanceOf(pocket));
    }

    /**
     * SIG-04 — tampering after signing: the amount is raised on an otherwise authorised
     * transaction. The signed preimage covers it, so the signature no longer verifies.
     */
    @Test
    void raisingTheAmountAfterSigningBreaksTheSignature() {
        AdversarialChain chain = chain();
        PublicAddress pocket = PublicAddress.random();
        Transaction authorised = chain.account("alice").send(pocket, 1_000, 0, 0);

        TransactionImpl tampered = (TransactionImpl) Transaction.of(authorised);
        tampered.amount(new rhizome.core.transaction.TransactionAmount(900_000L));

        assertFalse(tampered.signatureValid(), "the amount is inside the signed preimage");
        assertEquals(ExecutionStatus.INVALID_SIGNATURE,
            chain.engine().addBlock(chain.forge().transaction(tampered).seal()));
        assertEquals(0L, chain.balanceOf(pocket));
    }
}
