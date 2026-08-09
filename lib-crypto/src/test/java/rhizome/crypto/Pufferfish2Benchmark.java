package rhizome.crypto;

import java.security.MessageDigest;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.junit.jupiter.api.Test;

/**
 * Not a correctness test — a throughput probe for the memory-hard PoW hash, plus an
 * attribution of where its time goes.
 *
 * <p>Under the genesis costs ({@code cost_t=0, cost_m=8}) one hash performs, per
 * {@link Pufferfish2}: ~4 {@code hashSbox} rounds x 4 s-boxes x 64 KiB of HMAC-SHA512
 * input (~1 MiB of MAC per hash), and ~50k {@code encipher()} calls of 16 random
 * s-box lookups each (~800k dependent loads). This separates those two so an
 * optimisation effort targets whichever actually dominates.
 *
 * Enable manually: {@code ./gradlew :lib-crypto:test --tests Pufferfish2Benchmark -Dbench=on}.
 */
class Pufferfish2Benchmark {

    private static final int HASHES = 300;
    private static final PowCosts GENESIS = PowCosts.DEFAULT;

    @Test
    void probe() throws Exception {
        if (!"on".equals(System.getProperty("bench"))) {
            return;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        byte[] preimage = new byte[80]; // a block header preimage
        for (int i = 0; i < preimage.length; i++) {
            preimage[i] = (byte) i;
        }

        // ---- single-thread PoW throughput ----
        for (int i = 0; i < 50; i++) {
            PufferfishAlgorithm.compute(preimage, GENESIS);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < HASHES; i++) {
            preimage[0] = (byte) i;
            PufferfishAlgorithm.compute(preimage, GENESIS);
        }
        long seqNs = System.nanoTime() - t0;
        double msPerHash = seqNs / 1e6 / HASHES;

        // ---- parallel (miner / multi-verifier) throughput ----
        long parNs;
        try (var pool = new ForkJoinPool(cores)) {
            pool.submit(() -> IntStream.range(0, HASHES).parallel()
                .forEach(i -> PufferfishAlgorithm.compute(preimage.clone(), GENESIS))).get();
            long t1 = System.nanoTime();
            pool.submit(() -> IntStream.range(0, HASHES * 4).parallel()
                .forEach(i -> {
                    byte[] p = preimage.clone();
                    p[0] = (byte) i;
                    PufferfishAlgorithm.compute(p, GENESIS);
                })).get();
            parNs = System.nanoTime() - t1;
        }
        double parHashesPerSec = HASHES * 4 / (parNs / 1e9);

        // ---- attribution A: HMAC-SHA512 volume, BouncyCastle vs the JDK provider ----
        // One hash MACs roughly 4 rounds x 4 s-boxes x 2^(costM+5) words x 8 bytes.
        int sboxBytes = (1 << (GENESIS.costM() + 5)) * 8;
        long macBytesPerHash = 4L * 4L * sboxBytes;
        byte[] macInput = new byte[sboxBytes];
        byte[] macKey = new byte[64];

        long bcNs = timeMac(() -> {
            HMac mac = new HMac(new SHA512Digest());
            mac.init(new KeyParameter(macKey));
            byte[] out = new byte[64];
            for (int i = 0; i < 16; i++) {
                mac.update(macInput, 0, macInput.length);
                mac.doFinal(out, 0);
            }
            return out[0];
        });
        long jdkNs = timeMac(() -> {
            try {
                var mac = javax.crypto.Mac.getInstance("HmacSHA512");
                mac.init(new javax.crypto.spec.SecretKeySpec(macKey, "HmacSHA512"));
                byte[] out = null;
                for (int i = 0; i < 16; i++) {
                    mac.update(macInput, 0, macInput.length);
                    out = mac.doFinal();
                }
                return out[0];
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // 16 MACs of sboxBytes each per timed call.
        double bcMbPerSec = 16.0 * sboxBytes / (bcNs / 1e9) / (1024 * 1024);
        double jdkMbPerSec = 16.0 * sboxBytes / (jdkNs / 1e9) / (1024 * 1024);
        double bcMacMsPerHash = macBytesPerHash / (bcMbPerSec * 1024 * 1024) * 1000;
        double jdkMacMsPerHash = macBytesPerHash / (jdkMbPerSec * 1024 * 1024) * 1000;

        // ---- attribution B: raw SHA-512 (no HMAC framing), BC vs JDK ----
        long bcShaNs = timeMac(() -> {
            var d = new SHA512Digest();
            byte[] out = new byte[64];
            for (int i = 0; i < 16; i++) {
                d.update(macInput, 0, macInput.length);
                d.doFinal(out, 0);
            }
            return out[0];
        });
        long jdkShaNs = timeMac(() -> {
            try {
                var d = MessageDigest.getInstance("SHA-512");
                byte[] out = null;
                for (int i = 0; i < 16; i++) {
                    d.update(macInput, 0, macInput.length);
                    out = d.digest();
                }
                return out[0];
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        double bcShaMb = 16.0 * sboxBytes / (bcShaNs / 1e9) / (1024 * 1024);
        double jdkShaMb = 16.0 * sboxBytes / (jdkShaNs / 1e9) / (1024 * 1024);

        String report = String.format("""
            === pufferfish2 probe (cost_t=%d, cost_m=%d, %d cores) ===
            s-box set: %d KiB   HMAC input per hash: ~%d KiB

            PoW throughput
              single thread            : %8.3f ms/hash -> %8.1f hash/s
              %2d threads                : %8.3f ms/hash -> %8.1f hash/s   [speedup %.1fx = %.0f%% efficiency]

            Where the time goes (per hash, modelled from measured MAC bandwidth)
              HMAC-SHA512 (BouncyCastle): %8.3f ms  = %5.1f%% of the hash   [%6.1f MB/s]
              HMAC-SHA512 (JDK provider): %8.3f ms  = %5.1f%% of the hash   [%6.1f MB/s]  %.2fx
              remainder (Feistel/s-box) : %8.3f ms  = %5.1f%% of the hash

            Raw SHA-512 bandwidth (no HMAC framing)
              BouncyCastle SHA512Digest: %6.1f MB/s
              JDK MessageDigest SHA-512: %6.1f MB/s   %.2fx

            Upper bound if the MAC were swapped for the faster provider:
              %.3f ms/hash -> %.1f hash/s single thread  (%.2fx)
            """,
            GENESIS.costT(), GENESIS.costM(), cores,
            4 * sboxBytes / 1024, macBytesPerHash / 1024,
            msPerHash, 1000 / msPerHash,
            cores, 1000 / parHashesPerSec, parHashesPerSec,
            parHashesPerSec / (1000 / msPerHash), 100.0 * parHashesPerSec / (1000 / msPerHash) / cores,
            bcMacMsPerHash, 100 * bcMacMsPerHash / msPerHash, bcMbPerSec,
            jdkMacMsPerHash, 100 * jdkMacMsPerHash / msPerHash, jdkMbPerSec, bcMacMsPerHash / jdkMacMsPerHash,
            msPerHash - bcMacMsPerHash, 100 * (msPerHash - bcMacMsPerHash) / msPerHash,
            bcShaMb, jdkShaMb, jdkShaMb / bcShaMb,
            msPerHash - bcMacMsPerHash + jdkMacMsPerHash,
            1000 / (msPerHash - bcMacMsPerHash + jdkMacMsPerHash),
            msPerHash / (msPerHash - bcMacMsPerHash + jdkMacMsPerHash));
        System.out.print(report);
        java.nio.file.Files.writeString(
            java.nio.file.Path.of(System.getProperty("bench.out", "bench-pufferfish.txt")), report);
    }

    /** Times a MAC/digest body after warmup; the body returns a byte so the JIT cannot elide it. */
    private static long timeMac(java.util.function.Supplier<Byte> body) {
        byte sink = 0;
        for (int i = 0; i < 5; i++) {
            sink ^= body.get();
        }
        long t = System.nanoTime();
        sink ^= body.get();
        long ns = System.nanoTime() - t;
        if (sink == 123 && ns < 0) {
            throw new IllegalStateException("unreachable"); // keep sink live
        }
        return ns;
    }
}
