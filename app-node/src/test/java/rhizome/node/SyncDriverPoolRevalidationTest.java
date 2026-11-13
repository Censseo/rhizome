package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.crypto.Crypto;
import rhizome.crypto.PowAlgorithm;

/**
 * POOL-11 wiring: a sync round that extends the chain applies blocks without ever running
 * {@code submitBlock}, so the pool is purged only if {@code SyncDriver} calls
 * {@code NodeService.onChainSynced}. A transaction the synced chain already confirmed must not
 * keep its pool slot after the round (the unit proof only covers {@code MemPool.revalidate}).
 */
class SyncDriverPoolRevalidationTest {

    @TempDir
    Path tempDir;

    private static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).maxDifficulty(16).build();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void aSyncRoundThatConfirmsAPooledTransactionPurgesItFromThePool() throws Exception {
        var pair = Crypto.generateKeyPairTyped();
        PublicAddress sender = PublicAddress.of(pair.publicKey());
        int portA = freePort();
        NodeConfig configA = NodeConfig.defaults(FAST, tempDir.resolve("a").toString(), portA)
            .withMiner(sender).withBlockIntervalMs(50).withAllowPrivatePeers(true);
        try (RhizomeNode nodeA = new RhizomeNode(configA)) {
            nodeA.start();
            awaitHeight(nodeA, 6, 25_000);

            NodeConfig configB = NodeConfig.defaults(FAST, tempDir.resolve("b").toString(), freePort())
                .withPeers(List.of("http://localhost:" + portA)).withAllowPrivatePeers(true);
            try (RhizomeNode nodeB = new RhizomeNode(configB)) {
                nodeB.start();
                nodeB.syncRound(); // B now sees the sender funded

                Transaction tx = TransactionImpl.builder()
                    .from(sender).to(PublicAddress.random())
                    .amount(new TransactionAmount(1)).fee(new TransactionAmount(0))
                    .chainId(FAST.chainId()).nonce(nodeB.engine().nextNonce(sender))
                    .signingKey(pair.publicKey()).build();
                tx.sign(pair.privateKey());
                assertEquals(ExecutionStatus.SUCCESS, nodeB.service().submitTransaction(tx));
                assertEquals(1, nodeB.service().mempoolSize());

                // A (not B) confirms it; B learns of the confirmation only through sync.
                assertEquals(ExecutionStatus.SUCCESS, nodeA.service().submitTransaction(tx));
                long deadline = System.currentTimeMillis() + 25_000;
                while (nodeA.engine().nextNonce(sender) <= tx.nonce() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertTrue(nodeA.engine().nextNonce(sender) > tx.nonce(), "A mined the transaction");

                nodeB.syncRound();

                assertTrue(nodeB.engine().nextNonce(sender) > tx.nonce(), "B's chain now confirms it");
                assertEquals(0, nodeB.service().mempoolSize(),
                    "the sync round must purge the transaction its new chain already confirmed");
            }
        }
    }

    private static void awaitHeight(RhizomeNode node, long target, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (node.engine().height() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
