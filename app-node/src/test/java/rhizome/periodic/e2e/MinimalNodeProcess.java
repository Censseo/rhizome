package rhizome.periodic.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A trimmed, standalone copy of {@code rhizome.adversarial.e2e.ProcessHarness}'s
 * {@code java -cp ...}-child-process technique, for the periodic/manual bucket
 * (see {@link E2EGenesisExoticPathsTest}, {@link E2EGenesisLargeSnapshotStartupTest}).
 *
 * <p>Deliberately NOT the same class: {@code ProcessHarness} is package-private inside
 * {@code rhizome.adversarial.e2e}, which is Phase 1-4 infrastructure this build must not touch,
 * and this bucket's tests are deliberately placed OUTSIDE that package (and outside any
 * {@code *AttackTest}/{@code *AdversarialTest} class name) specifically so the Gradle
 * {@code adversarialTest} filter's package/name match does not sweep them into the ~1-minute
 * {@code ./gradlew adversarial} budget — being in the same package as {@code ProcessHarness}
 * would defeat that placement outright. A second, smaller launcher living beside its own callers
 * is the tradeoff for that isolation.
 */
final class MinimalNodeProcess implements AutoCloseable {

    private final Process process;
    private final Drain stdout;
    private final Drain stderr;
    private final int port;

    private MinimalNodeProcess(Process process, Drain stdout, Drain stderr, int port) {
        this.process = process;
        this.stdout = stdout;
        this.stderr = stderr;
        this.port = port;
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    int port() {
        return port;
    }

    String stdout() {
        return stdout.text();
    }

    String stderr() {
        return stderr.text();
    }

    boolean awaitPortOpen(long totalWaitMs, long intervalMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + totalWaitMs;
        while (true) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (IOException e) {
                // not open yet
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(Math.min(intervalMs, Math.max(0, deadline - System.currentTimeMillis())));
        }
    }

    boolean awaitExit(long timeoutMs) throws InterruptedException {
        return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
    }

    boolean isAlive() {
        return process.isAlive();
    }

    int exitCode() {
        return process.exitValue();
    }

    long pid() {
        return process.pid();
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            stdout.awaitDrained(2_000);
            stderr.awaitDrained(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String javaExecutable() {
        var command = ProcessHandle.current().info().command();
        if (command.isPresent() && !command.get().isBlank()) {
            return command.get();
        }
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Launches {@code rhizome.node.RhizomeNode.main(String[])} with the given environment, plus
     *  the required {@code RHIZOME_DATA}/{@code RHIZOME_PORT} defaults (an explicit entry for
     *  either in {@code env} wins, since it is applied last). Extra JVM args (e.g. {@code -Xmx})
     *  are inserted before {@code -cp}. */
    static MinimalNodeProcess start(Path dataDir, Map<String, String> env, String... jvmArgs)
            throws IOException {
        Files.createDirectories(dataDir);
        int port = freePort();

        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable());
        command.addAll(java.util.Arrays.asList(jvmArgs));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("rhizome.node.RhizomeNode");

        ProcessBuilder pb = new ProcessBuilder(command);
        Map<String, String> full = new LinkedHashMap<>();
        full.put("RHIZOME_DATA", dataDir.toString());
        full.put("RHIZOME_PORT", String.valueOf(port));
        full.putAll(env);
        pb.environment().putAll(full);

        Process process = pb.start();
        Drain out = new Drain(process.getInputStream(), "stdout-" + process.pid());
        Drain err = new Drain(process.getErrorStream(), "stderr-" + process.pid());
        return new MinimalNodeProcess(process, out, err, port);
    }

    private static final class Drain {
        private final Thread thread;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Drain(InputStream in, String name) {
            thread = new Thread(() -> {
                try {
                    in.transferTo(buffer);
                } catch (IOException ignored) {
                    // process died mid-read
                }
            }, "minimal-node-process-" + name);
            thread.setDaemon(true);
            thread.start();
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        void awaitDrained(long timeoutMs) throws InterruptedException {
            thread.join(timeoutMs);
        }
    }
}
