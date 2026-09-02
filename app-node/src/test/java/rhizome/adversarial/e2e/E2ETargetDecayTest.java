package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.EmissionCurve;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyTargetSchedule;
import rhizome.node.RhizomeNode;

/**
 * The {@code DECAY} family's end-to-end proofs (008-decaying-supply-target): real nodes, real
 * sockets, real mining — the adversarial catalogue's E2E rows for the decaying supply target.
 */
class E2ETargetDecayTest {

    @TempDir
    Path tempDir;

    /**
     * The instant-mining profile carrying the shared decay schedule. The constants are NOT restated
     * here: {@code CurveActiveNetwork.decaySchedule} is their one home (008 T001, WI-8), applied to
     * this suite's own base — {@code TestNetwork.CURVE_ACTIVE} mines instantly, which the lib-core
     * fixture's real-PoW base cannot do, and both carry the same test-scale peak the shared floor
     * is defined against.
     */
    private static NetworkParameters decayActive() {
        return CurveActiveNetwork.decaySchedule(TestNetwork.CURVE_ACTIVE.toBuilder()).build();
    }

    /**
     * E2E-87 — Mine a real node across a decay-epoch boundary (008's {@code SupplyTargetSchedule})
     * and join a fresh peer that syncs the whole history from scratch. The boundary is a plain
     * consensus fact by height: the block at the first decayed height pays the stepped target,
     * the block before it pays the peak, and a peer replaying the same headers re-derives every
     * committed supply identically — no rollback code, no cache, nothing to disagree about.
     *
     * <p>Anchoring discipline (WI-14, the E2E-71 flake): the producer runs inside
     * {@code start()}, so every assertion here anchors on a CAPTURED height read back through
     * {@code headerAt}/{@code blockAt} after an {@code awaitHeight}, never on the live tip.
     */
    @Test
    void aRealNodeMinesAcrossADecayEpochBoundaryAndAPeerSyncsTheHistoryFromScratch()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = decayActive();
            SupplyTargetSchedule schedule = params.supplyTargetSchedule();
            long boundary = schedule.startHeight() + schedule.epochBlocks(); // first decayed: 15
            long settleHeight = boundary + 2 * schedule.epochBlocks();       // well past it: 25

            RhizomeNode miner = network.node("miner").params(params).mining().start();
            TestNetwork.awaitHeight(miner, (int) settleHeight);

            // Captured heights, never the live tip: the block BEFORE the boundary pays the peak
            // target's reward; the boundary block pays the stepped target's.
            EmissionCurve peakCurve = EmissionCurve.build(params.supplyTarget(),
                params.emissionCoefficient(), params.emissionTableSteps());
            long supplyBefore = miner.engine().headerAt(boundary - 2).supply();
            long expectedBefore = Math.max(params.minerRevenueFloor(),
                peakCurve.raw(supplyBefore, schedule.peak()));
            long supplyAt = miner.engine().headerAt(boundary - 1).supply();
            long expectedAt = Math.max(params.minerRevenueFloor(),
                peakCurve.raw(supplyAt, schedule.targetAt(boundary)));
            long paidBefore = miner.engine().blockAt(boundary - 1).transactions()
                .get(0).amount().amount();
            long paidAt = miner.engine().blockAt(boundary).transactions()
                .get(0).amount().amount();
            assertEquals(expectedBefore, paidBefore,
                "the last pre-boundary block must pay the peak-target reward");
            assertEquals(expectedAt, paidAt,
                "the boundary block must pay the decayed-target reward");
            assertNotEquals(paidBefore, paidAt,
                "the epoch step must be a real, observable discontinuity in the mined chain");

            // A peer joining afterwards syncs the whole history across the boundary from
            // scratch and converges on the identical blocks — replay re-derives every
            // committed supply with no rollback code and no schedule state.
            RhizomeNode peer = network.node("peer").params(params)
                .peers(TestNetwork.urlOf(miner)).start();
            TestNetwork.await(() -> peer.engine().height() >= settleHeight,
                () -> "peer never synced past the decay boundary");
            for (long h = boundary - 1; h <= settleHeight; h++) {
                assertEquals(miner.engine().blockAt(h).hash(), peer.engine().blockAt(h).hash(),
                    "height " + h + " must converge on the identical block across the boundary");
                assertEquals(miner.engine().headerAt(h).supply(),
                    peer.engine().headerAt(h).supply(),
                    "height " + h + " must commit the identical supply on both nodes");
            }
        }
    }
}
