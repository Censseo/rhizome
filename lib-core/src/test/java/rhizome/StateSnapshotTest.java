package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.InMemoryNonceStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.StateAccumulator;
import rhizome.core.state.snapshot.DomainStateAdapter;
import rhizome.core.state.snapshot.SnapshotChunk;
import rhizome.core.state.snapshot.StateSnapshotExporter;
import rhizome.core.state.snapshot.StateSnapshotImporter;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenPayload;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

/**
 * State snapshots end to end (D5): a chain's full state — balances, nonces, boxes, tokens —
 * exports into chunks, round-trips the wire codec, rebuilds in a fresh tree to exactly the
 * committed root, and seeds fresh stores bit-identically. Any tampering (flipped byte,
 * dropped entry, duplicate) changes the rebuilt root and the import is refused.
 */
class StateSnapshotTest {

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private InMemoryNonceStore nonces;
    private InMemoryBoxStore boxStore;
    private InMemoryTokenStore tokenStore;
    private ChainEngine engine;
    private AtomicLong clock;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress bob;
    private PublicAddress miner;
    private byte[] boxId;
    private byte[] tokenId;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        ledger = new InMemoryLedger();
        nonces = new InMemoryNonceStore();
        boxStore = new InMemoryBoxStore();
        tokenStore = new InMemoryTokenStore();
        var boxes = new DefaultBoxProcessor(boxStore, params);
        var tokens = new DefaultTokenProcessor(tokenStore, params);
        var accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(),
            params.maxReorgDepth());
        clock = new AtomicLong(1_000_000L);

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        bob = PublicAddress.random();
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));

        engine = ChainEngine.init(params, ledger, new InMemoryChainStore(), nonces, snapshot, null,
            clock::get, null, null, boxes, tokens, accumulator);

        // Build real state: a transfer (ledger + nonce), a data box, a token mint.
        boxId = rhizome.core.box.Box.deriveId(sender, 1);
        tokenId = TokenMeta.deriveId(sender, 2);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(List.of(
            transfer(1_000, 0), boxCreate(5_000, 1, BoxRegister.string("agent memory")), tokenMint(1_000_000, 2)))));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(List.of(transfer(500, 3)))));
    }

    private Transaction transfer(long amount, long nonce) {
        Transaction t = Transaction.of(sender, bob, new TransactionAmount(amount), key,
            new TransactionAmount(0), clock.get(), params.chainId(), nonce);
        t.sign(priv);
        return t;
    }

    private Transaction boxCreate(long value, long nonce, BoxRegister... regs) {
        var tx = TransactionImpl.builder().from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).timestamp(clock.get())
            .kind(TransactionKind.BOX_CREATE).data(BoxPayload.encodeCreate(List.of(regs)))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(priv);
        return tx;
    }

    private Transaction tokenMint(long amount, long nonce) {
        var tx = TransactionImpl.builder().from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).timestamp(clock.get())
            .kind(TransactionKind.TOKEN_MINT).data(TokenPayload.encodeMint(amount, 2, "PNDA", "Panda"))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(priv);
        return tx;
    }

    private BlockImpl mine(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        engine.stampStateRoot(b);
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** Exports the engine's state and round-trips every chunk through the wire codec. */
    private List<SnapshotChunk> exportedChunks(int maxEntriesPerChunk) {
        var source = new DomainStateAdapter(ledger, nonces, boxStore, tokenStore, null, null);
        List<SnapshotChunk> chunks = StateSnapshotExporter.export(source, maxEntriesPerChunk);
        List<SnapshotChunk> decoded = new ArrayList<>();
        for (SnapshotChunk c : chunks) {
            decoded.add(SnapshotChunk.decode(c.encode()));
        }
        return decoded;
    }

    @Test
    void exportImportRebuildsTheExactRootAndStores() {
        byte[] committedRoot = engine.stateRoot();
        List<SnapshotChunk> chunks = exportedChunks(2); // tiny chunks: forces multi-chunk domains
        assertTrue(chunks.size() > 3, "expected several chunks, got " + chunks.size());

        // Fresh stores, fresh tree: import must reproduce the committed root exactly...
        var freshLedger = new InMemoryLedger();
        var freshNonces = new InMemoryNonceStore();
        var freshBoxes = new InMemoryBoxStore();
        var freshTokens = new InMemoryTokenStore();
        var sink = new DomainStateAdapter(freshLedger, freshNonces, freshBoxes, freshTokens, null, null);

        long pivot = engine.height();
        byte[] rebuilt = StateSnapshotImporter.importVerified(chunks, new InMemorySmtNodeStore(), committedRoot, sink);
        sink.flush(pivot);
        assertArrayEquals(committedRoot, rebuilt);

        // ...and the seeded stores must agree with the originals, bit for bit.
        assertEquals(ledger.getWalletValue(sender).amount(), freshLedger.getWalletValue(sender).amount());
        assertEquals(ledger.getWalletValue(bob).amount(), freshLedger.getWalletValue(bob).amount());
        assertEquals(ledger.getWalletValue(miner).amount(), freshLedger.getWalletValue(miner).amount());
        assertEquals(4L, freshNonces.next(sender));
        assertNotNull(freshBoxes.get(boxId));
        assertArrayEquals(boxStore.get(boxId).serialize(), freshBoxes.get(boxId).serialize());
        assertArrayEquals(tokenStore.getMeta(tokenId).serialize(), freshTokens.getMeta(tokenId).serialize());
        assertEquals(tokenStore.getBalance(tokenId, sender.toBytes()),
            freshTokens.getBalance(tokenId, sender.toBytes()));
        // Secondary indexes are rebuilt from the verified values, not transferred.
        assertEquals(1, freshBoxes.boxIdsByOwner(sender.toBytes(), null, 10).size());
        assertEquals(1, freshTokens.tokenIdsByHolder(sender.toBytes(), null, 10).size());
    }

    @Test
    void tamperedFlippedByteIsRefused() {
        byte[] committedRoot = engine.stateRoot();
        List<SnapshotChunk> chunks = exportedChunks(1000);
        // Flip one byte in the first entry's value of the first non-empty chunk.
        SnapshotChunk first = chunks.get(0);
        byte[] tamperedValue = first.entries().get(0).value().clone();
        tamperedValue[0] ^= 0x01;
        List<SnapshotChunk.Entry> entries = new ArrayList<>(first.entries());
        entries.set(0, new SnapshotChunk.Entry(first.entries().get(0).key(), tamperedValue));
        chunks.set(0, new SnapshotChunk(first.domain(), entries));

        assertThrows(StateSnapshotImporter.SnapshotVerificationException.class,
            () -> StateSnapshotImporter.verify(chunks, new InMemorySmtNodeStore(), committedRoot));
    }

    @Test
    void droppedEntryIsRefused() {
        byte[] committedRoot = engine.stateRoot();
        List<SnapshotChunk> chunks = exportedChunks(1000);
        SnapshotChunk first = chunks.get(0);
        List<SnapshotChunk.Entry> entries = new ArrayList<>(first.entries());
        entries.remove(0);
        chunks.set(0, new SnapshotChunk(first.domain(), entries));

        assertThrows(StateSnapshotImporter.SnapshotVerificationException.class,
            () -> StateSnapshotImporter.verify(chunks, new InMemorySmtNodeStore(), committedRoot));
    }

    @Test
    void smuggledExtraEntryIsRefused() {
        byte[] committedRoot = engine.stateRoot();
        List<SnapshotChunk> chunks = exportedChunks(1000);
        // Smuggle in a balance for an attacker address that was never in the state.
        chunks.add(new SnapshotChunk(rhizome.core.state.StateKeys.LEDGER, List.of(
            new SnapshotChunk.Entry(PublicAddress.random().toBytes(),
                rhizome.core.common.Utils.longToBytes(1_000_000_000L)))));

        assertThrows(StateSnapshotImporter.SnapshotVerificationException.class,
            () -> StateSnapshotImporter.verify(chunks, new InMemorySmtNodeStore(), committedRoot));
    }

    @Test
    void chunkOrderDoesNotMatter() {
        byte[] committedRoot = engine.stateRoot();
        List<SnapshotChunk> chunks = exportedChunks(2);
        java.util.Collections.reverse(chunks); // SMT root is a function of the binding set only
        assertArrayEquals(committedRoot,
            StateSnapshotImporter.verify(chunks, new InMemorySmtNodeStore(), committedRoot));
    }
}
