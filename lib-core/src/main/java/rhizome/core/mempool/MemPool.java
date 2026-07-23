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
import rhizome.crypto.SHA256Hash;
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

    /** Default per-sender ceiling: no honest account queues this many pending nonces. */
    private static final int DEFAULT_MAX_PER_SENDER = 1024;

    private final NetworkParameters params;
    private final SignatureVerifier verifier;
    private final AccountView accounts;
    private final int maxSize;
    private final int maxPerSender;

    /** Senders in unsigned-address order, so a block's transactions are selected deterministically. */
    private static final java.util.Comparator<PublicAddress> ADDRESS_ORDER =
        (a, b) -> java.util.Arrays.compareUnsigned(a.toBytes(), b.toBytes());
    // A sorted map, so getTransactionsForBlock iterates senders in address order without re-sorting
    // all keys on every block build (audit P11). The selection order is byte-identical to the old
    // explicit sort, so produced blocks (and their merkle roots) are unchanged.
    private final NavigableMap<PublicAddress, NavigableMap<Long, Transaction>> bySender =
        new TreeMap<>(ADDRESS_ORDER);
    private final Set<SHA256Hash> contentHashes = new HashSet<>();
    private final ReentrantLock lock = new ReentrantLock();
    private int size;

    public MemPool(NetworkParameters params, SignatureVerifier verifier, AccountView accounts, int maxSize) {
        this(params, verifier, accounts, maxSize, Math.min(maxSize, DEFAULT_MAX_PER_SENDER));
    }

    /**
     * As above, but with an explicit per-sender cap: one account cannot occupy more
     * than {@code maxPerSender} pooled transactions, so a single sender cannot flood
     * the whole pool and crowd out everyone else (the global {@code maxSize} bounds
     * total memory; this bounds per-account fairness).
     */
    public MemPool(NetworkParameters params, SignatureVerifier verifier, AccountView accounts,
                   int maxSize, int maxPerSender) {
        this.params = params;
        this.verifier = verifier;
        this.accounts = accounts;
        this.maxSize = maxSize;
        this.maxPerSender = maxPerSender;
    }

    /**
     * Maximum this transaction can debit its sender: value + fee, or value + full gas budget.
     * Overflow saturates to {@link Long#MAX_VALUE} instead of throwing: an overflowing
     * {@code gasLimit*gasPrice} used to escape {@code addTransaction} as an unhandled
     * ArithmeticException (audit M7); now it just reads as "more than any balance" and the
     * caller's balance check rejects it (BALANCE_TOO_LOW).
     */
    private static long maxSpend(TransactionImpl tx) {
        try {
            if (tx.kind().isContract()) {
                return Math.addExact(tx.amount().amount(), Math.multiplyExact(tx.gasLimit(), tx.gasPrice()));
            }
            return Math.addExact(tx.amount().amount(), tx.fee().amount());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public ExecutionStatus addTransaction(Transaction transaction) {
        var tx = (TransactionImpl) transaction;
        if (tx.isTransactionFee()) {
            return INVALID_TRANSACTION_NONCE; // coinbase is minted in blocks, never pooled
        }
        if (tx.kind() == rhizome.core.transaction.TransactionKind.BOX_COLLECT) {
            return INVALID_TRANSACTION_NONCE; // rent collection is minted in blocks, never pooled
        }
        if (tx.chainId() != params.chainId()) {
            return INVALID_CHAIN_ID;
        }
        if (tx.amount().amount() < 0 || tx.fee().amount() < 0) {
            return INVALID_TRANSACTION_AMOUNT; // negative would mint money / force negative balances
        }
        // Optional minimum-fee floor (0 = disabled, the default), so an operator can refuse free
        // transactions at admission rather than have the pool fill with zero-fee spam (audit L5).
        if (params.minFee() > 0 && tx.fee().amount() < params.minFee() && !tx.isTransactionFee()) {
            return TRANSACTION_FEE_TOO_LOW;
        }
        if (tx.kind().isContract() && (tx.gasLimit() < 0 || tx.gasPrice() < 0)) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        // Box ops run no VM and cost no gas; the gas fields are reserved and must be zero.
        if (tx.kind().isBox() && (tx.gasLimit() != 0 || tx.gasPrice() != 0)) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        // Token ops carry no gas and move no PDN (the token amount is in the payload).
        if (tx.kind().isToken() && (tx.gasLimit() != 0 || tx.gasPrice() != 0 || tx.amount().amount() != 0)) {
            return INVALID_TRANSACTION_AMOUNT;
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

            PublicAddress from = tx.from();
            // Sender must have a confirmed wallet, exactly as the block executor requires
            // (SENDER_DOES_NOT_EXIST). Without this, a free signed no-op (amount 0, fee 0)
            // from a fresh keypair is admitted, selected into every candidate block, gets the
            // block rejected at execution, is never purged, and halts production network-wide.
            if (!accounts.senderExists(from)) {
                return SENDER_DOES_NOT_EXIST;
            }
            long confirmedNonce = accounts.confirmedNextNonce(from);
            if (tx.nonce() < confirmedNonce) {
                return INVALID_TRANSACTION_NONCE; // already spent
            }

            NavigableMap<Long, Transaction> pending = bySender.get(from);
            if (pending != null && pending.containsKey(tx.nonce())) {
                return INVALID_TRANSACTION_NONCE; // duplicate nonce (no replace-by-fee yet)
            }
            if (pending != null && pending.size() >= maxPerSender) {
                return QUEUE_FULL; // one sender cannot monopolise the pool
            }

            // Cumulative spend across this sender's pending set + candidate. A contract
            // transaction can spend its attached value plus the whole gas budget.
            long spend = maxSpend(tx);
            if (pending != null) {
                for (Transaction p : pending.values()) {
                    spend += maxSpend((TransactionImpl) p);
                }
            }
            if (spend < 0 || spend > accounts.confirmedBalance(from)) {
                return BALANCE_TOO_LOW;
            }

            // Signature is verified BEFORE the capacity/eviction step, which is the only step here
            // that mutates the pool: makeRoomForParkedSlot evicts a parked victim, and if that ran
            // ahead of verification an attacker could evict honest parked transactions for free with
            // a garbage signature — an unauthenticated, zero-cost censorship of a full pool (audit
            // V4). The cheap read-only gates above still run first, so verify only ever pays for a
            // structurally-valid candidate; only the state-changing eviction now sits behind it.
            if (!verifier.verify(transaction)) {
                return INVALID_SIGNATURE;
            }

            // Capacity, checked after the validity gates AND the signature so eviction runs only for a
            // genuine, authenticated candidate. A full pool no longer blindly rejects: if some sender
            // is fully parked (its confirmed nonce is absent, so NONE of its txs are minable now) that
            // dead weight yields to a more useful newcomer, so honest ready/fee-paying traffic can
            // never be crowded out permanently by parked gap-txs (audit 5th-pass, mempool censorship).
            // If the pool is full of live txs instead, this is legitimate saturation and we still shed
            // the newcomer.
            if (size >= maxSize && !makeRoomForParkedSlot(from, tx)) {
                return QUEUE_FULL;
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
            // bySender is already ordered by ADDRESS_ORDER, so its keySet yields senders in the
            // deterministic address order the block needs — no per-build re-sort (audit P11).
            for (PublicAddress sender : bySender.keySet()) {
                if (selected.size() >= maxTransactions) {
                    break;
                }
                if (!accounts.senderExists(sender)) {
                    continue; // never select an unexecutable sender (see addTransaction)
                }
                NavigableMap<Long, Transaction> pending = bySender.get(sender);
                long nonce = accounts.confirmedNextNonce(sender);
                long budget = accounts.confirmedBalance(sender);
                Transaction next;
                while ((next = pending.get(nonce)) != null && selected.size() < maxTransactions) {
                    var tx = (TransactionImpl) next;
                    long spend = maxSpend(tx);
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

    /**
     * Called only when the pool is at capacity. Reclaims one slot held by a <em>fully parked</em>
     * sender — one whose confirmed next nonce is absent from its pending set, so none of its queued
     * transactions can be selected into a block now or by any contiguous run — in favour of a more
     * useful {@code incoming} transaction. Returns {@code true} iff a slot was freed (leaving room for
     * the caller to insert).
     *
     * <p>This is the eviction half of the nonce-gap-parking defence (audit 5th-pass): a pool with no
     * eviction and no TTL could be filled once, cheaply and permanently, with individually-valid but
     * never-minable gap transactions, censoring all honest traffic network-wide. A ready or
     * higher-fee newcomer now always displaces that dead weight. A live (progressing) sender is never
     * evicted, so legitimate saturation still yields {@code QUEUE_FULL}.
     */
    private boolean makeRoomForParkedSlot(PublicAddress from, TransactionImpl incoming) {
        PublicAddress victimSender = null;
        Transaction victim = null;
        long victimFee = Long.MAX_VALUE;
        for (Map.Entry<PublicAddress, NavigableMap<Long, Transaction>> e : bySender.entrySet()) {
            NavigableMap<Long, Transaction> pending = e.getValue();
            long confirmed = accounts.confirmedNextNonce(e.getKey());
            if (pending.containsKey(confirmed)) {
                continue; // sender is making progress (front present) — never evict a live queue
            }
            // Fully parked: its deepest (highest-nonce) tx is the furthest from ever being minable.
            Transaction deepest = pending.lastEntry().getValue();
            long fee = ((TransactionImpl) deepest).fee().amount();
            if (victim == null || fee < victimFee) {
                victimSender = e.getKey();
                victim = deepest;
                victimFee = fee;
            }
        }
        if (victim == null) {
            return false; // no parked slots — the pool is legitimately full of live transactions
        }
        // Only displace parked dead weight for a newcomer worth more than it: one that is itself ready
        // (immediately minable for its sender) or pays a strictly higher fee than the victim. A gapped,
        // no-higher-fee newcomer cannot churn the pool.
        boolean incomingReady = incoming.nonce() == accounts.confirmedNextNonce(from);
        if (!incomingReady && incoming.fee().amount() <= victimFee) {
            return false;
        }
        remove(victimSender, victim.hashContents());
        return true;
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
