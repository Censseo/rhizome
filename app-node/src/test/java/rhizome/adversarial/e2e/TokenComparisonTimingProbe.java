package rhizome.adversarial.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.node.RhizomeNode;

/**
 * Not a proof — a manual timing probe for the API-13 bearer comparison, kept beside
 * {@code TokenComparisonAttackTest} (the structural proof) and
 * {@code E2EApiAbuseTest#everyStrictPrefixOfTheBearerIsRefusedAndOnlyTheFullTokenPasses} (the
 * behavioural one).
 *
 * <p>No wall-clock assertion belongs in this suite. {@code bearerMatches} is one comparison inside
 * a request that also does header parsing, route classification and JSON error serialization, on a
 * loopback socket subject to GC pauses, JIT warmup and OS scheduling jitter this test does not
 * control — noise on that scale would swamp the few-nanosecond signal a `MessageDigest.isEqual`
 * length-independent walk is actually supposed to produce, so a fixed threshold would fail
 * intermittently for reasons that have nothing to do with the property under test, on any shared
 * CI machine. That is a flaky-test generator, not a security proof, so this reports a distribution
 * for a human to read and asserts nothing.
 *
 * <p>Enable manually: {@code ./gradlew :app-node:test --tests TokenComparisonTimingProbe -Dbench=on}.
 */
class TokenComparisonTimingProbe {

    @TempDir
    Path tempDir;

    private static final String TOKEN = "a-forty-character-long-operator-bearer-token-x";
    private static final int ROUNDS = 500;

    @Test
    void probe() throws Exception {
        if (!"on".equals(System.getProperty("bench"))) {
            return;
        }
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("probe").apiToken(TOKEN).start();
            int port = node.apiPort();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

            String shortestMismatch = "";
            String longestMismatch = TOKEN.substring(0, TOKEN.length() - 1);

            // Warmup: JIT the request path itself before any measurement.
            for (int i = 0; i < 50; i++) {
                RawHttp.post(port, "/add_transaction",
                    Map.of("Authorization", "Bearer " + shortestMismatch), body);
                RawHttp.post(port, "/add_transaction",
                    Map.of("Authorization", "Bearer " + longestMismatch), body);
            }

            long[] shortNs = new long[ROUNDS];
            long[] longNs = new long[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                long t0 = System.nanoTime();
                RawHttp.post(port, "/add_transaction",
                    Map.of("Authorization", "Bearer " + shortestMismatch), body);
                shortNs[i] = System.nanoTime() - t0;

                long t1 = System.nanoTime();
                RawHttp.post(port, "/add_transaction",
                    Map.of("Authorization", "Bearer " + longestMismatch), body);
                longNs[i] = System.nanoTime() - t1;
            }

            String report = String.format(
                "=== token comparison timing probe (%d rounds, %d-byte token) ===%n"
                + "empty-prefix mismatch:      median %.1f us, p90 %.1f us%n"
                + "n-1-byte-prefix mismatch:    median %.1f us, p90 %.1f us%n"
                + "(no assertion: request-level jitter dominates any MessageDigest.isEqual signal;%n"
                + " read this by eye for a gross regression, e.g. a difference tracking token length)%n",
                ROUNDS, TOKEN.length(),
                medianUs(shortNs), percentileUs(shortNs, 0.90),
                medianUs(longNs), percentileUs(longNs, 0.90));
            System.out.print(report);
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of(System.getProperty("bench.out", "bench.txt")), report);
            } catch (Exception ignored) {
                // best-effort, matches the other benchmarks in this repo
            }
        }
    }

    private static double medianUs(long[] samplesNs) {
        return percentileUs(samplesNs, 0.50);
    }

    private static double percentileUs(long[] samplesNs, double p) {
        long[] sorted = samplesNs.clone();
        java.util.Arrays.sort(sorted);
        int idx = Math.min(sorted.length - 1, (int) (p * sorted.length));
        return sorted[idx] / 1000.0;
    }
}
