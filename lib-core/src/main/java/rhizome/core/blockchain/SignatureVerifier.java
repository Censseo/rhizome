package rhizome.core.blockchain;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import rhizome.crypto.SHA256Hash;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionImpl;

/**
 * Fast Ed25519 verification for the validation hot path, targeting the
 * one-block-per-second goal where signature checks dominate CPU (~105 µs each).
 *
 * <p>Two levers:
 * <ul>
 *   <li><b>Verify once</b> — a bounded cache of already-verified transaction
 *       identities (content hash + signature). A transaction verified on mempool
 *       admission is a cache hit at block-validation time, so in steady state
 *       block validation pays almost nothing for signatures.</li>
 *   <li><b>Verify in parallel</b> — a cache miss set is checked across all cores
 *       (each {@code Ed25519Signer} is independent), turning the per-core limit
 *       into a per-machine one.</li>
 * </ul>
 *
 * <p>Thread-safe. The cache identity binds the content hash to the exact
 * signature bytes, so a cached content hash cannot validate a different
 * (malleable) signature.
 */
public final class SignatureVerifier {

    private final ForkJoinPool pool;
    // Two-generation ("clock") cache instead of an access-order LRU: a ConcurrentHashMap's
    // partial eviction walks bucket order, so a single global monitor (synchronizedMap +
    // LinkedHashMap access-order) was needed to get true LRU semantics — but that monitor mutates
    // on every read, which throttled parallel scaling to 43-54% of the raw Ed25519 ceiling (audit
    // review; see Ed25519ScalingBenchmark). Reads check `young` then `old`; an `old` hit is
    // promoted into `young` so hot entries survive the swap. `young` fills to `generation`
    // entries, then `rotate()` retires `old` and starts a fresh `young` — so an entry is only
    // evicted after two full generations without a touch. Bounded, approximately-LRU, lock-free
    // on the read/insert path; `rotate()` is the only synchronized section and it is rare.
    // Evicted entries are re-verifiable, so eviction only ever costs a recompute, never
    // correctness.
    private volatile Map<CacheKey, Boolean> young = new ConcurrentHashMap<>();
    private volatile Map<CacheKey, Boolean> old = new ConcurrentHashMap<>();
    private final int generation;

    /**
     * Cache identity: content hash + signature bytes + the signing public key. Including the
     * signature bytes defends against Ed25519 malleability; including the signing key means a
     * cached "valid" verdict is bound to the exact key that produced it, not just to
     * {@code (hash, sig)} (defense-in-depth, audit L5).
     *
     * <p>Keyed on the raw bytes, not hex strings: building two {@code toHexString()} strings (128 +
     * 64 chars) on every verify/isCached was ~half the warm-cache cost measured in ValidationBenchmark
     * and throttled parallel scaling (audit P3). {@link Bytes} gives a byte[] value-based equals/hash.
     * The cache is node-local and never consensus-visible, so this is a pure CPU/allocation win.
     */
    private record Bytes(byte[] value) {
        @Override public boolean equals(Object o) {
            return o instanceof Bytes b && java.util.Arrays.equals(value, b.value);
        }
        @Override public int hashCode() {
            return java.util.Arrays.hashCode(value);
        }
    }

    private record CacheKey(SHA256Hash contentHash, Bytes signature, Bytes signingKey) {}

    public SignatureVerifier() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors()), 1 << 20);
    }

    /** {@code cacheCapacity} is the total entry budget across both generations. */
    public SignatureVerifier(int parallelism, int cacheCapacity) {
        this.pool = new ForkJoinPool(parallelism);
        this.generation = cacheCapacity / 2;
    }

    private static CacheKey key(Transaction tx) {
        return new CacheKey(tx.hashContents(), new Bytes(tx.signature().toBytes()), new Bytes(tx.signingKey().toBytes()));
    }

    /** True if this exact transaction (content + signature) was already verified, in either generation. */
    public boolean isCached(Transaction t) {
        CacheKey key = key(t);
        return young.containsKey(key) || old.containsKey(key);
    }

    /**
     * Verifies one transaction, consulting and populating the cache. Coinbase is always valid.
     *
     * <p>Only POSITIVE verdicts are cached — deliberately: caching a negative would let an
     * attacker evict useful entries with garbage. The consequence (an invalid signature costs
     * a full ~100 µs Ed25519 check per resubmission) must be bounded by the CALLER: the HTTP
     * layer consumes an aggregated signature budget before decoding (audit F7). Any direct,
     * non-HTTP {@code MemPool} caller must provide an equivalent gate.
     */
    public boolean verify(Transaction t) {
        if (t.isTransactionFee()) {
            return true;
        }
        CacheKey key = key(t);
        if (young.containsKey(key)) {
            return true;
        }
        if (old.containsKey(key)) {
            young.put(key, Boolean.TRUE); // promote: survives the next rotate()
            return true;
        }
        boolean ok = t.signatureValid();
        if (ok) {
            remember(key);
        }
        return ok;
    }

    /** Below this batch size, parallelism costs more than it saves — verify inline. */
    private static final int PARALLEL_THRESHOLD = 32;

    /**
     * Verifies all transactions (cache-miss set checked in parallel for large
     * batches) and returns true only if every transaction is valid. Small
     * batches and the interrupted-thread fallback verify sequentially, so this
     * is safe to call from a mining loop whose thread may be interrupted.
     */
    public boolean verifyAll(List<Transaction> transactions) {
        if (transactions.size() < PARALLEL_THRESHOLD) {
            return verifySequential(transactions);
        }
        try {
            return pool.submit(() ->
                IntStream.range(0, transactions.size())
                    .parallel()
                    .allMatch(i -> verify(transactions.get(i)))
            ).get();
        } catch (InterruptedException e) {
            // Don't poison the caller: clear the flag and fall back to sequential.
            Thread.interrupted();
            return verifySequential(transactions);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Signature verification failed", e.getCause());
        }
    }

    private boolean verifySequential(List<Transaction> transactions) {
        for (Transaction t : transactions) {
            if (!verify(t)) {
                return false;
            }
        }
        return true;
    }

    private void remember(CacheKey key) {
        young.put(key, Boolean.TRUE);
        if (young.size() > generation) {
            rotate();
        }
    }

    /** Retires {@code old} and starts a fresh {@code young}. Re-checks the size under the lock
     *  because multiple threads can race past the unsynchronized size check in {@link #remember}. */
    private synchronized void rotate() {
        if (young.size() > generation) {
            old = young;
            young = new ConcurrentHashMap<>();
        }
    }

    /**
     * Pre-warms the verify-once cache for {@code t}. This now RE-VERIFIES the signature rather than
     * trusting the caller: caching an unverified transaction would poison the cache so that a forged
     * signature is treated as valid at block validation ({@code verifyAll} is a cache hit) — a
     * signature-check bypass sitting one careless caller away from the consensus path (audit V6h).
     * Returns whether the transaction is validly signed.
     */
    public boolean markVerified(Transaction t) {
        return verify(t);
    }

    public int cacheSize() {
        return young.size() + old.size();
    }

    public void shutdown() {
        pool.shutdown();
    }
}
