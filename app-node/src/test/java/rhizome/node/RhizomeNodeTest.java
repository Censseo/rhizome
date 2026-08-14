package rhizome.node;

import rhizome.net.HttpPeerSource;
import rhizome.net.PeerId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
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
    void aHostThatNeverSpokeTheProtocolIsDroppedNotBanned() throws Exception {
        // Ban by proxy (audit B-3): on an open node /add_peer is unauthenticated, so an attacker
        // could enqueue ANY public host. An ordinary web server answering 200 to everything makes
        // /block_count unparseable — worth a full protocol-violation penalty (PENALTY_INVALID,
        // three strikes before a ban) renewable for as long as the attacker keeps re-adding it.
        // The victim's own honest node would then be refused by this one. A host that never
        // completed a protocol exchange is a wrong address, not a misbehaving peer: drop it,
        // never ban it.
        var victim = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        victim.createContext("/", exchange -> {
            byte[] body = "<html>hello</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        victim.start();
        String victimUrl = "http://127.0.0.1:" + victim.getAddress().getPort();
        try {
            NodeConfig config = NodeConfig.defaults(FAST, tempDir.resolve("proxy-ban").toString(),
                freePort()).withAllowPrivatePeers(true);
            try (RhizomeNode node = new RhizomeNode(config)) {
                node.start();
                node.service().addPeer(victimUrl); // the /add_peer path: admission is off-loop
                long deadline = System.currentTimeMillis() + 10_000;
                while (!node.knownPeers().contains(victimUrl) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertTrue(node.knownPeers().contains(victimUrl), "the peer should have been admitted");

                node.syncRound(); // reads junk where /block_count should be

                assertFalse(node.knownPeers().contains(victimUrl),
                    "a host serving junk must be dropped from the registry");
                assertFalse(node.banList().isBanned(PeerId.of(victimUrl)),
                    "…but never banned: it never proved it was a Rhizome node at all");
            }
        } finally {
            victim.stop(0);
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
            .withMiner(PublicAddress.random()).withBlockIntervalMs(50).withAllowPrivatePeers(true);

        try (RhizomeNode nodeA = new RhizomeNode(configA)) {
            nodeA.start();
            awaitHeight(nodeA, 5, 25_000);

            int portB = freePort();
            NodeConfig configB = NodeConfig.defaults(FAST, tempDir.resolve("b").toString(), portB)
                .withPeers(java.util.List.of("http://localhost:" + portA)).withAllowPrivatePeers(true);

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

    @Test
    void allPeersBannedIsObservedAsAnEclipsedSync() throws Exception {
        // Testnet campaign S5: a node whose every known peer is banned keeps a full-height
        // silence (no log line, no degraded marker) while syncing from none of them. The sync
        // round must surface that state — peersSkippedBanned == all peers, rounds-without-
        // progress climbing, and the eclipsed flag set — so /stats shows it in seconds.
        NodeConfig config = NodeConfig.defaults(FAST, tempDir.resolve("eclipsed").toString(), freePort())
            .withAllowPrivatePeers(true);
        try (RhizomeNode node = new RhizomeNode(config)) {
            node.start();
            // A peer admitted and then banned OUTRIGHT (banList.ban, not penalize): this is the
            // registry-keeps-it shape, where the round can still see it and skip it. The other
            // shape — penalize, which evicts — is covered by noPeerAtAllIsAlsoAnEclipse below.
            String peerUrl = "http://127.0.0.1:9";
            node.service().addPeer(peerUrl); // admission runs off-loop (DNS)
            long deadline = System.currentTimeMillis() + 10_000;
            while (!node.knownPeers().contains(peerUrl) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(node.knownPeers().contains(peerUrl), "peer admitted");
            node.banList().ban(PeerId.of(peerUrl));

            node.syncRound();

            assertEquals(1, node.service().syncHealth().peersKnown());
            assertEquals(0, node.service().syncHealth().peersTried(),
                "the banned peer must not be tried");
            assertEquals(1, node.service().syncHealth().peersSkippedBanned(),
                "the banned peer must be counted as skipped");
            assertTrue(node.service().syncHealth().eclipsed(),
                "every known peer banned is an eclipse");
            assertEquals(0, node.service().syncHealth().roundsWithoutProgress(),
                "the first round is the stall baseline (nothing to compare against)");

            node.syncRound();
            node.syncRound();
            assertTrue(node.service().syncHealth().roundsWithoutProgress() >= 2,
                "an eclipsed round must keep accumulating the stall counter");
        }
    }

    @Test
    void noPeerAtAllIsAlsoAnEclipse() throws Exception {
        // Review follow-up: an EMPTY registry is the shape the eclipse actually takes, because
        // PeerRegistry.penalize evicts on ban — "every peer banned" collapses to "no peer left",
        // not to a registry full of banned entries. The round used to return before publishing
        // anything in that state, so /stats froze on the previous round's numbers and no WARN
        // ever fired: the metric was blind in exactly the case it exists for.
        NodeConfig config = NodeConfig.defaults(FAST, tempDir.resolve("no-peers").toString(), freePort());
        try (RhizomeNode node = new RhizomeNode(config)) {
            node.start();
            assertTrue(node.knownPeers().isEmpty(), "no peer configured");

            node.syncRound();
            node.syncRound();

            var health = node.service().syncHealth();
            assertEquals(0, health.peersKnown());
            assertEquals(0, health.peersTried());
            assertTrue(health.eclipsed(), "a round with no sync source at all is an eclipse");
            assertTrue(health.roundsWithoutProgress() >= 1,
                "the stall counter must climb with no peer to sync from");
        }
    }

    @Test
    void aDeepForkedPeerIsRefusedButNeverBanned() throws Exception {
        // Testnet campaign replay defect: a branch past the reorg horizon is not misbehaviour,
        // yet REORG_TOO_DEEP used to accumulate +25/strike — four rounds earned a 1 h ban,
        // renewed hourly, so two forked camps locked each other out permanently and the
        // natural heal (one camp out-pacing the other) could never happen. A deep-forked peer
        // must stay connected and unbanned: the verdict is refused, the peer is retried later.
        //
        // The finality window is shrunk to 8 for the test: the rule under test is "a fork DEEPER
        // than maxReorgDepth earns no ban", which 12 mined blocks prove exactly as well as the
        // 270 the production 120-block window would need — at a twentieth of the wall clock.
        NetworkParameters shallowFinality = FAST.toBuilder().maxReorgDepth(8).build();
        int portA = freePort();
        NodeConfig configA = NodeConfig.defaults(shallowFinality, tempDir.resolve("deepfork-a").toString(), portA)
            .withMiner(PublicAddress.random()).withBlockIntervalMs(30);
        try (RhizomeNode nodeA = new RhizomeNode(configA)) {
            nodeA.start();
            awaitHeight(nodeA, 14, 30_000);

            int portB = freePort();
            NodeConfig configB = NodeConfig.defaults(shallowFinality, tempDir.resolve("deepfork-b").toString(), portB)
                .withMiner(PublicAddress.random()).withBlockIntervalMs(30)
                .withPeers(java.util.List.of("http://localhost:" + portA));
            try (RhizomeNode nodeB = new RhizomeNode(configB)) {
                nodeB.start();
                // B mines its OWN branch from genesis (different miner than A, and A never
                // learns of B to gossip into it): when B syncs, the two chains share only
                // genesis, and B's fork depth is past maxReorgDepth by construction.
                awaitHeight(nodeB, 12, 30_000);
                long heightB = nodeB.engine().height();
                assertTrue(heightB - 1 > shallowFinality.maxReorgDepth(), "the fork must be past finality");
                // Identify B's OWN branch by its second block: the two chains share only genesis,
                // so adopting A's would replace this one. Height cannot be the witness here —
                // B mines its own branch at blockIntervalMs=30 throughout, so it can legitimately
                // gain a block between this line and the assertion below, and the test then failed
                // for the one reason it was not testing.
                var ownSecondBlock = nodeB.engine().blockAt(2).hash();

                nodeB.syncRound();

                assertEquals(ownSecondBlock, nodeB.engine().blockAt(2).hash(),
                    "nothing adopted from a branch past finality");
                assertTrue(nodeB.engine().height() >= heightB,
                    "B keeps its own branch; it never rolls back for a peer past finality");
                assertFalse(nodeB.banList().isBanned(PeerId.of("http://localhost:" + portA)),
                    "a deep-forked peer is not misbehaving: it must never be banned");
                assertTrue(nodeB.knownPeers().contains("http://localhost:" + portA),
                    "the deep-forked peer stays in the registry");
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
        var pair = rhizome.crypto.Crypto.generateKeyPair();
        var key = rhizome.crypto.PublicKey.of(pair.getPublic());
        var priv = new rhizome.crypto.PrivateKey(
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
