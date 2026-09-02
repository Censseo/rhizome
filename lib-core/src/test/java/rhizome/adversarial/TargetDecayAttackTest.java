package rhizome.adversarial;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.EmissionCurve;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyTargetSchedule;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008-decaying-supply-target: the {@code DECAY} family's attack proofs (docs/adversarial/spec.md).
 * The schedule is a pure function of height, so every scenario here is a variation of one con:
 * a miner or a peer pretending the height the chain actually occupies is not the height the
 * schedule reads — minting under a stale target, wishing the boundary into a different height, or
 * carrying a chain grown under different decay constants.
 *
 * <p>Fixture: the shared decay-active profile (short epoch — decay starts at height 10, one epoch
 * every 5 blocks), so the whole lifecycle fits inside heights a suite mines in milliseconds. The
 * FIRST DECAYED height is {@code startHeight + epochBlocks} = 15: that is where a stale-target
 * miner first diverges.
 */
class TargetDecayAttackTest {

    private static NetworkParameters decayParams() {
        return CurveActiveNetwork.decayActiveTestnet();
    }

    /** The first height whose target has decayed once ({@code startHeight + epochBlocks}). */
    private static long firstDecayedHeight(SupplyTargetSchedule s) {
        return s.startHeight() + s.epochBlocks();
    }

    /** The peak-curve raw value — the pre-008 evaluation, against the live target. */
    private static long rawAgainst(NetworkParameters params, long supply, long liveTarget) {
        return EmissionCurve.build(params.supplyTarget(), params.emissionCoefficient(),
            params.emissionTableSteps()).raw(supply, liveTarget);
    }

    /**
     * DECAY-01 — Mine across a decay-epoch boundary hoping the target step is advisory: keep
     * committing supply (and paying yourself the peak-era reward) at the first decayed height,
     * where the height-based schedule has already stepped the target down. The block carries a
     * perfectly valid proof of work and a perfectly wrong arithmetic: rejected on the supply
     * identity before PoW would even run, while the honest block at the same height applies.
     */
    @Test
    void aStaleTargetMinerCrossingTheEpochBoundaryIsRejectedWhileTheHonestBlockApplies() {
        AdversarialChain chain = AdversarialChain.on(decayParams()).build();
        SupplyTargetSchedule schedule = decayParams().supplyTargetSchedule();
        long firstDecayed = firstDecayedHeight(schedule);
        chain.extendBy((int) (firstDecayed - 1 - chain.height()));
        assertEquals(firstDecayed - 1, chain.height(), "sanity: the next block IS the boundary");

        // The stale-target con: the boundary block, minted as if the peak still governed —
        // higher coinbase, correspondingly higher committed supply, valid PoW re-mined after
        // the mutation.
        BlockForge staleForge = chain.forge();
        long parentSupply = chain.parentSupply();
        long staleReward = Math.max(chain.params().minerRevenueFloor(),
            rawAgainst(chain.params(), parentSupply, schedule.peak()));
        Block stale = staleForge
            .coinbase(Transaction.of(chain.miner(), new TransactionAmount(staleReward)))
            .seal();
        ((BlockImpl) stale).supply(parentSupply + staleReward);
        ((BlockImpl) stale).nonce(Miner.mineNonce(stale.hash(), stale.difficulty(),
            chain.params().powAlgorithm()));
        assertEquals(ExecutionStatus.INVALID_SUPPLY, chain.engine().addBlock(stale),
            "a stale-target mint at the first decayed height must die on the supply identity");
        assertEquals(firstDecayed - 1, chain.height(), "the rejected block must not extend");

        // The honest crossing at the same height applies cleanly.
        assertEquals(ExecutionStatus.SUCCESS, chain.extend());
        assertEquals(firstDecayed, chain.height());
    }

    /**
     * DECAY-02 — Manipulate timestamps (within every legal bound) hoping to reach the boundary
     * sooner or drag it later — the decay clock running on wall time instead of chain height.
     * The schedule reads HEIGHT only: two branches taken to the same heights under opposite
     * timestamp policies see the identical target at every height, and a block whose supply was
     * computed for a boundary "shifted" one epoch forward is rejected. No timestamp a miner
     * controls moves the boundary by a single block.
     */
    @Test
    void timestampManipulationCannotMoveTheDecayBoundaryBecauseHeightAloneGoverns() {
        AdversarialChain fast = AdversarialChain.on(decayParams()).build();
        AdversarialChain slow = AdversarialChain.on(decayParams()).build();
        SupplyTargetSchedule schedule = decayParams().supplyTargetSchedule();
        long deep = firstDecayedHeight(schedule) + schedule.epochBlocks(); // two boundaries in

        // "Fast": minimum legal timestamps, back to back. "Slow": one long wait, then a
        // catch-up run. Both reach the same HEIGHTS.
        while (fast.height() < deep) {
            BlockForge forge = fast.forge();
            forge.timestamp(fast.minimalTimestamp());
            assertEquals(ExecutionStatus.SUCCESS, fast.engine().addBlock(forge.seal()));
        }
        slow.setClock(slow.now() + 50_000L);
        while (slow.height() < deep) {
            assertEquals(ExecutionStatus.SUCCESS, slow.extend());
        }
        assertEquals(deep, fast.height());
        assertEquals(deep, slow.height());

        for (long h = 1; h <= deep; h++) {
            assertEquals(slow.params().supplyTargetAt(h), fast.params().supplyTargetAt(h),
                "height " + h + " must pay the same target under both timestamp policies");
            assertEquals(slow.params().supplyTargetAt(h), schedule.targetAt(h));
        }

        // A miner "wishing" the boundary one epoch EARLIER (minting as if the next height were
        // already a full epoch deeper into the decay) commits a supply no node derives.
        BlockForge wishful = fast.forge();
        long parentSupply = fast.parentSupply();
        long wishedTarget = schedule.targetAt(fast.height() + 1 + schedule.epochBlocks());
        long wishedReward = Math.max(fast.params().minerRevenueFloor(),
            rawAgainst(fast.params(), parentSupply, wishedTarget));
        Block wished = wishful
            .coinbase(Transaction.of(fast.miner(), new TransactionAmount(wishedReward)))
            .seal();
        ((BlockImpl) wished).supply(parentSupply + wishedReward);
        ((BlockImpl) wished).nonce(Miner.mineNonce(wished.hash(), wished.difficulty(),
            fast.params().powAlgorithm()));
        assertEquals(ExecutionStatus.INVALID_SUPPLY, fast.engine().addBlock(wished),
            "a boundary shifted by wishful thinking mints against a target no node computes");
        assertEquals(deep, fast.height());
    }

    /**
     * DECAY-03 — A miner running a DIFFERENT decay schedule (here 8/10 where consensus says
     * 9/10): below the decay start the two schedules are indistinguishable (the peak holds on
     * both), and the first decayed-height block minted under the divergent ratio commits a
     * supply no honest node derives — rejected on the supply identity despite chaining to the
     * real tip with valid PoW. Already-stored tips minted under a different schedule are the
     * boot-time refusal's subject, proven in
     * {@code ChainEngineBootConsistencyTest#aTipMintedUnderADifferentDecayScheduleRefusesBootAndNamesTheDecayStartHeight}.
     */
    @Test
    void aPeerOnADifferentDecayScheduleIsRejectedAtTheFirstDecayedHeight() {
        AdversarialChain chain = AdversarialChain.on(decayParams()).build();
        NetworkParameters divergentParams = decayParams().toBuilder().decayNum(8L).build();
        assertTrue(divergentParams.supplyTargetSchedule().isScheduled()
                && divergentParams.decayNum() != chain.params().decayNum(),
            "sanity: the divergent schedule is genuinely different");
        SupplyTargetSchedule schedule = decayParams().supplyTargetSchedule();
        long firstDecayed = firstDecayedHeight(schedule);
        chain.extendBy((int) (firstDecayed - 1 - chain.height()));

        // The divergent miner's boundary block: chained to the real tip, timestamped legally,
        // PoW'd honestly — but its coinbase and committed supply are computed under 8/10.
        BlockForge divergentForge = chain.forge();
        long parentSupply = chain.parentSupply();
        long divergentTarget = divergentParams.supplyTargetAt(firstDecayed);
        assertTrue(divergentTarget != schedule.targetAt(firstDecayed),
            "sanity: the two schedules genuinely disagree at this height");
        long divergentReward = Math.max(chain.params().minerRevenueFloor(),
            rawAgainst(chain.params(), parentSupply, divergentTarget));
        Block divergentBlock = divergentForge
            .coinbase(Transaction.of(chain.miner(), new TransactionAmount(divergentReward)))
            .seal();
        ((BlockImpl) divergentBlock).supply(parentSupply + divergentReward);
        ((BlockImpl) divergentBlock).nonce(Miner.mineNonce(divergentBlock.hash(),
            divergentBlock.difficulty(), chain.params().powAlgorithm()));
        assertEquals(ExecutionStatus.INVALID_SUPPLY, chain.engine().addBlock(divergentBlock),
            "the differently-scheduled block must be rejected at the first decayed height");
        assertEquals(firstDecayed - 1, chain.height(), "the divergent block must not land");

        // The honest boundary block applies: consensus continues on the real schedule.
        assertEquals(ExecutionStatus.SUCCESS, chain.extend());
        assertEquals(firstDecayed, chain.height());
    }
}
