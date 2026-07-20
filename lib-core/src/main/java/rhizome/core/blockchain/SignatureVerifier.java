package rhizome.core.blockchain;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import rhizome.core.crypto.SHA256Hash;
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
    private final int cacheCapacity;
    private final Map<CacheKey, Boolean> verified = new ConcurrentHashMap<>();

    /** Cache identity: content hash + signature bytes (defends against Ed25519 malleability). */
    private record CacheKey(SHA256Hash contentHash, String signatureHex) {}

    public SignatureVerifier() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors()), 1 << 20);
    }

    public SignatureVerifier(int parallelism, int cacheCapacity) {
        this.pool = new ForkJoinPool(parallelism);
        this.cacheCapacity = cacheCapacity;
    }

    private static CacheKey key(Transaction t) {
        var tx = (TransactionImpl) t;
        return new CacheKey(t.hashContents(), tx.signature().toHexString());
    }

    /** True if this exact transaction (content + signature) was already verified. */
    public boolean isCached(Transaction t) {
        return verified.containsKey(key(t));
    }

    /** Verifies one transaction, consulting and populating the cache. Coinbase is always valid. */
    public boolean verify(Transaction t) {
        if (((TransactionImpl) t).isTransactionFee()) {
            return true;
        }
        CacheKey key = key(t);
        Boolean cached = verified.get(key);
        if (cached != null) {
            return cached;
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
        if (verified.size() >= cacheCapacity) {
            // Coarse bound: drop a chunk when full. Verified transactions are
            // re-verifiable, so eviction only costs a recompute, never correctness.
            verified.clear();
        }
        verified.put(key, Boolean.TRUE);
    }

    /** Pre-warms the cache from a transaction known to be validly signed (mempool admission). */
    public void markVerified(Transaction t) {
        if (!((TransactionImpl) t).isTransactionFee()) {
            remember(key(t));
        }
    }

    public int cacheSize() {
        return verified.size();
    }

    public void shutdown() {
        pool.shutdown();
    }
}
