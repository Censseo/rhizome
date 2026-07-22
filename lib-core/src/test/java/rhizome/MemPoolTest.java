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
        public boolean senderExists(PublicAddress s) { return balances.containsKey(s); }
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

    private Transaction contractCall(long nonce, long gasLimit, long gasPrice) {
        Transaction t = rhizome.core.transaction.TransactionImpl.builder()
            .from(sender).to(PublicAddress.random())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(rhizome.core.transaction.TransactionKind.CALL)
            .data(new byte[] {1, 2, 3}).gasLimit(gasLimit).gasPrice(gasPrice)
            .build();
        t.sign(priv);
        return t;
    }

    @Test
    void admitsContractCallWithinGasBudget() {
        // Sender balance is 1_000_000; a gas budget within it is admitted.
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(contractCall(0, 100_000, 1)));
        assertEquals(1, mempool.size());
    }

    @Test
    void rejectsContractCallWhoseGasBudgetExceedsBalance() {
        // gasLimit * gasPrice = 2_000_000 > balance 1_000_000.
        assertEquals(ExecutionStatus.BALANCE_TOO_LOW, mempool.addTransaction(contractCall(0, 1_000_000, 2)));
        assertEquals(0, mempool.size());
    }

    @Test
    void rejectsNegativeAmountAndFeeAtAdmission() {
        // Defence-in-depth: negative-value transactions are refused before pooling,
        // never mind the consensus-level guard.
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_AMOUNT, mempool.addTransaction(send(-100, 0, 0)));
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_AMOUNT, mempool.addTransaction(send(0, -100, 0)));
        assertEquals(0, mempool.size());
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
    void rejectsAndNeverSelectsTransactionFromNonexistentSender() {
        // Regression: a free signed no-op (amount 0, fee 0) from a fresh unfunded keypair used to
        // be admitted and selected into every candidate block, get the block rejected at execution
        // (SENDER_DOES_NOT_EXIST), never be purged, and halt production network-wide.
        var freshPair = generateKeyPair();
        var freshKey = PublicKey.of(freshPair.getPublic());
        var freshPriv = new PrivateKey((Ed25519PrivateKeyParameters) freshPair.getPrivate());
        var freshAddr = PublicAddress.of(freshKey);
        Transaction poison = Transaction.of(freshAddr, PublicAddress.random(), new TransactionAmount(0),
            freshKey, new TransactionAmount(0), 1000L, params.chainId(), 0);
        poison.sign(freshPriv);
        assertEquals(ExecutionStatus.SENDER_DOES_NOT_EXIST, mempool.addTransaction(poison));
        assertEquals(0, mempool.size());
        assertEquals(0, mempool.getTransactionsForBlock(10).size());
    }

    @Test
    void enforcesSizeBound() {
        MemPool small = new MemPool(params, verifier, accounts, 2);
        assertEquals(ExecutionStatus.SUCCESS, small.addTransaction(send(1, 0, 0)));
        assertEquals(ExecutionStatus.SUCCESS, small.addTransaction(send(1, 0, 1)));
        assertEquals(ExecutionStatus.QUEUE_FULL, small.addTransaction(send(1, 0, 2)));
    }

    @Test
    void enforcesPerSenderCapSoOneAccountCannotFloodThePool() {
        // Global room for 100, but one sender is capped at 3: its 4th is refused
        // while a different sender is still admitted.
        MemPool pool = new MemPool(params, verifier, accounts, 100, 3);
        for (int i = 0; i < 3; i++) {
            assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(send(1, 0, i)));
        }
        assertEquals(ExecutionStatus.QUEUE_FULL, pool.addTransaction(send(1, 0, 3)));

        var otherPair = generateKeyPair();
        var otherKey = PublicKey.of(otherPair.getPublic());
        var otherPriv = new PrivateKey((Ed25519PrivateKeyParameters) otherPair.getPrivate());
        var other = PublicAddress.of(otherKey);
        accounts.balances.put(other, 1_000_000L);
        Transaction fromOther = Transaction.of(other, PublicAddress.random(), new TransactionAmount(1),
            otherKey, new TransactionAmount(0), 1000L, params.chainId(), 0);
        fromOther.sign(otherPriv);
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(fromOther));
    }

    /** A funded, independent sender for the parking-eviction tests. */
    private Transaction fromFreshSender(PublicAddress[] out, long fee, long nonce) {
        var pair = generateKeyPair();
        var k = PublicKey.of(pair.getPublic());
        var p = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        var addr = PublicAddress.of(k);
        accounts.balances.put(addr, 1_000_000L);
        out[0] = addr;
        Transaction t = Transaction.of(addr, PublicAddress.random(), new TransactionAmount(1),
            k, new TransactionAmount(fee), 1000L + nonce, params.chainId(), nonce);
        t.sign(p);
        return t;
    }

    @Test
    void readyTransactionDisplacesParkedDeadWeightWhenPoolIsFull() {
        // Audit 5th-pass (nonce-gap parking censorship): the pool must not be permanently fillable with
        // individually-valid but never-minable gap transactions. Here `sender` parks 3 txs above an
        // unfilled gap at nonce 0 (confirmedNextNonce==0), so none is selectable and the pool is full.
        MemPool pool = new MemPool(params, verifier, accounts, 3);
        for (int nonce = 1; nonce <= 3; nonce++) {
            assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(send(1, 1, nonce)));
        }
        assertEquals(3, pool.size());
        assertEquals(0, pool.getTransactionsForBlock(10).size(), "parked txs are unminable");

        // An honest sender's ready tx (nonce == its confirmed next) must be admitted by reclaiming a
        // parked slot, not shed with QUEUE_FULL.
        PublicAddress[] h = new PublicAddress[1];
        Transaction ready = fromFreshSender(h, 1, 0);
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(ready));
        assertTrue(pool.contains(ready.hashContents()));

        List<Transaction> selected = pool.getTransactionsForBlock(10);
        assertEquals(1, selected.size(), "the pool is no longer censored");
        assertEquals(h[0], ((TransactionImpl) selected.get(0)).from());
    }

    @Test
    void parkedNewcomerCannotChurnAFullParkedPool() {
        // A gapped, no-higher-fee newcomer must NOT evict parked dead weight — otherwise attackers
        // could churn each other's slots. Only a ready or strictly-higher-fee tx reclaims a slot.
        MemPool pool = new MemPool(params, verifier, accounts, 3);
        for (int nonce = 1; nonce <= 3; nonce++) {
            assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(send(1, 1, nonce)));
        }
        PublicAddress[] h = new PublicAddress[1];
        Transaction gappedSameFee = fromFreshSender(h, 1, 5); // gap at its front, fee == victim's
        assertEquals(ExecutionStatus.QUEUE_FULL, pool.addTransaction(gappedSameFee));
        assertEquals(3, pool.size());
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
