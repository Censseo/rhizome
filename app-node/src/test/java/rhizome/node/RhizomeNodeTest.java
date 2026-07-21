package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.ledger.PublicAddress;

/** Assembles real nodes: one mines and serves its API; a second syncs from it. */
class RhizomeNodeTest {

    @TempDir
    Path tempDir;

    // Instant-mining profile with a low maxDifficulty so mining stays feasible even once the
    // retarget legitimately raises difficulty for the 50 ms cadence (see audit L2).
    private static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).maxDifficulty(16).build();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void awaitHeight(rhizome.node.RhizomeNode node, long target, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (node.engine().height() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void miningNodeProducesAndServesApi() throws Exception {
        int port = freePort();
        NodeConfig config = NodeConfig.defaults(FAST, tempDir.resolve("miner").toString(), port)
            .withMiner(PublicAddress.random()).withBlockIntervalMs(50);

        try (RhizomeNode node = new RhizomeNode(config)) {
            node.start();
            awaitHeight(node, 4, 25_000);
            assertTrue(node.engine().height() >= 4, "node should mine blocks");

            // Served over HTTP.
            var peer = new HttpPeerSource("http://localhost:" + port);
            assertTrue(peer.height() >= 4);
            assertEquals(node.engine().tipHash(), peer.blockHash(peer.height()));
        }
    }

    @Test
    void secondNodeSyncsFromFirst() throws Exception {
        int portA = freePort();
        NodeConfig configA = NodeConfig.defaults(FAST, tempDir.resolve("a").toString(), portA)
            .withMiner(PublicAddress.random()).withBlockIntervalMs(50);

        try (RhizomeNode nodeA = new RhizomeNode(configA)) {
            nodeA.start();
            awaitHeight(nodeA, 5, 25_000);

            int portB = freePort();
            NodeConfig configB = NodeConfig.defaults(FAST, tempDir.resolve("b").toString(), portB)
                .withPeers(java.util.List.of("http://localhost:" + portA));

            try (RhizomeNode nodeB = new RhizomeNode(configB)) {
                nodeB.start();
                assertEquals(1, nodeB.engine().height()); // only genesis

                nodeB.syncRound(); // pull from A

                assertTrue(nodeB.engine().height() >= 5, "B should catch up to A");
                assertEquals(nodeA.engine().blockAt(nodeB.engine().height()).hash(),
                    nodeB.engine().tipHash());
            }
        }
    }

    private static byte[] loadCounter() {
        try (var in = RhizomeNodeTest.class.getResourceAsStream("/counter.wasm")) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void awaitNonce(rhizome.node.RhizomeNode node, rhizome.core.ledger.PublicAddress a,
                                   long target, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (node.engine().nextNonce(a) < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void miningNodeIncludesSubmittedContractTransactions() throws Exception {
        var pair = rhizome.core.common.Crypto.generateKeyPair();
        var key = rhizome.core.crypto.PublicKey.of(pair.getPublic());
        var priv = new rhizome.core.crypto.PrivateKey(
            (org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters) pair.getPrivate());
        var sender = PublicAddress.of(key);

        int port = freePort();
        // The sender mines, so its coinbase rewards fund the gas it spends.
        NodeConfig config = NodeConfig.defaults(FAST, tempDir.resolve("contract").toString(), port)
            .withMiner(sender).withBlockIntervalMs(30);

        try (RhizomeNode node = new RhizomeNode(config)) {
            node.start();
            awaitHeight(node, 40, 25_000); // accumulate enough balance to cover the gas reservation

            int chainId = node.engine().params().chainId();
            long gasLimit = 100_000;

            long deployNonce = node.engine().nextNonce(sender);
            rhizome.core.transaction.Transaction deployTx = rhizome.core.transaction.TransactionImpl.builder()
                .from(sender).to(PublicAddress.empty())
                .amount(new rhizome.core.transaction.TransactionAmount(0))
                .fee(new rhizome.core.transaction.TransactionAmount(0))
                .chainId(chainId).nonce(deployNonce).signingKey(key)
                .kind(rhizome.core.transaction.TransactionKind.DEPLOY)
                .data(loadCounter()).gasLimit(gasLimit).gasPrice(1)
                .build();
            deployTx.sign(priv);
            assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS, node.service().submitTransaction(deployTx));

            // The deploy is mined into a block: the sender's nonce advances.
            awaitNonce(node, sender, deployNonce + 1, 25_000);
            assertTrue(node.engine().nextNonce(sender) >= deployNonce + 1, "deploy tx should be included");

            var contract = rhizome.core.blockchain.Contracts.deriveAddress(sender, deployNonce);
            long callNonce = node.engine().nextNonce(sender);
            rhizome.core.transaction.Transaction callTx = rhizome.core.transaction.TransactionImpl.builder()
                .from(sender).to(contract)
                .amount(new rhizome.core.transaction.TransactionAmount(0))
                .fee(new rhizome.core.transaction.TransactionAmount(0))
                .chainId(chainId).nonce(callNonce).signingKey(key)
                .kind(rhizome.core.transaction.TransactionKind.CALL)
                .data(new byte[0]).gasLimit(gasLimit).gasPrice(1)
                .build();
            callTx.sign(priv);
            assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS, node.service().submitTransaction(callTx));

            awaitNonce(node, sender, callNonce + 1, 25_000);
            assertTrue(node.engine().nextNonce(sender) >= callNonce + 1, "call tx should be included");
        }
    }
}
