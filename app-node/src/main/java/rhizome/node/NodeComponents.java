package rhizome.node;

import java.util.Set;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.mempool.MemPool;
import rhizome.net.PeerBanList;
import rhizome.net.PeerBroadcaster;
import rhizome.net.PeerRegistry;
import rhizome.net.PeerTokenPolicy;
import rhizome.persistence.rocksdb.RocksDbBoxStore;
import rhizome.persistence.rocksdb.RocksDbContractStore;
import rhizome.persistence.rocksdb.RocksDbNodeStore;
import rhizome.persistence.rocksdb.RocksDbStateStore;
import rhizome.persistence.rocksdb.RocksDbTokenStore;

/**
 * The node's object graph, fully built: stores open, engine booted, services wired to each other.
 *
 * <p>Nothing here listens on a socket or drives a loop — that is {@link NodeRuntime}. The split is
 * what makes the assembly reachable from a test: {@link RhizomeNode#assemble} performs every
 * decision that used to be buried in {@code start()} — the exposure refusal, the snap-sync
 * bootstrap, the state-retention check, the {@code Host} allowlist discovery — and it performs
 * them without binding a port, so two of them can run side by side in one JVM.
 *
 * <p>Immutable by construction. Nine of these were mutable fields of {@code RhizomeNode} assigned
 * across four lifecycle methods, so every reader had to know which method had already run; here
 * the record's constructor is the proof that all of them have.
 *
 * @param syncHttpClient one shared client for every sync round, so a fresh client (and its
 *                       selector thread + connection pool) is not built per peer per round, and
 *                       keep-alive is reused (audit net #1)
 * @param blockPrivatePeers refuse/pin SSRF-prone (loopback / private / metadata) peer hosts. On by
 *                       default on every node; only RHIZOME_ALLOW_PRIVATE_PEERS=true opts out
 *                       (audit F4)
 * @param allowedHosts   the legitimate {@code Host} authorities for the DNS-rebinding defense,
 *                       including this machine's live non-loopback interface addresses — boot-time
 *                       discovery, which is why it is assembled here and not parsed in
 *                       {@link NodeConfig}
 */
record NodeComponents(
    RocksDbNodeStore store,
    RocksDbContractStore contractStore,
    RocksDbBoxStore boxStore,
    RocksDbTokenStore tokenStore,
    RocksDbStateStore stateStore,
    SignatureVerifier verifier,
    ChainEngine engine,
    MemPool mempool,
    NodeService service,
    PeerBanList banList,
    PeerRegistry registry,
    PeerBroadcaster broadcaster,
    PeerTokenPolicy peerTokenPolicy,
    java.net.http.HttpClient syncHttpClient,
    boolean blockPrivatePeers,
    Set<String> allowedHosts) implements AutoCloseable {

    /**
     * Releases the graph, in the order {@link RhizomeNode#close()} has always used: the gossip
     * fan-out and the verifier pool, then the snapshot spool, then the stores under the engine
     * lock. The {@code finally} is load-bearing — a failing broadcaster must not leave the data
     * directory locked open (audit: incomplete shutdown).
     *
     * @param closeStores false when a stuck API worker or sync thread may still be inside a
     *                    native RocksDB call: closing the handles under it is a use-after-free
     *                    (JVM-level SIGSEGV, not a catchable exception), and if it instead holds
     *                    the engine lock, acquiring that lock here hangs shutdown forever. Leaving
     *                    the stores open is the lesser evil — the threads are daemons that die
     *                    with the process, the OS releases the flock on exit, and RocksDB's WAL
     *                    recovers a torn write on the next open.
     */
    void release(boolean closeStores) {
        try {
            broadcaster.close();
            verifier.shutdown();
        } finally {
            service.close();
            if (closeStores) {
                engine.runExclusive(() -> {
                    store.close();
                    contractStore.close();
                    boxStore.close();
                    tokenStore.close();
                    stateStore.close();
                });
            }
        }
    }

    /** Releases everything, stores included — the shape a graph that never started needs. */
    @Override
    public void close() {
        release(true);
    }
}
