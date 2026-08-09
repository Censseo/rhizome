package rhizome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;

import static rhizome.crypto.Crypto.generateKeyPair;

/**
 * Not a correctness test — isolates WHERE the parallel signature path loses scaling.
 * {@link ValidationBenchmark} measures the composite; this one separates the raw
 * Ed25519 ceiling from the cost the verify-once cache adds on top of it.
 *
 * Enable manually: {@code ./gradlew :lib-core:test --tests Ed25519ScalingBenchmark -Dbench=on}.
 */
class Ed25519ScalingBenchmark {

    private static final int TX = 4000;

    @Test
    void probe() throws Exception {
        if (!"on".equals(System.getProperty("bench"))) {
            return;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        List<Transaction> txs = mint(TX);

        // Warm the JIT on every path we are about to time.
        for (int r = 0; r < 3; r++) {
            rawSequential(txs);
            rawParallel(txs, cores);
        }

        long rawSeq = time(() -> rawSequential(txs));
        long rawPar = time(() -> rawParallel(txs, cores));

        // A: current implementation — synchronizedMap(LinkedHashMap access-order).
        var current = new SignatureVerifier();
        long curCold = time(() -> current.verifyAll(txs));
        current.verifyAll(txs);
        long curWarm = time(() -> current.verifyAll(txs));

        // B: same cache identity, but a plain ConcurrentHashMap (no global monitor,
        //    no access-order mutation on read). Bounded by clear-on-overflow instead of LRU.
        var chm = new ChmVerifier(cores);
        long chmCold = time(() -> chm.verifyAll(txs));
        chm.verifyAll(txs);
        long chmWarm = time(() -> chm.verifyAll(txs));

        // D: two-generation ("clock") cache — bounded like the LRU, but lock-free. A hit in the
        //    old generation is promoted into the young one, so hot entries survive the swap;
        //    only entries untouched for a full generation are dropped. Addresses the audit's
        //    objection to a plain CHM (bucket-order eviction bias) without a global monitor.
        var gen = new GenVerifier(cores);
        long genCold = time(() -> gen.verifyAll(txs));
        gen.verifyAll(txs);
        long genWarm = time(() -> gen.verifyAll(txs));

        // C: cost of building the CacheKey alone (hash + 2 Arrays.hashCode), no map at all.
        long keyOnly = time(() -> {
            long acc = 0;
            for (Transaction t : txs) {
                acc += ChmVerifier.key(t).hashCode();
            }
            return acc != 0;
        });

        String report = String.format("""
            === ed25519 scaling probe (%d tx, %d cores) ===
            raw verify, sequential      : %7.2f us/tx -> %8.0f verif/s
            raw verify, parallel        : %7.2f us/tx -> %8.0f verif/s   [speedup %.1fx of %dx cores = %.0f%% efficiency]
            SignatureVerifier cold      : %7.2f us/tx -> %8.0f verif/s   [%.0f%% of raw parallel]
            SignatureVerifier warm      : %7.2f us/tx -> %8.0f verif/s
            ConcurrentHashMap cold      : %7.2f us/tx -> %8.0f verif/s   [%.0f%% of raw parallel]
            ConcurrentHashMap warm      : %7.2f us/tx -> %8.0f verif/s   [%.1fx vs current warm]
            two-generation cold         : %7.2f us/tx -> %8.0f verif/s   [%.0f%% of raw parallel]
            two-generation warm         : %7.2f us/tx -> %8.0f verif/s   [%.1fx vs current warm]
            cache-key build only (seq)  : %7.2f us/tx -> %8.0f key/s
            """,
            TX, cores,
            us(rawSeq), rate(rawSeq),
            us(rawPar), rate(rawPar), (double) rawSeq / rawPar, cores, 100.0 * rawSeq / rawPar / cores,
            us(curCold), rate(curCold), 100.0 * rawPar / curCold,
            us(curWarm), rate(curWarm),
            us(chmCold), rate(chmCold), 100.0 * rawPar / chmCold,
            us(chmWarm), rate(chmWarm), (double) curWarm / chmWarm,
            us(genCold), rate(genCold), 100.0 * rawPar / genCold,
            us(genWarm), rate(genWarm), (double) curWarm / genWarm,
            us(keyOnly), rate(keyOnly));
        System.out.print(report);
        java.nio.file.Files.writeString(
            java.nio.file.Path.of(System.getProperty("bench.out", "bench-ed25519-scaling.txt")), report);
    }

    private static double us(long ns) {
        return ns / 1000.0 / TX;
    }

    private static double rate(long ns) {
        return TX / (ns / 1e9);
    }

    private static long time(java.util.function.BooleanSupplier body) {
        long t0 = System.nanoTime();
        boolean ok = body.getAsBoolean();
        long ns = System.nanoTime() - t0;
        if (!ok) {
            throw new IllegalStateException("benchmark body reported failure");
        }
        return ns;
    }

    private static boolean rawSequential(List<Transaction> txs) {
        boolean all = true;
        for (Transaction t : txs) {
            all &= t.signatureValid();
        }
        return all;
    }

    private static boolean rawParallel(List<Transaction> txs, int cores) {
        try (var pool = new ForkJoinPool(cores)) {
            return pool.submit(() ->
                IntStream.range(0, txs.size()).parallel().allMatch(i -> txs.get(i).signatureValid())
            ).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Transaction> mint(int n) {
        var pair = generateKeyPair();
        var key = PublicKey.of(pair.getPublic());
        var priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        var from = PublicAddress.of(key);
        List<Transaction> txs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Transaction t = Transaction.of(from, PublicAddress.random(), new TransactionAmount(100),
                key, new TransactionAmount(1), 1000L + i, 1, i);
            t.sign(priv);
            txs.add(t);
        }
        return txs;
    }

    /**
     * Variant D: bounded two-generation cache. Reads hit {@code young} then {@code old};
     * an {@code old} hit is promoted to {@code young}. When {@code young} fills, it becomes
     * {@code old} and the previous {@code old} is dropped — so an entry is only evicted after
     * two full generations without a touch. Bounded, approximately-LRU, no global monitor.
     */
    private static final class GenVerifier {
        private volatile Map<ChmVerifier.CacheKey, Boolean> young = new ConcurrentHashMap<>();
        private volatile Map<ChmVerifier.CacheKey, Boolean> old = new ConcurrentHashMap<>();
        private final int generation = 1 << 19; // two generations ~= the 1<<20 LRU capacity
        private final ForkJoinPool pool;

        GenVerifier(int parallelism) {
            this.pool = new ForkJoinPool(parallelism);
        }

        boolean verify(Transaction t) {
            if (((TransactionImpl) t).isTransactionFee()) {
                return true;
            }
            ChmVerifier.CacheKey k = ChmVerifier.key(t);
            if (young.containsKey(k)) {
                return true;
            }
            if (old.containsKey(k)) {
                young.put(k, Boolean.TRUE); // promote: survives the next swap
                return true;
            }
            boolean ok = t.signatureValid();
            if (ok) {
                young.put(k, Boolean.TRUE);
                if (young.size() > generation) {
                    rotate();
                }
            }
            return ok;
        }

        private synchronized void rotate() {
            if (young.size() > generation) { // re-check under the (rare) rotation lock
                old = young;
                young = new ConcurrentHashMap<>();
            }
        }

        boolean verifyAll(List<Transaction> txs) {
            try {
                return pool.submit(() ->
                    IntStream.range(0, txs.size()).parallel().allMatch(i -> verify(txs.get(i)))
                ).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Variant B: identical cache identity, ConcurrentHashMap instead of a globally-locked LRU. */
    private static final class ChmVerifier {
        private record Bytes(byte[] value) {
            @Override public boolean equals(Object o) {
                return o instanceof Bytes b && java.util.Arrays.equals(value, b.value);
            }
            @Override public int hashCode() {
                return java.util.Arrays.hashCode(value);
            }
        }

        record CacheKey(SHA256Hash contentHash, Bytes signature, Bytes signingKey) {}

        private final Map<CacheKey, Boolean> verified = new ConcurrentHashMap<>();
        private final ForkJoinPool pool;

        ChmVerifier(int parallelism) {
            this.pool = new ForkJoinPool(parallelism);
        }

        static CacheKey key(Transaction t) {
            var tx = (TransactionImpl) t;
            return new CacheKey(t.hashContents(), new Bytes(tx.signature().toBytes()),
                new Bytes(tx.signingKey().toBytes()));
        }

        boolean verify(Transaction t) {
            if (((TransactionImpl) t).isTransactionFee()) {
                return true;
            }
            CacheKey k = key(t);
            Boolean cached = verified.get(k);
            if (cached != null) {
                return cached;
            }
            boolean ok = t.signatureValid();
            if (ok) {
                if (verified.size() > (1 << 20)) {
                    verified.clear();
                }
                verified.put(k, Boolean.TRUE);
            }
            return ok;
        }

        boolean verifyAll(List<Transaction> txs) {
            try {
                return pool.submit(() ->
                    IntStream.range(0, txs.size()).parallel().allMatch(i -> verify(txs.get(i)))
                ).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
