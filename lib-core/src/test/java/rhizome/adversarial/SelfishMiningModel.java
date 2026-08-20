package rhizome.adversarial;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;

import rhizome.core.block.Block;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.ChainSynchronizer.Result;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.crypto.SHA256Hash;

/**
 * A selfish-mining (Eyal &amp; Sirer 2014, SM1) simulation over two real {@link ChainEngine}s —
 * one holding the network's public view, one the attacker's private view — driven entirely
 * through the production consensus code: {@link BlockForge#seal()} for real proof of work,
 * {@link ChainSynchronizer#syncFrom} for every adoption decision.
 *
 * <p>Exists to answer REORG-11 (see {@code docs/adversarial/spec.md}): does withholding blocks
 * and releasing them selectively earn a miner more than its hash-rate share, on THIS chain's
 * rules specifically — base-only fork-choice ranking, a deterministic tip-hash tie-break instead
 * of network-timing luck, and GHOST uncle rewards that refund half of an orphaned block back to
 * its own miner. Those rules are not the ones the 2014 paper's numbers were computed for, so this
 * class measures them directly rather than assuming the paper's formula transfers.
 *
 * <h2>The strategy (SM1, adapted)</h2>
 *
 * <p>Each round draws one Bernoulli(&alpha;) trial deciding who finds the round's block. The
 * attacker never publishes while it is safely ahead; it publishes its <em>entire</em> private
 * branch the moment the public chain's length would otherwise tie or pass it. There is no partial
 * ("drip") release in SM1 at any lead — Eyal &amp; Sirer's algorithm always reveals everything it
 * is holding when it decides to reveal at all, so this loop only has two publish-worthy states,
 * not the finer-grained ones a hash-rate-split race would need. In the 2014 paper &gamma; is the
 * fraction of honest hash power that ends up mining atop the attacker's block during a race,
 * decided by network propagation timing; on this chain there is no such race to split, because
 * every node resolves a tie identically and immediately from a deterministic tip-hash comparison —
 * &gamma; here is a single fair coin, not a topology-dependent fraction an attacker could improve
 * by better connectivity (see {@code grindingTheTieBreakRequiresAFullProofOfWorkSolvePerAttemptAgainstAFairCoin}
 * in {@link SelfishMiningAttackTest} for the one lever that remains: grinding it, at a cost).
 *
 * <ul>
 *   <li><b>Attacker finds a block.</b> Mined privately, extending the attacker's own view. Never
 *       published on its own.</li>
 *   <li><b>Honest finds a block, attacker had no private lead.</b> Nothing to contest — the
 *       attacker's view was already in sync, so it just adopts the new public block.</li>
 *   <li><b>Honest finds a block, attacker had a private lead.</b> The attacker publishes its whole
 *       private branch. If its lead was 1, this creates an EXACT tie at the new height — resolved,
 *       for real, by {@link ChainSynchronizer}'s own deterministic tip-hash comparison. If its lead
 *       was 2 or more, the published branch is now strictly heavier than the public chain's new
 *       tip, so the sync always adopts it outright; no tie-break is reached.</li>
 * </ul>
 *
 * <p>A tie the attacker's branch loses ({@code NO_CHANGE}) still gossips the attacker's rejected
 * block into the honest engine's orphan pool first — exactly as a real node's
 * {@code NodeService.submitBlock} does for anything it receives, win or lose — so a lost race
 * remains uncle-eligible rather than silently worthless, which is the entire point of measuring
 * GHOST's mitigation (see {@code onlyTheFirstBlockOfAnOrphanedBranchIsEverRefundedAsAnUncle} in
 * {@link SelfishMiningAttackTest}).
 *
 * <h2>Determinism</h2>
 *
 * <p>The nonce is not in a block's hash preimage ({@code ChainEngine} — "the hash preimage does
 * not commit the nonce"), so mined hashes are already deterministic given deterministic header
 * content; what is NOT deterministic by default is {@link AdversarialChain.Builder#build()}'s
 * random miner address and random genesis, both of which land in the coinbase / genesis
 * commitment. This class fixes both: an unfunded, shared genesis via {@link AdversarialChain#on},
 * and a fixed miner address per side via {@link AdversarialChain.Builder#miner}. The whole
 * simulation is then a pure function of {@code (seed, config)} — see the reproducibility proof.
 */
final class SelfishMiningModel {

    /** Where the honest chain's canonical rewards land. Deliberately a plain, fixed address —
     *  never {@link PublicAddress#random()}, which would make every run's hashes non-reproducible. */
    static final PublicAddress HONEST_MINER = PublicAddress.of("00" + "BB".repeat(24));
    static final PublicAddress ATTACKER_MINER = PublicAddress.of("00" + "AA".repeat(24));

    /** {@code HONEST}: the attacker publishes every block the instant it mines it, exactly like an
     *  ordinary miner — no fork can ever exist, which is what makes it the measurement's own
     *  positive control. {@code SELFISH}: the withholding strategy described in the class javadoc. */
    enum Strategy { HONEST, SELFISH }

    record Config(NetworkParameters params, long seed, double alpha, int rounds, boolean citeUncles,
                  Strategy strategy) {
        Config(NetworkParameters params, long seed, double alpha, int rounds, boolean citeUncles) {
            this(params, seed, alpha, rounds, citeUncles, Strategy.SELFISH);
        }
    }

    record Result(
        int rounds,
        long canonicalHeight,
        SHA256Hash canonicalTip,
        int reorgs,
        int races,
        long maxPrivateLead,
        int finalDifficulty,
        int roundsAttackerFound,
        long honestBalance,
        long attackerBalance,
        long totalSupply,
        long uncleRefsOnCanonicalChain,
        long honestUncleRevenue,
        long attackerUncleRevenue) {

        double attackerShare() {
            long total = honestBalance + attackerBalance;
            return total == 0 ? 0.0 : (double) attackerBalance / total;
        }

        /** The hash-rate share the RNG actually drew this run — never exactly {@code alpha}, but
         *  close for a large enough {@code rounds}; used to sanity-check the draw itself. */
        double drawnAlpha() {
            return roundsAttackerFound / (double) rounds;
        }
    }

    private final Config config;
    private final AdversarialChain honest;
    private final AdversarialChain attacker;
    private final Random rng;
    private long simNow;

    private int reorgs;
    private int races;
    private long maxPrivateLead;
    private int roundsAttackerFound;

    private SelfishMiningModel(Config config) {
        this.config = config;
        this.honest = AdversarialChain.on(config.params()).miner(HONEST_MINER).build();
        this.attacker = AdversarialChain.on(config.params()).miner(ATTACKER_MINER).build();
        this.rng = new Random(config.seed());
        this.simNow = honest.now();
    }

    static Result run(Config config) {
        SelfishMiningModel model = new SelfishMiningModel(config);
        model.simulate();
        return model.result();
    }

    private void simulate() {
        for (int round = 0; round < config.rounds(); round++) {
            boolean attackerFound = rng.nextDouble() < config.alpha();
            simNow += config.params().desiredBlockTimeSec() * 1000L;

            if (attackerFound) {
                roundsAttackerFound++;
                mine(attacker, ATTACKER_MINER, false);
                if (config.strategy() == Strategy.HONEST) {
                    // No withholding at all: publish the instant it's mined, exactly like an
                    // ordinary miner. No competing honest block exists this round (honest didn't
                    // mine), so this is always a plain extension, never a race — routed around
                    // publish() specifically so it does not inflate the races/reorgs counters with
                    // ordinary relay that never contested anything.
                    honest.setClock(Math.max(honest.now(), simNow));
                    attacker.setClock(Math.max(attacker.now(), simNow));
                    relayOntoHonest();
                } else {
                    maxPrivateLead = Math.max(maxPrivateLead, attacker.height() - honest.height());
                }
            } else {
                long oldLead = attacker.height() - honest.height();
                // A block that is about to trigger a publish/race (oldLead >= 1) is validated by
                // BOTH engines within the same round, from two different chain-state perspectives —
                // and uncle eligibility ("forks from a recent block on the validating engine's OWN
                // main chain") is exactly the kind of check that can legitimately disagree between
                // those two perspectives mid-race. Citing only on uncontested blocks keeps every
                // citation's eligibility decided once, by whichever engine turns out to be the one
                // that matters, instead of needing to survive being re-litigated by the other.
                mine(honest, HONEST_MINER, oldLead < 1);
                // Each AdversarialChain drives its own clock, bumped only when IT mines. The side
                // that has been sitting idle (privately holding nothing while the other kept
                // mining, or simply not its round for several rounds running) would otherwise fall
                // behind simNow — and a cross-engine sync validates the incoming branch's
                // timestamps against the RECEIVING engine's own "now", so a stale clock there
                // rejects a perfectly legitimate block as future-dated. This is the two-chain form
                // of what ReorgAttackTest#releaseBranchTo does for one, applied right before either
                // engine is about to validate the other's branch.
                honest.setClock(Math.max(honest.now(), simNow));
                attacker.setClock(Math.max(attacker.now(), simNow));
                if (oldLead >= 1) {
                    publish();
                } else {
                    resyncAttackerToHonest();
                }
            }
        }
    }

    /**
     * Mines one block on {@code chain}, stamped on the shared timeline. Only the HONEST side ever
     * cites uncles, even under {@link Config#citeUncles}: uncle eligibility
     * ("must fork from a recent block on the validating engine's OWN main chain" — {@code
     * UncleRegistry}) is checked against whichever engine ends up APPLYING the block, not the one
     * that mined it. The attacker's private branch is mined consecutively against its own view, so
     * an uncle it considers eligible there is not guaranteed to still be eligible once the same
     * block is re-applied to the honest engine during a reorg — a genuine, narrow consensus
     * subtlety (not a fixture bug) that a real selfish miner has no reason to court anyway: citing
     * uncles helps the citer's revenue only once a block is canonical, and an attacker's growing
     * private branch has no orphans of its own to cite before it ever gets there. Honest mining,
     * by contrast, always cites against its own (the eventually-decisive) chain state, which is
     * self-consistent by construction — and is exactly the side whose citations matter for
     * measuring GHOST's mitigation of the attack (an honest block citing the attacker's lost race).
     */
    private void mine(AdversarialChain chain, PublicAddress payTo, boolean mayCiteUncles) {
        chain.setClock(simNow - config.params().desiredBlockTimeSec() * 1000L);
        BlockForge forge = chain.forge().coinbaseTo(payTo);
        if (config.citeUncles() && chain == honest && mayCiteUncles) {
            List<UncleRef> uncles = chain.engine().selectUncles();
            if (!uncles.isEmpty()) {
                forge.uncles(uncles);
            }
        }
        Block block = forge.seal();
        ExecutionStatus status = chain.engine().addBlock(block);
        if (status != ExecutionStatus.SUCCESS) {
            throw new IllegalStateException("simulation minted an invalid block: " + status);
        }
    }

    /** The attacker reveals its entire private branch; resolves who ends up canonical. */
    private void publish() {
        long fork = commonAncestorHeight();
        for (long h = fork + 1; h <= attacker.height(); h++) {
            // Gossip first, exactly as a real node's submitBlock does for everything it receives —
            // so a race the attacker LOSES still leaves its block orphan-pooled (uncle-eligible)
            // rather than silently worthless, which is the property GHOST's mitigation depends on.
            honest.engine().registerOrphan(attacker.engine().blockAt(h));
        }
        races++;
        ChainSynchronizer.Result outcome = new ChainSynchronizer(honest.engine())
            .syncFrom(AdversarialPeer.honest(attacker.engine()));
        if (outcome == ChainSynchronizer.Result.REORGED || outcome == ChainSynchronizer.Result.EXTENDED) {
            reorgs++;
            // The attacker's branch is now canonical; the attacker's own engine is already the
            // source of truth for it, so nothing further to reconcile.
        } else if (outcome == ChainSynchronizer.Result.NO_CHANGE) {
            resyncAttackerToHonest();
        } else {
            throw new IllegalStateException("unexpected sync outcome during a publish: " + outcome);
        }
    }

    /**
     * {@link Strategy#HONEST} only: relays the attacker's just-mined single block onto the honest
     * engine, exactly like {@link #publish}'s adoption path, but without touching the
     * races/reorgs counters — there is nothing contested here, honest never had a competing block
     * this round, so this is an ordinary extension.
     */
    private void relayOntoHonest() {
        ChainSynchronizer.Result outcome = new ChainSynchronizer(honest.engine())
            .syncFrom(AdversarialPeer.honest(attacker.engine()));
        if (outcome != ChainSynchronizer.Result.EXTENDED) {
            throw new IllegalStateException(
                "an uncontested single-block relay under Strategy.HONEST must always extend: " + outcome);
        }
    }

    /** The attacker abandons whatever it was privately holding and adopts the honest tip. */
    private void resyncAttackerToHonest() {
        if (attacker.height() == honest.height()
            && attacker.engine().tipHash().equals(honest.engine().tipHash())) {
            return; // already aligned — the common case when the attacker had no private lead
        }
        ChainSynchronizer.Result outcome = new ChainSynchronizer(attacker.engine())
            .syncFrom(AdversarialPeer.honest(honest.engine()));
        if (outcome != ChainSynchronizer.Result.REORGED && outcome != ChainSynchronizer.Result.EXTENDED) {
            throw new IllegalStateException(
                "the attacker could not resync to the (by construction, canonical) honest chain: "
                    + outcome);
        }
    }

    private long commonAncestorHeight() {
        long h = Math.min(attacker.height(), honest.height());
        while (h > 1 && !attacker.engine().blockAt(h).hash().equals(honest.engine().blockAt(h).hash())) {
            h--;
        }
        return h;
    }

    private Result result() {
        ChainEngine engine = honest.engine();
        long height = engine.height();

        long honestUncleRevenue = 0;
        long attackerUncleRevenue = 0;
        long uncleRefs = 0;
        for (long h = 2; h <= height; h++) {
            Block block = engine.blockAt(h);
            if (block.uncles().isEmpty()) {
                continue;
            }
            int nephewDifficulty = block.difficulty();
            for (UncleRef ref : block.uncles()) {
                uncleRefs++;
                int deficit = nephewDifficulty - ref.difficulty();
                long uncleAmount = config.params().uncleReward(h) >>> Math.min(63, Math.max(0, deficit));
                if (ref.miner().equals(HONEST_MINER)) {
                    honestUncleRevenue += uncleAmount;
                } else if (ref.miner().equals(ATTACKER_MINER)) {
                    attackerUncleRevenue += uncleAmount;
                }
            }
        }

        return new Result(
            config.rounds(),
            height,
            engine.tipHash(),
            reorgs,
            races,
            maxPrivateLead,
            engine.difficulty(),
            roundsAttackerFound,
            honest.balanceOf(HONEST_MINER),
            honest.balanceOf(ATTACKER_MINER),
            honest.totalSupply(),
            uncleRefs,
            honestUncleRevenue,
            attackerUncleRevenue);
    }
}
