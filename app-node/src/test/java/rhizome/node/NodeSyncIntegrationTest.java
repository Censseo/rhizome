package rhizome.node;

import rhizome.net.HttpPeerSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.eventloop.Eventloop;
import io.activej.http.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.ChainSynchronizer.Result;
import rhizome.core.blockchain.HeaderSynchronizer;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.ReorgWindowTestAccess;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.crypto.PowAlgorithm;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/** Two nodes syncing over real HTTP: a fresh node catches up to a mining peer. */
class NodeSyncIntegrationTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
    private static final long NOW = 100_000_000_000L;

    private Eventloop eventloop;
    private Thread eventloopThread;
    private HttpServer server;
    private int port;
    /** The serving engine, kept so tests can drive the (package-private) reorg window. */
    private ChainEngine servingEngine;

    private static ChainEngine newEngine() {
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, PARAMS.chainId());
        return ChainEngine.init(PARAMS, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, () -> NOW);
    }

    private static void mine(ChainEngine engine, PublicAddress miner, AtomicLong clock, int count) {
        for (int i = 0; i < count; i++) {
            long h = engine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder().id((int) h)
                .timestamp(clock.addAndGet(90_000)).difficulty(engine.difficulty())
                .lastBlockHash(engine.tipHash()).build();
            b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(h))));
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Peer node with a mined chain, served over HTTP.
        ChainEngine peerEngine = newEngine();
        mine(peerEngine, PublicAddress.random(), new AtomicLong(0), 5);
        servingEngine = peerEngine;
        var verifier = new SignatureVerifier();
        MemPool mempool = new MemPool(PARAMS, verifier, peerEngine, 1000);
        var node = new NodeService(peerEngine, mempool);

        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        eventloop = Eventloop.create();
        server = HttpServer.builder(eventloop, NodeApi.servlet(eventloop, node))
            .withListenPort(port)
            .build();

        eventloop.keepAlive(true);
        eventloopThread = new Thread(eventloop, "test-http");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
        eventloop.submit(() -> {
            server.listen();
        }).get();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        eventloop.submit(() -> server.close());
        eventloop.keepAlive(false);
        eventloop.execute(eventloop::breakEventloop);
        eventloopThread.join(2000);
    }

    @Test
    void freshNodeSyncsFromHttpPeer() {
        var peer = new HttpPeerSource("http://localhost:" + port);
        assertEquals(6, peer.height());

        ChainEngine local = newEngine();
        Result result = new ChainSynchronizer(local).syncFrom(peer);

        assertEquals(Result.EXTENDED, result);
        assertEquals(6, local.height());
        assertEquals(peer.totalWork(), local.totalWork());
        assertTrue(local.tipHash().equals(peer.blockHash(6)));
    }

    @Test
    void freshNodeSyncsHeadersFirstFromHttpPeer() {
        var peer = new HttpPeerSource("http://localhost:" + port);
        ChainEngine local = newEngine();
        // Drives the real /headers endpoint: HeaderChain validates the peer's headers,
        // then bodies are fetched and each verified against its validated header.
        Result result = new HeaderSynchronizer(local).syncFrom(peer);

        assertEquals(Result.EXTENDED, result);
        assertEquals(6, local.height());
        assertEquals(peer.totalWork(), local.totalWork());
        assertTrue(local.tipHash().equals(peer.blockHash(6)));
    }

    @Test
    void servesHeadersThatHashMatchTheBlocks() {
        var peer = new HttpPeerSource("http://localhost:" + port);
        List<BlockHeader> hs = peer.headers(1, peer.height());
        assertEquals(6, hs.size());
        for (BlockHeader h : hs) {
            // A header fetched over /headers must hash exactly as the full block does —
            // the whole point of headers-first: verify PoW/chaining without the body.
            assertEquals(peer.blockHash(h.id()), h.hash());
        }
        // Range is clamped to the tip, so an over-reaching request just returns what exists.
        assertEquals(6, peer.headers(1, 999).size());
    }

    @Test
    void aReorgingPeerServes503AndReadsAsUnavailableNotInvalid() {
        // Testnet campaign S5: a node mid-reorg sits truncated at the fork height. A peer that
        // reads height() just before the pop and headers() just after would see a transiently
        // empty / gapped chain and ban it for "serving an invalid chain" — one strike on the
        // old PENALTY_INVALID = BAN_THRESHOLD eclipsed a healthy node for an hour. The chain
        // endpoints answer 503 + Retry-After for the window, which the caller's transport maps
        // to PeerUnavailableException: retried next round, never a ban.
        try {
            assertTrue(ReorgWindowTestAccess.begin(servingEngine), "reorg window opens");

            var peer = new HttpPeerSource("http://localhost:" + port);
            try {
                peer.height();
                org.junit.jupiter.api.Assertions.fail("/block_count must 503 during a reorg window");
            } catch (HttpPeerSource.PeerUnavailableException expected) {
                // 503 → transport failure, as designed.
            }
            try {
                peer.headers(1, 6);
                org.junit.jupiter.api.Assertions.fail("/headers must 503 during a reorg window");
            } catch (HttpPeerSource.PeerUnavailableException expected) {
                // 503 → transport failure, as designed.
            }

            // A fresh node syncing from the reorging peer must get the transport failure, NOT a
            // ban-earning PEER_INVALID verdict (the whole point of the 503 gate).
            ChainEngine local = newEngine();
            try {
                new HeaderSynchronizer(local).syncFrom(peer);
                org.junit.jupiter.api.Assertions.fail("sync from a reorging peer must be unavailable");
            } catch (HttpPeerSource.PeerUnavailableException expected) {
                // Retried next round; no ban score was earned.
            }
            assertEquals(1, local.height(), "nothing applied from an unavailable peer");
        } finally {
            ReorgWindowTestAccess.end(servingEngine); // never leave the window open for the next test
        }
    }
}
