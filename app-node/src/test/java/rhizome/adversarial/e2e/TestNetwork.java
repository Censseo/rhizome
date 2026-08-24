package rhizome.adversarial.e2e;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.node.NodeConfig;
import rhizome.node.RhizomeNode;

/**
 * A network of real Rhizome nodes, for adversarial scenarios that only exist end to end.
 *
 * <p>Every node here is the production article: {@link RhizomeNode} with its RocksDB stores on
 * disk, its ActiveJ HTTP server on a real loopback socket, its block producer thread and its sync
 * loops. Peers talk to each other over HTTP through {@code HttpPeerSource}, exactly as they would
 * across a network. Nothing is stubbed, so what these tests exercise is the assembled system —
 * the wiring, the threading, the serialisation and the recovery paths — rather than a component
 * with its collaborators replaced.
 *
 * <p>That is the point, and also the cost: these scenarios are slow, they involve real mining and
 * real timeouts, and they can only assert what an outside observer can see (a height, a tip hash,
 * a balance, an HTTP status). A component test can assert <em>which</em> gate refused a block; an
 * end-to-end test can only assert that the chain did not move. The two layers answer different
 * questions and the catalogue keeps both.
 *
 * <p>Ports are allocated when a node is declared rather than when it starts, so a node can be
 * given its peers' URLs before those peers exist — which is what lets a scenario build a mutually
 * peered network in one pass.
 */
final class TestNetwork implements AutoCloseable {

    /**
     * Instant-mining profile: SHA-256 at 3 bits, with a low ceiling so the retarget cannot price
     * mining out of reach when a scenario drives blocks far faster than the 90 s target. Mirrors
     * the profile {@code RhizomeNodeTest} already uses, so E2E timings stay comparable.
     */
    static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256)
        .genesisDifficulty(3)
        .minDifficulty(3)
        .maxDifficulty(16)
        .build();

    /**
     * {@link #FAST} with the supply-driven emission curve active from height 1 — the fast-PoW twin
     * {@code CurveActiveNetwork.curveActiveTestnet()} does not provide, since that helper derives
     * from the "slow", real-PoW {@code NetworkParameters.testnet()} rather than this class's
     * instant-mining profile. Same test-scale {@code (S*, c, N)} triple as
     * {@code CurveActiveNetwork}, chosen only for a small, well-populated table — not calibrated to
     * any real-world timescale (see {@code NetworkParameters.cleanMainnet()} for that).
     */
    static final NetworkParameters CURVE_ACTIVE = FAST.toBuilder()
        .supplyTarget(1_000_000L)
        .emissionCoefficient(10_000L)
        .emissionTableSteps(16)
        .emissionCurveHeight(1)
        .build();

    /** How long a scenario waits for a network-wide condition before calling it a failure. */
    static final long PATIENCE_MS = 30_000;

    private final Path root;
    private final List<RhizomeNode> started = new ArrayList<>();
    /**
     * The currently-live node under each declared name, so {@link #reopen} can stop and replace
     * exactly one node without touching any other in the network. Kept alongside — not instead
     * of — {@link #started}: that list is the full teardown order {@link #close()} iterates in
     * reverse, and a name whose node has been replaced still needs its earlier incarnations gone
     * from it too (removed by {@link #reopen} as it closes them), so teardown never double-closes
     * a node this method has already stopped.
     */
    private final Map<String, RhizomeNode> byName = new LinkedHashMap<>();

    TestNetwork(Path root) {
        this.root = root;
    }

    /** Declares a node, reserving its port immediately so peers can name it before it starts. */
    Builder node(String name) {
        return new Builder(name);
    }

    /**
     * Stops the node currently tracked under {@code name} (if one is running) and returns a fresh
     * {@link Builder} pre-bound to the exact same {@link Builder#dataDir() data directory}, ready
     * for {@code .params(...)}/{@code .snapshot(...)}/{@code .start()} — the in-JVM twin of an
     * operator stopping a node, changing its configuration, and starting it again on the data it
     * already wrote. Generalises the single-node restart pattern in
     * {@code E2ENodeResilienceTest#aRestartOnTheSameDataDirectoryRestoresChainBalancesAndNonces}
     * (construct a new {@code RhizomeNode} over the same {@code dataDir}/port after closing the
     * old one) into a {@code TestNetwork}-level convenience that only touches the one named node.
     *
     * <p>{@code RhizomeNode.close()} is {@code public synchronized}, safe to call once and
     * idempotent, so closing it here is unconditional. The closed node is removed from both
     * {@link #started} and {@link #byName} <em>before</em> this method returns — not after the
     * caller's {@code .start()} runs — so a {@code .start()} that throws (the refusal scenarios
     * this exists for) leaves nothing dangling: {@link #close()} at test end has nothing left to
     * double-close for this name, and a subsequent {@code reopen(name)} finds no stale entry to
     * confuse it. The new {@link Builder} reuses the same node's freed port for restart realism;
     * if no node is currently tracked under {@code name} (nothing has been reopened or started
     * yet) a fresh port is allocated exactly as {@link #node(String)} would.
     */
    Builder reopen(String name) {
        RhizomeNode existing = byName.remove(name);
        if (existing == null) {
            return new Builder(name);
        }
        int port = existing.apiPort();
        started.remove(existing);
        existing.close();
        return new Builder(name, port);
    }

    static String urlOf(RhizomeNode node) {
        return "http://127.0.0.1:" + node.apiPort();
    }

    // ---- waiting on a real, concurrently-moving network ----

    /** Waits until {@code node} reaches {@code height}, failing the scenario if it never does. */
    static void awaitHeight(RhizomeNode node, long height) throws InterruptedException {
        await(() -> node.engine().height() >= height,
            () -> "node stalled at height " + node.engine().height() + ", expected " + height);
    }

    /**
     * Waits until every node reports the same tip hash. This is the real convergence question —
     * equal height is not agreement, since two branches of equal length disagree on history.
     */
    static void awaitSameTip(List<RhizomeNode> nodes) throws InterruptedException {
        await(() -> {
            SHA256Hash first = nodes.get(0).engine().tipHash();
            return nodes.stream().allMatch(n -> n.engine().tipHash().equals(first));
        }, () -> "nodes did not converge; tips: " + nodes.stream()
            .map(n -> n.engine().height() + ":" + n.engine().tipHash().toHexString().substring(0, 12))
            .toList());
    }

    /**
     * Drives sync rounds on {@code node} until {@code condition} holds. A node's own loop syncs on
     * a 10 s period by default, far longer than a scenario should wait, so scenarios pump rounds
     * explicitly instead of raising the patience.
     */
    static void syncUntil(RhizomeNode node, Condition condition) throws InterruptedException {
        syncUntil(List.of(node), condition);
    }

    /**
     * As above across several nodes, which is what a healing partition needs: neither side is the
     * client, so both must pull. Relying on the nodes' own 10 s sync period instead would let two
     * producers mine a fork deeper than the finality window before the first round ever fired —
     * the scenario would then be measuring a permanent split it created itself.
     */
    static void syncUntil(List<RhizomeNode> nodes, Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PATIENCE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) {
                return;
            }
            for (RhizomeNode node : nodes) {
                node.syncRound();
            }
            Thread.sleep(50);
        }
        if (!condition.holds()) {
            throw new AssertionError("condition never held after " + PATIENCE_MS + " ms of sync rounds");
        }
    }

    static void await(Condition condition, java.util.function.Supplier<String> failure)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + PATIENCE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(25);
        }
        if (!condition.holds()) {
            throw new AssertionError(failure.get());
        }
    }

    @FunctionalInterface
    interface Condition {
        boolean holds();
    }

    @Override
    public void close() {
        // Reverse order: a node still syncing from one being torn down should see the closure, not
        // a half-open socket.
        Collections.reverse(started);
        for (RhizomeNode node : started) {
            try {
                node.close();
            } catch (RuntimeException ignored) {
                // A scenario that already failed must not be masked by a teardown error.
            }
        }
        started.clear();
        byName.clear();
    }

    /** Fluent node declaration; the port is live from construction, the node from {@link #start()}. */
    final class Builder {

        private final String name;
        private final int port;
        private NetworkParameters params = FAST;
        private PublicAddress miner;
        private long blockIntervalMs = 30;
        private final List<String> peers = new ArrayList<>();
        private String apiToken;
        private Path snapshot;
        /**
         * Settings that live only in the {@link NodeConfig} record and have no {@code with*}
         * helper — pruning, snap-sync, the snapshot cadence. Applied as a transformation at start
         * rather than mirrored as fields here, so adding one costs a method instead of a
         * twenty-argument constructor call per knob.
         */
        private java.util.function.UnaryOperator<NodeConfig> tweak = config -> config;

        private Builder(String name) {
            this(name, freePort());
        }

        /** Used by {@link #reopen}, which already knows the port a prior incarnation freed. */
        private Builder(String name, int port) {
            this.name = name;
            this.port = port;
        }

        String url() {
            return "http://127.0.0.1:" + port;
        }

        Builder params(NetworkParameters params) {
            this.params = params;
            return this;
        }

        /** Runs a block producer paying a fresh random address. */
        Builder mining() {
            return mining(PublicAddress.random());
        }

        Builder mining(PublicAddress miner) {
            this.miner = miner;
            return this;
        }

        Builder blockInterval(long millis) {
            this.blockIntervalMs = millis;
            return this;
        }

        Builder peers(String... urls) {
            Collections.addAll(peers, urls);
            return this;
        }

        Builder peers(Builder... others) {
            for (Builder other : others) {
                peers.add(other.url());
            }
            return this;
        }

        /** Boots this node on a premined genesis ledger — see {@link E2EFixtures#premine}. */
        Builder snapshot(Path file) {
            this.snapshot = file;
            return this;
        }

        Builder apiToken(String token) {
            this.apiToken = token;
            return this;
        }

        /** Retains only the last {@code keep} block bodies; older ones are pruned at boot. */
        Builder keepBlocks(int keep) {
            return tweak(c -> new NodeConfig(c.params(), c.dataDir(), c.apiPort(), c.snapshotPath(),
                c.miner(), c.peers(), c.advertisedUrl(), c.syncPeriodMs(), c.blockIntervalMs(),
                c.mempoolSize(), c.allowPrivatePeers(), c.bindAddress(), c.apiToken(),
                keep, c.allowOpenApi(), c.peerToken(), c.snapSync(), c.protectReads(),
                c.trustXff(), c.vote(), c.snapshotEveryBlocks(), c.hostAllowlistEnabled(),
                c.extraAllowedHosts()));
        }

        /** Bootstraps from a peer's materialised state snapshot instead of replaying every block. */
        Builder snapSync() {
            return tweak(c -> new NodeConfig(c.params(), c.dataDir(), c.apiPort(), c.snapshotPath(),
                c.miner(), c.peers(), c.advertisedUrl(), c.syncPeriodMs(), c.blockIntervalMs(),
                c.mempoolSize(), c.allowPrivatePeers(), c.bindAddress(), c.apiToken(),
                c.keepBlocks(), c.allowOpenApi(), c.peerToken(), true, c.protectReads(),
                c.trustXff(), c.vote(), c.snapshotEveryBlocks(), c.hostAllowlistEnabled(),
                c.extraAllowedHosts()));
        }

        /** Materialises a state snapshot every {@code blocks} blocks. */
        Builder snapshotEvery(long blocks) {
            return tweak(c -> new NodeConfig(c.params(), c.dataDir(), c.apiPort(), c.snapshotPath(),
                c.miner(), c.peers(), c.advertisedUrl(), c.syncPeriodMs(), c.blockIntervalMs(),
                c.mempoolSize(), c.allowPrivatePeers(), c.bindAddress(), c.apiToken(),
                c.keepBlocks(), c.allowOpenApi(), c.peerToken(), c.snapSync(), c.protectReads(),
                c.trustXff(), c.vote(), blocks, c.hostAllowlistEnabled(), c.extraAllowedHosts()));
        }

        Builder tweak(java.util.function.UnaryOperator<NodeConfig> more) {
            java.util.function.UnaryOperator<NodeConfig> previous = this.tweak;
            this.tweak = config -> more.apply(previous.apply(config));
            return this;
        }

        /** The data directory this node will use — so a scenario can restart it by hand. */
        Path dataDir() {
            return root.resolve(name);
        }

        RhizomeNode start() throws IOException {
            NodeConfig config = NodeConfig.defaults(params, root.resolve(name).toString(), port)
                // Loopback peers are private addresses: without this the SSRF filter refuses every
                // node in the network, and the scenario silently tests an unpeered node.
                .withAllowPrivatePeers(true)
                .withPeers(List.copyOf(peers))
                .withBlockIntervalMs(blockIntervalMs);
            if (snapshot != null) {
                config = config.withSnapshot(snapshot.toString());
            }
            if (miner != null) {
                config = config.withMiner(miner);
            }
            if (apiToken != null) {
                config = config.withApiToken(apiToken);
            }
            RhizomeNode node = new RhizomeNode(tweak.apply(config));
            node.start();
            started.add(node);
            byName.put(name, node);
            return node;
        }
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port for the test network", e);
        }
    }
}
