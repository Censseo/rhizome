package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.adversarial.SelfishMiningModel.ATTACKER_MINER;
import static rhizome.adversarial.SelfishMiningModel.HONEST_MINER;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.ChainSynchronizer.Result;
import rhizome.core.blockchain.NetworkParameters;

/**
 * Selfish mining (REORG-11), and grinding the tip-hash tie-break (REORG-12) — see
 * docs/adversarial/spec.md.
 *
 * <p>The 2014 Eyal &amp; Sirer result does not transfer to this chain unmodified: Rhizome pays
 * GHOST uncle rewards (half a block reward to an orphaned block's own miner), so "an orphaned
 * block is a total loss" — the paper's central assumption — does not hold here, and the branch a
 * withheld block competes on (base-only work) is deliberately not the same quantity uncle rewards
 * are paid from (uncle-inclusive totals). {@link SelfishMiningModel} measures the actual advantage
 * on this chain's real rules directly, through the real consensus code, rather than assuming the
 * paper's numbers apply.
 */
class SelfishMiningAttackTest {

    private static NetworkParameters ghostOff() {
        // maxUnclesPerBlock(0) is a documented way to disable uncles entirely (NetworkParameters
        // javadoc: "0 disables uncles") — isolating the withholding advantage from GHOST's
        // mitigation of it, which Test 5 measures separately.
        return NetworkParameters.testnet().toBuilder().maxUnclesPerBlock(0).build();
    }

    // ---- REORG-11: deterministic proofs of the two hard caps (no RNG) ----

    /**
     * REORG-11 — the exact cap on GHOST's mitigation: only the first block of an orphaned branch
     * is ever refundable as an uncle, because {@code UncleRegistry} requires an uncle's own parent
     * to still be canonical. The refund does not scale with the depth of the reorg it is
     * compensating for — a three-deep orphaned suffix yields exactly one eligible uncle, never
     * three, and the balance moved is exactly one uncle-and-nephew reward, not the destroyed
     * subsidy back.
     */
    @Test
    void onlyTheFirstBlockOfAnOrphanedBranchIsEverRefundedAsAnUncle() {
        AdversarialChain honest = AdversarialChain.testnet().miner(HONEST_MINER).build();
        AdversarialChain attacker = AdversarialChain.on(honest.params()).miner(ATTACKER_MINER).build();
        assertEquals(honest.engine().blockAt(1).hash(), attacker.engine().blockAt(1).hash(),
            "both chains must share a genesis, or this proves nothing");

        honest.extendBy(3);
        attacker.extendBy(4); // heavier: replaces honest's three blocks on release
        honest.setClock(Math.max(honest.now(), attacker.now()));

        Result result = new ChainSynchronizer(honest.engine())
            .syncFrom(AdversarialPeer.honest(attacker.engine()));
        assertEquals(Result.REORGED, result);
        assertEquals(5, honest.height(), "genesis (height 1) plus the attacker's 4 extended blocks");

        // Captured AFTER the reorg, not before: the reorg REPLACES honest's own 3-block history
        // with the attacker's, so honest's pre-reorg balance is not the baseline the refund is
        // measured against — it is gone, replaced by whatever the now-canonical (attacker) branch
        // paid HONEST_MINER, which is nothing.
        long balanceBefore = honest.balanceOf(HONEST_MINER);
        long supplyBefore = honest.totalSupply();
        assertEquals(0L, balanceBefore, "the attacker's branch never paid HONEST_MINER anything");

        List<UncleRef> eligible = honest.engine().selectUncles();
        assertEquals(1, eligible.size(),
            "only the orphaned block whose OWN parent is still canonical may be cited — a deeper "
                + "orphan's parent is itself an orphan, which is structurally ineligible");

        honest.engine().addBlock(honest.forge().coinbaseTo(HONEST_MINER).uncles(eligible).seal());

        long subsidy = honest.params().miningReward(honest.height());
        long nephewBonus = honest.params().nephewReward(honest.height());
        long uncleAmount = honest.params().uncleReward(honest.height());
        long delta = honest.balanceOf(HONEST_MINER) - balanceBefore;
        assertEquals(subsidy + nephewBonus + uncleAmount, delta,
            "exactly one uncle's worth must be refunded, never one per orphaned block");
        assertEquals(delta, honest.totalSupply() - supplyBefore,
            "nothing else may have moved the ledger");
    }

    /**
     * REORG-11 — the hard cap: a branch withheld past the finality window is refused outright
     * (REORG-02's rule, pinned again here in balance terms), and — past {@code uncleMaxDepth} —
     * not even the GHOST refund applies, so the withheld branch is worth exactly zero to the
     * miner who paid for every block of it.
     */
    @Test
    void aBranchWithheldPastTheFinalityWindowEarnsNothingAtAll() {
        AdversarialChain honest = AdversarialChain.testnet().miner(HONEST_MINER).build();
        AdversarialChain attacker = AdversarialChain.on(honest.params()).miner(ATTACKER_MINER).build();
        int window = honest.params().maxReorgDepth();

        honest.extendBy(window + 1);
        attacker.extendBy(window + 2);
        Block attackerFirst = attacker.engine().blockAt(2);
        honest.setClock(Math.max(honest.now(), attacker.now()));

        var tipBefore = honest.engine().tipHash();
        long heightBefore = honest.height();
        Result result = new ChainSynchronizer(honest.engine())
            .syncFrom(AdversarialPeer.honest(attacker.engine()));
        assertEquals(Result.REORG_TOO_DEEP, result);
        assertEquals(tipBefore, honest.engine().tipHash(), "buried history is not rewritten");
        assertEquals(heightBefore, honest.height());
        assertEquals(0L, honest.balanceOf(ATTACKER_MINER),
            "a branch the victim never adopted must be worth exactly zero to the miner who paid for it");

        // Past uncleMaxDepth even a directly-gossiped first block of that branch is structurally
        // ineligible — not merely unreachable through sync, but refused on its own merits.
        honest.engine().registerOrphan(attackerFirst);
        honest.extendBy(honest.params().uncleMaxDepth() + 1);
        assertFalse(honest.engine().selectUncles().stream()
                .anyMatch(ref -> ref.hash().equals(attackerFirst.hash())),
            "past uncleMaxDepth, the withheld branch's own first block is not even uncle-eligible");
    }

    // ---- REORG-11: the measurement's own positive control ----

    /**
     * REORG-11 — the measurement's positive control, in exact equalities rather than a band. A
     * miner that publishes every block the instant it mines it can never fork the chain, so its
     * share of canonical blocks must equal exactly the share of rounds it won — no statistical
     * band can make this promise; only an exact equality can. If the attribution walk, the RNG, or
     * the revenue accounting were wrong, one of these equalities breaks.
     */
    @Test
    void aMinerThatPublishesEveryBlockEarnsExactlyItsHashRateShare() {
        int rounds = 800;
        var run = SelfishMiningModel.run(new SelfishMiningModel.Config(
            ghostOff(), 1L, 0.40, rounds, false, SelfishMiningModel.Strategy.HONEST));

        assertEquals(rounds + 1, run.canonicalHeight(), "no fork can exist, so every round adds a block");
        assertEquals(0, run.reorgs());
        assertEquals(0, run.races());
        assertEquals(0, run.uncleRefsOnCanonicalChain(), "no orphan can exist to be cited");
        assertEquals(run.drawnAlpha(), run.attackerShare(), 1e-12,
            "with nothing ever withheld, block share and revenue share must be the identical number");
        assertTrue(Math.abs(run.drawnAlpha() - 0.40) < 0.05,
            "the hash-rate model must actually draw at roughly the configured alpha");
        assertEquals(run.totalSupply(), run.honestBalance() + run.attackerBalance(),
            "the whole supply must be attributable to exactly these two addresses");
    }

    /**
     * REORG-11 — reproducibility. The tie-break reads real block hashes, so this is the direct
     * answer to whether that makes the measurement noisy: run the identical configuration twice
     * and require identical outcomes down to the canonical tip hash. A machine-checked guarantee
     * instead of an argument in a comment.
     */
    @Test
    void aRunIsAPureFunctionOfItsSeedSoTheMeasurementIsReproducible() {
        var config = new SelfishMiningModel.Config(NetworkParameters.testnet(), 42L, 0.40, 800, true);
        var first = SelfishMiningModel.run(config);
        var second = SelfishMiningModel.run(config);

        assertEquals(first.canonicalTip(), second.canonicalTip());
        assertEquals(first.canonicalHeight(), second.canonicalHeight());
        assertEquals(first.attackerBalance(), second.attackerBalance());
        assertEquals(first.honestBalance(), second.honestBalance());
        assertEquals(first.reorgs(), second.reorgs());
    }

    // ---- REORG-11: the attack, on both sides of its threshold ----

    private static final long[] SEEDS = {1L, 2L, 3L, 4L, 5L};
    private static final int ROUNDS = 1500;

    /**
     * REORG-11 — the positive control for the attack itself: at 40% of the hash rate, withholding
     * blocks earns a miner a clearly disproportionate share of issuance — every seed beats its own
     * 40% hash-rate share, not just the average. GHOST is disabled here so the measurement isolates
     * the withholding advantage on its own; Test 5 below adds GHOST back in.
     */
    @Test
    void withholdingBlocksEarnsAFortyPercentMinerMoreThanItsHashRateShare() {
        double sum = 0;
        for (long seed : SEEDS) {
            var run = SelfishMiningModel.run(new SelfishMiningModel.Config(ghostOff(), seed, 0.40, ROUNDS, false));
            // Rule 2 — the attack must actually have reached the gate it names: real forks, real
            // races, real orphaned work, not a model that happens to land on the right number.
            assertTrue(run.reorgs() > 50, "seed " + seed + ": too few reorgs to have exercised the attack");
            assertTrue(run.races() > 50, "seed " + seed + ": too few races to have exercised the tie-break");
            assertTrue(run.canonicalHeight() < ROUNDS + 1,
                "seed " + seed + ": orphaned work must have shortened the canonical chain");
            assertEquals(6, run.finalDifficulty(), "seed " + seed + ": difficulty must have stayed at the floor");
            assertTrue(run.attackerShare() > 0.40,
                "seed " + seed + ": a 40% miner that withholds must out-earn its own hash-rate share, got "
                    + run.attackerShare());
            sum += run.attackerShare();
        }
        double mean = sum / SEEDS.length;
        assertTrue(mean > 0.42, "mean attacker share across seeds should clear 40% with real margin, got " + mean);
        assertTrue(mean < 0.60, "sanity ceiling — a share this high suggests a broken model, not a strong attack");
    }

    /**
     * REORG-11 — the other side of the boundary: at 10% of the hash rate, withholding blocks
     * strictly COSTS the attacker revenue rather than earning it. Without this, Test 3 alone could
     * be satisfied by a model that simply always favours the attacker regardless of alpha, which
     * would prove nothing about a hash-rate THRESHOLD existing at all.
     */
    @Test
    void withholdingBlocksCostsATenPercentMinerMoreThanItEarns() {
        double sum = 0;
        for (long seed : SEEDS) {
            var run = SelfishMiningModel.run(new SelfishMiningModel.Config(ghostOff(), seed, 0.10, ROUNDS, false));
            assertTrue(run.reorgs() > 10, "seed " + seed + ": too few reorgs to have exercised the attack");
            assertTrue(run.attackerShare() < 0.10,
                "seed " + seed + ": a 10% miner that withholds must earn LESS than its hash-rate share, got "
                    + run.attackerShare());
            sum += run.attackerShare();
        }
        double mean = sum / SEEDS.length;
        assertTrue(mean < 0.08, "mean attacker share should clear below 10% with real margin, got " + mean);
    }

    /**
     * REORG-11 — GHOST shrinks the selfish miner's edge without closing it. Same five seeds under
     * both profiles: since a fixed seed draws the identical "who found block N" sequence in both
     * runs, the comparison is paired and the dominant source of variance (the coin sequence
     * itself) cancels out of the difference, which is what makes a modest, consistent shrink
     * detectable against only five seeds.
     */
    @Test
    void ghostUncleRewardsShrinkTheSelfishMinersEdgeWithoutClosingIt() {
        NetworkParameters ghostOn = NetworkParameters.testnet(); // uncles enabled at their real defaults
        double sumOff = 0;
        double sumOn = 0;
        long totalUncleRefs = 0;
        long totalHonestUncleRevenue = 0;
        long totalAttackerUncleRevenue = 0;
        for (long seed : SEEDS) {
            var off = SelfishMiningModel.run(new SelfishMiningModel.Config(ghostOff(), seed, 0.40, ROUNDS, false));
            var on = SelfishMiningModel.run(new SelfishMiningModel.Config(ghostOn, seed, 0.40, ROUNDS, true));
            assertTrue(on.attackerShare() < off.attackerShare(),
                "seed " + seed + ": GHOST must shrink this exact seed's edge (paired comparison), off="
                    + off.attackerShare() + " on=" + on.attackerShare());
            sumOff += off.attackerShare();
            sumOn += on.attackerShare();
            totalUncleRefs += on.uncleRefsOnCanonicalChain();
            totalHonestUncleRevenue += on.honestUncleRevenue();
            totalAttackerUncleRevenue += on.attackerUncleRevenue();
        }
        double meanOff = sumOff / SEEDS.length;
        double meanOn = sumOn / SEEDS.length;
        assertTrue(meanOn < meanOff - 0.02, "GHOST must shrink the mean edge by a real margin: off=" + meanOff
            + " on=" + meanOn);
        assertTrue(meanOn > 0.40, "GHOST must not CLOSE the edge — a 40% miner must still come out ahead: "
            + meanOn);
        assertTrue(totalUncleRefs > 0, "GHOST must actually have credited orphaned work at least once");
        assertTrue(totalHonestUncleRevenue > totalAttackerUncleRevenue,
            "most orphaned work is the honest side's own replaced history (the attacker wins most races "
                + "at 40%), so the refund should flow mainly to the honest miner, not the attacker");
    }

    // ---- REORG-12: grinding the deterministic tie-break ----

    /**
     * REORG-12 — grinding the tie-break. The nonce is not in a block's hash preimage ({@code
     * ChainEngine}: "the hash preimage does not commit the nonce"), so an attacker cannot search
     * for a smaller hash by trying different nonces against a fixed header — every nonce for a
     * fixed header yields the identical hash, and at most one of them is a valid proof of work at
     * all. Changing the resulting hash means changing a COMMITTED field (its timestamp, the
     * cheapest one to vary within the MTP/future bounds) and re-mining a FULL proof of work for
     * that new header from scratch: there is no way to preview a candidate's hash without paying
     * for it.
     *
     * <p>This proves the mechanism is real (two committed timestamps for otherwise-identical
     * content yield two different, independently-mined hashes) and empirically pins the economics
     * that make it a losing trade: {@code REORG-06}'s tie-break is close to a fair coin, so beating
     * a fixed honest hash takes a geometric number of attempts with mean 1/0.5 = 2 full PoW
     * solves — for a maximum gain of half a block reward, the uncle refund the loser earns anyway
     * (see {@code onlyTheFirstBlockOfAnOrphanedBranchIsEverRefundedAsAnUncle} above). Two solves
     * for at most half a reward, against the one solve the attacker already holds, is negative
     * expected value.
     */
    @Test
    void grindingTheTieBreakRequiresAFullProofOfWorkSolvePerAttemptAgainstAFairCoin() {
        AdversarialChain chain = AdversarialChain.testnet().miner(HONEST_MINER).build();
        long baseTimestamp = chain.minimalTimestamp();
        // .timestamp(...) before .coinbaseTo(...): the coinbase's own timestamp is read from the
        // block's CURRENT timestamp field at the moment coinbaseTo runs, so this order is what
        // keeps the coinbase content tied to the SAME controlled timestamp instead of whatever
        // chain.forge()'s own clock-driven default happened to stamp it with — otherwise every
        // fresh forge() call would vary the coinbase too, confounding "only the timestamp differs".
        java.util.function.LongFunction<Block> sealAt = ts ->
            chain.forge().timestamp(ts).coinbaseTo(HONEST_MINER).seal();

        // Independent PAIRS, not repeated comparisons against one fixed anchor: a single arbitrary
        // hash can land anywhere in the distribution by chance (near the top or bottom percentile),
        // so "how often do 200 fresh hashes beat THIS ONE" measures that one hash's own percentile
        // rank, not the coin's fairness. Comparing freshly-drawn pairs against EACH OTHER is what
        // actually estimates P(first < second) for two independent draws.
        int trials = 200;
        int firstWins = 0;
        for (int i = 0; i < trials; i++) {
            Block a = sealAt.apply(baseTimestamp + 2L * i);
            Block b = sealAt.apply(baseTimestamp + 2L * i + 1);
            assertTrue(!a.hash().equals(b.hash()),
                "distinct committed timestamps must not collide within " + trials + " trials");
            if (a.hash().toHexString().compareTo(b.hash().toHexString()) < 0) {
                firstWins++;
            }
        }
        double winRate = firstWins / (double) trials;
        assertTrue(winRate > 0.35 && winRate < 0.65,
            "the tie-break coin must be close to fair (50/50) for the negative-EV argument to hold — "
                + "observed P(first < second) = " + winRate + " over " + trials + " independent pairs; a "
                + "biased coin would make grinding profitable outright, a graver defect than this "
                + "scenario measures");

        // The mechanism itself, isolated: RESEALING the same, unchanged forge (no field touched
        // between the two seal() calls) must reproduce the identical hash — proving the variation
        // above is attributable entirely to the timestamp changing, not to any run-to-run noise in
        // sealing itself.
        BlockForge unchanged = chain.forge().timestamp(baseTimestamp).coinbaseTo(HONEST_MINER);
        assertEquals(unchanged.seal().hash(), unchanged.seal().hash(),
            "resealing an unchanged forge must reproduce the identical hash");
    }
}
