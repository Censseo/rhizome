package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
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
        long height = Long.MAX_VALUE; // default: past any activation, so box/token admission is not gated
        public long confirmedNextNonce(PublicAddress s) { return nonces.getOrDefault(s, 0L); }
        public long confirmedBalance(PublicAddress s) { return balances.getOrDefault(s, 0L); }
        public boolean senderExists(PublicAddress s) { return balances.containsKey(s); }
        public long confirmedHeight() { return height; }
    }

    @BeforeEach
    void setUp() {
        verifier = new SignatureVerifier();
        accounts = new MutableAccounts();
        mempool = new MemPool(params, verifier, accounts, 100);

        var pair = generateKeyPairTyped();
        key = pair.publicKey();
        priv = pair.privateKey();
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

    private Transaction boxCreate(long nonce) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(PublicAddress.random())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(rhizome.core.transaction.TransactionKind.BOX_CREATE)
            .data(new byte[] {1, 2, 3}).gasLimit(0).gasPrice(0)
            .build();
        t.sign(priv);
        return t;
    }

    private Transaction tokenMint(long nonce) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(PublicAddress.random())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(rhizome.core.transaction.TransactionKind.TOKEN_MINT)
            .data(new byte[] {1, 2, 3}).gasLimit(0).gasPrice(0)
            .build();
        t.sign(priv);
        return t;
    }

    @Test
    void rejectsBoxTransactionBeforeItsActivationHeight() {
        // A box tx submitted before boxActivationHeight would be selected into candidate blocks and
        // hard-fail the executor (BOX_UNAVAILABLE), halting production. The pool must refuse it until
        // the chain reaches activation, mirroring the executor's gate (audit: activation asymmetry).
        NetworkParameters activated = params.toBuilder().boxActivationHeight(100).build();
        MutableAccounts acc = new MutableAccounts();
        acc.balances.put(sender, 1_000_000L);
        MemPool pool = new MemPool(activated, verifier, acc, 100);

        acc.height = 50; // next block 51 < 100: too early
        assertEquals(ExecutionStatus.BOX_UNAVAILABLE, pool.addTransaction(boxCreate(0)));
        assertEquals(0, pool.size());

        acc.height = 99; // next block 100 == activation: now includable
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(boxCreate(0)));
        assertEquals(1, pool.size());
    }

    @Test
    void activationGateDoesNotOverflowTheConfirmedHeightSentinel() {
        // AccountView.confirmedHeight() uses Long.MAX_VALUE as the "past every activation"
        // sentinel. The admission gate must judge the NEXT block subtractively
        // (confirmedHeight < activation - 1); any activeAt(confirmedHeight + 1) form overflows
        // the sentinel to Long.MIN_VALUE and REFUSES a domain that activated long ago — the
        // pool would drop valid box/token traffic forever. The sentinel must admit.
        NetworkParameters activated = params.toBuilder()
            .boxActivationHeight(100).tokenActivationHeight(100).build();
        MutableAccounts acc = new MutableAccounts();
        acc.balances.put(sender, 1_000_000L);
        MemPool pool = new MemPool(activated, verifier, acc, 100);
        acc.height = Long.MAX_VALUE; // the sentinel itself

        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(boxCreate(0)),
            "a box tx at the sentinel is past activation and must be admitted");
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(tokenMint(1)),
            "a token tx at the sentinel is past activation and must be admitted");
        assertEquals(2, pool.size());
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
    void configuredMinFeeRejectsFreeTransactionsBoundingNonceDomainGrowth() {
        // Audit S7: the ACCOUNT_NONCE state domain keeps a permanent leaf for every account that has
        // ever sent a transaction — unlike the ledger domain it cannot self-prune at zero balance
        // without reintroducing replay. The bound on an attacker cycling a fixed principal through
        // fresh accounts to bloat that domain is the minFee floor: with it configured, a zero-fee
        // transfer is refused at admission, so every nonce-creating hop costs a real fee.
        NetworkParameters withFloor = params.toBuilder().minFee(10L).build();
        MemPool floored = new MemPool(withFloor, verifier, accounts, 100);
        assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, floored.addTransaction(send(100, 0, 0)));
        assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, floored.addTransaction(send(100, 9, 0)));
        assertEquals(ExecutionStatus.SUCCESS, floored.addTransaction(send(100, 10, 0)));

        // The floor is off by default (0): the same free transfer is admitted. This is the residual —
        // the lever exists but must be turned on to charge for nonce-domain growth.
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(100, 0, 0)));
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
        var freshPair = generateKeyPairTyped();
        var freshKey = freshPair.publicKey();
        var freshPriv = freshPair.privateKey();
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

        var otherPair = generateKeyPairTyped();
        var otherKey = otherPair.publicKey();
        var otherPriv = otherPair.privateKey();
        var other = PublicAddress.of(otherKey);
        accounts.balances.put(other, 1_000_000L);
        Transaction fromOther = Transaction.of(other, PublicAddress.random(), new TransactionAmount(1),
            otherKey, new TransactionAmount(0), 1000L, params.chainId(), 0);
        fromOther.sign(otherPriv);
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(fromOther));
    }

    /** A funded, independent sender for the parking-eviction tests. */
    private Transaction fromFreshSender(PublicAddress[] out, long fee, long nonce) {
        var pair = generateKeyPairTyped();
        var k = pair.publicKey();
        var p = pair.privateKey();
        var addr = PublicAddress.of(k);
        accounts.balances.put(addr, 1_000_000L);
        out[0] = addr;
        Transaction t = Transaction.of(addr, PublicAddress.random(), new TransactionAmount(1),
            k, new TransactionAmount(fee), 1000L + nonce, params.chainId(), nonce);
        t.sign(p);
        return t;
    }

    @Test
    void declaredGasBudgetDoesNotBuySelectionPriority() {
        // Fee-market fix: a contract CALL's gasLimit is an upper bound it never pays in full
        // (only gasUsed × gasPrice is charged; an instant reverter pays CALL_BASE), so ordering
        // on total miner revenue let a reverter declaring a huge budget crowd out every block
        // for the price of a temporarily locked balance. Selection must use the revenue RATE:
        // this CALL locks 1_000_000 of budget at a rate of 1, so the plain 100-fee transfer
        // goes first — while the admission floor still counts the locked total (both admitted).
        MemPool pool = new MemPool(params, verifier, accounts, 100);
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(contractCall(0, 1_000_000, 1)));
        PublicAddress[] h = new PublicAddress[1];
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(fromFreshSender(h, 100, 0)));

        List<Transaction> one = pool.getTransactionsForBlock(1);
        assertEquals(1, one.size());
        assertEquals(h[0], ((TransactionImpl) one.get(0)).from(),
            "a declared-but-unpaid gas budget must not outrank real per-unit fees");
        List<Transaction> both = pool.getTransactionsForBlock(10);
        assertEquals(2, both.size(), "both transactions are minable — just correctly ordered");
    }

    @Test
    void selectionPrioritizesHigherFeeOverAddressOrder() {
        // M9 regression: block assembly must take the best-paying executable transaction first,
        // not iterate senders in raw address order — otherwise grinding a low-prefix address let
        // zero-fee spam crowd out fee-paying traffic in every block for free.
        MemPool pool = new MemPool(params, verifier, accounts, 100);
        PublicAddress[] a = new PublicAddress[1];
        PublicAddress[] b = new PublicAddress[1];
        // Two funded senders: one paying 0 fee, one paying 100. Retry until the zero-fee sender
        // sorts BEFORE the fee-payer in unsigned-address order, so the old address-order
        // iteration would have picked it first (the exact attack shape).
        Transaction freeTx;
        Transaction paidTx;
        do {
            freeTx = fromFreshSender(a, 0, 0);
            paidTx = fromFreshSender(b, 100, 0);
        } while (java.util.Arrays.compareUnsigned(a[0].toBytes(), b[0].toBytes()) >= 0);
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(freeTx));
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(paidTx));

        List<Transaction> selected = pool.getTransactionsForBlock(10);
        assertEquals(2, selected.size());
        assertEquals(b[0], ((TransactionImpl) selected.get(0)).from(),
            "the fee-paying transaction must be selected before the zero-fee one (audit M9)");
        // And with room for only one transaction, the free one is crowded out entirely.
        List<Transaction> one = pool.getTransactionsForBlock(1);
        assertEquals(1, one.size());
        assertEquals(b[0], ((TransactionImpl) one.get(0)).from());
    }

    @Test
    void selectionStillRespectsPerSenderNonceOrder() {
        // Within one sender the contiguous nonce run must stay in order even when a LATER nonce
        // pays more: nonce 1 (fee 100) can only follow nonce 0 (fee 0), never leapfrog it.
        MemPool pool = new MemPool(params, verifier, accounts, 100);
        Transaction first = send(1, 0, 0);
        Transaction second = send(1, 100, 1);
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(second));
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(first));
        List<Transaction> selected = pool.getTransactionsForBlock(10);
        assertEquals(2, selected.size());
        assertEquals(0, ((TransactionImpl) selected.get(0)).nonce());
        assertEquals(1, ((TransactionImpl) selected.get(1)).nonce());
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

    /**
     * A ready-nonce, high-fee newcomer whose {@code from}/{@code signingKey} match (so it passes the
     * cheap gates) but whose signature was made by a DIFFERENT key, so it fails verification.
     */
    private Transaction readyButInvalidSig(PublicAddress[] out, long fee) {
        var pair = generateKeyPairTyped();
        var k = pair.publicKey();
        var addr = PublicAddress.of(k);
        accounts.balances.put(addr, 1_000_000L);
        out[0] = addr;
        Transaction t = Transaction.of(addr, PublicAddress.random(), new TransactionAmount(1),
            k, new TransactionAmount(fee), 1000L, params.chainId(), 0); // nonce 0 == confirmed next -> ready
        var wrong = generateKeyPairTyped();
        t.sign(wrong.privateKey()); // wrong key -> bad sig
        return t;
    }

    @Test
    void invalidSignatureNewcomerCannotEvictParkedVictimsFromAFullPool() {
        // audit V4: eviction (the only pool-mutating step in addTransaction) must run only for an
        // authenticated candidate. Fill the pool with honest parked dead weight, then submit a
        // ready-nonce, HIGH-fee newcomer with a garbage signature — exactly the shape that would win
        // makeRoomForParkedSlot. It must be rejected for the bad signature WITHOUT evicting a victim.
        MemPool pool = new MemPool(params, verifier, accounts, 3);
        List<Transaction> parked = new java.util.ArrayList<>();
        for (int nonce = 1; nonce <= 3; nonce++) {
            Transaction t = send(1, 1, nonce);
            assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(t));
            parked.add(t);
        }
        assertEquals(3, pool.size());

        PublicAddress[] a = new PublicAddress[1];
        Transaction badReady = readyButInvalidSig(a, 100); // ready + fee 100 > victim fee 1
        assertEquals(ExecutionStatus.INVALID_SIGNATURE, pool.addTransaction(badReady));

        assertEquals(3, pool.size(), "no victim may be evicted for an unverified transaction");
        for (Transaction t : parked) {
            assertTrue(pool.contains(t.hashContents()),
                "parked victim must survive an invalid-signature newcomer");
        }
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

    @Test
    void parkedTransactionsExpireAfterTheTtl() {
        // A fully parked queue (gap at the confirmed nonce) is never minable; it must expire after
        // PARKED_TTL_MILLIS instead of occupying its slots forever. Purge is lazy: it runs on
        // addTransaction / getTransactionsForBlock.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        MemPool pool = new MemPool(params, verifier, accounts, 100, 1024, clock::get);
        // sender parks nonces 1..3 above a gap at nonce 0.
        for (int nonce = 1; nonce <= 3; nonce++) {
            assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(send(1, 1, nonce)));
        }
        assertEquals(3, pool.size());

        clock.addAndGet(2 * 60 * 60 * 1000L); // exactly the TTL
        assertEquals(0, pool.getTransactionsForBlock(10).size(), "expired parked txs are purged on toBlock");
        assertEquals(0, pool.size());

        // A live queue (contiguous from the confirmed nonce) never expires, even past the TTL.
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(send(1, 1, 0)));
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(send(1, 1, 1)));
        clock.addAndGet(2 * 60 * 60 * 1000L);
        assertEquals(2, pool.getTransactionsForBlock(10).size(), "live transactions have no TTL");
    }

    @Test
    void parkedQueueSurvivesBelowTheTtl() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        MemPool pool = new MemPool(params, verifier, accounts, 100, 1024, clock::get);
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(send(1, 1, 2))); // gap at 0..1
        clock.addAndGet(2 * 60 * 60 * 1000L - 1); // one ms before expiry
        assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(send(1, 1, 3)),
            "an add below the TTL must not purge the parked queue");
        assertEquals(2, pool.size());
    }

    @Test
    void replaceByFeeBumpsALiveTransaction() {
        // RBF: a live (contiguous-nonce) pooled tx can be replaced at the same sender+nonce when
        // the new fee is >= old + max(1, old/10). Here old fee 100 → at least 110 required.
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(100, 100, 0)));
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE, mempool.addTransaction(send(100, 109, 0)),
            "a bump below +10% is rejected as before");
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE, mempool.addTransaction(send(100, 100, 0)),
            "an equal fee is rejected as before");

        Transaction bumped = send(100, 110, 0);
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(bumped),
            "a bump to exactly +10% replaces");
        assertEquals(1, mempool.size(), "the old transaction left the pool");
        assertTrue(mempool.contains(bumped.hashContents()));

        // The bumped transaction is the one selected for the block.
        List<Transaction> selected = mempool.getTransactionsForBlock(10);
        assertEquals(1, selected.size());
        assertEquals(110L, ((TransactionImpl) selected.get(0)).fee().amount());
    }

    @Test
    void replaceByFeeRequiresAPositiveFeeToReplaceAFreeOne() {
        // max(1, old/10): a 0-fee transaction needs at least fee 1 to be replaced.
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(100, 0, 0)));
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE, mempool.addTransaction(send(100, 0, 0)));
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(100, 1, 0)));
        assertEquals(1, mempool.size());
    }

    @Test
    void replaceByFeeRefusesToReplaceAParkedTransaction() {
        // Only LIVE transactions are replaceable: a gapped (parked) one is not — it expires by
        // TTL or yields to the capacity eviction, so an attacker cannot churn parked slots by fee.
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(100, 100, 2))); // gap at 0..1
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE, mempool.addTransaction(send(100, 1_000, 2)),
            "even a much higher fee must not replace a parked transaction");
        assertEquals(1, mempool.size());
        assertTrue(mempool.getTransactionsForBlock(10).isEmpty(), "the parked original is still unminable");
    }
}
