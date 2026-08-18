package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * Attacks that try to create coins (INFL family — see docs/adversarial/spec.md).
 *
 * <p>Every other defence in the protocol assumes the money supply is what the schedule says. The
 * two historical failures were an unchecked {@code uint64} subtraction that let balances underflow
 * into astronomical numbers (the {@code invalid.json} incident, WHITEPAPER §4.1) and a
 * floating-point reward compared by strict equality (§4.2). Both are ledger-level bugs whose only
 * observable symptom is the total supply, so that is what these scenarios assert: not "the block
 * was rejected", but "the sum of every balance moved by exactly the scheduled issuance".
 */
class InflationAttackTest {

    private AdversarialChain chain() {
        return AdversarialChain.testnet().fund("alice", 1_000_000L).build();
    }

    /** INFL-01 — a miner paying itself more than the schedule allows. */
    @Test
    void aCoinbaseAboveTheScheduleIsRefused() {
        AdversarialChain chain = chain();
        long supplyBefore = chain.totalSupply();
        long height = chain.height() + 1;

        assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, chain.engine().addBlock(chain.forge()
            .coinbase(Transaction.of(chain.miner(),
                new TransactionAmount(chain.params().miningReward(height) + 1)))
            .seal()));
        assertEquals(supplyBefore, chain.totalSupply(), "not one extra base unit was minted");
    }

    /** INFL-03 — the same, one base unit <em>below</em>: the reward is an exact value, not a cap. */
    @Test
    void aCoinbaseBelowTheScheduleIsRefusedToo() {
        AdversarialChain chain = chain();
        long height = chain.height() + 1;

        assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, chain.engine().addBlock(chain.forge()
            .coinbase(Transaction.of(chain.miner(),
                new TransactionAmount(chain.params().miningReward(height) - 1)))
            .seal()));
    }

    /** INFL-02 — a second coinbase, the direct way to double the block's issuance. */
    @Test
    void aSecondCoinbaseIsRefused() {
        AdversarialChain chain = chain();
        long supplyBefore = chain.totalSupply();
        long height = chain.height() + 1;
        PublicAddress pocket = PublicAddress.random();

        assertEquals(ExecutionStatus.EXTRA_MINING_FEE, chain.engine().addBlock(chain.forge()
            .transaction(Transaction.of(pocket, new TransactionAmount(chain.params().miningReward(height))))
            .seal()));
        assertEquals(supplyBefore, chain.totalSupply());
        assertEquals(0L, chain.balanceOf(pocket));
    }

    /** INFL-03 — and a block with no coinbase at all is refused rather than treated as a donation. */
    @Test
    void aBlockWithoutACoinbaseIsRefused() {
        AdversarialChain chain = chain();

        assertEquals(ExecutionStatus.NO_MINING_FEE, chain.engine().addBlock(chain.forge()
            .withoutCoinbase()
            .transaction(chain.account("alice").send(PublicAddress.random(), 1_000, 0, 0))
            .seal()));
    }

    /**
     * INFL-07 — the conservation property itself, which is what all of the above protect. A valid
     * block raises the total supply by exactly the scheduled reward: transfers move value between
     * balances and fees move it to the miner, so neither may change the sum. A ledger bug that
     * minted on a fee path, or an arithmetic wrap on a large transfer, shows up here and nowhere
     * else.
     */
    @Test
    void aValidBlockRaisesTheTotalSupplyByExactlyTheScheduledReward() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();

        long supplyBefore = chain.totalSupply();
        long height = chain.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS, chain.engine().addBlock(chain.forge()
            .transaction(chain.account("alice").send(victim, 100_000, 2_500, 0))
            .seal()));

        assertEquals(supplyBefore + chain.params().miningReward(height), chain.totalSupply(),
            "issuance is the reward alone — the fee is an internal transfer to the miner");
        assertEquals(100_000L, chain.balanceOf(victim));
        assertEquals(chain.params().miningReward(height) + 2_500L, chain.balanceOf(chain.miner()));

        // And a pop returns the supply exactly, with no residue.
        chain.pop();
        assertEquals(supplyBefore, chain.totalSupply());
    }
}
