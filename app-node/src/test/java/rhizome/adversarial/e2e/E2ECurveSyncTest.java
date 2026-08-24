package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.node.RhizomeNode;

/**
 * End-to-end snap-sync proof for the supply-driven logarithmic emission curve (§ integer log
 * curve): a node that never replays a single block body through {@code Executor} — it bootstraps
 * purely from a peer's materialised state snapshot — must still assemble a curve-correct coinbase
 * for its own next block. {@code BlockAssembler}/{@code ChainEngine.checkSupply} read
 * {@code parentSupply} from {@code BlockHeader.supply()}, a per-block committed header field, never
 * from a running total {@code Executor} accumulates as it replays — so this should already hold
 * with no curve-specific code, but {@code E2EPrunedAndSnapSyncTest}/{@code E2EGenesisIdentityTest}
 * never exercised a curve-active profile, and neither proved a snap-synced node's OWN next block
 * carried the correct coinbase.
 */
class E2ECurveSyncTest {

    @TempDir
    Path tempDir;

    /**
     * {@link TestNetwork#CURVE_ACTIVE} with a shallow finality window, mirroring
     * {@code E2EPrunedAndSnapSyncTest}'s {@code SHALLOW} — retention/pivot rules only need to
     * clear a handful of blocks to prove the point, not the production 120-block depth.
     */
    private static final NetworkParameters CURVE_SNAP =
        TestNetwork.CURVE_ACTIVE.toBuilder().maxReorgDepth(8).build();

    /**
     * E2E-79 — Bootstrap a fresh curve-active node purely via snap-sync (so it never replays chain
     * history through {@code Executor}) against an honest peer already several blocks past
     * {@code emissionCurveHeight}, then have the snap-synced node mine its own next block and
     * compare its coinbase against a fully-replaying peer's real block at the identical height,
     * extending the identical parent. Verified in the code: both parties' coinbase amounts are the
     * pure function {@code params.miningReward(height, parentSupply)} of nothing but the height and
     * the parent's header-committed supply, and the two nodes are first proven to agree on that
     * exact parent header — so the two coinbases are not a coincidence of timing, they are
     * mathematically forced to be identical once header agreement is established.
     */
    @Test
    void aSnapSyncedCurveActiveNodeMinesACoinbaseMatchingAFullyReplayedPeersAtTheSameHeight()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode source = network.node("source")
                .params(CURVE_SNAP).mining().blockInterval(30).snapshotEvery(5).start();

            TestNetwork.awaitHeight(source, 30);
            assertTrue(source.service().materializeSnapshot(),
                "the source node could not materialise a state snapshot to serve");
            TestNetwork.await(() -> source.service().snapshotPivot() > 1,
                () -> "no snapshot pivot was ever advertised");
            long pivot = source.service().snapshotPivot();
            assertTrue(pivot >= CURVE_SNAP.emissionCurveHeight(),
                "the pivot must sit at or past curve activation for this scenario to mean anything");

            // Bury the pivot well past the finality window AND past the fixed target height below,
            // so source's real chain already covers everything this test reads from it, with no
            // timing dependency on how fast fresh's sync happens to run.
            long targetHeight = pivot + 3;
            TestNetwork.awaitHeight(source, targetHeight + CURVE_SNAP.maxReorgDepth() + 2);

            RhizomeNode fresh = network.node("fresh")
                .params(CURVE_SNAP).snapSync().peers(TestNetwork.urlOf(source)).start();
            TestNetwork.syncUntil(fresh, () -> fresh.engine().height() >= targetHeight);
            long h = fresh.engine().height();

            // The snap-synced node genuinely never replayed the bodies below its pivot — it cannot
            // even serve them — which is the "never through Executor" half of this scenario,
            // mirroring E2EPrunedAndSnapSyncTest's own GONE check.
            var gone = RawHttp.get(fresh.apiPort(), "/sync?start=1&end=2", Map.of());
            assertEquals(410, gone.status(),
                "a genuinely snap-synced node must not hold bodies below its pivot");

            // Agreement on the exact header both nodes are about to build their next block against.
            assertEquals(source.engine().headerAt(h).hash(), fresh.engine().headerAt(h).hash(),
                "the snap-synced node disagrees with its sync source about the header it will extend");
            assertEquals(source.engine().headerAt(h).supply(), fresh.engine().headerAt(h).supply(),
                "the snap-synced node's committed supply at its own tip must match its sync source's");
            assertFalse(fresh.engine().isDegraded());

            long nextHeight = h + 1;
            TestNetwork.awaitHeight(source, nextHeight);
            Block sourceNext = source.engine().blockAt(nextHeight);
            assertEquals(fresh.engine().headerAt(h).hash(), sourceNext.lastBlockHash(),
                "source's own block at the compared height must extend the exact same parent the "
                    + "snap-synced node is about to mine on, or the comparison below proves nothing");

            // The snap-synced node mines its OWN next block — BlockAssembler/checkSupply read
            // parentSupply from the committed header above, never from a replayed running total.
            Block freshNext = E2EFixtures.mint(fresh, PublicAddress.random());
            assertEquals(nextHeight, fresh.engine().height());
            assertFalse(fresh.engine().isDegraded());

            long freshCoinbase = freshNext.transactions().get(0).amount().amount();
            long sourceCoinbase = sourceNext.transactions().get(0).amount().amount();
            assertEquals(sourceCoinbase, freshCoinbase,
                "the snap-synced node's own coinbase must match what a peer that replayed the entire "
                    + "chain through Executor computes at the identical height off the identical parent");

            // Positive control: the snap-synced node keeps working past this proof.
            E2EFixtures.mint(fresh, PublicAddress.random());
            assertEquals(nextHeight + 1, fresh.engine().height());
            assertFalse(fresh.engine().isDegraded());
        }
    }
}
