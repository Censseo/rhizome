package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;

/**
 * Attacks on the transaction commitment (MERKLE family — see docs/adversarial/spec.md).
 *
 * <p>The subject is CVE-2012-2459, the Merkle malleability defect that threatened Bitcoin 0.8: on a
 * tree that folds an odd level by duplicating its last hash, the body {@code [t0,t1,t2]} and the
 * body {@code [t0,t1,t2,t2]} produce the <em>same</em> root. A commitment that is only the root is
 * therefore not a commitment to the body, and a relay that caches "block hash H is invalid" can be
 * poisoned into rejecting the honest block too.
 *
 * <p>Rhizome's answer is two independent barriers, and this suite pins both — the collision is
 * real, so only the barriers stand between it and a body-swap:
 * {@code numTransactions} inside the hash preimage means the forgery is a different block that must
 * buy its own proof of work, and the executor's in-block content-hash deduplication rejects the
 * duplicate outright. Neither is redundant: the first stops the cheap version (reuse the victim's
 * PoW), the second stops the funded version (mine it yourself).
 */
class MerkleForgeryAttackTest {

    private AdversarialChain chain() {
        return AdversarialChain.testnet().fund("alice", 1_000_000L).build();
    }

    /**
     * MERKLE-01 — the collision the CVE is about exists in this tree too, so nothing below is
     * theoretical. Pinning it here means a future change to the fold (or to the odd-level rule)
     * that removes the collision does not silently make the two barriers untested.
     */
    @Test
    void duplicatingTheLastTransactionYieldsTheIdenticalMerkleRoot() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        Transaction coinbase = Transaction.of(chain.miner(),
            new rhizome.core.transaction.TransactionAmount(chain.params().miningReward(2)));
        Transaction t1 = chain.account("alice").send(victim, 1_000, 0, 0);
        Transaction t2 = chain.account("alice").send(victim, 2_000, 0, 1);

        List<Transaction> honest = List.of(coinbase, t1, t2);
        List<Transaction> forged = List.of(coinbase, t1, t2, t2);

        assertEquals(BlockForge.merkleRootOf(honest), BlockForge.merkleRootOf(forged),
            "CVE-2012-2459: an odd level folded by duplicating its last hash collides with the "
                + "body that carries that duplicate explicitly");
    }

    /**
     * MERKLE-01 — the cheap attack. Because {@code numTransactions} is in the hash preimage, the
     * forged body does not inherit the victim block's hash, so the victim's proof of work does not
     * carry it. That is the barrier the C++ predecessor lacked: its preimage omitted both {@code id}
     * and {@code numTransactions} (WHITEPAPER §4.5).
     */
    @Test
    void theForgedBodyDoesNotInheritTheVictimBlocksHashSoItsProofOfWorkDoesNotCarry() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        Transaction t1 = chain.account("alice").send(victim, 1_000, 0, 0);
        Transaction t2 = chain.account("alice").send(victim, 2_000, 0, 1);

        Block honest = chain.forge().transaction(t1).transaction(t2).seal();

        // The attacker takes that mined block and appends a copy of its last transaction. Header and
        // coinbase are reused verbatim, so the body is the ONLY difference between the two blocks.
        Block forged = chain.forge()
            .coinbase(honest.transactions().get(0))
            .transaction(t1)
            .transaction(t2)
            .duplicateLastTransaction()
            .timestamp(honest.timestamp())
            .sealWithoutPow();

        assertEquals(honest.merkleRoot(), forged.merkleRoot(), "the roots still collide");
        assertNotEquals(honest.hash(), forged.hash(),
            "but numTransactions is committed, so the forgery is a different block");
        assertEquals(ExecutionStatus.SUCCESS, chain.engine().addBlock(honest));
    }

    /**
     * MERKLE-02 — the funded attack: the adversary mines the forgery properly, so the PoW gate has
     * nothing to say. The account-nonce rule fires first (the duplicate replays nonce 1), and the
     * executor's content-hash deduplication stands behind it. Either way the chain does not move.
     */
    @Test
    void aFullyMinedDuplicateTransactionBlockIsStillRefusedAndTheChainDoesNotMove() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        long heightBefore = chain.height();

        Block forged = chain.forge()
            .transaction(chain.account("alice").send(victim, 1_000, 0, 0))
            .transaction(chain.account("alice").send(victim, 2_000, 0, 1))
            .duplicateLastTransaction()
            .seal();

        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE, chain.engine().addBlock(forged),
            "the duplicate replays its sender's nonce, caught before any state is touched");
        assertEquals(heightBefore, chain.height(), "the forgery must not extend the chain");
        assertEquals(0L, chain.balanceOf(victim), "and must move no value");
    }

    /**
     * MERKLE-03 — the commitment is to the <em>order</em>, not just the set. Sorting the body (as
     * the C++ predecessor did, in place, during validation — WHITEPAPER §4.4) would make
     * {@code [t0,t1]} and {@code [t1,t0]} share a root and therefore a block hash, while the
     * nonce rule reads them differently: the same hash accepted by one node and rejected by
     * another, which is a chain split rather than a rejected block.
     */
    @Test
    void reorderingTheBodyChangesTheRootSoOrderIsCommittedByTheProofOfWork() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        Transaction first = chain.account("alice").send(victim, 1_000, 0, 0);
        Transaction second = chain.account("alice").send(victim, 2_000, 0, 1);
        Transaction coinbase = Transaction.of(chain.miner(),
            new rhizome.core.transaction.TransactionAmount(chain.params().miningReward(2)));

        assertNotEquals(
            BlockForge.merkleRootOf(List.of(coinbase, first, second)),
            BlockForge.merkleRootOf(List.of(coinbase, second, first)),
            "transaction order is part of the commitment");

        // And the out-of-order body is refused at the nonce gate rather than reordered for us.
        Block outOfOrder = chain.forge().transaction(second).transaction(first).seal();
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE, chain.engine().addBlock(outOfOrder));
    }
}
