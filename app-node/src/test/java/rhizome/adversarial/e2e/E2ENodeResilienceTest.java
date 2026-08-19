package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.crypto.SHA256Hash;
import rhizome.node.NodeConfig;
import rhizome.node.RhizomeNode;

/**
 * What a node does when the load and the lifecycle are real: a flood of genuine transactions
 * arriving over HTTP, and a restart on the data it already wrote.
 *
 * <p>Both are conditions every deployed node meets and neither can be reached from a component
 * test. A flood exercises the interaction between the admission gates, the mempool bound and the
 * producer, all on the threads they really run on; a restart exercises the durability of everything
 * the node claimed to have committed. The historical failure mode for the second one is
 * particularly unforgiving — a store that survived every unit test and lost its last block on the
 * first power cut.
 */
class E2ENodeResilienceTest {

    @TempDir
    Path tempDir;

    private static final long PREMINE = 100_000_000L;

    /**
     * E2E-16 — a client floods the node with valid, signed transactions as fast as HTTP allows.
     * The node must keep producing blocks throughout: a mempool that can be filled faster than it
     * drains is a censorship lever, and a producer starved by admission work is a liveness failure.
     */
    @Test
    void aFloodOfSignedTransactionsOverHttpNeverStopsBlockProduction() throws Exception {
        E2EFixtures.Identity spender = E2EFixtures.Identity.generate();
        Path premine = E2EFixtures.premine(tempDir.resolve("premine.json"),
            TestNetwork.FAST, Map.of(spender, PREMINE));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("flooded")
                .snapshot(premine).mining().blockInterval(150).start();
            int port = node.apiPort();
            TestNetwork.awaitHeight(node, 3);

            long heightBeforeFlood = node.engine().height();
            int accepted = 0;
            for (long nonce = 0; nonce < 300; nonce++) {
                Transaction tx = spender.send(PublicAddress.random(), 1_000L, 0L, nonce,
                    TestNetwork.FAST);
                var response = RawHttp.post(port, "/add_transaction_json", Map.of(),
                    tx.toJson().toString().getBytes(StandardCharsets.UTF_8));
                if (response.status() == 200) {
                    accepted++;
                }
            }

            assertTrue(accepted > 0, "the node refused every transaction in the flood");
            assertTrue(node.engine().height() > heightBeforeFlood,
                "the node stopped producing blocks while being flooded");
            assertFalse(node.engine().isDegraded(), "the flood left the node degraded");

            // The flooded transactions must actually reach the chain, not merely be accepted:
            // an admission path that swallows work is its own kind of censorship.
            TestNetwork.await(() -> node.engine().nextNonce(spender.address()) > 0,
                () -> "no flooded transaction was ever mined");
            assertTrue(node.engine().confirmedBalance(spender.address()) < PREMINE,
                "the spender's balance must reflect the payments that were mined");
        }
    }

    /**
     * E2E-17 — the node is closed and started again on the same data directory. Its chain, its
     * balances and its account nonces must all come back exactly, because that is what "committed"
     * has to mean for a node that an operator restarts.
     */
    @Test
    void aRestartOnTheSameDataDirectoryRestoresChainBalancesAndNonces() throws Exception {
        E2EFixtures.Identity spender = E2EFixtures.Identity.generate();
        Path premine = E2EFixtures.premine(tempDir.resolve("premine.json"),
            TestNetwork.FAST, Map.of(spender, PREMINE));
        PublicAddress recipient = PublicAddress.random();

        String dataDir = tempDir.resolve("persistent").toString();
        int port = TestNetwork.freePort();
        NodeConfig config = NodeConfig.defaults(TestNetwork.FAST, dataDir, port)
            .withSnapshot(premine.toString())
            .withAllowPrivatePeers(true)
            .withMiner(PublicAddress.random())
            .withBlockIntervalMs(150);

        long heightBefore;
        SHA256Hash tipBefore;
        long balanceBefore;
        long nonceBefore;

        try (RhizomeNode node = new RhizomeNode(config)) {
            node.start();
            TestNetwork.awaitHeight(node, 4);

            Transaction payment = spender.send(recipient, 12_345L, 0L, 0L, TestNetwork.FAST);
            node.service().submitTransaction(payment);
            TestNetwork.await(() -> node.engine().confirmedBalance(recipient) == 12_345L,
                () -> "the payment was never mined before the restart");

            // Let a couple more blocks bury it, then take the reference snapshot of the truth.
            long buried = node.engine().height() + 2;
            TestNetwork.awaitHeight(node, buried);
            heightBefore = node.engine().height();
            tipBefore = node.engine().blockAt(heightBefore).hash();
            balanceBefore = node.engine().confirmedBalance(spender.address());
            nonceBefore = node.engine().nextNonce(spender.address());
        }

        // Same directory, same port, a brand new process-level object: this is the restart.
        try (RhizomeNode restarted = new RhizomeNode(config)) {
            restarted.start();

            assertTrue(restarted.engine().height() >= heightBefore,
                "the restarted node came back short: " + restarted.engine().height()
                    + " < " + heightBefore);
            assertEquals(tipBefore, restarted.engine().blockAt(heightBefore).hash(),
                "the restarted node disagrees with itself about its own history");
            assertEquals(12_345L, restarted.engine().confirmedBalance(recipient),
                "a confirmed payment did not survive the restart");
            assertEquals(balanceBefore, restarted.engine().confirmedBalance(spender.address()));
            assertEquals(nonceBefore, restarted.engine().nextNonce(spender.address()),
                "account nonces must survive, or every past transaction becomes replayable");
            assertFalse(restarted.engine().isDegraded(),
                "the node came back degraded, which means boot recovery found torn state");
        }
    }
}
