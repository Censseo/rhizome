package rhizome.periodic.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.testsupport.SubprocessRunner;

/**
 * E2E-G07 (the design's "manual/periodic" bucket): three {@code RHIZOME_SNAPSHOT} paths a real
 * operator's shell glob or a hostile local actor could point at, none of which are an ordinary
 * regular file. Deliberately placed OUTSIDE {@code rhizome.adversarial.e2e} and named without an
 * {@code AttackTest}/{@code AdversarialTest} suffix so neither of the {@code adversarialTest}
 * Gradle task's {@code includeTestsMatching} filters ({@code rhizome.adversarial.*}, or a class
 * name ending {@code AttackTest}/{@code AdversarialTest}) sweeps it into the fast
 * {@code ./gradlew adversarial}/{@code ./gradlew build} path. See the
 * {@code :app-node:periodicAdversarial} Gradle task (not wired into {@code build}/{@code check}/
 * {@code adversarial}) for how to run this class deliberately.
 *
 * <p>Two of the three cases (the symlink race and the FIFO hang) were RESIDUAL findings from this
 * class's first build-out; both are now DEFENDED after {@code SnapshotLoader.fromFile} was
 * rewritten to open the snapshot path exactly once (see its class javadoc):
 * <ul>
 *   <li>{@link #symlinkRetargetedBetweenTheSizeProbeAndTheReadNoLongerBypassesTheSizeCap()} --
 *       DEFENDED. {@code fromFile} used to probe {@code Files.size(path)} once, then separately
 *       call {@code Files.readString(path)} with no re-check after -- a symlink re-pointed in that
 *       window could bypass the size cap entirely (reproduced reliably, typically within
 *       300-600 attempts, before the fix). It now opens one {@code FileChannel} and derives both
 *       the size check and the content read from that single handle: once {@code open()} resolves
 *       a symlink to an inode, a later retarget of the link cannot affect the already-open
 *       descriptor on POSIX, so the two checks can no longer observe different targets. Re-run
 *       against 1000 racing attempts post-fix and confirmed zero bypasses.</li>
 *   <li>{@link #aDirectoryAsTheSnapshotPathFailsCleanlyThroughARealProcessBoot()} -- DEFENDED
 *       (unchanged): a directory now fails the {@code isRegularFile()} type check {@code fromFile}
 *       performs before ever attempting to open the path, refusing cleanly with a typed message,
 *       exactly like the oversized- and missing-snapshot scenarios it sits beside (E2E-49/50).</li>
 *   <li>{@link #aFifoWithNoWriterNowFailsFastInsteadOfHangingTheBoot()} -- DEFENDED for the
 *       realistic case tested here (a FIFO named directly, no race): opening a FIFO for reading
 *       blocks at the OS level until a writer appears, but {@code fromFile} now checks the
 *       resolved path's TYPE via {@code Files.readAttributes} (a {@code stat()}, which does not
 *       block on a FIFO -- verified empirically to return in under a millisecond) before ever
 *       calling the open that could block, and refuses anything that is not a regular file. A real
 *       child process pointed at a writerless FIFO now exits almost immediately with a clean
 *       error instead of hanging. The one part of this NOT fully closed: if the resolved target's
 *       type changes in the narrow window between that stat and the subsequent open (a race, not
 *       the direct case this test exercises), {@code fromFile} falls back to a bounded open+read
 *       (a generous fixed timeout) rather than a hang -- but verified empirically that
 *       {@code Thread.interrupt()} does NOT unblock a thread already stuck inside
 *       {@code FileChannel.open()} on a writerless FIFO (the open() syscall itself is not wired
 *       into Java NIO's interruptible-channel machinery), so that residual race is BOUNDED (the
 *       boot thread gives up and a daemon thread is abandoned) rather than eliminated. See
 *       {@code SnapshotLoader.fromFile}'s javadoc for the full account.</li>
 * </ul>
 */
class E2EGenesisExoticPathsTest {

    @TempDir
    Path tempDir;

    /**
     * E2E-55 -- race a symlink between the size check and the content read inside
     * {@code SnapshotLoader.fromFile}, hoping the two observe different targets so an operator's
     * (or a co-tenant's) 512 MiB cap is bypassed by whatever size the swapped-in target happens
     * to have.
     *
     * <p>DEFENDED: {@code fromFile} now opens the path exactly once and derives both the size
     * check and the content read from that single {@code FileChannel} handle, so there is no
     * longer a window between two independent filesystem calls for a symlink swap to land in. A
     * background thread continuously flips the symlink between a tiny valid snapshot and a
     * ~513 MiB one (over the cap) while the main loop calls {@code fromFile} through the link up
     * to 1000 times; this asserts that NONE of those calls returns the ~513 MiB target's content.
     *
     * <p>The race itself still runs in a forked {@link SymlinkRaceProbeMain} process with an
     * explicit, generous {@code -Xmx3g}, not inline in this test, for the same reason as before
     * the fix: the race target must genuinely exceed the production 512 MiB cap to prove anything,
     * and decoding a body that size does not fit in this module's fixed Gradle test-worker heap.
     */
    @Test
    void symlinkRetargetedBetweenTheSizeProbeAndTheReadNoLongerBypassesTheSizeCap() throws Exception {
        Path smallFile = tempDir.resolve("small.json");
        Files.writeString(smallFile,
            "{\"version\":1,\"source\":\"small\",\"sourceHeight\":0,\"chainId\":1,\"balances\":{}}",
            StandardCharsets.UTF_8);

        Path bigFile = tempDir.resolve("big.json");
        writePaddedJson(bigFile, "big", 513L * 1024 * 1024);
        assertTrue(Files.size(bigFile) > 512L * 1024 * 1024,
            "the race target must itself exceed the 512 MiB cap, or a successful read would prove "
                + "nothing about the cap being bypassed");

        Path link = tempDir.resolve("link.json");

        SubprocessRunner.Result result = SubprocessRunner.run(
            SymlinkRaceProbeMain.class.getName(),
            List.of("-Xmx3g"),
            List.of(smallFile.toString(), bigFile.toString(), link.toString(), "1000"),
            60_000);

        assertTrue(result.stdout().contains("NO_BYPASS"),
            "expected NONE of 1000 racing fromFile() calls to ingest the over-cap symlink target "
                + "now that fromFile opens the path once and derives size+content from the same "
                + "handle -- if this now reports BYPASS, the single-open fix regressed. exitCode="
                + result.exitCode() + " stdout=" + result.stdout() + " stderr=" + result.stderr());
    }

    /**
     * E2E-56 -- point {@code RHIZOME_SNAPSHOT} at a directory through a real child process, hoping
     * the directory read surfaces as an unhandled/confusing exception, or worse, that the process
     * binds its port before the read is attempted.
     *
     * <p>DEFENDED: the real process refuses to boot, with a clean, typed message and its port
     * never opened -- the same shape as the oversized- and missing-snapshot scenarios beside it
     * (E2E-49/50).
     */
    @Test
    void aDirectoryAsTheSnapshotPathFailsCleanlyThroughARealProcessBoot() throws Exception {
        Path directory = tempDir.resolve("snapshot-is-a-directory");
        Files.createDirectories(directory);

        try (MinimalNodeProcess node = MinimalNodeProcess.start(
                tempDir.resolve("dir-path-data"),
                Map.of("RHIZOME_NETWORK", "mainnet", "RHIZOME_SNAPSHOT", directory.toString()))) {
            boolean exited = node.awaitExit(15_000);
            assertTrue(exited, "a directory-as-snapshot-path process should fail fast, not hang: "
                + "stdout=" + node.stdout() + " stderr=" + node.stderr());
            assertNotEquals(0, node.exitCode(),
                "a directory snapshot path must refuse boot with a non-zero exit code");
            assertFalse(node.awaitPortOpen(2_000, 100),
                "the process must never accept a TCP connection -- the snapshot read must happen "
                    + "before the HTTP server is ever bound");
        }
    }

    /**
     * E2E-57 -- point {@code RHIZOME_SNAPSHOT} at a FIFO with no writer through a real child
     * process, hoping the boot either fails cleanly within a sane budget or, if it does not, that
     * nobody notices the difference between "still starting" and "will never start".
     *
     * <p>DEFENDED for the case tested here: {@code SnapshotLoader.fromFile} now stats the
     * resolved path's type ({@code Files.readAttributes}, which does not block on a FIFO) and
     * refuses anything that is not a regular file BEFORE ever calling the open that could block --
     * a real child process pointed at a writerless FIFO now exits almost immediately with a clean,
     * typed error, and never opens its port. (This does not close the narrower race where the
     * resolved target's type changes between that stat and the open; see {@code fromFile}'s
     * javadoc and this class's javadoc for why that residual is bounded by a timeout rather than
     * eliminated -- it is not what this specific test exercises.)
     */
    @Test
    void aFifoWithNoWriterNowFailsFastInsteadOfHangingTheBoot() throws Exception {
        Path fifo = tempDir.resolve("snapshot.fifo");
        Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString())
            .redirectErrorStream(true)
            .start();
        boolean created = mkfifo.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(created && mkfifo.exitValue() == 0,
            "could not create a FIFO with mkfifo -- this scenario needs a Linux-style FIFO");

        try (MinimalNodeProcess node = MinimalNodeProcess.start(
                tempDir.resolve("fifo-data"),
                Map.of("RHIZOME_NETWORK", "mainnet", "RHIZOME_SNAPSHOT", fifo.toString()))) {
            boolean exited = node.awaitExit(15_000);
            assertTrue(exited, "a writerless-FIFO snapshot path should now fail fast, not hang: "
                + "stdout=" + node.stdout() + " stderr=" + node.stderr());
            assertNotEquals(0, node.exitCode(),
                "a writerless-FIFO snapshot path must refuse boot with a non-zero exit code");
            assertTrue(node.stderr().contains("not a regular file"),
                "expected fromFile's type-check message naming the refusal in stderr, got: "
                    + node.stderr());
            assertFalse(node.awaitPortOpen(2_000, 100),
                "the process must never accept a TCP connection -- the type check (and therefore "
                    + "the refusal) must happen before the HTTP server is ever bound");
        }
        // MinimalNodeProcess.close() forcibly terminates the (by now already-exited) child.
    }

    /** Streams {@code prefix + N padding bytes + suffix} to {@code file} as valid JSON without
     *  ever holding the whole body in memory at once. */
    private static void writePaddedJson(Path file, String sourceTag, long paddingBytes) throws IOException {
        String prefix = "{\"version\":1,\"source\":\"" + sourceTag + "\",\"sourceHeight\":0,"
            + "\"chainId\":1,\"balances\":{},\"pad\":\"";
        String suffix = "\"}";
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)) {
            out.write(prefix.getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[1 << 20];
            Arrays.fill(chunk, (byte) 'a');
            long written = 0;
            while (written < paddingBytes) {
                int n = (int) Math.min(chunk.length, paddingBytes - written);
                out.write(chunk, 0, n);
                written += n;
            }
            out.write(suffix.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
