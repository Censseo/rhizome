package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ContractProcessor;
import rhizome.core.blockchain.Executor;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.box.Box;
import rhizome.core.box.BoxProcessor;
import rhizome.core.box.ScanPredicate;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.AccountView;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

/**
 * Locks the parity between the two sides of the admission policy: {@link MemPool} admits a
 * transaction exactly when {@link Executor} accepts it in a block, on the boundary
 * transactions — fee exactly at the floor and strictly below it, gas limit exactly at the
 * ceiling, a future nonce — so the two copies of the fee rule ({@code minerRevenue} + the
 * floor condition) can be fused into one policy without either side silently changing. A
 * divergence here means either a pool-admitted transaction would reject every candidate
 * block (production halt) or the pool drops consensus-valid work at the boundary.
 */
class AdmissionParityTest {

    private static final NetworkParameters FLOORED =
        NetworkParameters.testnet().toBuilder().minFee(10L).build();

    /** Pool side: adjustable confirmed state, like MemPoolTest's. */
    private static final class MutableAccounts implements AccountView {
        final Map<PublicAddress, Long> nonces = new HashMap<>();
        final Map<PublicAddress, Long> balances = new HashMap<>();
        long height = Long.MAX_VALUE;
        public long confirmedNextNonce(PublicAddress s) { return nonces.getOrDefault(s, 0L); }
        public long confirmedBalance(PublicAddress s) { return balances.getOrDefault(s, 0L); }
        public boolean senderExists(PublicAddress s) { return balances.containsKey(s); }
        public long confirmedHeight() { return height; }
    }

    private static final class KeyHolder {
        final PublicKey key;
        final PrivateKey priv;
        final PublicAddress address;
        KeyHolder() {
            var pair = generateKeyPairTyped();
            key = pair.publicKey();
            priv = pair.privateKey();
            address = PublicAddress.of(key);
        }
    }

    private static Transaction transfer(KeyHolder sender, NetworkParameters params,
                                        long amount, long fee, long nonce) {
        Transaction t = Transaction.of(sender.address, PublicAddress.random(),
            new TransactionAmount(amount), sender.key, new TransactionAmount(fee),
            1_000L + nonce, params.chainId(), nonce);
        t.sign(sender.priv);
        return t;
    }

    private static Transaction call(KeyHolder sender, NetworkParameters params,
                                    long nonce, long gasLimit, long gasPrice) {
        Transaction t = TransactionImpl.builder()
            .from(sender.address).to(PublicAddress.random())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(sender.key)
            .kind(TransactionKind.CALL)
            .data(new byte[] {1, 2, 3}).gasLimit(gasLimit).gasPrice(gasPrice)
            .build();
        t.sign(sender.priv);
        return t;
    }

    private static Transaction boxCreate(KeyHolder sender, NetworkParameters params, long nonce) {
        Transaction t = TransactionImpl.builder()
            .from(sender.address).to(PublicAddress.random())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(sender.key)
            .kind(TransactionKind.BOX_CREATE)
            .data(new byte[] {1, 2, 3}).gasLimit(5).gasPrice(0)
            .build();
        t.sign(sender.priv);
        return t;
    }

    private static final ContractProcessor CONTRACT_STUB = new ContractProcessor() {
        @Override public void begin() {}
        @Override public ContractResult run(PublicAddress from, TransactionKind kind, PublicAddress to,
                                            byte[] data, long value, long gasLimit, long nonce) {
            return ContractResult.ok(0, new byte[0], null);
        }
        @Override public void commit(long blockHeight) {}
        @Override public void discard() {}
        @Override public void revertBlock(long blockHeight) {}
        @Override public List<ContractReceipt> receipts(long blockHeight) { return List.of(); }
    };

    private static final BoxProcessor BOX_STUB = new BoxProcessor() {
        @Override public void begin() {}
        @Override public BoxResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                                       long amount, long nonce, byte[] data, long height) {
            return BoxResult.fail(ExecutionStatus.BOX_NOT_FOUND);
        }
        @Override public void commit(long blockHeight) {}
        @Override public void discard() {}
        @Override public void revertBlock(long blockHeight) {}
        @Override public List<BoxReceipt> receipts(long blockHeight) { return List.of(); }
        @Override public Box get(byte[] boxId) { return null; }
        @Override public Box getCommitted(byte[] boxId) { return null; }
        @Override public List<byte[]> collectableBoxIds(long height, int limit) { return List.of(); }
        @Override public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) { return List.of(); }
        @Override public ScanPage scan(ScanPredicate predicate, byte[] afterId, int limit, int window) {
            return new ScanPage(List.of(), null);
        }
    };

    /** Asserts the pool and the executor agree on this boundary transaction. */
    private static void assertParity(NetworkParameters params, Transaction tx, long senderBalance) {
        // Pool side: a fresh pool over the same confirmed state.
        MutableAccounts accounts = new MutableAccounts();
        accounts.balances.put(tx.from(), senderBalance);
        MemPool pool = new MemPool(params, new SignatureVerifier(), accounts, 100);
        boolean poolAccepted = pool.addTransaction(tx) == ExecutionStatus.SUCCESS;

        // Executor side: a fresh ledger, one candidate block at height 2 (consensusV2 active).
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(tx.from());
        ledger.deposit(tx.from(), new TransactionAmount(senderBalance));
        PublicAddress miner = PublicAddress.random();
        var b = BlockImpl.builder().id(2).timestamp(5_000L).difficulty(params.genesisDifficulty())
            .lastBlockHash(SHA256Hash.empty()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(2))));
        b.addTransaction(tx);
        boolean executorAccepted = Executor.executeBlock(b, ledger, h -> false, params,
            null, CONTRACT_STUB, BOX_STUB, null) == ExecutionStatus.SUCCESS;

        assertEquals(executorAccepted, poolAccepted,
            "pool admission and executor acceptance must agree on " + tx.kind()
                + " amount=" + tx.amount().amount() + " fee=" + tx.fee().amount()
                + " gasLimit=" + tx.gasLimit() + " gasPrice=" + tx.gasPrice());
    }

    @Test
    void feeFloorBoundaryAgrees() {
        KeyHolder sender = new KeyHolder();
        assertParity(FLOORED, transfer(sender, FLOORED, 0, 10, 0), 1_000_000L); // exactly at the floor
        assertParity(FLOORED, transfer(sender, FLOORED, 0, 9, 0), 1_000_000L);  // one under
        assertParity(FLOORED, transfer(sender, FLOORED, 0, 0, 0), 1_000_000L);  // free
    }

    @Test
    void contractGasBudgetCountsTowardTheFloorAgree() {
        KeyHolder sender = new KeyHolder();
        // minerRevenue = gasLimit × gasPrice (contract fee is reserved at 0): 10 reaches the floor exactly.
        assertParity(FLOORED, call(sender, FLOORED, 0, 10, 1), 1_000_000L);
        assertParity(FLOORED, call(sender, FLOORED, 0, 9, 1), 1_000_000L);
    }

    @Test
    void futureNonceAgrees() {
        // A gapped nonce is admitted (parked) and is structurally valid to the executor —
        // nonce sequentiality is the engine's rule, not the executor's.
        KeyHolder sender = new KeyHolder();
        assertParity(FLOORED, transfer(sender, FLOORED, 0, 10, 5), 1_000_000L);
    }

    @Test
    void gasCeilingBoundaryAgrees() {
        NetworkParameters ceiling = NetworkParameters.testnet();
        KeyHolder sender = new KeyHolder();
        long balance = 1_000_000_000_000L;
        assertParity(ceiling, call(sender, ceiling, 0, ceiling.maxTxGas(), 1), balance);
        assertParity(ceiling, call(sender, ceiling, 0, ceiling.maxTxGas() + 1, 1), balance);
    }

    @Test
    void reservedGasFieldsAgree() {
        // A box op carrying gas fields is refused by both — the pool as a structural admission
        // rule, the executor as a payload rule. Both reject; the parity is what is pinned.
        KeyHolder sender = new KeyHolder();
        assertParity(NetworkParameters.testnet(), boxCreate(sender, NetworkParameters.testnet(), 0), 1_000_000L);
    }

    /**
     * POOL-09 — Make every honest miner assemble an invalid block by filling the pool with
     * max-gas CALLs: six at {@code maxTxGas} exceed the 5x block gas cap, which only validation
     * enforced, so selection kept producing a block every node rejected.
     */
    @Test
    void selectionNeverExceedsTheBlockGasCap() {
        NetworkParameters p = NetworkParameters.testnet();
        int n = (int) (p.maxBlockGas() / p.maxTxGas()) + 1;
        MutableAccounts accounts = new MutableAccounts();
        MemPool pool = new MemPool(p, new SignatureVerifier(), accounts, 100);
        InMemoryLedger ledger = new InMemoryLedger();
        for (int i = 0; i < n; i++) {
            KeyHolder k = new KeyHolder();
            accounts.balances.put(k.address, 1_000_000L);
            ledger.createWallet(k.address);
            ledger.deposit(k.address, new TransactionAmount(1_000_000L));
            assertEquals(ExecutionStatus.SUCCESS, pool.addTransaction(call(k, p, 0, p.maxTxGas(), 0)));
        }
        List<Transaction> selected = pool.getTransactionsForBlock(100);
        assertEquals(n - 1, selected.size(), "the sixth max-gas call must be left in the pool");
        assertEquals(n, pool.size(), "skipping selects nothing away: the call stays pooled");

        var b = BlockImpl.builder().id(2).timestamp(5_000L).difficulty(p.genesisDifficulty())
            .lastBlockHash(SHA256Hash.empty()).build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(p.miningReward(2))));
        selected.forEach(b::addTransaction);
        assertEquals(ExecutionStatus.SUCCESS, Executor.executeBlock(b, ledger, h -> false, p,
            null, CONTRACT_STUB, BOX_STUB, null));
    }

    /**
     * INFL-11 — Outrank all traffic for free with a contract tx whose {@code fee} field counted as
     * miner revenue but was never debited: both the pool and the executor refuse a non-zero fee.
     */
    @Test
    void contractFeeFieldIsRefusedByPoolAndExecutor() {
        NetworkParameters p = NetworkParameters.testnet();
        KeyHolder k = new KeyHolder();
        Transaction t = TransactionImpl.builder()
            .from(k.address).to(PublicAddress.random())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(Long.MAX_VALUE))
            .chainId(p.chainId()).nonce(0).signingKey(k.key)
            .kind(TransactionKind.CALL)
            .data(new byte[] {1, 2, 3}).gasLimit(1_000).gasPrice(0)
            .build();
        t.sign(k.priv);

        MutableAccounts accounts = new MutableAccounts();
        accounts.balances.put(k.address, 1_000_000L);
        MemPool pool = new MemPool(p, new SignatureVerifier(), accounts, 100);
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_AMOUNT, pool.addTransaction(t));
        assertEquals(0, pool.size());

        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(k.address);
        ledger.deposit(k.address, new TransactionAmount(1_000_000L));
        var b = BlockImpl.builder().id(2).timestamp(5_000L).difficulty(p.genesisDifficulty())
            .lastBlockHash(SHA256Hash.empty()).build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(p.miningReward(2))));
        b.addTransaction(t);
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_AMOUNT, Executor.executeBlock(b, ledger, h -> false, p,
            null, CONTRACT_STUB, BOX_STUB, null));
        assertEquals(1_000_000L, ledger.getWalletValue(k.address).amount(), "refusal moved no value");
    }
}
