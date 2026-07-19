package rhizome.node;

import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.transaction.Transaction;

/**
 * Facade the node API serves over: the chain engine plus the mempool. Keeps the
 * HTTP layer free of consensus logic — it only marshals requests to these calls.
 */
public final class NodeService {

    private final ChainEngine engine;
    private final MemPool mempool;
    private volatile java.util.function.Consumer<Block> onBlockAccepted;
    private volatile java.util.function.Consumer<Transaction> onTransactionAccepted;
    private volatile PeerRegistry peers;

    public NodeService(ChainEngine engine, MemPool mempool) {
        this.engine = engine;
        this.mempool = mempool;
    }

    /** Called when a freshly submitted block/transaction is accepted (for gossip). */
    public void setOnBlockAccepted(java.util.function.Consumer<Block> listener) {
        this.onBlockAccepted = listener;
    }

    public void setOnTransactionAccepted(java.util.function.Consumer<Transaction> listener) {
        this.onTransactionAccepted = listener;
    }

    public void setPeers(PeerRegistry registry) {
        this.peers = registry;
    }

    /** Peer base URLs this node knows (empty if discovery is not enabled). */
    public java.util.List<String> knownPeers() {
        return peers == null ? java.util.List.of() : peers.snapshot();
    }

    /** Registers a peer that announced itself; returns true if newly added. */
    public boolean addPeer(String url) {
        return peers != null && peers.add(url);
    }

    public NetworkParameters params() {
        return engine.params();
    }

    public int chainId() {
        return engine.params().chainId();
    }

    public String networkName() {
        return engine.params().networkName();
    }

    public long blockCount() {
        return engine.height();
    }

    public java.math.BigInteger totalWork() {
        return engine.totalWork();
    }

    public int difficulty() {
        return engine.difficulty();
    }

    public Block block(long height) {
        return engine.blockAt(height);
    }

    /** Blocks in the inclusive range, already clamped by the caller. */
    public List<Block> blocks(long start, long end) {
        List<Block> out = new ArrayList<>();
        for (long h = start; h <= end; h++) {
            out.add(engine.blockAt(h));
        }
        return out;
    }

    public long balance(PublicAddress wallet) {
        return engine.confirmedBalance(wallet);
    }

    public long nextNonce(PublicAddress wallet) {
        return engine.confirmedNextNonce(wallet);
    }

    /** Admits a transaction to the mempool (signature verified once here). */
    public ExecutionStatus submitTransaction(Transaction transaction) {
        ExecutionStatus status = mempool.addTransaction(transaction);
        if (status == ExecutionStatus.SUCCESS) {
            notify(onTransactionAccepted, transaction);
        }
        return status;
    }

    /** Accepts a mined block; on success the mempool is purged of its transactions. */
    public ExecutionStatus submitBlock(Block block) {
        ExecutionStatus status = engine.addBlock(block);
        if (status == ExecutionStatus.SUCCESS) {
            mempool.onBlockApplied(block);
            notify(onBlockAccepted, block);
        } else {
            // A block that didn't extend our tip may be a valid sibling that lost the
            // race; keep it (PoW-gated inside) so a later block can cite it as an uncle.
            engine.registerOrphan(block);
        }
        return status;
    }

    private static <T> void notify(java.util.function.Consumer<T> listener, T value) {
        if (listener != null) {
            listener.accept(value);
        }
    }

    public int mempoolSize() {
        return mempool.size();
    }
}
