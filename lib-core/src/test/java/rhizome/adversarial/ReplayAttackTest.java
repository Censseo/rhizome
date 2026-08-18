package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * Attacks that try to make one authorisation pay twice (REPLAY family — see docs/adversarial/spec.md).
 *
 * <p>Three replay surfaces exist in an account-model chain, and they fail differently: the same
 * transaction twice on one branch, the same transaction on another network, and the same
 * transaction across a reorganisation. The third is the subtle one — a pop MUST un-execute a
 * transaction (or it is censored forever, unspendable although never confirmed) and MUST NOT leave
 * its ledger effect behind (or re-including it pays twice). Both halves are asserted here, because
 * a store that got one right and the other wrong would still look correct from either side alone.
 */
class ReplayAttackTest {

    private AdversarialChain chain() {
        return AdversarialChain.testnet().fund("alice", 1_000_000L).build();
    }

    /** REPLAY-01 — the same authorisation submitted twice on one branch pays once. */
    @Test
    void resubmittingAConfirmedTransactionIsRefused() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        Transaction authorised = chain.account("alice").send(victim, 10_000, 0, 0);

        assertEquals(ExecutionStatus.SUCCESS,
            chain.engine().addBlock(chain.forge().transaction(authorised).seal()));
        assertEquals(10_000L, chain.balanceOf(victim));

        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE,
            chain.engine().addBlock(chain.forge().transaction(authorised).seal()),
            "the nonce is spent; the executor's content-hash dedup is the second barrier behind it");
        assertEquals(10_000L, chain.balanceOf(victim), "value moved exactly once");
    }

    /**
     * REPLAY-02 — a nonce, once spent, is spent for every transaction: a <em>different</em>
     * transfer reusing it is refused too. Without this an attacker who observed a confirmed
     * transaction could re-authorise a different payment under the same signature envelope.
     */
    @Test
    void aDifferentTransactionReusingASpentNonceIsRefused() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        PublicAddress pocket = PublicAddress.random();

        assertEquals(ExecutionStatus.SUCCESS, chain.engine().addBlock(
            chain.forge().transaction(chain.account("alice").send(victim, 10_000, 0, 0)).seal()));

        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE, chain.engine().addBlock(
            chain.forge().transaction(chain.account("alice").send(pocket, 5_000, 0, 0)).seal()));
        assertEquals(0L, chain.balanceOf(pocket));
    }

    /**
     * REPLAY-03 — cross-network replay. The chain id sits in the signed preimage, so a
     * transaction authorised for one network carries no authority on another. Pandanite's
     * {@code hashContents} had neither chain id nor account nonce (WHITEPAPER §4.6).
     */
    @Test
    void aTransactionSignedForAnotherNetworkCarriesNoAuthorityHere() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        int foreignChainId = chain.params().chainId() + 1;

        Transaction foreign = Transaction.of(chain.account("alice").address(), victim,
            new TransactionAmount(10_000L), chain.account("alice").publicKey(),
            new TransactionAmount(0L), chain.now(), foreignChainId, 0L);
        foreign.sign(chain.account("alice").privateKey());

        assertEquals(ExecutionStatus.INVALID_CHAIN_ID,
            chain.engine().addBlock(chain.forge().transaction(foreign).seal()));
        assertEquals(0L, chain.balanceOf(victim));
    }

    /**
     * REPLAY-04 — the reorganisation case, and the reason this suite exists. A popped transaction
     * must be un-executed <em>exactly</em>: still spendable (or the attacker censors it forever by
     * getting it mined into a branch they then orphan), and its ledger effect gone (or re-including
     * it on the winning branch pays the recipient twice).
     */
    @Test
    void aTransactionUndoneByAReorgIsSpendableAgainAndPaysExactlyOnce() {
        AdversarialChain chain = chain();
        PublicAddress victim = PublicAddress.random();
        long aliceBefore = chain.account("alice").balance();
        long supplyBefore = chain.totalSupply();

        Transaction authorised = chain.account("alice").send(victim, 10_000, 0, 0);
        assertEquals(ExecutionStatus.SUCCESS,
            chain.engine().addBlock(chain.forge().transaction(authorised).seal()));
        assertEquals(10_000L, chain.balanceOf(victim));
        assertEquals(1L, chain.engine().nextNonce(chain.account("alice").address()));

        // The branch carrying it loses a fork race.
        chain.pop();

        assertEquals(0L, chain.balanceOf(victim), "the ledger effect is gone");
        assertEquals(aliceBefore, chain.account("alice").balance(), "the sender is made whole");
        assertEquals(0L, chain.engine().nextNonce(chain.account("alice").address()),
            "and the nonce is available again — otherwise the sender is permanently stuck");
        assertNull(chain.engine().transactionHeight(authorised.hashContents()),
            "the executed-set entry must go with the block, or the transaction is censored forever");
        assertEquals(supplyBefore, chain.totalSupply(), "no issuance survives the pop");

        // The winning branch re-includes the very same signed transaction.
        assertEquals(ExecutionStatus.SUCCESS,
            chain.engine().addBlock(chain.forge().transaction(authorised).seal()));
        assertEquals(10_000L, chain.balanceOf(victim), "paid once, not twice");
        assertEquals(aliceBefore - 10_000L, chain.account("alice").balance());
    }
}
