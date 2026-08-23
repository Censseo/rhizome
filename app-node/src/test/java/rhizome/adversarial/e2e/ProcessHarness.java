package rhizome.adversarial.e2e;

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
 * Launches {@link rhizome.node.RhizomeNode} as a genuine, separate OS process, for the handful of
 * scenarios that a real {@link rhizome.node.RhizomeNode} object inside the test JVM cannot prove.
 *
 * <p>Every other end-to-end fixture in this package ({@code TestNetwork}, {@code HostilePeer}) runs
 * nodes as real Java objects sharing the test JVM: real RocksDB, real sockets, real mining, but one
 * process. That is indistinguishable from a real deployment for consensus and networking questions,
 * but it cannot answer a question like "did the process ever open a socket" or "what did it print to
 * stderr before it died" — an in-JVM {@code RhizomeNode.start()} either returns or throws, there is
 * no separate stderr to inspect, and a failed {@code assemble()} never had a chance to bind anything
 * in the first place, in-JVM or not, which is exactly the ambiguity a real process removes: the
 * proof becomes "no TCP SYN was ever answered on this port", not "the constructor happened to throw
 * before a certain line".
 *
 * <p>Talks to {@code rhizome.node.RhizomeNode.main(String[])} exactly as an operator's shell would:
 * a {@code java -cp <classpath> rhizome.node.RhizomeNode} invocation, real environment variables
 * (not an injected lookup function — see {@code NodeConfig.fromEnv(UnaryOperator)}'s test-only
 * overload, which every other suite uses instead), a real stdout/stderr pair, and a real exit code.
 * The classpath is {@link System#getProperty} {@code "java.class.path"} verbatim: empirically (see
 * this class's test-writing history) the Gradle test worker's own classpath is already an explicit,
 * colon-separated list of real jar/directory paths — not a manifest-jar wrapper — so passing it
 * straight through to a child {@code -cp} resolves identically.
 */
final class ProcessHarness implements AutoCloseable {

    private final Process process;
    private final StreamDrain stdout;
    private final StreamDrain stderr;
    private final int port;
    private final Path dataDir;

    private ProcessHarness(Process process, StreamDrain stdout, StreamDrain stderr,
                           int port, Path dataDir) {
        this.process = process;
        this.stdout = stdout;
        this.stderr = stderr;
        this.port = port;
        this.dataDir = dataDir;
        try {
            // Nothing this harness launches ever reads stdin; closing it promptly means a hung
            // child blocked on stdin is not this harness's fault to diagnose.
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    static Builder builder() {
        return new Builder();
    }

    int port() {
        return port;
    }

    String url() {
        return "http://127.0.0.1:" + port;
    }

    Path dataDir() {
        return dataDir;
    }

    long pid() {
        return process.pid();
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** Everything the process has written to stdout so far (or in total, once it has exited). */
    String stdout() {
        return stdout.text();
    }

    /** Everything the process has written to stderr so far (or in total, once it has exited). */
    String stderr() {
        return stderr.text();
    }

    /**
     * Polls a raw TCP connect to this process's configured port until one succeeds or
     * {@code totalWaitMs} elapses. Used both to confirm a healthy boot ("the port DID open") and,
     * negated by the caller, to confirm a fail-fast boot never reached the point of listening
     * ("the port never opened") — the latter is a claim about the process, not about Java
     * exceptions, and a raw socket connect is the only way to observe it from outside.
     */
    boolean awaitPortOpen(long totalWaitMs, long intervalMs) throws InterruptedException {
        return awaitPortOpen(port, totalWaitMs, intervalMs);
    }

    static boolean awaitPortOpen(int port, long totalWaitMs, long intervalMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + totalWaitMs;
        while (true) {
            if (portOpen(port)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(Math.min(intervalMs, Math.max(0, deadline - System.currentTimeMillis())));
        }
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Waits up to {@code timeoutMs} for the process to exit; true if it did within the budget. */
    boolean awaitExit(long timeoutMs) throws InterruptedException {
        return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** The process's exit code. Throws {@link IllegalThreadStateException} if still alive. */
    int exitCode() {
        return process.exitValue();
    }

    void destroyForcibly() {
        process.destroyForcibly();
    }

    /**
     * Always releases the OS process, even when a scenario's assertion already failed — a failing
     * test must never leak a listening child into the rest of the suite.
     */
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

    /** Resolves the java launcher for a child process the same way the current JVM was started,
     *  rather than assuming {@code java} is on {@code PATH} — this repo pins JDK 25 specifically,
     *  and a box with several JDKs installed could otherwise launch the child on the wrong one. */
    private static String javaExecutable() {
        var command = ProcessHandle.current().info().command();
        if (command.isPresent() && !command.get().isBlank()) {
            return command.get();
        }
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Fluent node declaration, mirroring {@code TestNetwork.Builder}'s shape. */
    static final class Builder {

        private final Map<String, String> env = new LinkedHashMap<>();
        private Path dataDir;
        private int port = TestNetwork.freePort();

        private Builder() {
        }

        /** The data directory this process will use. Required — see the class's launch precondition. */
        Builder dataDir(Path dir) {
            this.dataDir = dir;
            return this;
        }

        /** Overrides the port reserved at construction (rarely needed — a fresh one is picked already). */
        Builder port(int port) {
            this.port = port;
            return this;
        }

        int port() {
            return port;
        }

        /** Sets one environment variable the child process will see via the real {@code System.getenv}. */
        Builder env(String key, String value) {
            env.put(key, value);
            return this;
        }

        /** Bulk form of {@link #env}, for a scenario that already has a map of variables to set. */
        Builder environment(Map<String, String> vars) {
            env.putAll(vars);
            return this;
        }

        Builder network(String name) {
            return env("RHIZOME_NETWORK", name);
        }

        Builder snapshot(Path file) {
            return env("RHIZOME_SNAPSHOT", file.toString());
        }

        Builder snapshot(String rawPath) {
            return env("RHIZOME_SNAPSHOT", rawPath);
        }

        /**
         * Launches {@code rhizome.node.RhizomeNode.main(String[])} as a real child process:
         * real {@code java -cp}, a fresh data directory, and this builder's environment layered
         * over the two defaults ({@code RHIZOME_DATA}, {@code RHIZOME_PORT}) every launch needs —
         * an explicit {@link #env} call for either key still wins, since it is applied last.
         */
        ProcessHarness start() throws IOException {
            if (dataDir == null) {
                throw new IllegalStateException(
                    "ProcessHarness needs a dataDir (one fresh subdirectory per launched process)");
            }
            Files.createDirectories(dataDir);

            String javaExe = javaExecutable();
            String classpath = System.getProperty("java.class.path");

            ProcessBuilder pb = new ProcessBuilder(javaExe, "-cp", classpath, "rhizome.node.RhizomeNode");
            pb.environment().put("RHIZOME_DATA", dataDir.toString());
            pb.environment().put("RHIZOME_PORT", String.valueOf(port));
            // Explicit overrides win: applied last, over the two defaults above.
            pb.environment().putAll(env);

            Process process = pb.start();
            StreamDrain stdout = new StreamDrain(process.getInputStream(), "stdout-" + process.pid());
            StreamDrain stderr = new StreamDrain(process.getErrorStream(), "stderr-" + process.pid());
            return new ProcessHarness(process, stdout, stderr, port, dataDir);
        }
    }

    /**
     * Drains one stream on its own daemon thread into an in-memory buffer as the process runs.
     *
     * <p>Never read one of a process's streams to completion before starting to read the other: a
     * process that fills the OS pipe buffer on the unread stream while its writer blocks on
     * {@code waitFor()} deadlocks forever. Two independent threads, one per stream, is what avoids
     * that regardless of how much either side writes or when.
     */
    private static final class StreamDrain {

        private final Thread thread;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamDrain(InputStream in, String name) {
            thread = new Thread(() -> {
                try {
                    in.transferTo(buffer);
                } catch (IOException ignored) {
                    // the process died mid-read (pipe closed) -- whatever was captured stands
                }
            }, "process-harness-" + name);
            thread.setDaemon(true);
            thread.start();
        }

        /** {@link ByteArrayOutputStream}'s accessors are all {@code synchronized}, so this is a
         *  consistent (if possibly not-yet-final) snapshot even while the drain thread is still
         *  writing -- callers may read it on demand while the process is still running. */
        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        void awaitDrained(long timeoutMs) throws InterruptedException {
            thread.join(timeoutMs);
        }
    }
}
