package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.blockchain.Issuance;
import rhizome.core.ledger.PublicAddress;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * End-to-end proofs for the supply header commitment (§ supply header commitment): a running
 * node's real consensus engine and a real syncing peer must agree, on the wire, about what the
 * committed native supply is at every height — not just when {@code ChainEngine.addBlock} or
 * {@code HeaderChain.validate} are called directly with an in-process fixture.
 *
 * <p>Three angles: a forged block posted straight at {@code /submit} (does the real HTTP/consensus
 * boundary refuse it, and stay healthy afterwards?), a forged header stream served to a real
 * syncing node (does the lie survive real parsing, caps and deadlines?), and two real mining nodes
 * that fork and reorg over real HTTP (do their independently-read committed figures actually agree,
 * and do they match what the converged chain's own headers say happened — not a number derived any
 * other way)?
 */
class E2ESupplyCommitmentTest {

    @TempDir
    Path tempDir;

    /** Block cadence for the fork/converge scenario — see {@code E2EForkConvergenceTest}'s BLOCK_MS
     *  javadoc: fast enough that propagation beats production, so the fork heals before finality. */
    private static final long BLOCK_MS = 250;

    private record Untouched(SHA256Hash secondBlock, long height, BigInteger work) {
        static Untouched of(RhizomeNode node) {
            return new Untouched(node.engine().blockAt(2).hash(), node.engine().height(),
                node.engine().totalWork());
        }
    }

    /** The "refusal is free" assertion: an encounter with a lying peer must cost the victim
     *  nothing — same shape as {@code E2EHostilePeerTest#assertSurvivedIntact}. */
    private static void assertSurvivedIntact(RhizomeNode victim, Untouched before)
            throws InterruptedException {
        assertEquals(before.secondBlock(), victim.engine().blockAt(2).hash(),
            "the victim's history was rewritten by a peer that proved nothing");
        assertTrue(victim.engine().height() >= before.height(),
            "the victim lost chain height to a hostile peer");
        assertTrue(victim.engine().totalWork().compareTo(before.work()) >= 0,
            "the victim lost accumulated work");
        assertFalse(victim.engine().isDegraded(),
            "the encounter left the victim degraded, which halts every new-tip write");

        long resumed = victim.engine().height();
        TestNetwork.await(() -> victim.engine().height() > resumed,
            () -> "the victim stopped producing blocks after the encounter");
    }

    /** Adds one peer and waits for its admission to complete before the caller adds another. */
    private static void admit(RhizomeNode node, String peerUrl) throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }

    private static void meet(RhizomeNode victim, String peerUrl) throws InterruptedException {
        admit(victim, peerUrl);
        for (int round = 0; round < 4; round++) {
            victim.syncRound();
        }
    }

    /** The deepest height both nodes have, backed off two blocks so neither tip is in flight. */
    private static long settledHeight(RhizomeNode a, RhizomeNode b) {
        return Math.max(2, Math.min(a.engine().height(), b.engine().height()) - 2);
    }

    /**
     * E2E-34 — Push a supply-forged block straight at a real node's {@code /submit} route, hoping
     * the API boundary, the real consensus engine and the gossip fault table disagree about
     * whether it is an accepted mutation or a rejected structural fault.
     *
     * <p>No producer, for the same reason {@code E2EContractTest}'s poison-block scenario has
     * none: with a live miner the forged block would go stale (a new honest tip lands under it)
     * before it could be posted, and the scenario would prove nothing about the supply gate
     * specifically. {@code BlockImpl.supply} is called on an already fully-mined block, which
     * invalidates its cached hash — expected, and exactly the point: {@code ChainEngine.addBlock}
     * checks supply, cheap header-only arithmetic, before it ever re-verifies that (now-stale)
     * nonce (WHITEPAPER §3.5, cheapest-first ordering).
     */
    @Test
    void aSupplyForgedBlockPushedAtTheSubmitRouteIsRejectedAndTheNodeStaysHealthy() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("victim").start();
            int port = node.apiPort();

            E2EFixtures.mintEmpty(node, PublicAddress.random(), 5);
            assertEquals(6, node.engine().height());

            Block honest = E2EFixtures.build(node, PublicAddress.random());
            BlockImpl poison = (BlockImpl) honest;
            long parentSupply = node.engine().headerAt(node.engine().height()).supply();
            long heightBefore = node.engine().height();
            // Forge the committed value: parent supply plus a wrong delta that skips this
            // block's own scheduled issuance entirely.
            poison.supply(parentSupply + 1);

            var response = RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(poison));

            assertNotEquals(200, response.status(),
                "the node accepted a block whose committed supply does not match its own issuance");
            assertEquals(heightBefore, node.engine().height(),
                "the supply-forged block must not extend the chain");
            assertFalse(node.engine().isDegraded(),
                "refusing the supply-forged block left the node degraded");

            // Alive, and still willing to do honest work — a refusal that wedges the node is not
            // a defence.
            E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
            assertEquals(200, RawHttp.get(port, "/block_count", Map.of()).status());
        }
    }

    /**
     * E2E-35 — Serve a real syncing node a headers-only response whose supply delta is forged
     * partway through, over a real socket, hoping the lie survives real parsing, real caps and
     * real deadlines long enough to cost the victim a single body fetch or a byte of local state.
     *
     * <p>Simplification, noted rather than fought: {@code HostilePeer.servesHeaders} answers every
     * {@code /headers} query with the same fixed byte stream, exactly like the existing
     * {@code .serves()}/{@code /sync} case — it does not slice by the requested range the way a
     * real peer would. That means the ancestor-locator probe and the bulk branch fetch both land
     * on the same bytes, so the header run the victim actually validates is discontinuous at its
     * very first candidate rather than failing deep at the forged tail specifically. The proof this
     * asserts is therefore the weaker-but-still-real claim the scenario explicitly allows: the
     * victim's local chain, read back from its own engine, is provably untouched by this peer —
     * every real byte offered (forged supply included) was decoded by the real {@code HeaderCodec}
     * and the real synchronizer over a real socket, and none of it moved victim state.
     */
    @Test
    void aHostileHeadersResponseWithAForgedSupplyDeltaLeavesTheVictimsChainUntouched() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode source = network.node("source").mining().blockInterval(150).start();
            TestNetwork.awaitHeight(source, 7);

            // Honest prefix: real headers 1..6 (heights 2..6 are source's own real blocks, height
            // 1 is the shared genesis both nodes derive identically from the same network
            // parameters). The forged header replaces height 7.
            long prefixTop = 6;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (long h = 1; h <= prefixTop; h++) {
                out.writeBytes(HeaderCodec.encode(source.engine().headerAt(h)));
            }
            BlockHeader honestLast = source.engine().headerAt(prefixTop + 1);
            BlockHeader forged = new BlockHeader(
                honestLast.id(), honestLast.timestamp(), honestLast.difficulty(),
                honestLast.numTransactions(), honestLast.lastBlockHash(), honestLast.merkleRoot(),
                honestLast.nonce(), honestLast.stateRoot(), honestLast.vote(),
                honestLast.supply() + 1, honestLast.uncles());
            out.writeBytes(HeaderCodec.encode(forged));
            byte[] hostileHeaders = out.toByteArray();

            RhizomeNode victim = network.node("victim").mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(source)
                    .claimsHeight(prefixTop + 1)
                    // Properly JSON-shaped (HostilePeer's own default is a bare decimal string,
                    // which HttpPeerSource.totalWork() would already reject as malformed before
                    // ever reaching /headers) -- so the sync round genuinely walks into the
                    // headers-first path this scenario is about, instead of being turned away one
                    // call earlier for an unrelated reason.
                    .claimsWork(() -> new JSONObject().put("totalWork",
                        BigInteger.TWO.pow(200).toString()).toString())
                    .servesHeaders(() -> hostileHeaders)
                    .start()) {
                meet(victim, liar.url());
                assertSurvivedIntact(victim, before);

                // Never adopts anything at or beyond the forged height, even once the victim's own
                // mining reaches it.
                long forgedHeight = prefixTop + 1;
                TestNetwork.await(() -> victim.engine().height() >= forgedHeight,
                    () -> "the victim never resumed mining up to the forged height");
                assertNotEquals(forged.hash(), victim.engine().blockAt(forgedHeight).hash(),
                    "the victim must never adopt the branch carrying the forged supply");
            }
        }
    }

    /**
     * E2E-36 — Fork two real mining nodes with divergent uncle inclusion so their per-block
     * issuance genuinely diverges, let them reorg to the heavier branch over real HTTP sync, and
     * see whether the two nodes' real, independently-read supply figures agree once they converge.
     *
     * <p>Chosen approach, per the scenario's own escape hatch: deliberately engineering divergent
     * uncle inclusion between two independently-mining real nodes is not attempted here — this test
     * harness has no shared miner wiring to force one node's orphaned blocks into another's uncle
     * set on a schedule, and building that machinery is a project of its own. This proof
     * demonstrates the baseline instead: exact supply agreement across a real fork-then-reorg
     * between two independently mining nodes, with the expected figure derived from nothing but the
     * converged chain's own headers — each header's actual committed difficulty and actual
     * (possibly empty) uncle list, walked through {@code Issuance.minted} from genesis — so the
     * assertion cannot pass by coincidence with a hardcoded number. If the two branches happen to
     * produce genuine uncles along the way (orphaned blocks registered during the reorg and later
     * referenced), the recomputation still holds, because it reads each header's real uncle list
     * rather than assuming it is empty.
     */
    @Test
    void twoForkedMiningNodesThatConvergeAgreeOnSupplyAtTheSettledHeight() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode left = network.node("left").mining().blockInterval(BLOCK_MS).start();
            RhizomeNode right = network.node("right").mining().blockInterval(BLOCK_MS).start();

            TestNetwork.awaitHeight(left, 8);
            TestNetwork.awaitHeight(right, 8);
            assertNotEquals(left.engine().blockAt(2).hash(), right.engine().blockAt(2).hash(),
                "the partition must have produced two real histories");

            admit(left, TestNetwork.urlOf(right));
            admit(right, TestNetwork.urlOf(left));

            TestNetwork.syncUntil(List.of(left, right), () -> {
                long settled = settledHeight(left, right);
                return left.engine().blockAt(settled).hash().equals(right.engine().blockAt(settled).hash());
            });

            long settled = settledHeight(left, right);
            assertEquals(left.engine().blockAt(settled).hash(), right.engine().blockAt(settled).hash());
            assertEquals(left.engine().blockAt(2).hash(), right.engine().blockAt(2).hash(),
                "convergence must reach the fork point, not just the recent tail");

            long leftSupply = left.engine().headerAt(settled).supply();
            long rightSupply = right.engine().headerAt(settled).supply();
            assertTrue(leftSupply >= 0,
                "the left node's committed supply must be genuinely committed, not absent");
            assertTrue(rightSupply >= 0,
                "the right node's committed supply must be genuinely committed, not absent");
            assertEquals(leftSupply, rightSupply,
                "two nodes that converged on the same history must read back the same supply");

            // Recompute independently from the converged chain's own headers — every block's ACTUAL
            // difficulty and ACTUAL uncle refs, never a hardcoded number.
            long recomputed = left.engine().headerAt(1).supply();
            for (long h = 2; h <= settled; h++) {
                BlockHeader header = left.engine().headerAt(h);
                recomputed = Math.addExact(recomputed, Issuance.minted(
                    left.engine().params(), header.id(), recomputed, header.difficulty(), header.uncles()));
            }
            assertEquals(recomputed, leftSupply,
                "the committed supply must equal the sum of every block's own issuance since genesis");
        }
    }
}
