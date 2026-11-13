package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.vm.InMemoryContractStore;

class SnapshotBootstrapFailClosedAttackTest {

    @TempDir
    Path tempDir;

    private boolean run(SnapTestFixture f, SnapTestFixture.Target target, SnapTestFixture.Peer peer) {
        return SnapshotBootstrap.bootstrap(SnapTestFixture.PARAMS, f.genesisSnapshot, target,
            new InMemoryContractStore(), peer, SnapTestFixture.NOW, tempDir);
    }

    /**
     * STATE-08 — Make a snap bootstrap fail AFTER its verification passed and the stores were
     * seeded (a crash-equivalent: disk full, a store error), hoping the abort path clears the
     * in-progress marker over half-seeded stores so the next boot runs on them. Once anything has
     * been written the marker stays set and the failure propagates, so boot refuses with the
     * "inconsistent, delete and restart" message instead; an attempt that wrote nothing (a
     * verification failure) still clears the marker so another peer can be tried.
     */
    @Test
    void anAbortAfterSeedingLeavesTheMarkerSetAndStopsTheSearch() {
        SnapTestFixture f = new SnapTestFixture();
        var peer = new SnapTestFixture.Peer(100, f.info(), f.chain(5), f.chunk, BigInteger.TEN);

        var failing = new SnapTestFixture.Target();
        failing.failOnHeaders = true;
        assertThrows(RuntimeException.class, () -> run(f, failing, peer));
        assertTrue(failing.bootstrapping, "marker must stay set over half-seeded stores");

        // Nothing written (the chunk does not rebuild the committed root): marker cleared, retryable.
        byte[] wrongChunk = new SnapTestFixture().chunk;
        var liar = new SnapTestFixture.Peer(100, f.info(), f.chain(5), wrongChunk, BigInteger.TEN);
        var untouched = new SnapTestFixture.Target();
        assertFalse(run(f, untouched, liar));
        assertFalse(untouched.bootstrapping, "a refusal that wrote nothing clears the marker");
        assertEquals(0, untouched.adoptedHeaders);

        // And the honest path still adopts and clears the marker.
        var ok = new SnapTestFixture.Target();
        assertTrue(run(f, ok, peer));
        assertFalse(ok.bootstrapping);
    }
}
