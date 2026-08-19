package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.node.RhizomeNode;

/**
 * The double-spend, end to end: one funded account, two branches, two conflicting spends, and a
 * real reorganisation deciding between them.
 *
 * <p>This is the attack the whole system exists to prevent, and it is the one that cannot be
 * proved at component level. In {@code lib-core} a conflicting spend is refused by the nonce rule
 * inside a single engine — true, but it says nothing about what happens when the conflict is
 * resolved by a fork race across a network, where each side legitimately accepted its own spend
 * and one of them has to give it up. The properties that matter are only observable afterwards:
 * the loser's recipient must end with nothing, the winner's with the coins, and the sender must
 * have spent exactly once.
 *
 * <p>Both nodes boot on the same premined genesis, so the account is funded on both branches. That
 * detail is what makes the conflict real rather than theatrical: funded from mining rewards
 * instead, the losing spend would simply be unfunded on the winning branch and would have been
 * refused for the wrong reason.
 */
class E2EDoubleSpendTest {

    @TempDir
    Path tempDir;

    private static final long PREMINE = 1_000_000L;
    private static final long BLOCK_MS = 250;

    private static void addPeerAndAwaitAdmission(RhizomeNode node, String peerUrl)
            throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }

    /**
     * E2E-03 — the same coins are spent to two different recipients on two branches. When the
     * branches meet, exactly one payment survives and the sender's nonce advanced exactly once.
     */
    @Test
    void conflictingSpendsOnTwoBranchesResolveToExactlyOnePayment() throws Exception {
        E2EFixtures.Identity spender = E2EFixtures.Identity.generate();
        Path premine = E2EFixtures.premine(tempDir.resolve("premine.json"),
            TestNetwork.FAST, Map.of(spender, PREMINE));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode winner = network.node("winner")
                .snapshot(premine).mining().blockInterval(BLOCK_MS).start();
            RhizomeNode loser = network.node("loser").snapshot(premine).start();

            assertEquals(winner.engine().blockAt(1).hash(), loser.engine().blockAt(1).hash(),
                "a shared premine must produce a shared genesis, or these are two networks");
            assertEquals(PREMINE, winner.engine().confirmedBalance(spender.address()));

            // The losing branch pays recipientA and buries the payment under three more blocks.
            PublicAddress recipientA = PublicAddress.random();
            Transaction toA = spender.send(recipientA, 250_000L, 0L, 0L, TestNetwork.FAST);
            E2EFixtures.mint(loser, PublicAddress.random(), toA);
            E2EFixtures.mintEmpty(loser, PublicAddress.random(), 3);
            assertEquals(250_000L, loser.engine().confirmedBalance(recipientA));

            // The winning branch spends the SAME coins, at the same nonce, to recipientB.
            PublicAddress recipientB = PublicAddress.random();
            Transaction toB = spender.send(recipientB, 400_000L, 0L, 0L, TestNetwork.FAST);
            assertEquals(ExecutionStatus.SUCCESS, winner.service().submitTransaction(toB));
            TestNetwork.await(() -> winner.engine().confirmedBalance(recipientB) == 400_000L,
                () -> "the winning branch never mined its own spend");
            TestNetwork.awaitHeight(winner, 12);

            assertNotEquals(winner.engine().blockAt(2).hash(), loser.engine().blockAt(2).hash(),
                "the branches must genuinely differ");

            // The branches meet. The heavier one wins, and the losing payment must unwind.
            addPeerAndAwaitAdmission(loser, TestNetwork.urlOf(winner));
            TestNetwork.syncUntil(loser,
                () -> loser.engine().blockAt(2).hash().equals(winner.engine().blockAt(2).hash()));

            assertEquals(0L, loser.engine().confirmedBalance(recipientA),
                "the losing branch's recipient must keep nothing");
            assertEquals(400_000L, loser.engine().confirmedBalance(recipientB),
                "the winning branch's recipient keeps the coins");
            assertEquals(PREMINE - 400_000L, loser.engine().confirmedBalance(spender.address()),
                "the sender paid exactly once");
            assertEquals(1L, loser.engine().nextNonce(spender.address()),
                "and consumed exactly one nonce");
            assertNull(loser.engine().transactionHeight(toA.hashContents()),
                "the undone payment must leave no trace in the executed set");
        }
    }

    /**
     * E2E-04 — the mirror property, and the one a chain gets wrong in the other direction: a
     * transaction undone by a reorganisation must become spendable again. If the executed-set
     * entry survived the pop, the sender would be permanently unable to make that payment on the
     * winning branch — censorship by fork race, achievable by any miner willing to lose one.
     */
    @Test
    void aPaymentUndoneByAReorgCanBeMinedAgainOnTheWinningBranchAndPaysOnce() throws Exception {
        E2EFixtures.Identity spender = E2EFixtures.Identity.generate();
        Path premine = E2EFixtures.premine(tempDir.resolve("premine.json"),
            TestNetwork.FAST, Map.of(spender, PREMINE));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode winner = network.node("winner")
                .snapshot(premine).mining().blockInterval(BLOCK_MS).start();
            RhizomeNode loser = network.node("loser").snapshot(premine).start();

            PublicAddress recipient = PublicAddress.random();
            Transaction payment = spender.send(recipient, 300_000L, 0L, 0L, TestNetwork.FAST);

            // Confirmed on the branch that is about to lose.
            E2EFixtures.mint(loser, PublicAddress.random(), payment);
            E2EFixtures.mintEmpty(loser, PublicAddress.random(), 2);
            assertEquals(300_000L, loser.engine().confirmedBalance(recipient));

            TestNetwork.awaitHeight(winner, 10);
            addPeerAndAwaitAdmission(loser, TestNetwork.urlOf(winner));
            TestNetwork.syncUntil(loser,
                () -> loser.engine().blockAt(2).hash().equals(winner.engine().blockAt(2).hash()));

            assertEquals(0L, loser.engine().confirmedBalance(recipient), "the payment is undone");
            assertEquals(0L, loser.engine().nextNonce(spender.address()),
                "and the nonce is free again — otherwise the sender is stuck forever");

            // The very same signed transaction is now submitted to the winning branch.
            assertEquals(ExecutionStatus.SUCCESS, winner.service().submitTransaction(payment));
            TestNetwork.await(() -> winner.engine().confirmedBalance(recipient) == 300_000L,
                () -> "the re-submitted payment was never mined on the winning branch");

            TestNetwork.syncUntil(loser,
                () -> loser.engine().confirmedBalance(recipient) == 300_000L);
            assertEquals(300_000L, loser.engine().confirmedBalance(recipient),
                "paid once on the branch that survived");
            assertEquals(PREMINE - 300_000L, loser.engine().confirmedBalance(spender.address()),
                "and debited once, not twice");
            assertTrue(loser.engine().transactionHeight(payment.hashContents()) != null,
                "the payment is executed again, on the winning branch this time");
        }
    }
}
