package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.BlockAssembler;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;

/**
 * The block assembler's size accounting must charge the SAME bytes the consensus
 * {@code serializedSize} charges: the wire uncle-count int (4 B) and 61 B per uncle record
 * (hash 32 + difficulty 4 + miner 25). Before the fix the counter omitted them, so the
 * assembler could pack a block the network rejects as BLOCK_TOO_LARGE — after the PoW was
 * already spent. Both now build on {@link Block#fixedOverheadBytes}, one formula instead of
 * two hand-copies that could drift again.
 */
class BlockAssemblerUncleSizeTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private MemPool mempool;
    private AtomicLong clock;
    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress miner;

    /** Consensus-side serialized size: the same {@link Block#fixedOverheadBytes} ChainEngine uses. */
    private static long consensusSize(Block block) {
        long size = Block.fixedOverheadBytes(block.uncles().size());
        for (Transaction t : block.transactions()) {
            size += ((TransactionImpl) t).sizeBytes();
        }
        return size;
    }

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        var pair = generateKeyPairTyped();
        key = pair.publicKey();
        priv = pair.privateKey();
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();
    }

    private Transaction send(long amount, long nonce) {
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(amount),
            key, new TransactionAmount(0), clock.get(), params.chainId(), nonce);
        t.sign(priv);
        return t;
    }

    private BlockImpl mineNext() {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000L)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(PublicAddress.random(),
            new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void uncleBytesAreChargedAgainstTheSizeCap() {
        // Calibrate the cap from the kind-fixed wire sizes (a TRANSFER and a coinbase share the
        // same sizeBytes): exactly room for the header, the uncle-count int, one uncle record
        // (61 B), the coinbase and ONE transfer — not two.
        int transferSize = ((TransactionImpl) TransactionImpl.builder().build()).sizeBytes();
        int cap = (int) Block.fixedOverheadBytes(1) + 2 * transferSize;
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3)
            .maxBlockSizeBytes(cap).build();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));
        engine = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot).clock(clock::get).build();
        mempool = new MemPool(params, new rhizome.core.blockchain.SignatureVerifier(), engine, 100);

        // Height 2 (fits: coinbase-only), then an orphan sibling of the tip so the next
        // candidate carries exactly one uncle reference.
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext()));
        BlockImpl orphan = (BlockImpl) BlockImpl.builder().id(2)
            .timestamp(clock.addAndGet(1500L)).difficulty(engine.difficulty())
            .lastBlockHash(engine.blockAt(1).hash()).build();
        orphan.addTransaction(Transaction.of(PublicAddress.random(),
            new TransactionAmount(params.miningReward(2))));
        var orphanTree = new MerkleTree();
        orphanTree.setItems(orphan.transactions());
        orphan.merkleRoot(orphanTree.getRootHash());
        orphan.nonce(Miner.mineNonce(orphan.hash(), orphan.difficulty(), params.powAlgorithm()));
        engine.registerOrphan(orphan);

        // Two ready transfers compete for the single slot the uncle accounting leaves.
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(100, 0)));
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(100, 1)));

        Block candidate = BlockAssembler.assemble(engine, mempool, miner, clock.get());

        assertEquals(1, candidate.uncles().size(), "the candidate must cite the eligible orphan");
        assertEquals(2, candidate.transactions().size(),
            "only one transfer fits once the uncle bytes are charged (coinbase + 1)");
        assertTrue(consensusSize(candidate) <= cap,
            "the assembled block must satisfy the consensus size cap, was " + consensusSize(candidate));

        // And it is actually accepted after mining — never BLOCK_TOO_LARGE.
        var b = (BlockImpl) candidate;
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
    }
}
