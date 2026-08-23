package rhizome.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches an arbitrary {@code main(String[])} class as a genuine, separate {@code java}
 * child process with caller-chosen JVM flags, and waits for it to finish.
 *
 * <p>Exists for the handful of scenarios that need a JVM property genuinely isolated from the
 * one running the test — a specific {@code -Xmx} (this module's Gradle test workers run with a
 * fixed 512 MiB heap, too small for a scenario that must legitimately hold more than that), or a
 * process-global piece of state such as a {@code URLStreamHandlerFactory} that the JVM only lets
 * be set once. {@code rhizome.adversarial.e2e.ProcessHarness} solves the same family of problem
 * but only for {@code rhizome.node.RhizomeNode.main} with a data directory and a port; this is
 * the generic form, usable from any test package (public, unlike {@code ProcessHarness}) for any
 * main class.
 */
public final class SubprocessRunner {

    private SubprocessRunner() {
    }

    /** Exit code, stdout and stderr of one completed child process run. */
    public record Result(int exitCode, String stdout, String stderr) {
    }

    /**
     * Runs {@code mainClass} with this JVM's own classpath, {@code jvmArgs} inserted before
     * {@code -cp} (e.g. {@code -Xmx64m}), and {@code programArgs} passed to {@code main}. Blocks
     * up to {@code timeoutMs}; forcibly kills and reports a synthetic non-zero exit if the child
     * does not finish in time (the caller's assertion should treat that as a hang, not swallow it).
     */
    public static Result run(String mainClass, List<String> jvmArgs, List<String> programArgs,
                              long timeoutMs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(programArgs);

        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outDrain = drain(process.getInputStream(), stdout);
        Thread errDrain = drain(process.getErrorStream(), stderr);

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        outDrain.join(2_000);
        errDrain.join(2_000);

        int exitCode = finished ? process.exitValue() : Integer.MIN_VALUE; // sentinel: timed out
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private static Thread drain(java.io.InputStream in, StringBuilder into) {
        Thread thread = new Thread(() -> {
            try {
                into.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // the process died mid-read -- whatever was captured stands
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String javaExecutable() {
        var command = ProcessHandle.current().info().command();
        if (command.isPresent() && !command.get().isBlank()) {
            return command.get();
        }
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Convenience for callers with no extra JVM flags. */
    public static Result run(String mainClass, List<String> programArgs, long timeoutMs)
            throws IOException, InterruptedException {
        return run(mainClass, List.of(), programArgs, timeoutMs);
    }
}
