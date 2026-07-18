package rhizome.core.mempool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import rhizome.core.block.Block;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionImpl;

import static rhizome.core.mempool.ExecutionStatus.*;

/**
 * Transaction pool: admits, deduplicates and orders pending transactions, and
 * feeds block building.
 *
 * <p>Admission is where the expensive work happens once, so block validation
 * later is cheap (the one-block-per-second design): the signature is verified
 * through a shared {@link SignatureVerifier}, which caches the result so the
 * block executor gets a cache hit.
 *
 * <p>Correctness rules fixing Pandanite flaws:
 * <ul>
 *   <li><b>Cumulative balance</b> — a sender's total pending spend
 *       (amount + fee across all its queued transactions) must fit its confirmed
 *       balance, not just each transaction individually (Pandanite PR #13).</li>
 *   <li><b>Account nonces</b> — transactions are kept per sender ordered by
 *       nonce; block building only emits the contiguous run starting at the
 *       confirmed next nonce, so ordering is unambiguous.</li>
 *   <li><b>Bounded</b> — a hard size cap prevents unbounded memory growth
 *       (Pandanite's rate limiter leaked, issue #52).</li>
 * </ul>
 *
 * <p>Thread-safe.
 */
public final class MemPool {

    private final NetworkParameters params;
    private final SignatureVerifier verifier;
    private final AccountView accounts;
    private final int maxSize;

    private final Map<PublicAddress, NavigableMap<Long, Transaction>> bySender = new HashMap<>();
    private final Set<SHA256Hash> contentHashes = new HashSet<>();
    private final ReentrantLock lock = new ReentrantLock();
    private int size;

    public MemPool(NetworkParameters params, SignatureVerifier verifier, AccountView accounts, int maxSize) {
        this.params = params;
        this.verifier = verifier;
        this.accounts = accounts;
        this.maxSize = maxSize;
    }

    public ExecutionStatus addTransaction(Transaction transaction) {
        var tx = (TransactionImpl) transaction;
        if (tx.isTransactionFee()) {
            return INVALID_TRANSACTION_NONCE; // coinbase is minted in blocks, never pooled
        }
        if (tx.chainId() != params.chainId()) {
            return INVALID_CHAIN_ID;
        }
        if (!PublicAddress.of(tx.signingKey()).equals(tx.from())) {
            return WALLET_SIGNATURE_MISMATCH;
        }

        lock.lock();
        try {
            SHA256Hash id = transaction.hashContents();
            if (contentHashes.contains(id)) {
                return ALREADY_IN_QUEUE;
            }
            if (size >= maxSize) {
                return QUEUE_FULL;
            }

            PublicAddress from = tx.from();
            long confirmedNonce = accounts.confirmedNextNonce(from);
            if (tx.nonce() < confirmedNonce) {
                return INVALID_TRANSACTION_NONCE; // already spent
            }

            NavigableMap<Long, Transaction> pending = bySender.get(from);
            if (pending != null && pending.containsKey(tx.nonce())) {
                return INVALID_TRANSACTION_NONCE; // duplicate nonce (no replace-by-fee yet)
            }

            // Cumulative spend across this sender's pending set + candidate.
            long spend = tx.amount().amount() + tx.fee().amount();
            if (pending != null) {
                for (Transaction p : pending.values()) {
                    var pt = (TransactionImpl) p;
                    spend += pt.amount().amount() + pt.fee().amount();
                }
            }
            if (spend < 0 || spend > accounts.confirmedBalance(from)) {
                return BALANCE_TOO_LOW;
            }

            if (!verifier.verify(transaction)) {
                return INVALID_SIGNATURE;
            }

            bySender.computeIfAbsent(from, a -> new TreeMap<>()).put(tx.nonce(), transaction);
            contentHashes.add(id);
            size++;
            return SUCCESS;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Selects up to {@code maxTransactions} transactions for a new block:
     * per sender, the contiguous nonce run starting at the confirmed next nonce,
     * within the confirmed balance. Senders are visited in address order for
     * determinism.
     */
    public List<Transaction> getTransactionsForBlock(int maxTransactions) {
        lock.lock();
        try {
            List<Transaction> selected = new ArrayList<>(Math.min(maxTransactions, size));
            List<PublicAddress> senders = new ArrayList<>(bySender.keySet());
            senders.sort((a, b) -> java.util.Arrays.compareUnsigned(a.toBytes(), b.toBytes()));

            for (PublicAddress sender : senders) {
                if (selected.size() >= maxTransactions) {
                    break;
                }
                NavigableMap<Long, Transaction> pending = bySender.get(sender);
                long nonce = accounts.confirmedNextNonce(sender);
                long budget = accounts.confirmedBalance(sender);
                Transaction next;
                while ((next = pending.get(nonce)) != null && selected.size() < maxTransactions) {
                    var tx = (TransactionImpl) next;
                    long spend = tx.amount().amount() + tx.fee().amount();
                    if (spend > budget) {
                        break;
                    }
                    selected.add(next);
                    budget -= spend;
                    nonce++;
                }
            }
            return selected;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drops a block's transactions and any now-stale ones (nonce below the new
     * confirmed next nonce). Call after the chain applies a block.
     */
    public void onBlockApplied(Block block) {
        lock.lock();
        try {
            for (Transaction t : block.transactions()) {
                if (!((TransactionImpl) t).isTransactionFee()) {
                    remove(((TransactionImpl) t).from(), t.hashContents());
                }
            }
            pruneStale();
        } finally {
            lock.unlock();
        }
    }

    private void pruneStale() {
        for (PublicAddress sender : new ArrayList<>(bySender.keySet())) {
            NavigableMap<Long, Transaction> pending = bySender.get(sender);
            long confirmed = accounts.confirmedNextNonce(sender);
            var stale = pending.headMap(confirmed, false);
            for (Transaction t : new ArrayList<>(stale.values())) {
                contentHashes.remove(t.hashContents());
                size--;
            }
            stale.clear();
            if (pending.isEmpty()) {
                bySender.remove(sender);
            }
        }
    }

    private void remove(PublicAddress sender, SHA256Hash contentHash) {
        NavigableMap<Long, Transaction> pending = bySender.get(sender);
        if (pending == null) {
            return;
        }
        pending.values().removeIf(t -> {
            boolean match = t.hashContents().equals(contentHash);
            if (match) {
                contentHashes.remove(contentHash);
                size--;
            }
            return match;
        });
        if (pending.isEmpty()) {
            bySender.remove(sender);
        }
    }

    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public boolean contains(SHA256Hash contentHash) {
        lock.lock();
        try {
            return contentHashes.contains(contentHash);
        } finally {
            lock.unlock();
        }
    }
}
