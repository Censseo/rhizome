package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

/**
 * Locks the clean-chain consensus model: the block-hash preimage commits to
 * every header field, and the transaction preimage carries chain-id and account
 * nonce for replay protection. These are the fixes for Pandanite C++ flaws
 * (incomplete block preimage; replay across networks; timestamp-only tx identity).
 */
class ConsensusModelTest {

    private static BlockImpl block(int id, long timestamp) {
        return (BlockImpl) BlockImpl.builder()
            .id(id)
            .timestamp(timestamp)
            .difficulty(10)
            .merkleRoot(SHA256Hash.empty())
            .lastBlockHash(SHA256Hash.empty())
            .build();
    }

    @Test
    void blockHashCommitsToId() {
        var a = block(1, 1000);
        var b = block(2, 1000);
        assertNotEquals(a.hash(), b.hash());
    }

    @Test
    void blockHashCommitsToTransactionCount() {
        var a = block(1, 1000);
        var b = block(1, 1000);
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(50)));
        assertNotEquals(a.hash(), b.hash());
    }

    @Test
    void verifyNonceRespectsPowAlgorithm() {
        // A block mined at a low (but non-zero) difficulty under each algorithm verifies under
        // that same algorithm — exercising algorithm routing (incl. a real Pufferfish2 run).
        // Difficulty 0 is no longer a valid shortcut: checkLeadingZeroBits rejects a non-positive
        // challenge so no block can satisfy the PoW with zero work (audit C1).
        for (PowAlgorithm algo : new PowAlgorithm[] {PowAlgorithm.SHA256, PowAlgorithm.PUFFERFISH2}) {
            var b = (BlockImpl) BlockImpl.builder()
                .id(1).timestamp(1000).difficulty(1)
                .merkleRoot(SHA256Hash.empty())
                .lastBlockHash(SHA256Hash.empty())
                .nonce(SHA256Hash.empty())
                .build();
            b.nonce(rhizome.core.blockchain.Miner.mineNonce(b.hash(), b.difficulty(), algo));
            assertTrue(b.verifyNonce(algo));
        }
    }

    @Test
    void transactionPreimageCommitsToChainIdAndNonce() {
        var to = PublicAddress.random();
        var from = PublicAddress.random();

        Transaction base = tx(from, to, 1, 0);
        Transaction otherChain = tx(from, to, 2, 0);
        Transaction otherNonce = tx(from, to, 1, 1);

        assertNotEquals(base.hashContents(), otherChain.hashContents());
        assertNotEquals(base.hashContents(), otherNonce.hashContents());
    }

    @Test
    void identicalSendsWithDifferentNoncesAreDistinctTransactions() {
        // Pandanite: same to/from/amount/fee/timestamp == same transaction (issue: no
        // account nonce). Clean chain: the nonce disambiguates them.
        var to = PublicAddress.random();
        var from = PublicAddress.random();
        Transaction first = tx(from, to, 1, 0);
        Transaction second = tx(from, to, 1, 1);
        assertNotEquals(first.hashContents(), second.hashContents());
        assertNotEquals(first, second);
    }

    @Test
    void signatureCoversChainIdAndNonce() {
        var pair = generateKeyPair();
        var signingKey = PublicKey.of(pair.getPublic());
        var from = PublicAddress.of(signingKey);
        var to = PublicAddress.random();

        Transaction t = Transaction.of(from, to, new TransactionAmount(100), signingKey,
            new TransactionAmount(1), 123456789L, 1, 7);
        t.sign(new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate()));
        assertTrue(t.signatureValid());

        // Tampering with the nonce after signing must invalidate the signature.
        ((TransactionImpl) t).nonce(8);
        assertTrue(!t.signatureValid());
    }

    @Test
    void dtoRoundTripPreservesChainIdAndNonce() {
        var pair = generateKeyPair();
        var signingKey = PublicKey.of(pair.getPublic());
        var from = PublicAddress.of(signingKey);

        Transaction t = Transaction.of(from, PublicAddress.random(), new TransactionAmount(100),
            signingKey, new TransactionAmount(1), 123456789L, 1, 42);
        t.sign(new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate()));

        Transaction restored = Transaction.of(t.serialize());
        assertEquals(1, ((TransactionImpl) restored).chainId());
        assertEquals(42, ((TransactionImpl) restored).nonce());
        assertEquals(t.hashContents(), restored.hashContents());
    }

    @Test
    void jsonRoundTripPreservesChainIdAndNonce() {
        var pair = generateKeyPair();
        var signingKey = PublicKey.of(pair.getPublic());
        var from = PublicAddress.of(signingKey);

        Transaction t = Transaction.of(from, PublicAddress.random(), new TransactionAmount(100),
            signingKey, new TransactionAmount(1), 123456789L, 1, 42);
        t.sign(new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate()));

        Transaction restored = Transaction.of(t.toJson());
        assertEquals(t.hashContents(), restored.hashContents());
    }

    @Test
    void compareToOrdersByContentHash() {
        var to = PublicAddress.random();
        var from = PublicAddress.random();
        TransactionImpl a = (TransactionImpl) tx(from, to, 1, 0);
        TransactionImpl b = (TransactionImpl) tx(from, to, 1, 1);
        assertEquals(-Integer.signum(b.compareTo(a)), Integer.signum(a.compareTo(b)));
        assertEquals(0, a.compareTo(tx(from, to, 1, 0)));
    }

    private static Transaction tx(PublicAddress from, PublicAddress to, int chainId, long nonce) {
        return Transaction.of(from, to, new TransactionAmount(100), PublicKey.empty(),
            new TransactionAmount(1), 123456789L, chainId, nonce);
    }
}
