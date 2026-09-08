package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * End-to-end proof that flooding a real {@link TestNetwork#PUFFERFISH} node's {@code /submit}
 * route stays cheap and safe, whichever side of the PoW gate a flood of forged blocks lands on --
 * the Pufferfish2 twin of {@code E2EEmissionCurveTest
 * #floodingSubmitWithInvalidPowBlocksThatReachTheNegativeCurveBranchLeavesTheNodeHealthy}, but the
 * subject here is the memory-hard PoW gate itself rather than the curve evaluation behind it.
 */
class E2EPufferfishFloodTest {

    @TempDir
    Path tempDir;

    /**
     * A structurally plausible next block whose {@code lastBlockHash} does not name {@code node}'s
     * real tip -- refused at {@code ChainEngine.addBlock}'s parent-linkage check, the cheapest
     * post-checkpoint structural gate, strictly before the difficulty, supply, merkle-root and
     * account-nonce checks that follow it, and long before {@code block.verifyNonce} ever runs
     * (WHITEPAPER §3.5's DoS-armor ordering). Every other field is left as cheap and unremarkable
     * as possible: this candidate never needs to survive past the very first structural gate.
     */
    private static BlockImpl blockWithWrongParentLink(RhizomeNode node, PublicAddress miner) {
        long height = node.engine().height() + 1;
        BlockImpl block = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(node.engine().nextBlockTimestamp(System.currentTimeMillis()))
            .difficulty(node.engine().difficulty())
            .lastBlockHash(SHA256Hash.random()) // wrong parent link -- never the real tip
            .build();
        block.addTransaction(Transaction.of(miner, new TransactionAmount(1L)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        block.nonce(SHA256Hash.random());
        return block;
    }

    /**
     * A block that is honest in every field {@code ChainEngine.addBlock} checks BEFORE proof of
     * work -- correct id, parent link, timestamp, difficulty, committed supply, merkle root and
     * account nonces -- so it genuinely reaches, and is refused by, the real Pufferfish2 PoW gate
     * itself rather than any cheaper check ahead of it.
     *
     * <p>A uniformly random nonce has a real, non-negligible chance of accidentally satisfying this
     * profile's low test difficulty by luck alone (the same hazard {@code ChainEngineTest
     * #rejectsBadMerkleAndBadPow} documents and works around) -- so this draws fresh random nonces
     * until the candidate is PROVABLY invalid under a real, independent {@code verifyNonce} check,
     * making the "never accepted" assertion below deterministic rather than merely likely.
     */
    private static BlockImpl blockWithWrongNonce(RhizomeNode node, PublicAddress miner) {
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
            .supply(SupplyStamp.next(node.engine(), height, node.engine().difficulty()))
            .build();
        block.addTransaction(Transaction.of(miner, new TransactionAmount(honestReward)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        node.engine().stampStateRoot(block);
        do {
            block.nonce(SHA256Hash.random()); // deliberately NOT real proof of work
        } while (block.verifyNonce(params.powAlgorithm(), params.powCostsAt(height)));
        return block;
    }

    /**
     * E2E-90 -- Flood a real Pufferfish2 node's {@code /submit} route two ways.
     *
     * <p>The first arm's candidates ({@link #blockWithWrongParentLink}) are cheaply rejectable
     * BEFORE the block's own PoW is ever checked. Verified in the code: {@code ChainEngine.addBlock}
     * checks parent linkage, timestamps, difficulty, committed supply and the merkle root -- all
     * cheap, header/structural comparisons -- strictly before {@code block.verifyNonce} ever runs
     * (WHITEPAPER §3.5's DoS-armor ordering), so none of this arm's memory-hard Pufferfish2 hashing
     * is ever paid for. Since a genuine Pufferfish2 hash at this profile's genesis cost measures
     * ~18 ms single-threaded on this class of hardware ({@link TestNetwork#PUFFERFISH}'s own
     * javadoc), a moderate flood of these would be measurably slow if the memory-hard hash ran even
     * once per candidate -- so this test asserts on wall clock, calibrated against the second arm's
     * own measured per-request cost on THIS run rather than a fixed millisecond constant, so the
     * bound stays meaningful across hardware and shared-box load instead of being either flaky or
     * toothless.
     *
     * <p>The second arm's candidates ({@link #blockWithWrongNonce}) are honest in every CHEAP
     * field -- correct parent link, timestamp, difficulty, supply, merkle root -- and differ only in
     * a provably-unmined nonce, so each one genuinely reaches and is refused by the real PoW gate;
     * kept to a handful (not hundreds), since each one costs one real Pufferfish2 hash.
     *
     * <p>Neither arm ever advances the chain or leaves the node degraded, and -- the positive
     * control -- a genuinely mined block is still accepted afterward and the tip advances normally,
     * proving the flood left the real mining/validation path intact rather than merely idle. Class
     * A1: an ordinary HTTP client submitting arbitrary bytes to a public route, at scale.
     */
    @Test
    void floodingSubmitWithCheapAndPowInvalidPufferfishBlocksLeavesTheNodeHealthyAndStillAbleToMine()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("victim").params(TestNetwork.PUFFERFISH).start();
            int port = node.apiPort();
            long heightBefore = node.engine().height();

            // ---- Arm 1: cheaply rejectable before PoW is ever checked. ----
            int cheapFloodSize = 100;
            int cheapAccepted = 0;
            long cheapStart = System.currentTimeMillis();
            for (int i = 0; i < cheapFloodSize; i++) {
                BlockImpl candidate = blockWithWrongParentLink(node, PublicAddress.random());
                var response = RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(candidate));
                if (response.status() == 200) {
                    cheapAccepted++;
                }
            }
            long cheapElapsed = System.currentTimeMillis() - cheapStart;
            assertEquals(0, cheapAccepted,
                "no wrong-parent-link block should ever be accepted");
            assertEquals(heightBefore, node.engine().height(),
                "the cheap-reject flood must not have advanced the chain");
            assertFalse(node.engine().isDegraded(),
                "flooding the cheap pre-PoW rejection path must not degrade the node");

            // ---- Arm 2: reaches the real PoW gate, refused there on a provably wrong nonce. ----
            int powFloodSize = 5;
            int powAccepted = 0;
            long powStart = System.currentTimeMillis();
            for (int i = 0; i < powFloodSize; i++) {
                BlockImpl candidate = blockWithWrongNonce(node, PublicAddress.random());
                var response = RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(candidate));
                if (response.status() == 200) {
                    powAccepted++;
                }
            }
            long powElapsed = System.currentTimeMillis() - powStart;
            assertEquals(0, powAccepted,
                "no wrong-nonce block should ever be accepted, no matter how many times the real "
                    + "Pufferfish2 PoW gate is genuinely reached and evaluated underneath it");
            assertEquals(heightBefore, node.engine().height(),
                "the wrong-nonce flood must not have advanced the chain");
            assertFalse(node.engine().isDegraded(),
                "flooding the real PoW gate with wrong nonces must not degrade the node");

            // The discriminating assertion: this run's own measured per-request cost of a request
            // that genuinely reaches the real PoW gate, used to calibrate arm 1's budget instead of
            // a fixed millisecond constant. If arm 1's cheap rejections paid for even one real
            // Pufferfish2 hash each, cheapFloodSize of them would cost roughly
            // cheapFloodSize * avgPowMillis -- comfortably longer than a quarter of that budget.
            double avgPowMillis = powElapsed / (double) powFloodSize;
            assertTrue(avgPowMillis >= 1.0,
                "the PoW-gate arm's own measured per-request cost is suspiciously small ("
                    + avgPowMillis + "ms) to calibrate the cheap arm's budget against -- a real "
                    + "Pufferfish2 hash should not be free");
            double budgetMillis = avgPowMillis * cheapFloodSize / 4.0;
            assertTrue(cheapElapsed < budgetMillis,
                "flooding " + cheapFloodSize + " cheaply-rejectable blocks took " + cheapElapsed
                    + "ms, not comfortably under the " + budgetMillis + "ms quarter-budget derived "
                    + "from this run's own " + avgPowMillis + "ms measured real-PoW-gate cost per "
                    + "request -- the cheap rejection path may be paying for PoW it should never reach");

            // Positive control: the node still does real, honest work -- and real PoW -- after both floods.
            Block honest = E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
            assertEquals(honest.hash(), node.engine().headerAt(node.engine().height()).hash());
            assertFalse(node.engine().isDegraded());
        }
    }
}
