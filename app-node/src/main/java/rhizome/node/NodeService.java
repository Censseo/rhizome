package rhizome.node;

import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ContractProcessor.ContractLog;
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
    private volatile java.util.function.LongFunction<List<ContractLog>> logSource;
    private volatile java.util.function.LongFunction<List<rhizome.core.box.BoxProcessor.BoxEvent>> boxEventSource;
    private volatile java.util.function.LongFunction<List<rhizome.core.token.TokenProcessor.TokenEvent>> tokenEventSource;
    private volatile rhizome.core.blockchain.ContractProcessor contracts;

    /** Maximum blocks a single /logs catch-up scan spans, so agents poll in bounded chunks. */
    public static final int LOG_SCAN_WINDOW = 128;

    /** Maximum boxes a single /scan query examines, so a scan runs in bounded, pollable chunks. */
    public static final int BOX_SCAN_WINDOW = 512;

    private final ScanRegistry scans = new ScanRegistry();

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

    /** Source of contract event logs by block height (the contract processor). */
    public void setLogSource(java.util.function.LongFunction<List<ContractLog>> source) {
        this.logSource = source;
    }

    /** Source of box lifecycle events by block height (the box processor). */
    public void setBoxEventSource(java.util.function.LongFunction<List<rhizome.core.box.BoxProcessor.BoxEvent>> source) {
        this.boxEventSource = source;
    }

    /**
     * Event logs emitted by a block: contract logs plus box lifecycle events (mapped
     * to the same {@code (contract, topic, data)} shape, with the box owner as contract,
     * the event type as topic, and the box id as data), so agents watch both on one feed.
     */
    public List<ContractLog> logsAt(long height) {
        var logs = logSource;
        var boxes = boxEventSource;
        var tokens = tokenEventSource;
        List<ContractLog> out = new ArrayList<>(logs == null ? List.of() : logs.apply(height));
        if (boxes != null) {
            for (var e : boxes.apply(height)) {
                out.add(new ContractLog(e.owner(), e.type().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    e.boxId()));
            }
        }
        if (tokens != null) {
            for (var e : tokens.apply(height)) {
                out.add(new ContractLog(e.actor(), e.type().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    e.tokenId()));
            }
        }
        return out;
    }

    /** Source of token lifecycle events by block height (the token processor). */
    public void setTokenEventSource(java.util.function.LongFunction<List<rhizome.core.token.TokenProcessor.TokenEvent>> source) {
        this.tokenEventSource = source;
    }

    /** Committed metadata for {@code tokenId}, or {@code null}. */
    public rhizome.core.token.TokenMeta tokenMeta(byte[] tokenId) {
        return engine.tokenMeta(tokenId);
    }

    /** Committed balance of {@code tokenId} held by {@code address}. */
    public long tokenBalance(byte[] tokenId, byte[] address) {
        return engine.tokenBalance(tokenId, address);
    }

    /** Token ids minted by {@code minter}, paginated after {@code afterId} (null = start). */
    public List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit) {
        return engine.tokenIdsByMinter(minter, afterId, limit);
    }

    /** Token ids {@code address} holds, paginated after {@code afterId} (null = start). */
    public List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit) {
        return engine.tokenIdsByHolder(address, afterId, limit);
    }

    // ---- authenticated state ----

    /** The current miner-voted box params: {@code [storageFeeFactor, minValuePerByte]}. */
    public long[] voteableParams() {
        return engine.voteableParams();
    }

    /** The current authenticated state root, or {@code null} if the accumulator is off. */
    public byte[] stateRoot() {
        return engine.stateRoot();
    }

    /** A membership proof for a state entry at the current root, or {@code null} if absent / off. */
    public rhizome.core.state.StateProof stateProof(byte domain, byte[] rawKey) {
        return engine.stateProof(domain, rawKey);
    }

    /** The contract processor, for read-only dry-run calls. */
    public void setContracts(rhizome.core.blockchain.ContractProcessor contracts) {
        this.contracts = contracts;
    }

    /** Whether read-only contract calls are available (a contract processor is wired). */
    public boolean dryRunAvailable() {
        return contracts != null;
    }

    /** Runs a read-only CALL against committed state, discarding writes (no ledger effect). */
    public rhizome.core.blockchain.ContractProcessor.ContractResult dryRun(
            PublicAddress from, PublicAddress to, byte[] input, long value, long gasLimit) {
        return contracts.dryRun(from, to, input, value, gasLimit);
    }

    /** A box from committed state, or {@code null} if none / boxes disabled. */
    public rhizome.core.box.Box box(byte[] id) {
        return engine.box(id);
    }

    /** Box ids owned by {@code owner}, paginated after {@code afterId} (null = start). */
    public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
        return engine.boxIdsByOwner(owner, afterId, limit);
    }

    // ---- box scans (EIP-1) ----

    /** Registers a declarative box scan; returns its node-local id. */
    public int registerScan(rhizome.core.box.ScanPredicate predicate) {
        return scans.register(predicate);
    }

    /** Removes a registered scan; true if it existed. */
    public boolean deregisterScan(int scanId) {
        return scans.deregister(scanId);
    }

    /** The predicate of a registered scan, or {@code null} if the id is unknown. */
    public rhizome.core.box.ScanPredicate scanPredicate(int scanId) {
        return scans.get(scanId);
    }

    /** All registered scans, id → predicate. */
    public java.util.Map<Integer, rhizome.core.box.ScanPredicate> scans() {
        return scans.all();
    }

    /** Evaluates a predicate over committed boxes, one bounded, pollable window at a time. */
    public rhizome.core.box.BoxProcessor.ScanPage scan(
            rhizome.core.box.ScanPredicate predicate, byte[] afterId, int limit) {
        return engine.scanBoxes(predicate, afterId, limit, BOX_SCAN_WINDOW);
    }

    /**
     * A height-cursor catch-up scan: logs from {@code fromHeight} up to the tip, each
     * tagged with its block height, bounded to {@link #LOG_SCAN_WINDOW} blocks so an
     * agent streams by repeatedly polling from {@code toHeight + 1}. Returns the
     * scanned {@code toHeight} and the collected logs.
     */
    public LogPage logsFrom(long fromHeight) {
        long from = Math.max(1, fromHeight);
        long to = Math.min(engine.height(), from + LOG_SCAN_WINDOW - 1);
        List<HeightLog> out = new ArrayList<>();
        for (long h = from; h <= to; h++) {
            for (ContractLog log : logsAt(h)) {
                out.add(new HeightLog(h, log));
            }
        }
        return new LogPage(from, Math.max(from - 1, to), out);
    }

    /** A contract log tagged with the height of the block that emitted it. */
    public record HeightLog(long height, ContractLog log) {}

    /** One page of a height-cursor log scan: the range covered and the logs in it. */
    public record LogPage(long fromHeight, long toHeight, List<HeightLog> logs) {}

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
