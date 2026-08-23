package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.testsupport.SubprocessRunner;

/**
 * Build I6 -- closes a real Medium-severity post-implementation-review finding (T025):
 * {@code SnapshotLoader.fromResource(String)} takes only a bare resource path, giving no seam to
 * inject a {@link java.net.URLConnection} that reports {@code getContentLengthLong() == -1}, so
 * its size-unknown fallback branch has never been exercised by a real, uninstrumented resource.
 * {@link SnapshotFallbackProbeMain} closes the seam gap with a classloader trick rather than a
 * production code change (see its javadoc); this class drives it as a forked process (required
 * for an independent {@code -Xmx}) and interprets the results.
 *
 * <p><b>Why not the literal 512 MiB cap.</b> The obvious design -- a resource just under, and just
 * over, the real 512 MiB cap -- turns out to have no discriminating power under any heap
 * dramatically smaller than 512 MiB: accumulating ~511 MiB and ~513 MiB both overrun (say) a
 * 64 MiB heap identically, so "just under the cap succeeds, just over it fails cleanly" cannot be
 * demonstrated that way at all -- both sides would simply OOM, telling you nothing about the
 * fallback branch's own behaviour. What actually distinguishes outcomes under a small heap is the
 * resource's size RELATIVE TO THAT HEAP, not relative to the cap, so this test picks sizes on
 * either side of the -Xmx64m boundary instead, both comfortably under the real 512 MiB cap:
 * <ul>
 *   <li>{@link #aResourceComfortablyUnderTheHeapCompletesWithoutOom()} -- an 8 MiB body (Case A):
 *       completes successfully under a 64 MiB heap. DEFENDED for the ordinary case: an unknown
 *       declared length does not, by itself, break a normal-sized resource.</li>
 *   <li>{@link #aResourceWellUnderTheCapButOverTheHeapStillOomsInsteadOfStreaming()} -- a 200 MiB
 *       body (Case B), still four times under the 512 MiB pinned ceiling: RESIDUAL, unchanged by
 *       the read-path fix below -- see that method's javadoc for the root-cause investigation and
 *       exactly what was and was not fixed.</li>
 * </ul>
 * The documented cap itself (the {@code declaredSize >= 0} probe path, and {@code readAtMost}'s
 * own {@code exceeds cap} rejection under an ample heap) is already covered by
 * {@code LedgerSnapshotTest#resourceLoadingCarriesTheSameGuardsAsFileLoading}; this class is
 * specifically about the allocation profile of the fallback branch under a constrained heap,
 * which nothing else exercises.
 */
class E2EGenesisSnapshotFallbackTest {

    private static final long TIMEOUT_MS = 20_000;

    /**
     * E2E-59 -- feed {@code SnapshotLoader.fromResource}'s size-unknown fallback branch an
     * ordinary-sized (8 MiB) resource under a constrained 64 MiB heap, hoping the untested
     * fallback path turns out to break normal operation, not just the extreme case.
     *
     * <p>DEFENDED: completes successfully, no {@link OutOfMemoryError}.
     */
    @Test
    void aResourceComfortablyUnderTheHeapCompletesWithoutOom() throws Exception {
        SubprocessRunner.Result result = SubprocessRunner.run(
            SnapshotFallbackProbeMain.class.getName(),
            List.of("-Xmx64m"),
            List.of(Long.toString(8L * 1024 * 1024)),
            TIMEOUT_MS);

        assertTrue(result.stdout().contains("RESULT:SUCCESS"),
            "expected the 8 MiB fallback-path resource to load successfully under a 64 MiB heap: "
                + "exitCode=" + result.exitCode() + " stdout=" + result.stdout()
                + " stderr=" + result.stderr());
    }

    /**
     * E2E-60 -- feed the same fallback branch a 200 MiB resource (still comfortably under the
     * real 512 MiB pinned cap) under the same constrained 64 MiB heap, hoping the fallback path
     * turns out to stream/bound its read against the cap rather than buffering the whole body,
     * which would make it safe on a small-heap deployment regardless of the resource's size
     * relative to that heap.
     *
     * <p>RESIDUAL, investigated and only partially closed. {@code SnapshotLoader.readAtMost} was
     * rewritten to read into fixed-size (64 KiB) chunks merged into one right-sized array at the
     * end, instead of a {@code ByteArrayOutputStream} (whose doubling growth plus its own
     * {@code toByteArray()} copy momentarily held two-to-three full-size copies of the content
     * alive at once). Root-cause isolation (a standalone probe instrumented step-by-step, not
     * included in this suite) found BOTH of the two suspected causes are real and independent:
     * <ol>
     *   <li>{@code readAtMost}'s own buffering overhead (now reduced, not eliminated, by the
     *       chunked rewrite): under a 64 MiB heap, the previous implementation's practical
     *       ceiling for a full read+parse round-trip was content around 10 MiB; the chunked
     *       version pushes that to roughly 25-29 MiB before the final merge or the JSON parse
     *       itself exhausts the same heap.</li>
     *   <li>{@code org.json}'s DOM parse of the resulting content independently roughly doubles
     *       to triples peak memory relative to the raw byte count (per-key/value String and
     *       {@code HashMap} overhead, plus {@code JSONTokener} building large string values
     *       character-by-character into a growing buffer) -- confirmed by the probe: content
     *       whose read and UTF-8 decode succeeded outright still {@code OutOfMemoryError}'d
     *       during the {@code JSONObject} construction alone.</li>
     * </ol>
     * Neither cause, nor both together, gets anywhere close to fitting a 200 MiB body in a 64 MiB
     * heap -- this test still reliably reports {@link OutOfMemoryError} rather than completing or
     * cleanly rejecting via the "too large" {@code IOException}, exactly as before the read-path
     * fix. Fully eliminating this would require never materializing the whole decoded body at
     * all -- i.e. parsing JSON from a bounded {@code Reader} directly rather than a {@code String}
     * -- which is a materially larger change (effectively a different JSON-parsing strategy) than
     * this fix's scope covers, so the residual is declared here rather than silently narrowed or
     * force-closed. If a future change streams the parse, this scenario should be promoted from
     * RESIDUAL to DEFENDED and this assertion updated to expect success.
     */
    @Test
    void aResourceWellUnderTheCapButOverTheHeapStillOomsInsteadOfStreaming() throws Exception {
        SubprocessRunner.Result result = SubprocessRunner.run(
            SnapshotFallbackProbeMain.class.getName(),
            List.of("-Xmx64m"),
            List.of(Long.toString(200L * 1024 * 1024)),
            TIMEOUT_MS);

        assertTrue(result.stdout().contains("RESULT:OOM"),
            "expected a 200 MiB fallback-path resource (well under the real 512 MiB cap) to "
                + "OutOfMemoryError under a 64 MiB heap, confirming the fallback branch buffers "
                + "rather than streams -- if this now reports RESULT:SUCCESS, the fallback path "
                + "was fixed to stream/bound its memory and this test should be promoted off "
                + "RESIDUAL. exitCode=" + result.exitCode() + " stdout=" + result.stdout()
                + " stderr=" + result.stderr());
    }
}
