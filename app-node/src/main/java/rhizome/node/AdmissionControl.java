package rhizome.node;

import java.util.concurrent.Semaphore;

import rhizome.net.RateLimiter;

/**
 * The node's aggregate admission budgets: what the whole world, summed, may make this node do per
 * second.
 *
 * <p>Every one of these exists because a per-IP limit is not a bound. The HTTP rate limiter caps
 * one client; a handful of clients each inside their own budget still sum to unbounded work on the
 * single event-loop thread, and each of the four gates below closed one instance of that gap after
 * it was found in an audit. They were four unrelated fields of {@code NodeService} with four
 * chained constructors, which had two consequences worth naming: a test that wanted to shrink ONE
 * budget had to restate the other two positionally (they are all {@code RateLimiter}, so a
 * transposition compiled), and {@code mempoolSigGate} — the one that was never made injectable —
 * had no seam at all, so the only aggregate budget with no test is the one guarding Ed25519.
 *
 * <p>What each request costs is declared per route in {@link RoutePolicy}; this is what the costs
 * are charged against.
 */
@lombok.Builder
final class AdmissionControl {

    /**
     * Global (all-clients) cap on the memory-hard PoW verifications that {@code /submit} can
     * trigger per second. The per-IP HTTP limiter allows ~125 submits/s/IP with no aggregate
     * bound, so a single IP resending one PoW-free block (public parent hash, in-window id,
     * garbage nonce) can pin the single event-loop thread on ~40 memory-hard hashes/s and, via the
     * shared consensus lock, stall block production and sync (audit F1). This single-bucket
     * limiter bounds the total across every source IP, well below loop capacity; an over-budget
     * submit is dropped WITHOUT hashing. Declining to speculatively verify is safe: both
     * verification sites already drop non-verifying blocks (orphan admission is best-effort), and
     * honest blocks still arrive via sync, which calls the engine directly and is not gated here.
     */
    static final int SUBMIT_POW_MAX_PER_SEC = 25;

    /**
     * Aggregate (all-IP) cap on mempool transaction admissions per second, bounding the Ed25519
     * verifications {@code /add_transaction} can trigger on the event-loop thread. {@code /submit}
     * has the PoW gate, but {@code /add_transaction} had no equivalent: each admission runs one
     * ~100 µs signature verify inline (MemPool.addTransaction), INVALID signatures are never
     * cached (only valid ones are remembered), so re-playing the same corrupt-signature tx re-pays
     * the crypto every time — and the per-IP limiter has no aggregate bound (audit M1). This
     * single global bucket caps total admissions/s well below loop capacity; an over-budget tx is
     * shed at the HTTP boundary (429) before the body is decoded. Sized generously for honest
     * gossip (a few tx/s network-wide) while making a distributed signature-flood uneconomic.
     */
    static final int MEMPOOL_SIG_MAX_PER_SEC = 100;

    /**
     * Aggregate compute budget for {@code /call_readonly} dry-runs, in gas units per second,
     * summed across every source IP. A dry-run runs the VM interpreter for up to
     * {@code MAX_READONLY_GAS} (25M, clamped in ContractApi to exactly this budget) synchronously
     * on the single event-loop thread; the per-IP HTTP rate limiter bounds only one IP, so a few
     * IPs each within their per-IP budget could still pin the loop with back-to-back gas-sink runs
     * and starve block ingestion/sync (audit 5th-pass, net Finding 1 — the same aggregate-vs-per-IP
     * gap the F1 submit gate closed). This single global bucket caps total dry-run gas/s below loop
     * capacity; an over-budget call is shed (HTTP 429) WITHOUT running the VM. Sized to admit many
     * cheap dashboard queries while throttling repeated max-gas sinks to one per second — a full
     * 25M-gas interpreted run is already a substantial slice of event-loop time (audit: readonly
     * gas calibration).
     */
    static final int READONLY_GAS_MAX_PER_SEC = 25_000_000;

    /**
     * Aggregate budget for the explorer read endpoints that fully decode blocks from RocksDB
     * <em>under the consensus lock</em> ({@code /stats}, {@code /blocks}, {@code /block},
     * {@code /transaction}, {@code /address_txs}), in {@code requestCost} units per second summed
     * across every source IP. The per-IP rate limiter weights these by the blocks they read, but
     * bounds only one IP — so a distributed flood of many IPs, each within its per-IP budget, still
     * sums to unbounded lock-guarded block decodes on the single event-loop thread, contending
     * block production and sync (audit 5th-pass, net Finding 2). This single global bucket caps the
     * total; an over-budget read is shed (HTTP 429) before it decodes anything. Sized well above
     * heavy multi-client dashboard use (each client is already ≤ the per-IP budget) yet far below
     * loop capacity, so it only bites a genuine flood. The peer-serving heavyweights ({@code /sync},
     * {@code /headers}, {@code /state/snapshot/chunk}, {@code /orphan}) are charged to this budget
     * too (RoutePolicy.Guard.READ_BUDGET): they run the same lock-guarded store reads per block
     * served and amplify egress by up to hundreds of blocks (or ~16 MiB) per request, so an
     * unauthenticated flood of "peers" must not sum past the aggregate bound either — the budget is
     * sized far above any plausible convergence traffic, so honest sync is unaffected.
     */
    static final int READ_DECODE_MAX_PER_SEC = 8_000;

    /**
     * Cap on what one dry-run may charge the gas budget, so a caller cannot exhaust a second's
     * worth of budget with a single absurd gasLimit declaration.
     */
    static final long MAX_READONLY_GAS_CHARGE = 25_000_000L;

    /** Concurrent dry-runs admitted; the rest are shed rather than queued behind the loop. */
    static final int MAX_CONCURRENT_DRY_RUNS = 8;

    @lombok.Builder.Default
    private final RateLimiter submitPow = new RateLimiter(SUBMIT_POW_MAX_PER_SEC, 1000, 1);
    @lombok.Builder.Default
    private final RateLimiter mempoolSig = new RateLimiter(MEMPOOL_SIG_MAX_PER_SEC, 1000, 1);
    @lombok.Builder.Default
    private final RateLimiter readonlyGas = new RateLimiter(READONLY_GAS_MAX_PER_SEC, 1000, 1);
    @lombok.Builder.Default
    private final RateLimiter read = new RateLimiter(READ_DECODE_MAX_PER_SEC, 1000, 1);
    @lombok.Builder.Default
    private final Semaphore dryRunSlots = new Semaphore(MAX_CONCURRENT_DRY_RUNS);

    /** The production budgets. */
    static AdmissionControl defaults() {
        return AdmissionControl.builder().build();
    }

    /** Reserves one {@code /submit} PoW verification. */
    boolean trySubmit() {
        return submitPow.allow("submit");
    }

    /** Reserves one mempool admission (one inline Ed25519 verification). */
    boolean tryMempoolSig() {
        return mempoolSig.allow("tx");
    }

    /** Reserves {@code cost} units of the lock-guarded block-decode budget. */
    boolean tryRead(int cost) {
        return read.allow("read", cost);
    }

    /** Reserves {@code gasLimit} gas, charged at most {@link #MAX_READONLY_GAS_CHARGE}. */
    boolean tryReadonlyGas(long gasLimit) {
        return readonlyGas.allow("readonly", (int) Math.min(gasLimit, MAX_READONLY_GAS_CHARGE));
    }

    /** Takes a concurrency slot for a dry-run, or false when all are in use. */
    boolean tryDryRunSlot() {
        return dryRunSlots.tryAcquire();
    }

    void releaseDryRunSlot() {
        dryRunSlots.release();
    }

    int dryRunSlotsAvailable() {
        return dryRunSlots.availablePermits();
    }
}
