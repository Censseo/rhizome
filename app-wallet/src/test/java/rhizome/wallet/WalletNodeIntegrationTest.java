package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.Helpers;
import rhizome.crypto.PowAlgorithm;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionAmount;
import rhizome.node.NodeConfig;
import rhizome.node.RhizomeNode;

/** End-to-end: a funded wallet sends coins through a running, mining node. */
class WalletNodeIntegrationTest {

    @TempDir
    Path tempDir;

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void fundedWalletSendsCoinsThroughRunningNode() throws Exception {
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).build();

        Wallet alice = Wallet.create();
        Wallet bob = Wallet.create();

        // Genesis snapshot funding Alice with 1000 PDN.
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(alice.address(), Helpers.PDN(1000));
        Path snapFile = tempDir.resolve("snapshot.json");
        Files.writeString(snapFile, snapshot.toJson().toString(), StandardCharsets.UTF_8);

        int port = freePort();
        NodeConfig config = NodeConfig.defaults(params, tempDir.resolve("data").toString(), port)
            .withSnapshot(snapFile.toString())
            .withMiner(PublicAddress.random())
            .withBlockIntervalMs(50);

        try (RhizomeNode node = new RhizomeNode(config)) {
            node.start();

            String url = "http://localhost:" + port;
            WalletClient client = new WalletClient(url);

            assertEquals(params.chainId(), client.chainId());
            assertEquals(Helpers.PDN(1000).amount(), client.walletInfo(alice.address()).balance());
            assertEquals(0, client.walletInfo(alice.address()).nextNonce());

            // Alice sends 100 PDN to Bob.
            var tx = alice.signedSend(bob.address(), Helpers.PDN(100), new TransactionAmount(0),
                client.chainId(), client.walletInfo(alice.address()).nextNonce(),
                System.currentTimeMillis());
            assertEquals("SUCCESS", client.submit(tx));

            // The mining node includes it; wait for Bob's balance to reflect the transfer.
            long deadline = System.currentTimeMillis() + 25_000;
            while (client.walletInfo(bob.address()).balance() == 0
                && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertEquals(Helpers.PDN(100).amount(), client.walletInfo(bob.address()).balance());
            assertEquals(Helpers.PDN(900).amount(), client.walletInfo(alice.address()).balance());
            assertEquals(1, client.walletInfo(alice.address()).nextNonce());
        }
    }
}
