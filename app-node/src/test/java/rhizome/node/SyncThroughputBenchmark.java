package rhizome.node;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import io.activej.eventloop.Eventloop;
import io.activej.http.HttpServer;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.common.Constants;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;

import static rhizome.crypto.Crypto.generateKeyPairTyped;

/**
 * Not a correctness test — it measures how one sync round splits between network I/O and
 * block application, to size the gain of prefetching window N+1 while window N is applied.
 *
 * <p>{@code ChainSynchronizer.applyRange} alternates strictly: it materialises a whole
 * {@code BLOCKS_PER_FETCH} window from the peer, then applies it block-by-block under the
 * engine lock, then fetches the next. The network is idle during apply and the CPU is idle
 * during fetch. This probe replicates that loop verbatim against a real HTTP peer and times
 * the two halves separately. Fetch is timed wholesale (HTTP + decode), because a prefetch
 * thread would overlap both.
 *
 * <p>Loopback has no propagation delay and effectively unbounded bandwidth, so the measured
 * fetch share is a FLOOR. The report therefore also projects the split onto real link
 * profiles from the transferred byte count, which is the same figure {@code /sync} puts on
 * the wire ({@code SyncApi} streams {@link BlockCodec#encode} per block).
 *
 * <p>Enable manually:
 * {@code ./gradlew :app-node:test --tests SyncThroughputBenchmark -Dbench=on}
 * (optionally {@code -Dbench.sync.blocks=600 -Dbench.sync.tx=50}).
 */
class SyncThroughputBenchmark {

    /** Chain length to sync. Default spans three full BLOCKS_PER_FETCH windows. */
    private static final int BLOCKS = Integer.getInteger("bench.sync.blocks", 600);
    /** Transfers per block on top of the coinbase — drives both body size and apply cost. */
    private static final int TX_PER_BLOCK = Integer.getInteger("bench.sync.tx", 0);

    /** SHA256 PoW, as testnet uses; the mainnet Pufferfish2 cost is folded in by the report. */
    private static final NetworkParameters PARAMS = NetworkParameters.testnet();
    /** Measured on this box by {@code Pufferfish2Benchmark} (cost_t=0, cost_m=8). */
    private static final double PUFFERFISH_MS = Double.parseDouble(
        System.getProperty("bench.sync.pufferfishMs", "13.339"));

    private static final long NOW = 100_000_000_000L;

    @Test
    void probe() throws Exception {
        if (!"on".equals(System.getProperty("bench"))) {
            return;
        }

        var pair = generateKeyPairTyped();
        PublicKey key = pair.publicKey();
        PrivateKey priv = pair.privateKey();
        PublicAddress sender = PublicAddress.of(key);
        PublicAddress miner = PublicAddress.random();

        ChainEngine peerEngine = newEngine(sender);
        AtomicLong clock = new AtomicLong(0);
        AtomicLong nonce = new AtomicLong(0);
        long mineStart = System.nanoTime();
        for (int i = 0; i < BLOCKS; i++) {
            ExecutionStatus st = peerEngine.addBlock(
                nextBlock(peerEngine, clock, miner, sender, key, priv, nonce));
            if (st != ExecutionStatus.SUCCESS) {
                throw new IllegalStateException("fixture block rejected: " + st);
            }
        }
        long mineNs = System.nanoTime() - mineStart;

        // Exact /sync payload size for the whole range.
        long wireBytes = 0;
        for (long h = 2; h <= peerEngine.height(); h++) {
            wireBytes += BlockCodec.encode(peerEngine.blockAt(h)).length;
        }

        Eventloop eventloop = Eventloop.create();
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        var service = new NodeService(peerEngine, new MemPool(PARAMS, new SignatureVerifier(), peerEngine, 1000));
        // /sync is weighted ~1 unit per block requested, so the production budget (1000 units per
        // second) caps a loopback probe at five windows/s. The client now rides that out by
        // backing off (HttpPeerSourceThrottleTest), so the probe would still complete — but the
        // waits would land inside the fetch timing and measure the throttle instead of the
        // transport. Lift the budget: rate limiting is a separate concern from this split.
        var limiter = new rhizome.net.RateLimiter(Integer.MAX_VALUE, 1000, 8192);
        HttpServer server = HttpServer.builder(eventloop, NodeApi.servlet(eventloop, service, limiter))
            .withListenPort(port).build();
        eventloop.keepAlive(true);
        Thread eventloopThread = new Thread(eventloop, "bench-http");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
        eventloop.submit(server::listen).get();

        try {
            Split warm = syncRound(port, sender, peerEngine.height());  // JIT the path
            Split run = syncRound(port, sender, peerEngine.height());
            String report = report(run, warm, wireBytes, mineNs);
            System.out.print(report);
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of(System.getProperty("bench.out", "bench-sync.txt")), report);
            } catch (Exception ignored) {
                // best effort, same as the other probes
            }
        } finally {
            eventloop.submit(() -> server.close());
            eventloop.keepAlive(false);
            eventloop.execute(eventloop::breakEventloop);
            eventloopThread.join(2000);
        }
    }

    /** Timings of one full catch-up, split the way the real applyRange loop splits. */
    private record Split(long fetchNs, long applyNs, long slowestFetchNs, long slowestApplyNs,
                         int windows, long blocks) {}

    /**
     * Replicates {@code ChainSynchronizer.applyRange} against a real HTTP peer — same window
     * size, same serial in-order apply — but times the fetch and the apply separately.
     */
    private Split syncRound(int port, PublicAddress fundedSender, long targetHeight) {
        var peer = new rhizome.net.HttpPeerSource("http://localhost:" + port);
        ChainEngine local = newEngine(fundedSender);
        long fetchNs = 0;
        long applyNs = 0;
        long slowestFetch = 0;
        long slowestApply = 0;
        int windows = 0;
        long applied = 0;

        for (long start = 2; start <= targetHeight; start += Constants.BLOCKS_PER_FETCH) {
            long end = Math.min(targetHeight, start + Constants.BLOCKS_PER_FETCH - 1);

            long t0 = System.nanoTime();
            List<Block> window = peer.blocks(start, end);
            long fetch = System.nanoTime() - t0;

            long t1 = System.nanoTime();
            for (Block block : window) {
                ExecutionStatus st = local.addBlock(block);
                if (st != ExecutionStatus.SUCCESS) {
                    throw new IllegalStateException("apply failed at " + block.id() + ": " + st);
                }
                applied++;
            }
            long apply = System.nanoTime() - t1;

            fetchNs += fetch;
            applyNs += apply;
            slowestFetch = Math.max(slowestFetch, fetch);
            slowestApply = Math.max(slowestApply, apply);
            windows++;
        }
        return new Split(fetchNs, applyNs, slowestFetch, slowestApply, windows, applied);
    }

    private static String report(Split run, Split warm, long wireBytes, long mineNs) {
        double fetchMs = run.fetchNs() / 1e6;
        double applyMs = run.applyNs() / 1e6;
        double serialMs = fetchMs + applyMs;
        double perWindowFetch = fetchMs / run.windows();
        double perWindowApply = applyMs / run.windows();
        double prefetchMs = pipelined(perWindowFetch, perWindowApply, run.windows());

        StringBuilder projection = new StringBuilder();
        // Link profiles: name, Mbit/s, RTT ms. Fetch time is modelled as the measured
        // loopback cost (decode + framing, which no link removes) plus transfer plus one RTT
        // per window request.
        double[][] links = {
            {1000, 0.5}, {100, 20}, {100, 80}, {20, 150},
        };
        String[] names = {"LAN 1 Gb/s, 0.5 ms", "WAN 100 Mb/s, 20 ms", "WAN 100 Mb/s, 80 ms", "WAN 20 Mb/s, 150 ms"};
        for (int i = 0; i < links.length; i++) {
            double transferMs = (wireBytes * 8.0 / (links[i][0] * 1e6)) * 1000.0;
            double linkFetch = fetchMs + transferMs + links[i][1] * run.windows();
            double linkPerWindowFetch = linkFetch / run.windows();
            double linkSerial = linkFetch + applyMs;
            double linkPrefetch = pipelined(linkPerWindowFetch, perWindowApply, run.windows());
            projection.append(String.format(
                "  %-22s fetch %8.0f ms | serial %8.0f ms | prefetched %8.0f ms | %4.2fx%n",
                names[i], linkFetch, linkSerial, linkPrefetch, linkSerial / linkPrefetch));
        }

        // Mainnet swaps SHA256 for Pufferfish2: one memory-hard hash per block header verify.
        double pufferApplyMs = applyMs + run.blocks() * PUFFERFISH_MS;
        double pufferPerWindowApply = pufferApplyMs / run.windows();
        StringBuilder pufferProjection = new StringBuilder();
        for (int i = 0; i < links.length; i++) {
            double transferMs = (wireBytes * 8.0 / (links[i][0] * 1e6)) * 1000.0;
            double linkFetch = fetchMs + transferMs + links[i][1] * run.windows();
            double linkPerWindowFetch = linkFetch / run.windows();
            double linkSerial = linkFetch + pufferApplyMs;
            double linkPrefetch = pipelined(linkPerWindowFetch, pufferPerWindowApply, run.windows());
            pufferProjection.append(String.format(
                "  %-22s fetch %8.0f ms | serial %8.0f ms | prefetched %8.0f ms | %4.2fx%n",
                names[i], linkFetch, linkSerial, linkPrefetch, linkSerial / linkPrefetch));
        }

        return String.format("""
            === sync split probe (%d blocks, %d tx/block, %d windows of %d, %d cores) ===
            fixture mined in %.1f s | /sync wire payload %.2f MiB (%.0f B/block)

            Measured on loopback (fetch floor: no propagation delay, no bandwidth limit)
              fetch (HTTP + decode)  : %9.1f ms  = %5.1f%% of the round | %6.3f ms/block
              apply (addBlock, SHA256): %9.1f ms  = %5.1f%% of the round | %6.3f ms/block
              serial round (as today) : %9.1f ms  -> %8.1f blocks/s
              ideal double buffering  : %9.1f ms  -> %8.1f blocks/s  | %.2fx
              warmup round was %.1f ms (JIT check: measured round should be lower)

            Projected onto real links — testnet PoW (SHA256)
            %s
            Projected onto real links — mainnet PoW (Pufferfish2 @ %.2f ms/block verify)
              apply becomes %.0f ms total (%.2f ms/block), dominating every profile below
            %s""",
            run.blocks(), TX_PER_BLOCK, run.windows(), Constants.BLOCKS_PER_FETCH,
            Runtime.getRuntime().availableProcessors(),
            mineNs / 1e9, wireBytes / 1024.0 / 1024.0, (double) wireBytes / run.blocks(),
            fetchMs, 100 * fetchMs / serialMs, fetchMs / run.blocks(),
            applyMs, 100 * applyMs / serialMs, applyMs / run.blocks(),
            serialMs, run.blocks() / (serialMs / 1000.0),
            prefetchMs, run.blocks() / (prefetchMs / 1000.0), serialMs / prefetchMs,
            (warm.fetchNs() + warm.applyNs()) / 1e6,
            projection,
            PUFFERFISH_MS, pufferApplyMs, pufferApplyMs / run.blocks(),
            pufferProjection);
    }

    /**
     * Wall-clock of {@code windows} fetch/apply pairs with one window of prefetch depth: the
     * first fetch and the last apply cannot overlap with anything, and every step in between
     * costs the slower of the two halves. Never exceeds the serial {@code w * (f + a)}.
     */
    private static double pipelined(double fetchMs, double applyMs, int windows) {
        return fetchMs + (windows - 1) * Math.max(fetchMs, applyMs) + applyMs;
    }

    /**
     * Mirrors {@code RhizomeNode}'s wiring on the one axis that dominates apply cost: a real
     * node passes a {@link SignatureVerifier}, so {@code Executor} takes the parallel+cached
     * {@code verifyAll} path. The shorter {@code init} overloads leave it null and fall back to
     * per-transaction {@code signatureValid()}, which would overstate apply by the verifier's
     * whole speedup. Each engine gets its OWN verifier: a syncing node's cache is cold.
     */
    private static ChainEngine newEngine(PublicAddress fundedSender) {
        LedgerSnapshot snapshot = new LedgerSnapshot("bench", 0, PARAMS.chainId());
        snapshot.put(fundedSender, new TransactionAmount(Long.MAX_VALUE / 4));
        return ChainEngine.init(PARAMS, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, () -> NOW, new SignatureVerifier());
    }

    private static Block nextBlock(ChainEngine engine, AtomicLong clock, PublicAddress miner,
                                   PublicAddress sender, PublicKey key, PrivateKey priv,
                                   AtomicLong nonce) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(90_000)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(height))));
        List<Transaction> txs = new ArrayList<>(TX_PER_BLOCK);
        for (int i = 0; i < TX_PER_BLOCK; i++) {
            Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1),
                key, new TransactionAmount(0), NOW, PARAMS.chainId(), nonce.getAndIncrement());
            t.sign(priv);
            txs.add(t);
        }
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
        return b;
    }
}
