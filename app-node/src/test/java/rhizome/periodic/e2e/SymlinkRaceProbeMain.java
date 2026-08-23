package rhizome.periodic.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.SnapshotLoader;

/**
 * Standalone probe for {@link E2EGenesisExoticPathsTest}'s TOCTOU scenario, run as a genuinely
 * separate {@code java} process (via {@code rhizome.testsupport.SubprocessRunner}) with an
 * explicit, generous heap.
 *
 * <p>Why a subprocess at all, rather than racing this in the test itself: this module's Gradle
 * test workers run with a fixed 512 MiB heap (measured, not assumed -- see the class javadoc on
 * {@link E2EGenesisExoticPathsTest}), and the whole point of this scenario is a race target that
 * must genuinely exceed the production 512 MiB cap. Reading and JSON-parsing such a file needs
 * more live heap than the target's own raw byte size (the decoded {@code String} plus
 * {@code org.json}'s parsed copy of its large string field), which cannot fit in a heap no bigger
 * than the cap itself. A forked process with its own {@code -Xmx} sidesteps that entirely without
 * needing to touch the test worker's heap for the whole module.
 *
 * <p>Args: {@code <smallFile> <bigFile> <symlink> <maxIterations>}. Prints exactly one line:
 * {@code BYPASS:<iteration>} the first time {@code SnapshotLoader.fromFile(symlink)} returns the
 * big target's content successfully, or {@code NO_BYPASS} if it never does within
 * {@code maxIterations}.
 */
public final class SymlinkRaceProbeMain {

    private SymlinkRaceProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Path smallFile = Path.of(args[0]);
        Path bigFile = Path.of(args[1]);
        Path link = Path.of(args[2]);
        int maxIterations = Integer.parseInt(args[3]);

        AtomicBoolean stop = new AtomicBoolean(false);
        Thread flipper = new Thread(() -> {
            boolean toBig = true;
            while (!stop.get()) {
                try {
                    Path tmp = link.resolveSibling(link.getFileName() + ".tmp." + System.nanoTime());
                    Files.createSymbolicLink(tmp, toBig ? bigFile : smallFile);
                    Files.move(tmp, link, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                    toBig = !toBig;
                } catch (IOException ignored) {
                    // lost a race with our own rename -- try again next loop
                }
            }
        });
        flipper.setDaemon(true);
        flipper.start();

        int bypassAt = -1;
        for (int i = 0; i < maxIterations; i++) {
            try {
                LedgerSnapshot snapshot = SnapshotLoader.fromFile(link);
                if (snapshot.source().equals("big")) {
                    bypassAt = i;
                    break;
                }
            } catch (IOException expectedMostOfTheTime) {
                // "too large" (the probe caught the big target) or a transient dangling-symlink
                // race with the flipper thread -- both fine, we are fishing for the one
                // interleaving that is neither.
            }
        }
        stop.set(true);
        flipper.join(5_000);

        System.out.println(bypassAt >= 0 ? "BYPASS:" + bypassAt : "NO_BYPASS");
    }
}
