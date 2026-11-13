package rhizome.node;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.BootstrapTarget;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.InMemoryNonceStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.NonceStore;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.box.BoxStore;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.RootStore;
import rhizome.core.state.SmtNodeStore;
import rhizome.core.state.SparseMerkleTree;
import rhizome.core.state.StateKeys;
import rhizome.core.state.snapshot.SnapshotChunk;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenStore;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;

/**
 * A complete, honest one-chunk snap-sync world over the in-memory ports: a real PoW-valid header
 * chain, a one-entry ledger snapshot whose root the pivot header commits to, and a peer serving
 * both. Attack tests derive variants (more work, shorter chain, failing target) from it.
 */
final class SnapTestFixture {

    static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4)
        .maxReorgDepth(3).build();
    static final long NOW = 100_000_000_000L;
    static final long PIVOT = 2;

    final LedgerSnapshot genesisSnapshot = new LedgerSnapshot("t", 0, PARAMS.chainId());
    final byte[] chunk;
    final byte[] root;

    SnapTestFixture() {
        var entry = new SnapshotChunk.Entry(PublicAddress.random().toBytes(),
            rhizome.core.common.Utils.longToBytes(42L));
        var c = new SnapshotChunk(StateKeys.LEDGER, List.of(entry));
        chunk = c.encode();
        root = new SparseMerkleTree(new InMemorySmtNodeStore()).update(SparseMerkleTree.EMPTY_ROOT,
            StateKeys.key(StateKeys.LEDGER, entry.key()), StateKeys.valueHash(entry.value()));
    }

    BlockHeader mineHeader(long id, SHA256Hash parent, long timestamp, SHA256Hash stateRoot, long supply) {
        var b = (BlockImpl) BlockImpl.builder().id((int) id).timestamp(timestamp)
            .difficulty(PARAMS.genesisDifficulty()).lastBlockHash(parent)
            .merkleRoot(SHA256Hash.random()).stateRoot(stateRoot).supply(supply).build();
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
        return BlockHeader.of(b);
    }

    /** Headers 2..{@code tip}; the pivot header commits {@link #root}. */
    List<BlockHeader> chain(long tip) {
        SHA256Hash parent = BlockHeader.of(GenesisBlock.build(PARAMS, genesisSnapshot)).hash();
        List<BlockHeader> out = new ArrayList<>();
        long supply = genesisSnapshot.totalSupply(); // genesis commits it; every header must follow
        for (long h = 2; h <= tip; h++) {
            supply += PARAMS.miningReward(h, supply);
            BlockHeader header = mineHeader(h, parent, h * 1000L,
                h == PIVOT ? SHA256Hash.of(root) : SHA256Hash.empty(), supply);
            out.add(header);
            parent = header.hash();
        }
        return out;
    }

    PeerSource.SnapshotInfo info() {
        return new PeerSource.SnapshotInfo(PIVOT, root, 1);
    }

    /** A peer serving {@code headers}, the honest chunk, and a self-reported {@code totalWork}. */
    static final class Peer implements PeerSource {
        private final long height;
        private final PeerSource.SnapshotInfo info;
        private final List<BlockHeader> headers;
        private final byte[] chunk;
        private final BigInteger totalWork;

        Peer(long height, PeerSource.SnapshotInfo info, List<BlockHeader> headers, byte[] chunk,
             BigInteger totalWork) {
            this.height = height;
            this.info = info;
            this.headers = headers;
            this.chunk = chunk;
            this.totalWork = totalWork;
        }

        @Override public long height() { return height; }
        @Override public PeerSource.SnapshotInfo snapshotInfo() { return info; }
        @Override public List<Block> blocks(long start, long end) { return List.of(); }
        @Override public List<BlockHeader> headers(long start, long end) {
            return headers.stream().filter(h -> h.id() >= start && h.id() <= end).toList();
        }
        @Override public byte[] snapshotChunk(int index) { return chunk; }
        @Override public BigInteger totalWork() { return totalWork; }
        @Override public SHA256Hash blockHash(long h) { return SHA256Hash.empty(); }
    }

    /** The whole seed target, in memory; {@code failOnHeaders} simulates a crash after seeding. */
    static final class Target implements BootstrapTarget {
        private final ChainStore chain = new InMemoryChainStore();
        private final Ledger ledger = new InMemoryLedger();
        private final NonceStore nonces = new InMemoryNonceStore();
        private final BoxStore boxes = new InMemoryBoxStore();
        private final TokenStore tokens = new InMemoryTokenStore();
        private final SmtNodeStore nodes = new InMemorySmtNodeStore();
        private final RootStore roots = new InMemoryRootStore();
        boolean failOnHeaders;
        boolean bootstrapping;
        int adoptedHeaders;

        @Override public ChainStore chainStore() { return chain; }
        @Override public Ledger ledger() { return ledger; }
        @Override public NonceStore nonceStore() { return nonces; }
        @Override public BoxStore boxes() { return boxes; }
        @Override public TokenStore tokens() { return tokens; }
        @Override public SmtNodeStore stateNodes() { return nodes; }
        @Override public RootStore stateRoots() { return roots; }
        @Override public void beginBootstrap() { bootstrapping = true; }
        @Override public void bootstrapHeaders(List<BlockHeader> headers) {
            if (failOnHeaders) {
                throw new IllegalStateException("disk full");
            }
            adoptedHeaders += headers.size();
        }
        @Override public void endBootstrap() { bootstrapping = false; }
    }
}
