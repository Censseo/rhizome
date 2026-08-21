package rhizome.node;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import rhizome.core.mempool.ExecutionStatus;

/**
 * Gossip ban-score for the PUSH paths: a bounded, windowed strike count per client.
 *
 * <p>The pull-sync ban list only punishes what this node fetches, never what is pushed at it, so a
 * peer spamming {@code /submit} with invalid blocks or {@code /add_transaction} with
 * corrupt-signature transactions accumulated no score at all (audit: gossip push ban-score). Past
 * {@link #STRIKE_LIMIT} faults in a window the HTTP boundary sheds that client's next push with
 * 429 <em>before decoding the body</em>, for the rest of the window. The peer is NOT evicted from
 * the registry — honest pull-sync is unaffected.
 *
 * <p>The table is bounded because its key is attacker-influenced, and the bound is where this
 * gets subtle. Overflow used to be metered into a shared bucket that the shed never consulted, so
 * once 8192 keys were live the shed silently stopped applying to every new IP (audit I-2,
 * fail-open). Consulting that bucket instead would have been worse: shedding every untracked
 * client hands an attacker a global push-path DoS for the price of filling the table, the same
 * eclipse lever {@code PeerBanList} refuses for its own overflow. Evicting the stalest window has
 * neither failure mode, and the victim is another recent offender — honest clients accrue no
 * strikes at all (see {@link #isFault}).
 */
final class PushStrikeTable {

    /** Push faults a client may commit per rolling window before the early shed applies. */
    static final int STRIKE_LIMIT = 20;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_KEYS = 8_192;

    private static final class Strikes {
        volatile long windowStart;
        int count;
        Strikes(long windowStart) { this.windowStart = windowStart; }
    }

    private final Map<String, Strikes> strikes = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    PushStrikeTable() {
        this(System::currentTimeMillis);
    }

    /**
     * @param clock wall-clock source in milliseconds. Injectable because the window is the whole
     *              behaviour and it is a minute long: without a clock seam, "the count resets when
     *              the window rolls" can only be asserted by sleeping through it, so it never was.
     */
    PushStrikeTable(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * True for a push outcome that is PROVABLE junk — an explicit whitelist, never a prefix match.
     * Honest gossip routinely produces non-success statuses that must NOT strike: a rebroadcast
     * block we already have ({@code INVALID_BLOCK_ID} / {@code INVALID_LASTBLOCK_HASH}), an uncle
     * reference our pool has not seen ({@code INVALID_UNCLES} — normal for a node with an empty
     * orphan pool, exactly the condition the sync path now fetches around), a nonce consumed
     * meanwhile or an insufficient RBF bump ({@code INVALID_TRANSACTION_NONCE}), clock drift
     * ({@code INVALID_TRANSACTION_TIMESTAMP}, {@code BLOCK_TIMESTAMP_TOO_OLD/TOO_CLOSE}), a state
     * root our accumulator config disagrees on ({@code INVALID_STATE_ROOT}), a balance view race
     * ({@code BALANCE_TOO_LOW}), a duplicate tx ({@code ALREADY_IN_QUEUE}) or an anti-DoS shed
     * ({@code SUBMIT_THROTTLED}). Striking those would throttle honest peers (audit collateral).
     * The listed statuses are all deterministic structural violations no valid block/tx produces.
     */
    static boolean isFault(ExecutionStatus status) {
        return status != null && FAULT_STATUSES.contains(status);
    }

    private static final Set<ExecutionStatus> FAULT_STATUSES = Set.of(
        ExecutionStatus.INVALID_SIGNATURE,
        ExecutionStatus.INVALID_NONCE,               // failed PoW
        ExecutionStatus.INVALID_CHAIN_ID,
        ExecutionStatus.INVALID_DIFFICULTY,
        ExecutionStatus.INVALID_MERKLE_ROOT,
        ExecutionStatus.INVALID_TRANSACTION_COUNT,
        ExecutionStatus.BLOCK_TOO_LARGE,
        ExecutionStatus.BLOCK_ID_TOO_LARGE,
        ExecutionStatus.INVALID_TRANSACTION_AMOUNT,
        ExecutionStatus.TRANSACTION_FEE_TOO_LOW,
        ExecutionStatus.INVALID_VOTE,
        ExecutionStatus.HEADER_HASH_INVALID,
        // § supply header commitment: a wrong accounting delta, a mid-chain start or a dropped
        // commitment are all deterministic structural violations no valid block produces — the
        // same provable-junk bar as every other entry here.
        ExecutionStatus.INVALID_SUPPLY);

    /**
     * True when {@code clientKey} has pushed more than {@link #STRIKE_LIMIT} faults inside the
     * current window: the HTTP boundary should shed its next push with 429 before decoding the
     * body, for the rest of the window.
     */
    boolean isShed(String clientKey) {
        Strikes s = strikes.get(clientKey);
        if (s == null) {
            return false;
        }
        long now = clock.getAsLong();
        synchronized (s) {
            if (now - s.windowStart >= WINDOW_MS) {
                return false; // stale window: the next fault starts a fresh count
            }
            return s.count >= STRIKE_LIMIT;
        }
    }

    /** Records one fault against {@code clientKey}, rolling or creating its window as needed. */
    void note(String clientKey) {
        long now = clock.getAsLong();
        Strikes s = strikes.get(clientKey);
        if (s == null) {
            // Check-and-insert under one monitor (the PeerBanList discipline): a lock-free size()
            // check racing computeIfAbsent lets concurrent faults push the table past its bound.
            synchronized (strikes) {
                s = strikes.get(clientKey);
                if (s == null) {
                    if (strikes.size() >= MAX_KEYS) {
                        // Table full: reclaim expired windows first, then make room by dropping
                        // the STALEST window. The client offending right now is always tracked,
                        // so its next fault sheds. See the class javadoc for why neither
                        // fail-open nor shed-the-untracked is acceptable here.
                        strikes.values().removeIf(w -> now - w.windowStart >= WINDOW_MS);
                        if (strikes.size() >= MAX_KEYS) {
                            evictStalest();
                        }
                    }
                    s = strikes.computeIfAbsent(clientKey, k -> new Strikes(now));
                }
            }
        }
        synchronized (s) {
            if (now - s.windowStart >= WINDOW_MS) {
                s.windowStart = now;
                s.count = 0;
            }
            s.count++;
        }
    }

    /** Drops the entry whose window started longest ago, freeing exactly one slot. Called only
     *  under the {@link #strikes} monitor, and only when the table is full and nothing expired —
     *  the same O(n) cost the sweep just above already pays in that state. */
    private void evictStalest() {
        String stalestKey = null;
        long stalest = Long.MAX_VALUE;
        for (var e : strikes.entrySet()) {
            if (e.getValue().windowStart < stalest) {
                stalest = e.getValue().windowStart;
                stalestKey = e.getKey();
            }
        }
        if (stalestKey != null) {
            strikes.remove(stalestKey);
        }
    }

    /** Strikes recorded for {@code clientKey} in the current window; 0 once the window rolls. */
    int count(String clientKey) {
        Strikes s = strikes.get(clientKey);
        if (s == null) {
            return 0;
        }
        synchronized (s) {
            return clock.getAsLong() - s.windowStart >= WINDOW_MS ? 0 : s.count;
        }
    }

    /** Tracked keys, for tests and diagnostics. */
    int size() {
        return strikes.size();
    }
}
