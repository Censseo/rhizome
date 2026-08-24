package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * End-to-end proofs for the supply-driven logarithmic emission curve (§ integer log curve),
 * exercising a real running node rather than {@code Executor}/{@code ChainEngine} called directly
 * with a hand-built {@code NetworkParameters} — the unit/integration coverage
 * ({@code EmissionCurveTest}, {@code NetworkParametersTest}, {@code ExecutorTest},
 * {@code HeaderChainTest}, {@code LedgerReversalExactnessTest}, the 327-vector artifact) already
 * does that and stays out of scope here.
 */
class E2EEmissionCurveTest {

    @TempDir
    Path tempDir;

    /**
     * Builds a fully-mined (real PoW) block extending {@code node}'s current tip whose header
     * {@code supply} is the correct curve-aware stamp ({@link SupplyStamp#next}) but whose sole
     * coinbase transaction pays {@code forgedReward} rather than the honest scheduled amount.
     *
     * <p>Deliberately NOT a forged header-{@code supply} field (that gate — {@code checkSupply},
     * cheap header-only arithmetic before merkle/nonces/PoW — is already proven by
     * {@code E2ESupplyCommitmentTest}'s E2E-34/35). This helper forges the opposite half of the
     * same identity: the header's {@code supply} is honest, so {@code checkSupply} passes, and the
     * contradiction is only caught later, during {@code Executor.executeBlock}'s
     * {@code coinbase == miningReward(height, parentSupply)} exactness check
     * ({@code INCORRECT_MINING_FEE}) — a costlier, later gate that only a genuinely-mined block can
     * even reach.
     */
    private static BlockImpl mineWithForgedCoinbase(RhizomeNode node, PublicAddress miner, long forgedReward) {
        NetworkParameters params = node.engine().params();
        long height = node.engine().height() + 1;
        BlockImpl block = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(node.engine().nextBlockTimestamp(System.currentTimeMillis()))
            .difficulty(node.engine().difficulty())
            .lastBlockHash(node.engine().tipHash())
            .supply(SupplyStamp.next(node.engine(), height, node.engine().difficulty()))
            .build();
        block.addTransaction(Transaction.of(miner, new TransactionAmount(forgedReward)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        node.engine().stampStateRoot(block);
        block.nonce(Miner.mineNonce(block.hash(), block.difficulty(),
            params.powAlgorithm(), params.powCostsAt(height)));
        return block;
    }

    /**
     * As {@link #mineWithForgedCoinbase}, but the nonce is a random, deliberately UNMINED value
     * rather than genuine proof of work. {@code ChainEngine.checkSupply} is cheap, header-only,
     * and runs before the block's own PoW is ever checked (WHITEPAPER §3.5's DoS-armor
     * ordering) -- so this candidate still reaches, and exercises, the curve evaluation inside
     * it before being refused at the (later) PoW gate.
     */
    private static BlockImpl unminedBlockAboveTarget(RhizomeNode node, PublicAddress miner, long claimedReward) {
        long height = node.engine().height() + 1;
        BlockImpl block = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(node.engine().nextBlockTimestamp(System.currentTimeMillis()))
            .difficulty(node.engine().difficulty())
            .lastBlockHash(node.engine().tipHash())
            .supply(SupplyStamp.next(node.engine(), height, node.engine().difficulty()))
            .build();
        block.addTransaction(Transaction.of(miner, new TransactionAmount(claimedReward)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        node.engine().stampStateRoot(block);
        block.nonce(SHA256Hash.random()); // deliberately NOT real proof of work
        return block;
    }

    /**
     * A fully-mined (real PoW) block extending {@code node}'s current tip whose coinbase pays the
     * true, honestly-scheduled reward for the real parent supply, but whose header {@code supply}
     * field -- the field THIS block itself declares, not the parent's -- is {@code
     * forgedHeaderSupply} instead of the honest {@link SupplyStamp#next} stamp. The mirror of
     * {@link #mineWithForgedCoinbase}: that helper forges the coinbase and keeps the header supply
     * honest; this one keeps the coinbase honest and forges the header supply itself, isolating
     * exactly which field an attacker controls.
     */
    private static BlockImpl mineWithForgedHeaderSupply(RhizomeNode node, PublicAddress miner,
            long forgedHeaderSupply) {
        NetworkParameters params = node.engine().params();
        long height = node.engine().height() + 1;
        long parentSupply = node.engine().headerAt(node.engine().height()).supply();
        long honestReward = parentSupply == BlockImpl.SUPPLY_ABSENT
            ? params.miningReward(height)
            : params.miningReward(height, parentSupply);
        BlockImpl block = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(node.engine().nextBlockTimestamp(System.currentTimeMillis()))
            .difficulty(node.engine().difficulty())
            .lastBlockHash(node.engine().tipHash())
            .supply(forgedHeaderSupply)
            .build();
        block.addTransaction(Transaction.of(miner, new TransactionAmount(honestReward)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        node.engine().stampStateRoot(block);
        block.nonce(Miner.mineNonce(block.hash(), block.difficulty(),
            params.powAlgorithm(), params.powCostsAt(height)));
        return block;
    }

    /**
     * E2E-61 — Push a block built by the unmodified {@code E2EFixtures.build}/{@code mint} onto a
     * curve-active node, verifying that the mismatch between the curve-sensitive supply stamp
     * ({@code SupplyStamp.next}, which calls {@code Issuance.minted}) and a curve-insensitive
     * coinbase (the height-only {@code params.miningReward(height)} form) is rejected, then that
     * the curve-aware fixture is accepted at the same height. Turns the fix into a permanent
     * regression proof: {@code E2EFixtures.build} now reads the SAME {@code parentSupply} that
     * {@code SupplyStamp.next} does and pays {@code params.miningReward(height, parentSupply)},
     * mirroring {@code BlockAssembler.assemble}'s own dispatch — before this fix, every block this
     * fixture built on a curve-active profile carried a coinbase that contradicted its own
     * committed supply header, and {@code Executor.runBlock} refused it with
     * {@code INCORRECT_MINING_FEE}. This is the blocking infrastructure gap the CURVE E2E test plan
     * (Groupe 0) diagnosed: no honest block could be built on a curve-active profile with the
     * fixture as it stood, so nothing else in the plan was constructible until this was fixed.
     */
    @Test
    void aCurveAwareFixtureBuiltBlockIsAcceptedOnACurveActiveNodeWhileTheLegacyFixtureBuiltBlockIsRejected()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("victim").params(TestNetwork.CURVE_ACTIVE).start();

            E2EFixtures.mintEmpty(node, PublicAddress.random(), 3);
            long heightBefore = node.engine().height();
            assertEquals(4, heightBefore);

            NetworkParameters params = node.engine().params();
            long height = heightBefore + 1;
            long parentSupply = node.engine().headerAt(heightBefore).supply();
            // The pre-fix E2EFixtures.build() behaviour: the height-only, curve-INSENSITIVE
            // geometric coinbase paired with a correct curve-aware supply stamp.
            BlockImpl legacy = mineWithForgedCoinbase(node, PublicAddress.random(), params.miningReward(height));
            assertNotEquals(params.miningReward(height), params.miningReward(height, parentSupply),
                "the geometric and curve-scheduled rewards must genuinely differ for this regression to mean anything");

            ExecutionStatus legacyStatus = node.service().submitBlock(legacy);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, legacyStatus,
                "a curve-insensitive coinbase must be rejected on a curve-active profile, since it "
                    + "contradicts the block's own curve-aware committed supply");
            assertEquals(heightBefore, node.engine().height(),
                "the curve-insensitive block must not extend the chain");
            assertFalse(node.engine().isDegraded(),
                "refusing the curve-insensitive block left the node degraded");

            // Positive control: the fixed fixture, same node, same next height, must be accepted.
            Block honest = E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
            assertEquals(honest.hash(), node.engine().headerAt(node.engine().height()).hash());
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-62 — Mine a block with genuinely valid proof of work under a curve-active profile, keep
     * the header's committed {@code supply} honest (so the cheap, pre-PoW {@code checkSupply} gate
     * agrees with it), but pay the coinbase the reward a NEIGHBOURING curve table position would
     * pay rather than this block's own parent supply — a plausible-looking forgery, not an
     * arbitrary one — then push it at the real {@code /submit} HTTP route. As a positive control,
     * an honest block at the same height is then accepted.
     *
     * <p>Distinct from E2E-61's regression proof: that one forges the OLD pre-curve geometric
     * value (correctly rejected as a byproduct of proving the fixture fix); this one forges a
     * value that is still a real, schedule-derived curve reward — just for the wrong supply
     * position — and reaches the network purely over the real HTTP boundary rather than the
     * in-process {@code submitBlock} call, mirroring E2E-34's emphasis on that boundary
     * specifically.
     */
    @Test
    void aCurveTableNeighbourRewardPushedAtTheSubmitRouteIsRejectedAndAnHonestBlockAtTheSameHeightIsAccepted()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("victim").params(TestNetwork.CURVE_ACTIVE).start();
            E2EFixtures.mintEmpty(node, PublicAddress.random(), 2);
            NetworkParameters params = node.engine().params();

            long heightBefore = node.engine().height();
            long height = heightBefore + 1;
            long parentSupply = node.engine().headerAt(heightBefore).supply();
            long honestReward = params.miningReward(height, parentSupply);
            long stepWidth = params.supplyTarget() / params.emissionTableSteps();
            long forgedReward = params.miningReward(height, parentSupply + stepWidth);
            assertNotEquals(honestReward, forgedReward,
                "the neighbouring table position's reward must genuinely differ from this block's honest one");

            BlockImpl poison = mineWithForgedCoinbase(node, PublicAddress.random(), forgedReward);
            int port = node.apiPort();
            var response = RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(poison));
            assertNotEquals(200, response.status(),
                "a reward from a neighbouring curve table position must be rejected at the real /submit route");
            assertEquals(heightBefore, node.engine().height());
            assertFalse(node.engine().isDegraded());

            Block honest = E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
            assertEquals(honestReward, honest.transactions().get(0).amount().amount());
        }
    }

    /**
     * E2E-63 — Boot a real node from a genesis snapshot whose committed supply is EXACTLY a
     * generated table position {@code S_i = i * stepWidth}, then submit a coinbase exactly one
     * base unit above the true value at that exact parent supply — targeting the {@code floorDiv}
     * interpolation at a segment boundary, where an off-by-one in {@code EmissionCurve.interpolate}
     * would be most likely to surface (a potentially negative numerator, or the zero-width last
     * segment when {@code supplyTarget} does not divide evenly by the step count).
     *
     * <p>The boundary cannot be reached by mining from an empty genesis: per-block rewards are
     * consensus-chosen, not steerable, and honest mining's granularity steps over every table
     * position (a 27,725-unit flat reward against a 62,500-unit step width lands the chain at
     * 83,175 — a segment interior, never the boundary). The premined snapshot — the same fixture
     * E2E-64 uses to position supply above the target — commits the exact boundary instead, and
     * the forged block still travels the node's real submit path.
     *
     * <p>Verified in the code that this SHOULD hold: at {@code point == sLo} the interpolation
     * numerator is exactly zero, so {@code floorDiv} returns the exact table value with no
     * off-by-one risk — this test is a confirmation that no such bug exists at the boundary the
     * plan identified as the most plausible place for one, not a probe for a suspected bug.
     */
    @Test
    void aOneBaseUnitOverstatedRewardAtATableStepBoundaryIsRejectedAndTheNodeStaysHealthy() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE;
            long stepWidth = params.supplyTarget() / params.emissionTableSteps();
            E2EFixtures.Identity funded = E2EFixtures.Identity.generate();
            Path snapshot = E2EFixtures.premine(tempDir.resolve("genesis-step-boundary.json"), params,
                Map.of(funded, stepWidth));

            RhizomeNode node = network.node("victim").params(params).snapshot(snapshot).start();
            long heightBefore = node.engine().height();
            long parentSupply = node.engine().headerAt(heightBefore).supply();
            assertEquals(stepWidth, parentSupply,
                "genesis must commit the exact table-step boundary supply");

            long height = heightBefore + 1;
            long honestReward = params.miningReward(height, parentSupply);
            long forgedReward = Math.addExact(honestReward, 1L);

            BlockImpl poison = mineWithForgedCoinbase(node, PublicAddress.random(), forgedReward);
            ExecutionStatus status = node.service().submitBlock(poison);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, status,
                "a reward overstated by exactly one base unit at a table step boundary must still be rejected");
            assertEquals(heightBefore, node.engine().height());
            assertFalse(node.engine().isDegraded());

            E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
        }
    }

    /**
     * E2E-64 — Boot a node from a genesis snapshot already funded at twice {@code supplyTarget},
     * so the very first curve-active block's parent supply sits deep in the curve's negative
     * (mirrored) branch, where the honest reward is clamped to exactly 0
     * ({@code Math.max(0L, emissionCurve.raw(parentSupply))}, the single clamp site in
     * {@code NetworkParameters.miningReward(long, long)}). Claiming any positive amount there must
     * still be rejected — this exercises the clamp site directly through a real node, which no
     * unit test that calls {@code EmissionCurve} in isolation reaches.
     */
    @Test
    void aPositiveRewardClaimedAboveTheSupplyTargetIsRejectedAndTheNodeStaysHealthy() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE;
            long fundedTotal = Math.multiplyExact(params.supplyTarget(), 2L);
            E2EFixtures.Identity funded = E2EFixtures.Identity.generate();
            Path snapshot = E2EFixtures.premine(tempDir.resolve("genesis-above-target.json"), params,
                Map.of(funded, fundedTotal));

            RhizomeNode node = network.node("victim").params(params).snapshot(snapshot).start();
            long heightBefore = node.engine().height();
            long parentSupply = node.engine().headerAt(heightBefore).supply();
            assertEquals(fundedTotal, parentSupply, "genesis must commit the funded snapshot's total exactly");

            long height = heightBefore + 1;
            assertEquals(0L, params.miningReward(height, parentSupply),
                "honest reward this far past supplyTarget must clamp to exactly 0");

            BlockImpl poison = mineWithForgedCoinbase(node, PublicAddress.random(), 1L);
            ExecutionStatus status = node.service().submitBlock(poison);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, status,
                "any positive reward claimed above supplyTarget must be rejected by the clamp's own gate");
            assertEquals(heightBefore, node.engine().height());
            assertFalse(node.engine().isDegraded());

            Block honest = E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
            assertEquals(0L, honest.transactions().get(0).amount().amount());
        }
    }

    /**
     * E2E-65 — Mine a real chain up to the band, immediately below {@code supplyTarget}, where the
     * honest reward genuinely floors to exactly 0 (a real, positive-width stretch the stepped
     * table's interpolation truncates to zero before reaching {@code supplyTarget} itself — see
     * {@code EmissionCurve}'s class javadoc) — the terminal state every curve-active chain
     * eventually reaches. Verifies both directions at once: a rival block claiming one extra unit
     * at that exact parent supply is rejected, and the node then keeps producing through several
     * consecutive genuinely-zero-reward blocks rather than confusing "expected reward is zero" with
     * "no check ran".
     */
    @Test
    void theRealNodeKeepsProducingThroughAGenuineZeroRewardBlockAndRejectsAnyExtraUnitClaimedThere()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("victim").params(TestNetwork.CURVE_ACTIVE).start();
            NetworkParameters params = node.engine().params();

            E2EFixtures.mintToSupply(node, PublicAddress.random(), params.supplyTarget());
            long heightBefore = node.engine().height();
            long height = heightBefore + 1;
            long parentSupply = node.engine().headerAt(heightBefore).supply();
            assertTrue(parentSupply < params.supplyTarget(),
                "honest mining alone must never cross supplyTarget -- the zero-reward plateau stops it first");
            assertEquals(0L, params.miningReward(height, parentSupply),
                "mintToSupply must have stalled exactly because the honest reward here is genuinely zero");

            // The rival, submitted while this parent is still the tip, so both blocks genuinely
            // compete for the same next height rather than one being a stale sibling of the other.
            BlockImpl poison = mineWithForgedCoinbase(node, PublicAddress.random(), 1L);
            ExecutionStatus status = node.service().submitBlock(poison);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, status);
            assertEquals(heightBefore, node.engine().height());
            assertFalse(node.engine().isDegraded());

            for (int i = 0; i < 3; i++) {
                Block block = E2EFixtures.mint(node, PublicAddress.random());
                assertEquals(0L, block.transactions().get(0).amount().amount(),
                    "block " + (i + 1) + " past the plateau boundary must still pay exactly 0");
            }
            assertEquals(heightBefore + 3, node.engine().height());
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-66 — Fill a genuinely-mined (real PoW) block's uncle list with {@link UncleRef}s naming
     * hashes no orphan pool has ever seen, compute the header's {@code supply} field to be
     * self-consistent with that lie via {@link Issuance#minted} (the exact identity
     * {@code checkSupply} itself re-derives), keep the coinbase exact, and push at
     * {@code /submit}. Verified in the code: {@code ChainEngine.checkSupply} reads the block's
     * raw, unvalidated {@code uncles()} list — before the block's own PoW check and well before
     * {@code UncleRegistry.validateUncles} (after PoW) — so the supply commitment can be internally
     * self-consistent with fabricated uncles before anything verifies they are real. The real uncle
     * validation gate must still catch it.
     */
    @Test
    void fabricatedUncleRefsThatMakeTheSupplyCommitmentSelfConsistentAreStillRejectedAtRealUncleValidation()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("victim").params(TestNetwork.CURVE_ACTIVE).start();
            E2EFixtures.mintEmpty(node, PublicAddress.random(), 2);
            NetworkParameters params = node.engine().params();

            long heightBefore = node.engine().height();
            long height = heightBefore + 1;
            int difficulty = node.engine().difficulty();
            long parentSupply = node.engine().headerAt(heightBefore).supply();
            List<UncleRef> fabricatedUncles = List.of(
                new UncleRef(SHA256Hash.random(), difficulty, PublicAddress.random()));
            long selfConsistentSupply = Math.addExact(parentSupply,
                Issuance.minted(params, height, parentSupply, difficulty, fabricatedUncles));

            BlockImpl poison = (BlockImpl) BlockImpl.builder()
                .id((int) height)
                .timestamp(node.engine().nextBlockTimestamp(System.currentTimeMillis()))
                .difficulty(difficulty)
                .lastBlockHash(node.engine().tipHash())
                .uncles(fabricatedUncles)
                .supply(selfConsistentSupply)
                .build();
            poison.addTransaction(Transaction.of(PublicAddress.random(),
                new TransactionAmount(params.miningReward(height, parentSupply))));
            MerkleTree tree = new MerkleTree();
            tree.setItems(poison.transactions());
            poison.merkleRoot(tree.getRootHash());
            node.engine().stampStateRoot(poison);
            poison.nonce(Miner.mineNonce(poison.hash(), poison.difficulty(),
                params.powAlgorithm(), params.powCostsAt(height)));

            ExecutionStatus status = node.service().submitBlock(poison);
            assertEquals(ExecutionStatus.INVALID_UNCLES, status,
                "a supply commitment self-consistent with fabricated uncles must still fail real uncle validation");
            assertEquals(heightBefore, node.engine().height());
            assertFalse(node.engine().isDegraded());

            Block honest = E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-75 — Mine a real node, with a genuine live {@code BlockProducer} (not manually driven),
     * far past {@code supplyTarget} into the sustained region where the honest reward is pinned to
     * exactly 0 for many consecutive real blocks — the terminal state every curve-active chain
     * eventually reaches. Checks sustained liveness specifically: no confusion with the unrelated
     * {@code NO_MINING_FEE}/{@code EXTRA_MINING_FEE} statuses, a correct balance read through the
     * real HTTP {@code /wallet} route for the (never-paid) miner, and that nothing downstream
     * assumes a non-zero coinbase amount.
     */
    @Test
    void aRealNodeMinedDeepPastTheSupplyTargetProducesManyZeroRewardBlocksAndStaysHealthy() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE;
            long fundedTotal = Math.multiplyExact(params.supplyTarget(), 5L);
            E2EFixtures.Identity funded = E2EFixtures.Identity.generate();
            Path snapshot = E2EFixtures.premine(tempDir.resolve("genesis-deep-past-target.json"), params,
                Map.of(funded, fundedTotal));

            PublicAddress miner = PublicAddress.random();
            RhizomeNode node = network.node("victim").params(params).snapshot(snapshot)
                .mining(miner).blockInterval(50).start();

            TestNetwork.awaitHeight(node, 10);
            for (long h = 2; h <= node.engine().height(); h++) {
                assertEquals(0L, node.engine().blockAt(h).transactions().get(0).amount().amount(),
                    "block " + h + " deep past supplyTarget must still pay exactly 0, never confused "
                        + "with NO_MINING_FEE/EXTRA_MINING_FEE");
            }
            assertFalse(node.engine().isDegraded());
            assertEquals(0L, node.service().balance(miner),
                "the miner must genuinely never be paid this deep past supplyTarget");

            int port = node.apiPort();
            var response = RawHttp.get(port, "/wallet?address=" + miner.toHexString(), Map.of());
            assertEquals(200, response.status());
            assertEquals("0", new JSONObject(response.body()).get("balance").toString(),
                "the real /wallet route must also read exactly 0 for the never-paid miner");

            // Sustained, not a one-off: many MORE zero-reward blocks past the first ten.
            long heightBefore = node.engine().height();
            TestNetwork.awaitHeight(node, heightBefore + 5);
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-83 — Mine a long (40-block), internally consistent chain with a real, LIVE mining source
     * node under a coarse "crossable" curve profile (see {@code
     * E2ECurveReorgTest.crossableProfile()}) whose committed supply genuinely crosses
     * {@code supplyTarget} along the way, then sync a real, non-mining victim node to it over real
     * HTTP. Verifies the real sync completes within {@code TestNetwork.PATIENCE_MS} and that the
     * victim keeps answering unrelated real HTTP traffic ({@code /block_count}) throughout the sync
     * and keeps admitting a further real peer afterward -- a bounded-liveness proof, deliberately
     * not a strict/fragile performance benchmark. Class A3.
     *
     * <p>Deviation noted: rather than hand-crafting a headers-only byte stream through
     * {@code HostilePeer} (as the header-sync-forgery scenarios in this package do), this test lets
     * a real peer-to-peer sync run end to end against a genuinely mining source -- the scenario is
     * about bounded liveness of the real sync path over a long, HONEST run, not about a specific
     * hostile framing, so the real thing is the stronger proof here.
     */
    @Test
    void syncingALongRealCurveCrossingChainStaysWithinPatienceAndTheVictimStaysResponsiveThroughout()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.FAST.toBuilder()
                .supplyTarget(1000L)
                .emissionCoefficient(800L)
                .emissionTableSteps(2)
                .emissionCurveHeight(1)
                .build();

            RhizomeNode source = network.node("source").params(params).mining().blockInterval(80).start();
            TestNetwork.awaitHeight(source, 40);
            long targetHeight = source.engine().height();
            SHA256Hash targetHash = source.engine().blockAt(targetHeight).hash();

            boolean crossed = false;
            for (long h = 2; h <= targetHeight; h++) {
                if (source.engine().headerAt(h).supply() >= params.supplyTarget()) {
                    crossed = true;
                    break;
                }
            }
            assertTrue(crossed,
                "this long real-mined run must genuinely cross supplyTarget along the way, or the "
                    + "scenario exercises nothing new");

            RhizomeNode victim = network.node("victim").params(params).start();
            int victimPort = victim.apiPort();

            AtomicBoolean keepPolling = new AtomicBoolean(true);
            AtomicInteger polls = new AtomicInteger();
            AtomicInteger unresponsive = new AtomicInteger();
            Thread poller = new Thread(() -> {
                while (keepPolling.get()) {
                    try {
                        var response = RawHttp.get(victimPort, "/block_count", Map.of());
                        polls.incrementAndGet();
                        if (response.status() != 200) {
                            unresponsive.incrementAndGet();
                        }
                    } catch (RuntimeException ignored) {
                        unresponsive.incrementAndGet();
                    }
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            poller.start();

            long start = System.currentTimeMillis();
            long elapsed;
            try {
                victim.service().addPeer(TestNetwork.urlOf(source));
                TestNetwork.await(() -> victim.knownPeers().contains(TestNetwork.urlOf(source)),
                    () -> "the victim never admitted its real mining source peer");
                TestNetwork.syncUntil(victim,
                    () -> victim.engine().height() >= targetHeight
                        && victim.engine().blockAt(targetHeight).hash().equals(targetHash));
                elapsed = System.currentTimeMillis() - start;
            } finally {
                // On the failure path the non-daemon poller would otherwise spin against the
                // closing port for the rest of the forked JVM's life.
                keepPolling.set(false);
                poller.join(5_000);
            }

            assertTrue(elapsed <= TestNetwork.PATIENCE_MS,
                "syncing a real " + targetHeight + "-height curve-crossing chain took " + elapsed
                    + "ms, past the patience budget of " + TestNetwork.PATIENCE_MS + "ms");
            assertTrue(polls.get() > 0, "the liveness poller never got a chance to run during the sync");
            assertEquals(0, unresponsive.get(),
                "the victim must keep answering /block_count throughout the sync, not just before or "
                    + "after it");

            assertEquals(source.engine().headerAt(targetHeight).supply(),
                victim.engine().headerAt(targetHeight).supply(),
                "the synced victim must agree exactly on the crossed committed supply");
            assertFalse(victim.engine().isDegraded());
            assertEquals(200, RawHttp.get(victimPort, "/block_count", Map.of()).status());

            RhizomeNode third = network.node("third").params(params).start();
            victim.service().addPeer(TestNetwork.urlOf(third));
            TestNetwork.await(() -> victim.knownPeers().contains(TestNetwork.urlOf(third)),
                () -> "the victim stopped admitting new peers after the long sync");
        }
    }

    /**
     * E2E-84 — Flood a curve-active node's real {@code /submit} route with PoW-INVALID blocks,
     * each built on the real current tip whose parent supply is already {@code >= supplyTarget}
     * (the same "above the target" setup as {@code
     * aPositiveRewardClaimedAboveTheSupplyTargetIsRejectedAndTheNodeStaysHealthy} above). Verified
     * in the code: {@code ChainEngine.addBlock}'s {@code checkSupply} -- and, through it, {@code
     * Issuance.minted}'s call into {@code EmissionCurve.raw}'s negative branch -- runs before
     * merkle/nonces/PoW (WHITEPAPER §3.5's DoS-armor ordering), so every one of these invalid-PoW
     * candidates still reaches and exercises that branch before being refused. NodeApi already
     * wires a {@code RateLimiter} ahead of {@code /submit} in general ({@code
     * NodeApiTest#submitPowGateShedsBlocksBeforeTheBodyIsDecoded}); this scenario's value is
     * confirming that defence holds specifically with a genuinely reachable negative-branch curve
     * evaluation wired in, and that the node stays healthy -- still produces and answers -- after
     * the flood. Class A1; a modest flood (a few dozen real requests) is enough, reproducing the
     * dedicated RateLimiter suite's 1000+-request scale is out of scope.
     */
    @Test
    void floodingSubmitWithInvalidPowBlocksThatReachTheNegativeCurveBranchLeavesTheNodeHealthy()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE;
            long fundedTotal = Math.multiplyExact(params.supplyTarget(), 2L);
            E2EFixtures.Identity funded = E2EFixtures.Identity.generate();
            Path snapshot = E2EFixtures.premine(tempDir.resolve("genesis-flood-above-target.json"), params,
                Map.of(funded, fundedTotal));

            RhizomeNode node = network.node("victim").params(params).snapshot(snapshot).start();
            int port = node.apiPort();
            long heightBefore = node.engine().height();
            long parentSupply = node.engine().headerAt(heightBefore).supply();
            assertTrue(parentSupply >= params.supplyTarget(),
                "the flood must be built on a parent supply that genuinely reaches the curve's "
                    + "negative branch, or this proof exercises nothing new");

            int floodSize = 80;
            int accepted = 0;
            for (int i = 0; i < floodSize; i++) {
                BlockImpl candidate = unminedBlockAboveTarget(node, PublicAddress.random(), 1L);
                var response = RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(candidate));
                if (response.status() == 200) {
                    accepted++;
                }
            }
            assertEquals(0, accepted,
                "no invalid-PoW block should ever be accepted, no matter how many times the "
                    + "reachable negative curve branch is evaluated underneath it");
            assertEquals(heightBefore, node.engine().height(), "the flood must not have advanced the chain");
            assertFalse(node.engine().isDegraded(),
                "flooding the reachable negative-branch curve evaluation must not degrade the node");

            // Positive control: the node still does real, honest work after the flood.
            Block honest = E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
            assertEquals(0L, honest.transactions().get(0).amount().amount(),
                "the honest reward this far past supplyTarget is still the clamped zero value");
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-85 — Submit several individually-valid (real PoW), CONCURRENTLY competing blocks for the
     * same next height on a real curve-active node whose parent supply is already
     * {@code >= supplyTarget} (so every one of them shares the same negative/mirrored-branch
     * evaluation) -- some honest (paying the real clamped-to-zero reward) and some forged (claiming
     * a positive amount the clamp forbids) -- fired from real Java threads at the real
     * {@code /submit} route simultaneously. Verifies {@code ChainEngine}'s single lock serializes
     * the curve evaluation and coinbase check correctly under real concurrency: exactly one
     * candidate is ever accepted, it is one of the honest ones (never a forged one, regardless of
     * interleaving), and the node is neither corrupted nor degraded. Class A1, LOW priority --
     * expected to pass ({@code EmissionCurve} is immutable by construction), reconfirming the
     * single-lock invariant rather than probing a suspected bug.
     */
    @Test
    void concurrentSubmitOfCompetingBlocksAboveTheSupplyTargetAdmitsExactlyOne() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE;
            long fundedTotal = Math.multiplyExact(params.supplyTarget(), 2L);
            E2EFixtures.Identity funded = E2EFixtures.Identity.generate();
            Path snapshot = E2EFixtures.premine(tempDir.resolve("genesis-concurrent-above-target.json"), params,
                Map.of(funded, fundedTotal));

            RhizomeNode node = network.node("victim").params(params).snapshot(snapshot).start();
            int port = node.apiPort();
            long heightBefore = node.engine().height();
            long parentSupply = node.engine().headerAt(heightBefore).supply();
            assertTrue(parentSupply >= params.supplyTarget(),
                "every competing block below must genuinely fall into the curve's negative/mirror "
                    + "branch, or this proof exercises nothing new");
            assertEquals(0L, params.miningReward(heightBefore + 1, parentSupply),
                "the honest reward at this parent supply must be the clamp's zero value, so a real "
                    + "honest candidate can exist alongside the forged ones");

            // Built single-threaded, all on the SAME real current tip: the concurrency this test
            // exercises is in the SUBMIT step below, not in mining.
            List<BlockImpl> candidates = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                candidates.add(mineWithForgedCoinbase(node, PublicAddress.random(), 0L)); // honest
            }
            for (int i = 0; i < 4; i++) {
                candidates.add(mineWithForgedCoinbase(node, PublicAddress.random(), 1L)); // forged
            }

            ExecutorService pool = Executors.newFixedThreadPool(candidates.size());
            List<Integer> statuses;
            try {
                List<Future<Integer>> futures = new ArrayList<>();
                for (BlockImpl candidate : candidates) {
                    futures.add(pool.submit(() ->
                        RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(candidate)).status()));
                }
                statuses = new ArrayList<>();
                for (Future<Integer> future : futures) {
                    statuses.add(future.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdown();
            }

            long acceptedCount = statuses.stream().filter(s -> s == 200).count();
            assertEquals(1, acceptedCount,
                "exactly one of the concurrently-submitted competing blocks must be accepted; got "
                    + "statuses " + statuses);
            assertEquals(heightBefore + 1, node.engine().height(),
                "the chain must have advanced by exactly one block, not zero and not more than one");
            assertEquals(0L, node.engine().blockAt(heightBefore + 1).transactions().get(0).amount().amount(),
                "the single accepted block among the concurrent competitors must be one of the "
                    + "honest, clamped-to-zero-reward candidates -- never a forged one");
            assertFalse(node.engine().isDegraded(),
                "concurrent competing submissions must never corrupt or degrade the node");

            // Positive control: the node still does real, honest work after the concurrent flood.
            E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 2, node.engine().height());
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-86 — A client-fabricated block whose OWN declared header {@code supply} field (not the
     * parent's) is a wire-legal extreme value ({@code Long.MAX_VALUE}) or an arbitrary value
     * disconnected from the real parent supply, posted at the real {@code /submit} route. Verified
     * in the code: {@code ChainEngine.checkSupply}/{@code HeaderChain.checkSupply} both read {@code
     * parent.supply()} -- never {@code b.supply()}/{@code header.supply()} of the CANDIDATE block
     * itself -- as the sole argument threaded into {@code Issuance.minted} (and, downstream,
     * {@code EmissionCurve.raw}); the candidate's own declared field is used ONLY in the final
     * equality comparison against the recomputed expected value. So a self-declared value
     * disconnected from reality here can only ever produce an ordinary {@code INVALID_SUPPLY}
     * mismatch -- never feed an attacker-chosen number into the curve's own internal arithmetic, and
     * never crash the server. Class A1, LOW priority -- expected to pass, confirming a data-flow
     * property (which field feeds which computation) rather than a suspected bug.
     */
    @Test
    void aWireLegalExtremeSelfDeclaredSupplyFieldNeverFeedsTheCurveArithmeticAndOnlyMismatchesCleanly()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("victim").params(TestNetwork.CURVE_ACTIVE).start();
            E2EFixtures.mintEmpty(node, PublicAddress.random(), 3);
            long heightBefore = node.engine().height();
            int port = node.apiPort();

            // Long.MAX_VALUE: the single most extreme value the wire's 8-byte signed-long supply
            // field can carry (HeaderCodec places no upper bound on it, only the -1 SUPPLY_ABSENT
            // sentinel lower bound) -- if this field ever reached EmissionCurve.raw as an argument,
            // it is the single most likely input to overflow or misbehave arithmetically.
            BlockImpl extreme = mineWithForgedHeaderSupply(node, PublicAddress.random(), Long.MAX_VALUE);
            ExecutionStatus extremeStatus = node.service().submitBlock(extreme);
            assertEquals(ExecutionStatus.INVALID_SUPPLY, extremeStatus,
                "a self-declared Long.MAX_VALUE supply must be an ordinary INVALID_SUPPLY mismatch, "
                    + "never a crash or an unrelated rejection reason");
            assertEquals(heightBefore, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // The same forgery, freshly rebuilt (the chain never moved, so the same parent/height
            // still apply), pushed at the real /submit HTTP route specifically -- confirming the
            // HTTP boundary itself never turns this into a 500 (a server-side crash), only a clean
            // rejection.
            BlockImpl extremeOverWire = mineWithForgedHeaderSupply(node, PublicAddress.random(), Long.MAX_VALUE);
            var extremeResponse = RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(extremeOverWire));
            assertNotEquals(200, extremeResponse.status(),
                "an extreme self-declared supply must be rejected over the real /submit route");
            assertTrue(extremeResponse.status() < 500,
                "the real HTTP boundary must answer with a clean rejection, never a server error, for "
                    + "an attacker-chosen extreme value that is never fed into the curve's own arithmetic");
            assertEquals(heightBefore, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // An arbitrary value disconnected from the real parent supply -- not the extreme
            // boundary, but an ordinary-looking, plausibly-attempted wrong number.
            long parentSupply = node.engine().headerAt(heightBefore).supply();
            long disconnected = Math.addExact(Math.multiplyExact(parentSupply, 7L), 12345L);
            BlockImpl arbitrary = mineWithForgedHeaderSupply(node, PublicAddress.random(), disconnected);
            ExecutionStatus arbitraryStatus = node.service().submitBlock(arbitrary);
            assertEquals(ExecutionStatus.INVALID_SUPPLY, arbitraryStatus,
                "an arbitrary self-declared supply disconnected from the real parent must also be an "
                    + "ordinary INVALID_SUPPLY mismatch");
            assertEquals(heightBefore, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // Positive control: the node is still alive and does honest work after all three forgeries.
            Block honest = E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
            assertFalse(node.engine().isDegraded());
        }
    }
}
