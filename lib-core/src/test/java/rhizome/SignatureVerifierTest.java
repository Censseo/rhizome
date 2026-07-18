package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.generateKeyPair;

import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

class SignatureVerifierTest {

    private Transaction signed(long nonce) {
        var pair = generateKeyPair();
        var key = PublicKey.of(pair.getPublic());
        Transaction t = Transaction.of(PublicAddress.of(key), PublicAddress.random(),
            new TransactionAmount(100), key, new TransactionAmount(1), 1000L, 1, nonce);
        t.sign(new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate()));
        return t;
    }

    @Test
    void verifiesAndCachesValidSignature() {
        var verifier = new SignatureVerifier();
        Transaction t = signed(0);
        assertFalse(verifier.isCached(t));
        assertTrue(verifier.verify(t));
        assertTrue(verifier.isCached(t));
        assertTrue(verifier.verify(t)); // cache hit
        verifier.shutdown();
    }

    @Test
    void rejectsTamperedSignature() {
        var verifier = new SignatureVerifier();
        Transaction t = signed(0);
        ((TransactionImpl) t).nonce(99); // invalidate after signing
        assertFalse(verifier.verify(t));
        assertFalse(verifier.isCached(t));
        verifier.shutdown();
    }

    @Test
    void coinbaseAlwaysValid() {
        var verifier = new SignatureVerifier();
        Transaction coinbase = Transaction.of(PublicAddress.random(), new TransactionAmount(50));
        assertTrue(verifier.verify(coinbase));
        verifier.shutdown();
    }

    @Test
    void verifyAllParallelMatchesSequential() {
        var verifier = new SignatureVerifier();
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            txs.add(signed(i));
        }
        assertTrue(verifier.verifyAll(txs));

        // Inject one invalid transaction -> whole batch fails.
        Transaction bad = signed(999);
        ((TransactionImpl) bad).amount(new TransactionAmount(7)); // tamper after signing
        txs.add(bad);
        assertFalse(verifier.verifyAll(txs));
        verifier.shutdown();
    }

    @Test
    void markVerifiedPrewarmsCache() {
        var verifier = new SignatureVerifier();
        Transaction t = signed(0);
        verifier.markVerified(t);
        assertTrue(verifier.isCached(t));
        assertEquals(1, verifier.cacheSize());
        verifier.shutdown();
    }

    @Test
    void cacheKeyBindsSignatureSoMalleatedSigMisses() {
        // Same content hash but a different signature must not be a cache hit.
        var verifier = new SignatureVerifier();
        Transaction t = signed(0);
        verifier.verify(t);
        assertTrue(verifier.isCached(t));

        Transaction sameContent = Transaction.of(t);
        ((TransactionImpl) sameContent).signature(rhizome.core.transaction.TransactionSignature.random());
        assertEquals(t.hashContents(), sameContent.hashContents());
        assertFalse(verifier.isCached(sameContent));
        verifier.shutdown();
    }
}
