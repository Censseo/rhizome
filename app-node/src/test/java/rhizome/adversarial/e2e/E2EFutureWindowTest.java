package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.HeaderCodec;
import rhizome.core.ledger.PublicAddress;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * End-to-end proofs for {@code maxFutureBlockTimeSec}: the bound is enforced identically whether a
 * genuinely-mined block arrives through a client's {@code /submit} request or a peer's header run,
 * and refusing either costs the victim nothing.
 *
 * <p>Two angles, mirroring the split {@code E2ESupplyCommitmentTest} already uses for the supply
 * commitment (a forged field reaches consensus two different ways, so it is proven two different
 * ways): a client posting an otherwise-honest, genuinely re-mined block whose timestamp alone sits
 * past the bound straight at a real node's HTTP surface, and a hostile peer serving a real victim a
 * headers-only run whose final header is future-stamped past the same bound over a real socket. The
 * peer's forged header needs no real proof of work — the future-window check runs before
 * {@code HeaderChain.validate} ever re-hashes it (WHITEPAPER §3.5's cheapest-first ordering) — while
 * the client's forged block does, since {@code ChainEngine.addBlock} only reaches its own copy of
 * the same check after the block has already paid for a real nonce (protocol rule 2: the attack
 * must reach the gate it names, not die earlier at the PoW check the mutation itself broke).
 */
class E2EFutureWindowTest {

    @TempDir
    Path tempDir;

    /**
     * Safety margin either side of the {@code maxFutureBlockTimeSec} boundary, absorbing the real
     * elapsed time between a timestamp being stamped (block: before its nonce is mined; header:
     * before the peer serves it) and the moment a real node's own clock reads it during validation
     * -- real Pufferfish2 mining and real socket/HTTP round trips, not an instantaneous fixture
     * clock. {@code TestNetwork.PUFFERFISH}'s own javadoc bounds worst-case single-block mining at
     * "a few seconds" (maxDifficulty(8), ~18 ms/hash observed on this box); this margin is
     * comfortably above that while staying small next to the 120 s window it sits inside.
     */
    private static final long MARGIN_MS = 20_000;

    /**
     * E2E-92 -- Post a block whose timestamp sits just past {@code maxFutureBlockTimeSec}'s bound
     * at a real node's {@code /submit} route -- genuinely mined <em>after</em> the timestamp is
     * stamped, so real proof of work and real merkle/state roots, invalid only in its timestamp --
     * hoping the real HTTP/consensus boundary accepts it, costs the node anything on refusal, or
     * treats an otherwise-identical block just inside the same bound any differently.
     */
    @Test
    void aFutureTimestampedBlockJustPastTheBoundaryIsRejectedForFreeWhileOneJustInsideIsAccepted()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("victim").params(TestNetwork.PUFFERFISH).start();
            int port = node.apiPort();
            long windowMs = TestNetwork.PUFFERFISH.maxFutureBlockTimeSec() * 1000L;

            long heightBefore = node.engine().height();
            SHA256Hash tipBefore = node.engine().tipHash();

            // Timestamp adjustment happens before merkle/state-root stamping and before the nonce
            // is mined (E2EFixtures#build's timestamp-adjust overload), so this block is fully
            // valid -- real PoW, correct merkle and state roots -- except for its timestamp.
            Block tooFar = E2EFixtures.build(node, PublicAddress.random(),
                ts -> ts + windowMs + MARGIN_MS);
            var rejected = RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(tooFar));
            assertEquals(400, rejected.status(),
                "a block stamped past the future-window bound must not be accepted");
            assertEquals("BLOCK_TIMESTAMP_IN_FUTURE",
                new JSONObject(rejected.body()).getString("status"),
                "the specific future-timestamp gate must be the one that refused it, not some "
                    + "other check the mutation happened to also trip");
            assertEquals(heightBefore, node.engine().height(),
                "a rejected block must not extend the chain");
            assertEquals(tipBefore, node.engine().tipHash(),
                "a rejected block must not move the tip -- refusal must be free (protocol rule 3)");

            Block justInside = E2EFixtures.build(node, PublicAddress.random(),
                ts -> ts + windowMs - MARGIN_MS);
            var accepted = RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(justInside));
            assertEquals(200, accepted.status(),
                "an otherwise-identical block stamped inside the future-window bound must be accepted");
            assertEquals("SUCCESS", new JSONObject(accepted.body()).getString("status"));
            assertEquals(heightBefore + 1, node.engine().height(),
                "the accepted block must actually extend the chain, not just report success");
        }
    }

    /**
     * E2E-93 -- Serve a real victim node, over a real socket through a hostile peer that otherwise
     * looks honest (shares the victim's real genesis, claims plausible height and work), a
     * headers-only run whose final header is future-stamped just past {@code maxFutureBlockTimeSec}
     * -- hoping the lie survives real parsing and real deadlines long enough to move the victim's
     * tip or cost it anything, or that the header-sync gate disagrees with the {@code /submit} gate
     * about where the bound sits.
     */
    @Test
    void aHostilePeerServingAFutureStampedFinalHeaderNeverMovesTheVictimsTipAndLeavesItHealthy()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode source = network.node("source").params(TestNetwork.PUFFERFISH).start();
            E2EFixtures.mintEmpty(source, PublicAddress.random(), 2);
            long prefixTop = source.engine().height() - 1; // one real block held back for forging
            assertEquals(3, source.engine().height());

            // Honest prefix: real headers up to prefixTop (height 1 is the genesis both nodes
            // derive identically from the same profile, with no shared snapshot needed).
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (long h = 1; h <= prefixTop; h++) {
                out.writeBytes(HeaderCodec.encode(source.engine().headerAt(h)));
            }
            // Only the timestamp is forged -- difficulty, supply, uncles and every other field stay
            // exactly what the real, legitimately-mined header committed, so this candidate reaches
            // HeaderChain.validate's future-window check specifically rather than dying earlier at
            // the (also cheaper) supply or difficulty checks the timestamp mutation does not touch.
            BlockHeader honestLast = source.engine().headerAt(prefixTop + 1);
            long windowMs = TestNetwork.PUFFERFISH.maxFutureBlockTimeSec() * 1000L;
            BlockHeader forged = new BlockHeader(
                honestLast.id(), System.currentTimeMillis() + windowMs + MARGIN_MS,
                honestLast.difficulty(), honestLast.numTransactions(), honestLast.lastBlockHash(),
                honestLast.merkleRoot(), honestLast.nonce(), honestLast.stateRoot(),
                honestLast.vote(), honestLast.supply(), honestLast.uncles());
            out.writeBytes(HeaderCodec.encode(forged));
            byte[] hostileHeaders = out.toByteArray();

            RhizomeNode victim = network.node("victim").params(TestNetwork.PUFFERFISH).start();
            long heightBefore = victim.engine().height();
            SHA256Hash tipBefore = victim.engine().tipHash();

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(source)
                    .claimsHeight(prefixTop + 1)
                    // Properly JSON-shaped, like E2ESupplyCommitmentTest's equivalent peer: a bare
                    // decimal string is malformed and would turn the victim away before it ever
                    // reaches /headers, proving nothing about this gate specifically.
                    .claimsWork(() -> new JSONObject().put("totalWork",
                        BigInteger.TWO.pow(200).toString()).toString())
                    .servesHeaders(() -> hostileHeaders)
                    .start()) {
                victim.service().addPeer(liar.url());
                TestNetwork.await(() -> victim.knownPeers().contains(liar.url()),
                    () -> "peer " + liar.url() + " was never admitted");
                for (int round = 0; round < 4; round++) {
                    victim.syncRound();
                }

                assertEquals(heightBefore, victim.engine().height(),
                    "a future-stamped forged header must not extend the victim's chain");
                assertEquals(tipBefore, victim.engine().tipHash(),
                    "the victim's tip must not move for an unproven header run (protocol rule 3)");
                assertFalse(victim.engine().isDegraded(),
                    "the encounter left the victim degraded, which halts every new-tip write");
            }
        }
    }
}
