package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.node.RhizomeNode;

/**
 * End-to-end proofs that the two independent per-height dispatches touched by the supply-driven
 * logarithmic emission curve — {@code NetworkParameters.emissionCurveActiveAt} (which reward a
 * coinbase must pay) and {@code NetworkParameters.consensusV2} (whether the consensus fee floor is
 * enforced at all) — never interfere with each other, whichever order their activation heights are
 * scheduled in, and even when both flip on the very same real block. {@code Executor.runBlock}
 * computes {@code expectedReward} and {@code consensusV2} up front from the SAME block height but
 * otherwise treats them as unrelated: the fee floor is checked per-transaction inside pass 1's loop,
 * the coinbase-reward comparison only after that loop completes. That ordering means an under-floor
 * fee is reported before a wrong reward is ever compared when a block carries both defects — a
 * concrete, code-verified prediction this suite locks in as an assertion, not just an incidental
 * observation.
 */
class E2ECurveConsensusV2Test {

    @TempDir
    Path tempDir;

    private static final long PREMINE = 100_000L; // well under supplyTarget: leaves reward headroom
    private static final long MIN_FEE = 10L;

    /**
     * As the sibling curve E2E suites' own {@code mineWithForgedCoinbase}, generalized to accept an
     * explicit coinbase amount (honest or deliberately wrong) alongside any extra transactions —
     * what probing the fee-floor/reward-gate interaction needs: one real, PoW-valid block per
     * combination of "coinbase right/wrong" x "included transaction's fee at/under the floor".
     */
    private static BlockImpl mineBlock(RhizomeNode node, PublicAddress miner, long coinbaseReward,
            Transaction... transactions) {
        NetworkParameters params = node.engine().params();
        long height = node.engine().height() + 1;
        BlockImpl block = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(node.engine().nextBlockTimestamp(System.currentTimeMillis()))
            .difficulty(node.engine().difficulty())
            .lastBlockHash(node.engine().tipHash())
            .supply(SupplyStamp.next(node.engine(), height, node.engine().difficulty()))
            .build();
        block.addTransaction(Transaction.of(miner, new TransactionAmount(coinbaseReward)));
        for (Transaction t : transactions) {
            block.addTransaction(t);
        }
        MerkleTree tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        node.engine().stampStateRoot(block);
        block.nonce(Miner.mineNonce(block.hash(), block.difficulty(),
            params.powAlgorithm(), params.powCostsAt(height)));
        return block;
    }

    /**
     * E2E-78 — {@code consensusV2Height} (3) scheduled strictly before {@code emissionCurveHeight}
     * (6). At height 3 the fee floor alone is enforced (the curve is still off, so the honest reward
     * stays the plain geometric one); at height 6 the curve alone newly activates while the floor,
     * already active since height 3, keeps applying. At that second boundary a real block is probed
     * three ways: an honest (curve) coinbase paired with an under-floor fee (fee gate alone must
     * refuse it), an honest (at-floor) fee paired with the now-stale geometric coinbase (reward gate
     * alone must refuse it), and both honest at once (accepted — the positive control proving
     * neither gate masks the other once both are live).
     */
    @Test
    void consensusV2ActivatesBeforeTheCurveAndEachGateStillFlipsExactlyAtItsOwnHeight() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE.toBuilder()
                .emissionCurveHeight(6)
                .consensusV2Height(3)
                .minFee(MIN_FEE)
                .build();
            E2EFixtures.Identity spender = E2EFixtures.Identity.generate();
            Path premine = E2EFixtures.premine(tempDir.resolve("premine-a.json"), params, Map.of(spender, PREMINE));
            RhizomeNode node = network.node("victim").params(params).snapshot(premine).start();
            PublicAddress recipient = PublicAddress.random();
            long nonce = 0;

            // Height 2: neither gate active yet. A genuinely zero-value, zero-fee transfer is
            // accepted under the legacy rule, establishing the pre-activation baseline both
            // boundaries below are measured against.
            assertFalse(params.consensusV2(2));
            assertFalse(params.emissionCurveActiveAt(2));
            Transaction baseline = spender.send(recipient, 0L, 0L, nonce, params);
            Block honest2 = E2EFixtures.build(node, PublicAddress.random(), baseline);
            assertEquals(ExecutionStatus.SUCCESS, node.service().submitBlock(honest2),
                "with neither gate active a zero-fee, zero-value transfer must be accepted");
            assertEquals(2, node.engine().height());
            nonce++;

            // Height 3: consensusV2Height alone has been reached -- the fee floor is now a
            // consensus rule, but the curve is still off, so the honest reward is unchanged.
            assertTrue(params.consensusV2(3));
            assertFalse(params.emissionCurveActiveAt(3));
            Transaction underFloor3 = spender.send(recipient, 0L, 0L, nonce, params);
            Block poison3 = E2EFixtures.build(node, PublicAddress.random(), underFloor3);
            assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, node.service().submitBlock(poison3),
                "the fee floor must apply from consensusV2Height even though the curve has not activated yet");
            assertEquals(2, node.engine().height());
            assertFalse(node.engine().isDegraded());

            Transaction atFloor3 = spender.send(recipient, 0L, MIN_FEE, nonce, params);
            Block honest3 = E2EFixtures.mint(node, PublicAddress.random(), atFloor3);
            assertEquals(params.miningReward(3), honest3.transactions().get(0).amount().amount(),
                "the reward must still be the plain geometric one at height 3: the curve is not active yet");
            assertEquals(3, node.engine().height());
            nonce++;

            // Heights 4-5: plain, uneventful mining up to the curve's activation height.
            E2EFixtures.mintEmpty(node, PublicAddress.random(), 2);
            assertEquals(5, node.engine().height());

            // Height 6: emissionCurveHeight is reached. consensusV2 has been active since height 3,
            // so BOTH gates now govern this block -- the crux of the scenario.
            assertTrue(params.consensusV2(6));
            assertTrue(params.emissionCurveActiveAt(6));
            long parentSupply = node.engine().headerAt(5).supply();
            long honestReward = params.miningReward(6, parentSupply);
            long staleReward = params.miningReward(6);
            assertNotEquals(honestReward, staleReward,
                "the stale geometric value and the newly-active curve value must genuinely differ, "
                    + "or this probe cannot distinguish the two gates");

            // (a) an honest, curve-correct coinbase must not excuse an under-floor fee.
            Transaction underFloor6 = spender.send(recipient, 0L, 0L, nonce, params);
            Block feeBad = mineBlock(node, PublicAddress.random(), honestReward, underFloor6);
            assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, node.service().submitBlock(feeBad),
                "a correct curve-reward coinbase must not mask an under-floor fee");
            assertEquals(5, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // (b) an honest, at-floor fee must not excuse a stale (pre-curve) reward.
            Transaction atFloor6a = spender.send(recipient, 0L, MIN_FEE, nonce, params);
            Block rewardBad = mineBlock(node, PublicAddress.random(), staleReward, atFloor6a);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, node.service().submitBlock(rewardBad),
                "a correct, at-floor fee must not mask a stale pre-curve coinbase once the curve is active");
            assertEquals(5, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // (c) a block carrying BOTH defects at once -- an under-floor fee AND a stale
            // reward -- must report the fee failure, never the reward one. Executor.runBlock
            // computes expectedReward and consensusV2 once from height, then checks the fee
            // floor per-transaction inside pass 1's loop -- before the coinbase-reward
            // comparison, which only runs after that loop completes -- so the fee-floor
            // rejection wins regardless of the coinbase's own correctness.
            Transaction bothBad6 = spender.send(recipient, 0L, 0L, nonce, params);
            Block doubleBad = mineBlock(node, PublicAddress.random(), staleReward, bothBad6);
            assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, node.service().submitBlock(doubleBad),
                "a block with both an under-floor fee and a stale reward must be refused for the "
                    + "fee, not the reward: pass 1's per-transaction floor check runs before the "
                    + "post-loop coinbase-reward comparison");
            assertEquals(5, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // (d) both honest at once: the positive control -- the two gates cooperate rather than
            // stepping on each other in the same real block.
            Transaction atFloor6b = spender.send(recipient, 0L, MIN_FEE, nonce, params);
            Block honest6 = mineBlock(node, PublicAddress.random(), honestReward, atFloor6b);
            assertEquals(ExecutionStatus.SUCCESS, node.service().submitBlock(honest6),
                "an honest reward paired with an honest fee must be accepted once both gates are active");
            assertEquals(6, node.engine().height());
            assertEquals(honestReward, node.engine().blockAt(6).transactions().get(0).amount().amount());
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-78 — The mirror ordering: {@code emissionCurveHeight} (3) scheduled strictly before
     * {@code consensusV2Height} (6). At height 3 the curve alone activates while the fee floor is
     * still off, so a genuinely zero-fee transfer is still accepted, but the reward gate keeps
     * refusing a stale (pre-curve) coinbase regardless — proving the reward dispatch is not
     * accidentally piggy-backing on the fee gate's activation. At height 6 the floor activates while
     * the curve is already long active, probed the same three ways as the other ordering.
     */
    @Test
    void theCurveActivatesBeforeConsensusV2AndEachGateStillFlipsExactlyAtItsOwnHeight() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE.toBuilder()
                .emissionCurveHeight(3)
                .consensusV2Height(6)
                .minFee(MIN_FEE)
                .build();
            E2EFixtures.Identity spender = E2EFixtures.Identity.generate();
            Path premine = E2EFixtures.premine(tempDir.resolve("premine-b.json"), params, Map.of(spender, PREMINE));
            RhizomeNode node = network.node("victim").params(params).snapshot(premine).start();
            PublicAddress recipient = PublicAddress.random();
            long nonce = 0;

            // Height 2: neither gate active yet, same baseline as the other ordering.
            assertFalse(params.consensusV2(2));
            assertFalse(params.emissionCurveActiveAt(2));
            Transaction baseline = spender.send(recipient, 0L, 0L, nonce, params);
            Block honest2 = E2EFixtures.build(node, PublicAddress.random(), baseline);
            assertEquals(ExecutionStatus.SUCCESS, node.service().submitBlock(honest2),
                "with neither gate active a zero-fee, zero-value transfer must be accepted");
            assertEquals(2, node.engine().height());
            nonce++;

            // Height 3: emissionCurveHeight alone has been reached -- the curve now dictates the
            // reward, but the fee floor is still off (consensusV2Height is 6).
            assertTrue(params.emissionCurveActiveAt(3));
            assertFalse(params.consensusV2(3));
            long parentSupply3 = node.engine().headerAt(2).supply();
            long honestReward3 = params.miningReward(3, parentSupply3);
            long staleReward3 = params.miningReward(3);
            assertNotEquals(honestReward3, staleReward3,
                "the stale geometric value and the newly-active curve value must genuinely differ, "
                    + "or this probe cannot distinguish the two gates");

            // A wrong (pre-curve) coinbase must still be refused even though the fee floor is off
            // and the accompanying transfer is genuinely zero-fee -- the reward gate does not
            // depend on the fee gate being active.
            Transaction zeroFeeA = spender.send(recipient, 0L, 0L, nonce, params);
            Block rewardBad3 = mineBlock(node, PublicAddress.random(), staleReward3, zeroFeeA);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, node.service().submitBlock(rewardBad3),
                "the reward gate must refuse a stale coinbase at the curve's own activation height "
                    + "regardless of the fee floor's state");
            assertEquals(2, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // Positive control: the SAME zero-fee transfer, paired with the honest curve reward,
            // is accepted -- proof the floor is genuinely off here, not merely lenient.
            Transaction zeroFeeB = spender.send(recipient, 0L, 0L, nonce, params);
            Block honest3 = mineBlock(node, PublicAddress.random(), honestReward3, zeroFeeB);
            assertEquals(ExecutionStatus.SUCCESS, node.service().submitBlock(honest3),
                "a genuinely zero-fee transfer must still be accepted while the floor is inactive, "
                    + "even though the curve itself is already active");
            assertEquals(3, node.engine().height());
            nonce++;

            // Heights 4-5: plain mining up to consensusV2's activation height, curve already active.
            E2EFixtures.mintEmpty(node, PublicAddress.random(), 2);
            assertEquals(5, node.engine().height());

            // Height 6: consensusV2Height is reached. The curve has been active since height 3, so
            // BOTH gates now govern this block.
            assertTrue(params.consensusV2(6));
            assertTrue(params.emissionCurveActiveAt(6));
            long parentSupply6 = node.engine().headerAt(5).supply();
            long honestReward6 = params.miningReward(6, parentSupply6);
            long staleReward6 = params.miningReward(6);
            assertNotEquals(honestReward6, staleReward6,
                "the stale geometric value and the curve value must genuinely differ at height 6 too");

            // (a) an honest, curve-correct coinbase must not excuse an under-floor fee.
            Transaction underFloor6 = spender.send(recipient, 0L, 0L, nonce, params);
            Block feeBad = mineBlock(node, PublicAddress.random(), honestReward6, underFloor6);
            assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, node.service().submitBlock(feeBad),
                "a correct curve-reward coinbase must not mask an under-floor fee once the floor activates");
            assertEquals(5, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // (b) an honest, at-floor fee must not excuse a stale (pre-curve) reward.
            Transaction atFloor6a = spender.send(recipient, 0L, MIN_FEE, nonce, params);
            Block rewardBad6 = mineBlock(node, PublicAddress.random(), staleReward6, atFloor6a);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, node.service().submitBlock(rewardBad6),
                "a correct, at-floor fee must not mask a stale pre-curve coinbase");
            assertEquals(5, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // (c) a block carrying BOTH defects at once, same reasoning as the other ordering: the
            // fee failure must be reported, never the reward one.
            Transaction bothBad6 = spender.send(recipient, 0L, 0L, nonce, params);
            Block doubleBad = mineBlock(node, PublicAddress.random(), staleReward6, bothBad6);
            assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, node.service().submitBlock(doubleBad),
                "a block with both an under-floor fee and a stale reward must be refused for the "
                    + "fee, not the reward, regardless of which gate activated first");
            assertEquals(5, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // (d) both honest at once: the positive control.
            Transaction atFloor6b = spender.send(recipient, 0L, MIN_FEE, nonce, params);
            Block honest6 = mineBlock(node, PublicAddress.random(), honestReward6, atFloor6b);
            assertEquals(ExecutionStatus.SUCCESS, node.service().submitBlock(honest6),
                "an honest reward paired with an honest fee must be accepted once both gates are active");
            assertEquals(6, node.engine().height());
            assertEquals(honestReward6, node.engine().blockAt(6).transactions().get(0).amount().amount());
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-78 — {@code consensusV2Height} and {@code emissionCurveHeight} scheduled at the EXACT
     * same height (4): the coincidence case the plan specifically calls out. Below height 4 neither
     * gate is active; at height 4 both flip in the very same real block. Probed the same three ways
     * as the staggered orderings — under-floor fee with an honest curve reward, an honest fee with a
     * stale reward, and both honest at once — plus a follow-up block one height later proving the
     * simultaneous activation is not a one-block fluke: both gates keep applying afterward.
     */
    @Test
    void bothGatesActivateInTheExactSameBlockAndNeitherMasksTheOther() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            long activation = 4L;
            NetworkParameters params = TestNetwork.CURVE_ACTIVE.toBuilder()
                .emissionCurveHeight(activation)
                .consensusV2Height(activation)
                .minFee(MIN_FEE)
                .build();
            E2EFixtures.Identity spender = E2EFixtures.Identity.generate();
            Path premine = E2EFixtures.premine(tempDir.resolve("premine-c.json"), params, Map.of(spender, PREMINE));
            RhizomeNode node = network.node("victim").params(params).snapshot(premine).start();
            PublicAddress recipient = PublicAddress.random();
            long nonce = 0;

            // Heights 2-3: neither gate active. A zero-fee, zero-value transfer at height 2 is
            // accepted under the legacy rule; height 3 is plain mining up to the shared boundary.
            assertFalse(params.consensusV2(2));
            assertFalse(params.emissionCurveActiveAt(2));
            Transaction baseline = spender.send(recipient, 0L, 0L, nonce, params);
            Block honest2 = E2EFixtures.build(node, PublicAddress.random(), baseline);
            assertEquals(ExecutionStatus.SUCCESS, node.service().submitBlock(honest2));
            assertEquals(2, node.engine().height());
            nonce++;
            assertFalse(params.consensusV2(3));
            assertFalse(params.emissionCurveActiveAt(3));
            E2EFixtures.mintEmpty(node, PublicAddress.random(), 1);
            assertEquals(3, node.engine().height());

            // Height 4: BOTH consensusV2Height and emissionCurveHeight are reached in the same block.
            assertTrue(params.consensusV2(activation));
            assertTrue(params.emissionCurveActiveAt(activation));
            long parentSupply4 = node.engine().headerAt(3).supply();
            long honestReward4 = params.miningReward(activation, parentSupply4);
            long staleReward4 = params.miningReward(activation);
            assertNotEquals(honestReward4, staleReward4,
                "the stale geometric value and the newly-active curve value must genuinely differ, "
                    + "or the reward half of this probe proves nothing");

            // (a) an honest, curve-correct coinbase must not excuse an under-floor fee, even though
            // the floor has NEVER applied to any earlier block on this chain.
            Transaction underFloor4 = spender.send(recipient, 0L, 0L, nonce, params);
            Block feeBad = mineBlock(node, PublicAddress.random(), honestReward4, underFloor4);
            assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, node.service().submitBlock(feeBad),
                "the fee floor must already govern the very block at which it first activates");
            assertEquals(3, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // (b) an honest, at-floor fee must not excuse a stale reward, even though the curve has
            // NEVER applied to any earlier block on this chain either.
            Transaction atFloor4a = spender.send(recipient, 0L, MIN_FEE, nonce, params);
            Block rewardBad = mineBlock(node, PublicAddress.random(), staleReward4, atFloor4a);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, node.service().submitBlock(rewardBad),
                "the curve must already govern the very block at which it first activates");
            assertEquals(3, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // (c) a block carrying BOTH defects at once, right at the block where both rules first
            // apply: the fee failure must be reported, never the reward one, even though neither
            // gate has ever governed any earlier block on this chain.
            Transaction bothBad4 = spender.send(recipient, 0L, 0L, nonce, params);
            Block doubleBad = mineBlock(node, PublicAddress.random(), staleReward4, bothBad4);
            assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, node.service().submitBlock(doubleBad),
                "a block with both an under-floor fee and a stale reward must be refused for the "
                    + "fee, not the reward, even at the very block where both gates first activate");
            assertEquals(3, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // (d) both honest at once, at the exact activation block of BOTH rules: the central
            // positive control for this scenario.
            Transaction atFloor4b = spender.send(recipient, 0L, MIN_FEE, nonce, params);
            Block honest4 = mineBlock(node, PublicAddress.random(), honestReward4, atFloor4b);
            assertEquals(ExecutionStatus.SUCCESS, node.service().submitBlock(honest4),
                "a block satisfying both simultaneously-activating rules at once must be accepted");
            assertEquals(4, node.engine().height());
            assertEquals(honestReward4, node.engine().blockAt(4).transactions().get(0).amount().amount());
            nonce++;
            assertFalse(node.engine().isDegraded());

            // Height 5: not a one-block fluke -- both gates keep applying past the coincidence block.
            long parentSupply5 = node.engine().headerAt(4).supply();
            long honestReward5 = params.miningReward(5, parentSupply5);
            Transaction underFloor5 = spender.send(recipient, 0L, 0L, nonce, params);
            Block feeBad5 = mineBlock(node, PublicAddress.random(), honestReward5, underFloor5);
            assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, node.service().submitBlock(feeBad5),
                "the fee floor must keep applying past the coincidence block, not just at it");
            assertEquals(4, node.engine().height());
            assertFalse(node.engine().isDegraded());

            Transaction atFloor5 = spender.send(recipient, 0L, MIN_FEE, nonce, params);
            Block honest5 = E2EFixtures.mint(node, PublicAddress.random(), atFloor5);
            assertEquals(honestReward5, honest5.transactions().get(0).amount().amount());
            assertEquals(5, node.engine().height());
            assertFalse(node.engine().isDegraded());
        }
    }
}
