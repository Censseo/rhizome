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

    /**
     * How long a fully parked transaction (its sender's confirmed next nonce is absent, so a
     * nonce gap makes NONE of its queued transactions minable) may occupy the pool before it
     * expires (audit: parked-TTL DoS). Without an expiry, gap transactions — cheap to sign,
     * never executable — could occupy their slots forever, and the capacity eviction
     * ({@code makeRoomForParkedSlot}) only helps when the pool is full. 2 hours is far above
     * any honest nonce-gap resolution time (a missing predecessor is one block away) and far
     * below any practical pool-lifetime goal. Purged lazily on {@code addTransaction} and
     * {@code getTransactionsForBlock}, so expiry costs nothing when the pool is idle. Live
     * (contiguous-nonce) transactions never expire this way.
     */
    static final long PARKED_TTL_MILLIS = 2 * 60 * 60 * 1000L;

    /**
     * Minimum fee increase, in percent, for replace-by-fee: a pooled transaction may be replaced
     * by another from the same sender at the same nonce only if the new fee is at least
     * {@code old + max(1, old / RBF_MIN_BUMP_PERCENT)} — strictly greater, so a replacement always
     * pays the miner more, and large enough that repeated replacement cannot churn the pool for
     * free (each bump compounds). A 0-fee transaction thus needs at least fee 1 to be replaced.
     */
    static final long RBF_MIN_BUMP_PERCENT = 10;

    /** How far past the local clock a pooled transaction's timestamp may lie (audit B-4). */
    static final long MAX_TX_FUTURE_DRIFT_MS = 2 * 60 * 60 * 1000L;

    private final NetworkParameters params;
    private final SignatureVerifier verifier;
    private final AccountView accounts;
    private final int maxSize;
    private final int maxPerSender;
    private final java.util.function.LongSupplier clock;

    /** Senders in unsigned-address order, the deterministic tie-break of block selection. */
    private static final java.util.Comparator<PublicAddress> ADDRESS_ORDER =
        (a, b) -> java.util.Arrays.compareUnsigned(a.toBytes(), b.toBytes());
    // A sorted map, so eviction scans and selection seeding iterate senders in a stable order
    // without re-sorting all keys on every block build (audit P11).
    private final NavigableMap<PublicAddress, NavigableMap<Long, Transaction>> bySender =
        new TreeMap<>(ADDRESS_ORDER);
    private final Set<SHA256Hash> contentHashes = new HashSet<>();
    /** Where a pooled transaction lives, so eviction is O(log n) instead of scanning the
     *  sender's whole queue (audit perf: O(queue) removal per block transaction). */
    private record Slot(PublicAddress sender, long nonce) {}
    private final Map<SHA256Hash, Slot> slotByHash = new HashMap<>();
    /** Admission time (ms, {@link #clock}) per pooled transaction — the parked-TTL reference. */
    private final Map<SHA256Hash, Long> admittedAt = new HashMap<>();
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
        this(params, verifier, accounts, maxSize, maxPerSender, System::currentTimeMillis);
    }

    /** As above, with an explicit clock (tests) driving the parked-TTL expiry. */
    public MemPool(NetworkParameters params, SignatureVerifier verifier, AccountView accounts,
                   int maxSize, int maxPerSender, java.util.function.LongSupplier clock) {
        this.params = params;
        this.verifier = verifier;
        this.accounts = accounts;
        this.maxSize = maxSize;
        this.maxPerSender = maxPerSender;
        this.clock = clock;
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
        // Optional minimum-fee floor (0 = disabled, the default on testnet), so an operator can
        // refuse free transactions at admission rather than have the pool fill with zero-fee spam
        // (audit L5). Contract calls pay through gas, not the fee field, so their declared gas
        // budget counts toward the floor — its full value is already locked by the cumulative
        // balance check below, and their realized revenue is bounded below by the intrinsic CALL
        // gas charge, so a zero-fee, zero-gasPrice call can never sneak past a positive floor.
        if (params.minFee() > 0 && minerRevenue(tx) < params.minFee()) {
            return TRANSACTION_FEE_TOO_LOW;
        }
        if (tx.kind().isContract() && (tx.gasLimit() < 0 || tx.gasPrice() < 0)) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        // Mirror the consensus per-transaction gas ceiling at admission so an over-cap call is never
        // pooled or relayed (it would be rejected by executeBlock anyway). Defense in depth against the
        // unbounded-consensus-gas vector; the block-wide ceiling stays a consensus-only rule.
        if (tx.kind().isContract() && params.maxTxGas() > 0 && tx.gasLimit() > params.maxTxGas()) {
            return GAS_LIMIT_EXCEEDED;
        }
        // Box ops run no VM and cost no gas; the gas fields are reserved and must be zero.
        if (tx.kind().isBox() && (tx.gasLimit() != 0 || tx.gasPrice() != 0)) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        // Token ops carry no gas and move no PDN (the token amount is in the payload).
        if (tx.kind().isToken() && (tx.gasLimit() != 0 || tx.gasPrice() != 0 || tx.amount().amount() != 0)) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        // Activation-height gate, mirrored from the executor. A box/token tx is only valid in a block at
        // or above its activation height; the executor otherwise hard-fails such a tx (BOX_UNAVAILABLE /
        // TOKEN_UNAVAILABLE), which aborts the WHOLE block. Without this gate a pre-activation box/token
        // tx would be admitted, selected into every candidate block, and halt production until activation.
        // Reject it here so it never enters the pool. Compared as `confirmedHeight < activation - 1` to
        // avoid overflowing the Long.MAX_VALUE "no gating" sentinel; the shipped networks activate at 0.
        long confirmedHeight = accounts.confirmedHeight();
        if (tx.kind().isBox() && params.boxActivationHeight() > 0
            && confirmedHeight < params.boxActivationHeight() - 1) {
            return BOX_UNAVAILABLE;
        }
        if (tx.kind().isToken() && params.tokenActivationHeight() > 0
            && confirmedHeight < params.tokenActivationHeight() - 1) {
            return TOKEN_UNAVAILABLE;
        }
        if (!PublicAddress.of(tx.signingKey()).equals(tx.from())) {
            return WALLET_SIGNATURE_MISMATCH;
        }
        // Timestamp sanity window: the field is signed but otherwise unconstrained, so a tx
        // stamped far in the future could sit in the pool as permanently "fresh" junk with no
        // consensus rule ever catching it (the signed field was inert — audit B-4). A generous
        // 2-hour future drift absorbs honest clock skew; negative timestamps are malformed.
        long txTimestamp = tx.timestamp();
        if (txTimestamp < 0 || txTimestamp > clock.getAsLong() + MAX_TX_FUTURE_DRIFT_MS) {
            return INVALID_TRANSACTION_TIMESTAMP;
        }

        lock.lock();
        try {
            purgeExpiredParked(); // lazy TTL: gap-parked transactions eventually die (PARKED_TTL_MILLIS)
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
            Transaction replaced = null;
            if (pending != null && (replaced = pending.get(tx.nonce())) != null) {
                // Replace-by-fee (RBF_MIN_BUMP_PERCENT): a LIVE pooled transaction (in the sender's
                // contiguous, currently-minable nonce run) may be replaced by one paying strictly
                // more TO THE MINER — compared on minerRevenue (fee + declared gas budget for
                // calls), the same metric as the admission floor, so a CALL cannot be "replaced"
                // by a tx with a higher plain fee but lower actual revenue (audit follow-up).
                // Without a bump rule a sender whose fee became uncompetitive could never raise
                // it (its only resubmit was rejected as a duplicate), and a free replacement
                // would let anyone churn slots. A PARKED transaction is never replaced here —
                // it expires by TTL or yields to the capacity eviction instead.
                long oldRevenue = minerRevenue((TransactionImpl) replaced);
                long required;
                try {
                    required = Math.addExact(oldRevenue, Math.max(1, oldRevenue / RBF_MIN_BUMP_PERCENT));
                } catch (ArithmeticException overflow) {
                    return TRANSACTION_FEE_TOO_LOW; // an astronomical old revenue cannot be bumped
                }
                if (minerRevenue(tx) < required || !isLive(pending, confirmedNonce, tx.nonce())) {
                    return INVALID_TRANSACTION_NONCE; // duplicate nonce without a sufficient bump
                }
            }
            if (pending != null && pending.size() >= maxPerSender && replaced == null) {
                return QUEUE_FULL; // one sender cannot monopolise the pool
            }

            // Cumulative spend across this sender's pending set + candidate. A contract
            // transaction can spend its attached value plus the whole gas budget. A replaced
            // transaction's spend is excluded — it leaves the pool in the same insert below.
            long spend = maxSpend(tx);
            if (pending != null) {
                for (Transaction p : pending.values()) {
                    if (p != replaced) {
                        spend += maxSpend((TransactionImpl) p);
                    }
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
            if (size >= maxSize && replaced == null && !makeRoomForParkedSlot(from, tx)) {
                return QUEUE_FULL;
            }

            if (replaced != null) {
                contentHashes.remove(replaced.hashContents());
                slotByHash.remove(replaced.hashContents());
                admittedAt.remove(replaced.hashContents());
                size--;
            }
            bySender.computeIfAbsent(from, a -> new TreeMap<>()).put(tx.nonce(), transaction);
            contentHashes.add(id);
            slotByHash.put(id, new Slot(from, tx.nonce()));
            admittedAt.put(id, clock.getAsLong());
            size++;
            return SUCCESS;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether the pooled transaction at {@code nonce} is LIVE: part of the sender's contiguous
     * nonce run starting at the confirmed next nonce, so it is currently minable. Only live
     * transactions are replaceable by fee bump (see {@code addTransaction}).
     */
    private static boolean isLive(NavigableMap<Long, Transaction> pending, long confirmedNonce, long nonce) {
        if (nonce < confirmedNonce) {
            return false;
        }
        return pending.subMap(confirmedNonce, true, nonce, true).size() == nonce - confirmedNonce + 1;
    }

    /**
     * Lazy parked-TTL expiry (PARKED_TTL_MILLIS): drops every transaction whose sender is FULLY
     * parked — its confirmed next nonce absent, so a nonce gap makes none of its queue minable —
     * and that has sat in the pool past the TTL. Live queues are never touched. Runs on
     * {@code addTransaction} and {@code getTransactionsForBlock}, so an idle pool costs nothing.
     *
     * <p>Throttled to at most one full scan per {@link #PURGE_INTERVAL_MS}: each sender check
     * calls {@code confirmedNextNonce}, which takes the consensus lock and reads the nonce store,
     * so an unthrottled per-add scan let a cheap multi-sender flood turn every admission into
     * O(senders) consensus-lock acquisitions (audit follow-up: anti-DoS fix turned amplifier).
     * The TTL is hours; a minute of extra lag changes nothing.
     */
    private static final long PURGE_INTERVAL_MS = 60_000L;
    // Seeded far in the past so the very first call always scans (tests and boot both rely on it).
    private long lastPurgeAt = Long.MIN_VALUE / 2;

    private void purgeExpiredParked() {
        long now = clock.getAsLong();
        if (now - lastPurgeAt < PURGE_INTERVAL_MS) {
            return;
        }
        lastPurgeAt = now;
        for (PublicAddress sender : new ArrayList<>(bySender.keySet())) {
            NavigableMap<Long, Transaction> pending = bySender.get(sender);
            if (pending.containsKey(accounts.confirmedNextNonce(sender))) {
                continue; // live queue — the TTL only expires never-minable dead weight
            }
            for (Transaction t : new ArrayList<>(pending.values())) {
                Long since = admittedAt.get(t.hashContents());
                if (since != null && now - since >= PARKED_TTL_MILLIS) {
                    remove(t.hashContents());
                }
            }
        }
    }

    /**
     * The revenue a miner can earn from {@code tx}: the plain fee for value/box/token ops; for a
     * contract call the fee plus its declared gas budget ({@code gasLimit × gasPrice}, saturating)
     * — an upper bound on the realized {@code gasUsed × gasPrice}, deterministic at assembly time
     * when gasUsed is still unknown. Used for the minFee admission floor and the RBF bump
     * (audit M9) — NOT for selection ordering, see {@link #priorityRate}.
     */
    private static long minerRevenue(TransactionImpl tx) {
        if (!tx.kind().isContract()) {
            return tx.fee().amount();
        }
        try {
            return Math.addExact(tx.fee().amount(), Math.multiplyExact(tx.gasLimit(), tx.gasPrice()));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Selection priority: miner revenue per unit of declared block weight — {@code gasLimit}
     * for a contract call, 1 for a fixed-cost op (so its priority is simply its fee). Ordering
     * on raw {@link #minerRevenue} let a transaction buy the front of every block with gas it
     * never pays: a CALL that reverts immediately is charged only {@code gasUsed × gasPrice}
     * (CALL_BASE) but could declare up to maxBlockGas, outranking all honest traffic for the
     * cost of a temporarily locked balance. Bitcoin/Ethereum order by a rate (sat/vB, gas
     * price) for exactly this reason. {@code minerRevenue} stays the metric for the admission
     * floor and RBF, where the total locked value is what matters.
     */
    private static long priorityRate(TransactionImpl tx) {
        long weight = tx.kind().isContract() ? Math.max(1L, tx.gasLimit()) : 1L;
        return minerRevenue(tx) / weight;
    }

    /**
     * Selects up to {@code maxTransactions} transactions for a new block: per sender, the
     * contiguous nonce run starting at the confirmed next nonce, within the confirmed balance.
     *
     * <p>Selection is greedy by revenue RATE (audit M9, then fee-market fix): at each step the
     * highest-{@link #priorityRate} <em>currently selectable</em> transaction — the front of
     * some sender's contiguous run — is taken, then that sender's run advances. Ties break by
     * nonce then address, so the result is still a deterministic pure function of pool + chain
     * state. The previous raw-address-order iteration let an attacker grind low-prefix
     * addresses and fill every block with zero-fee transactions, permanently crowding out
     * fee-paying traffic for free; miners now always take the best-paying executable work
     * first — and paying for priority requires a real rate, not a declared-never-paid budget.
     */
    public List<Transaction> getTransactionsForBlock(int maxTransactions) {
        lock.lock();
        try {
            purgeExpiredParked(); // lazy TTL: never select expired gap-parked dead weight
            // Per-sender selection cursor: the next nonce of its contiguous run and the balance
            // still available to that run. Only the cursor's front tx is a selection candidate.
            record Cursor(long nextNonce, long budget) {}
            Map<PublicAddress, Cursor> cursors = new HashMap<>();
            // The candidate frontier: one entry per sender, ordered by priority rate (desc),
            // then nonce, then address — a total order, so the greedy pick is deterministic.
            java.util.PriorityQueue<Map.Entry<PublicAddress, Transaction>> frontier =
                new java.util.PriorityQueue<>(Map.Entry.<PublicAddress, Transaction>comparingByValue(
                    (a, b) -> {
                        var ta = (TransactionImpl) a;
                        var tb = (TransactionImpl) b;
                        int byRate = Long.compare(priorityRate(tb), priorityRate(ta));
                        if (byRate != 0) {
                            return byRate;
                        }
                        int byNonce = Long.compare(ta.nonce(), tb.nonce());
                        return byNonce != 0 ? byNonce : java.util.Arrays.compareUnsigned(
                            ta.from().toBytes(), tb.from().toBytes());
                    }).thenComparing(Map.Entry.comparingByKey(ADDRESS_ORDER)));
            for (Map.Entry<PublicAddress, NavigableMap<Long, Transaction>> e : bySender.entrySet()) {
                if (!accounts.senderExists(e.getKey())) {
                    continue; // never select an unexecutable sender (see addTransaction)
                }
                long nonce = accounts.confirmedNextNonce(e.getKey());
                Transaction front = e.getValue().get(nonce);
                if (front != null) {
                    cursors.put(e.getKey(), new Cursor(nonce, accounts.confirmedBalance(e.getKey())));
                    frontier.add(Map.entry(e.getKey(), front));
                }
            }
            List<Transaction> selected = new ArrayList<>(Math.min(maxTransactions, size));
            while (selected.size() < maxTransactions && !frontier.isEmpty()) {
                var best = frontier.poll();
                PublicAddress sender = best.getKey();
                Cursor cursor = cursors.get(sender);
                var tx = (TransactionImpl) best.getValue();
                long spend = maxSpend(tx);
                if (spend <= cursor.budget()) {
                    selected.add(tx);
                    NavigableMap<Long, Transaction> pending = bySender.get(sender);
                    Transaction next = pending.get(cursor.nextNonce() + 1);
                    cursors.put(sender, new Cursor(cursor.nextNonce() + 1, cursor.budget() - spend));
                    if (next != null) {
                        frontier.add(Map.entry(sender, next));
                    }
                }
                // Over-budget: the sender's run stalls here (its later nonces cannot be selected
                // either), so nothing is re-offered for it.
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
                    remove(t.hashContents());
                }
            }
            pruneStale();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Throttle for the parked-candidate scan in {@link #makeRoomForParkedSlot}: each sender check
     * calls {@code confirmedNextNonce}, which takes the consensus lock and reads the nonce store,
     * so an unthrottled per-admission scan let a flood of signed gap-transactions turn every
     * admission into O(pool-senders) consensus-lock acquisitions — with the mempool lock held
     * across the whole scan, so admissions transitively stalled behind multi-second {@code
     * addBlock} executions (audit follow-up: the eviction path needed the same throttle its
     * sibling {@link #purgeExpiredParked} already had). One scan per second is ample: evictions
     * between scans drain the cached candidate list, and each candidate is re-validated live
     * (O(1)) immediately before removal, so a stale cache can never evict a sender that has
     * become live since the scan.
     */
    private static final long PARKED_SCAN_INTERVAL_MS = 1_000L;
    // Seeded far in the past so the very first call always scans (tests and boot both rely on it).
    private long lastParkedScanAt = Long.MIN_VALUE / 2;
    private List<Map.Entry<Transaction, Long>> parkedCandidates = List.of();

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
        long now = clock.getAsLong();
        if (now - lastParkedScanAt >= PARKED_SCAN_INTERVAL_MS) {
            lastParkedScanAt = now;
            List<Map.Entry<Transaction, Long>> candidates = new ArrayList<>();
            for (Map.Entry<PublicAddress, NavigableMap<Long, Transaction>> e : bySender.entrySet()) {
                NavigableMap<Long, Transaction> pending = e.getValue();
                long confirmed = accounts.confirmedNextNonce(e.getKey());
                if (pending.containsKey(confirmed)) {
                    continue; // sender is making progress (front present) — never evict a live queue
                }
                // Fully parked: its deepest (highest-nonce) tx is the furthest from ever being minable.
                Transaction deepest = pending.lastEntry().getValue();
                candidates.add(Map.entry(deepest, ((TransactionImpl) deepest).fee().amount()));
            }
            parkedCandidates = candidates;
        }
        Transaction victim = null;
        long victimFee = Long.MAX_VALUE;
        for (Map.Entry<Transaction, Long> c : parkedCandidates) {
            // Skip candidates already gone from the pool (evicted, expired or replaced since the scan).
            // victim == null: the first live candidate is always elected, even if its fee were
            // Long.MAX_VALUE (parity with the pre-cache scan loop).
            if ((victim == null || c.getValue() < victimFee) && contentHashes.contains(c.getKey().hashContents())) {
                victim = c.getKey();
                victimFee = c.getValue();
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
        // The parked status is cached: re-verify the victim's sender is STILL fully parked (one
        // consensus-lock read, not a scan) so a sender unparked since the scan never loses a live tx.
        Slot victimSlot = slotByHash.get(victim.hashContents());
        if (victimSlot == null) {
            return false;
        }
        NavigableMap<Long, Transaction> victimPending = bySender.get(victimSlot.sender());
        if (victimPending == null
                || victimPending.containsKey(accounts.confirmedNextNonce(victimSlot.sender()))) {
            return false;
        }
        remove(victim.hashContents());
        return true;
    }

    private void pruneStale() {
        for (PublicAddress sender : new ArrayList<>(bySender.keySet())) {
            NavigableMap<Long, Transaction> pending = bySender.get(sender);
            long confirmed = accounts.confirmedNextNonce(sender);
            var stale = pending.headMap(confirmed, false);
            for (Transaction t : new ArrayList<>(stale.values())) {
                contentHashes.remove(t.hashContents());
                slotByHash.remove(t.hashContents());
                admittedAt.remove(t.hashContents());
                size--;
            }
            stale.clear();
            if (pending.isEmpty()) {
                bySender.remove(sender);
            }
        }
    }

    private void remove(SHA256Hash contentHash) {
        Slot slot = slotByHash.remove(contentHash);
        if (slot == null) {
            return; // unknown (or already evicted) — nothing indexed for this hash
        }
        NavigableMap<Long, Transaction> pending = bySender.get(slot.sender());
        if (pending != null) {
            pending.remove(slot.nonce());
            if (pending.isEmpty()) {
                bySender.remove(slot.sender());
            }
        }
        contentHashes.remove(contentHash);
        admittedAt.remove(contentHash);
        size--;
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
