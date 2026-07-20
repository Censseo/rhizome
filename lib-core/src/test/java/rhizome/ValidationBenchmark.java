package rhizome;

import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

import static rhizome.core.common.Crypto.generateKeyPair;

/**
 * Not a correctness test — a rough throughput probe for the validation hot path.
 * Enable manually: {@code ./gradlew :lib-core:test --tests ValidationBenchmark -Dbench=on}.
 */
class ValidationBenchmark {

    private static final int TX = 2000;

    @Test
    void probe() {
        if (!"on".equals(System.getProperty("bench"))) {
            return;
        }
        var pair = generateKeyPair();
        var key = PublicKey.of(pair.getPublic());
        var priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        var from = PublicAddress.of(key);

        List<Transaction> txs = new ArrayList<>(TX);
        for (int i = 0; i < TX; i++) {
            Transaction t = Transaction.of(from, PublicAddress.random(), new TransactionAmount(100),
                key, new TransactionAmount(1), 1000L + i, 1, i);
            t.sign(priv);
            txs.add(t);
        }

        // Warmup
        for (int r = 0; r < 3; r++) {
            for (Transaction t : txs) {
                t.hashContents();
                t.signatureValid();
            }
        }

        long t0 = System.nanoTime();
        for (Transaction t : txs) {
            t.hashContents();
        }
        long hashNs = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        int ok = 0;
        for (Transaction t : txs) {
            if (t.signatureValid()) ok++;
        }
        long sigNs = System.nanoTime() - t1;

        // Parallel + cache verifier: cold (first pass) then warm (mempool scenario).
        var verifier = new rhizome.core.blockchain.SignatureVerifier();
        verifier.verifyAll(txs); // warmup pass fills nothing new but JITs the path
        long t2 = System.nanoTime();
        boolean allCold = new rhizome.core.blockchain.SignatureVerifier().verifyAll(txs);
        long parNs = System.nanoTime() - t2;

        verifier.verifyAll(txs); // ensure cache warm
        long t3 = System.nanoTime();
        boolean allWarm = verifier.verifyAll(txs);
        long warmNs = System.nanoTime() - t3;

        String report = String.format(
            "=== validation probe (%d tx, %d cores) ===%n"
            + "hashContents:            %.2f us/tx -> %.0f k/s%n"
            + "sig verify (sequential): %.2f us/tx -> %6.0f verif/s | block/s @%dtx: %5.2f%n"
            + "sig verify (parallel):   %.2f us/tx -> %6.0f verif/s | block/s @%dtx: %5.2f  [ok=%b]%n"
            + "sig verify (warm cache): %.2f us/tx -> %6.0f verif/s | block/s @%dtx: %5.1f  [ok=%b]%n",
            TX, Runtime.getRuntime().availableProcessors(),
            hashNs / 1000.0 / TX, TX / (hashNs / 1e9) / 1000,
            sigNs / 1000.0 / TX, TX / (sigNs / 1e9), TX, 1.0 / (sigNs / 1e9),
            parNs / 1000.0 / TX, TX / (parNs / 1e9), TX, 1.0 / (parNs / 1e9), allCold,
            warmNs / 1000.0 / TX, TX / (warmNs / 1e9), TX, 1.0 / (warmNs / 1e9), allWarm);
        System.out.print(report);
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("bench.out", "bench.txt")), report);
        } catch (Exception ignored) {
            // best effort
        }
    }
}
