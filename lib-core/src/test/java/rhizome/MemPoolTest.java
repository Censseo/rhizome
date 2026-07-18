package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.generateKeyPair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.AccountView;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

class MemPoolTest {

    private final NetworkParameters params = NetworkParameters.testnet();
    private SignatureVerifier verifier;
    private MutableAccounts accounts;
    private MemPool mempool;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;

    /** Adjustable confirmed state for tests. */
    private static final class MutableAccounts implements AccountView {
        final Map<PublicAddress, Long> nonces = new HashMap<>();
        final Map<PublicAddress, Long> balances = new HashMap<>();
        public long confirmedNextNonce(PublicAddress s) { return nonces.getOrDefault(s, 0L); }
        public long confirmedBalance(PublicAddress s) { return balances.getOrDefault(s, 0L); }
    }

    @BeforeEach
    void setUp() {
        verifier = new SignatureVerifier();
        accounts = new MutableAccounts();
        mempool = new MemPool(params, verifier, accounts, 100);

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        accounts.balances.put(sender, 1_000_000L);
    }

    private Transaction send(long amount, long fee, long nonce) {
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(amount),
            key, new TransactionAmount(fee), 1000L + nonce, params.chainId(), nonce);
        t.sign(priv);
        return t;
    }

    @Test
    void admitsValidTransactionAndWarmsVerifierCache() {
        Transaction t = send(100, 1, 0);
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(t));
        assertEquals(1, mempool.size());
        assertTrue(mempool.contains(t.hashContents()));
        // Admission verified the signature -> block validation is a cache hit.
        assertTrue(verifier.isCached(t));
    }

    @Test
    void rejectsDuplicate() {
        Transaction t = send(100, 1, 0);
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(t));
        assertEquals(ExecutionStatus.ALREADY_IN_QUEUE, mempool.addTransaction(t));
    }

    @Test
    void rejectsWrongChainId() {
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(100),
            key, new TransactionAmount(1), 1000L, params.chainId() + 9, 0);
        t.sign(priv);
        assertEquals(ExecutionStatus.INVALID_CHAIN_ID, mempool.addTransaction(t));
    }

    @Test
    void rejectsBadSignature() {
        Transaction t = send(100, 1, 0);
        ((TransactionImpl) t).amount(new TransactionAmount(999)); // tamper post-sign
        assertEquals(ExecutionStatus.INVALID_SIGNATURE, mempool.addTransaction(t));
    }

    @Test
    void rejectsStaleNonce() {
        accounts.nonces.put(sender, 5L);
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE, mempool.addTransaction(send(100, 1, 4)));
    }

    @Test
    void enforcesCumulativeBalanceNotPerTransaction() {
        accounts.balances.put(sender, 150L);
        // Each fits alone (100 <= 150), but together 100+100 > 150.
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(100, 0, 0)));
        assertEquals(ExecutionStatus.BALANCE_TOO_LOW, mempool.addTransaction(send(100, 0, 1)));
    }

    @Test
    void enforcesSizeBound() {
        MemPool small = new MemPool(params, verifier, accounts, 2);
        assertEquals(ExecutionStatus.SUCCESS, small.addTransaction(send(1, 0, 0)));
        assertEquals(ExecutionStatus.SUCCESS, small.addTransaction(send(1, 0, 1)));
        assertEquals(ExecutionStatus.QUEUE_FULL, small.addTransaction(send(1, 0, 2)));
    }

    @Test
    void selectsContiguousNonceRunInOrder() {
        mempool.addTransaction(send(100, 0, 0));
        mempool.addTransaction(send(100, 0, 1));
        mempool.addTransaction(send(100, 0, 3)); // gap at 2

        List<Transaction> selected = mempool.getTransactionsForBlock(10);
        assertEquals(2, selected.size()); // stops at the gap
        assertEquals(0, ((TransactionImpl) selected.get(0)).nonce());
        assertEquals(1, ((TransactionImpl) selected.get(1)).nonce());
    }

    @Test
    void selectionRespectsMaxAndBalance() {
        for (int i = 0; i < 5; i++) {
            mempool.addTransaction(send(100, 0, i));
        }
        assertEquals(3, mempool.getTransactionsForBlock(3).size());
    }

    @Test
    void onBlockAppliedRemovesIncludedAndStale() {
        Transaction t0 = send(100, 0, 0);
        Transaction t1 = send(100, 0, 1);
        mempool.addTransaction(t0);
        mempool.addTransaction(t1);
        assertEquals(2, mempool.size());

        // Simulate the chain confirming nonce 0..0 (next becomes 1) in a block.
        var block = (rhizome.core.block.BlockImpl) rhizome.core.block.Block.empty();
        block.addTransaction(t0);
        accounts.nonces.put(sender, 1L);
        mempool.onBlockApplied(block);

        assertEquals(1, mempool.size());
        assertFalse(mempool.contains(t0.hashContents()));
        assertTrue(mempool.contains(t1.hashContents()));
    }
}
