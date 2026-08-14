package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.InMemoryNonceStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.StateAccumulator;
import rhizome.core.state.snapshot.DomainStateAdapter;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;

/**
 * The snap-sync export as a component: where its spools live, what it does when it cannot export,
 * and what survives a capture that fails halfway.
 *
 * <p>The spool directory used to be a {@code volatile} field of {@link NodeService} installed by a
 * setter that did file I/O, so reaching any of this needed a whole node with an HTTP surface. Two of
 * the three behaviours below had no test at all: a service with no source (the {@code null}-guard
 * every caller then had to repeat) and the failed capture — the one branch where "the previous
 * snapshot is kept" is a promise rather than a comment.
 */
class SnapshotServiceTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4).build();

    @TempDir
    Path tempDir;

    private ChainEngine engine;
    private DomainStateAdapter source;

    @BeforeEach
    void setUp() {
        var ledger = new InMemoryLedger();
        var nonces = new InMemoryNonceStore();
        var boxStore = new InMemoryBoxStore();
        var tokenStore = new InMemoryTokenStore();
        var accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(),
            PARAMS.maxReorgDepth());
        AtomicLong clock = new AtomicLong(1_000_000L);

        var pair = generateKeyPairTyped();
        PublicKey key = pair.publicKey();
        PrivateKey priv = pair.privateKey();
        PublicAddress sender = PublicAddress.of(key);
        LedgerSnapshot genesis = new LedgerSnapshot("t", 0, PARAMS.chainId());
        genesis.put(sender, new TransactionAmount(5_000_000L));

        engine = ChainEngine.boot(
                PARAMS,
                TestNodeStores.mixing(ledger, new InMemoryChainStore(), nonces),
                genesis)
            .clock(clock::get)
            .boxes(new DefaultBoxProcessor(boxStore, PARAMS))
            .tokens(new DefaultTokenProcessor(tokenStore, PARAMS))
            .stateAccumulator(accumulator)
            .build();

        // Two blocks with a transfer each, so the exported state is not just the genesis balance.
        PublicAddress miner = PublicAddress.random();
        for (int i = 0; i < 2; i++) {
            long h = engine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(clock.addAndGet(90_000))
                .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
            b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(h))));
            Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1_000),
                key, new TransactionAmount(0), clock.get(), PARAMS.chainId(), i);
            t.sign(priv);
            b.addTransaction(t);
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            engine.stampStateRoot(b);
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        }

        source = new DomainStateAdapter(ledger, nonces, boxStore, tokenStore, null, null);
    }

    @Test
    void openSweepsStaleSpoolsOnceAndNewSpoolsLandInTheWiredDirectory() throws Exception {
        // The spool dir lives with the node's data (the OS temp dir is often a tmpfs, which would
        // silently put the whole state back in RAM). A SIGKILLed predecessor leaves
        // rhizome-snapshot-* files behind — close() only runs on a clean shutdown — so opening the
        // directory sweeps them, and only them.
        Path dir = tempDir.resolve("nested/snapshots");
        Files.createDirectories(dir);
        Path stale = Files.write(dir.resolve("rhizome-snapshot-stale.chunks"), new byte[]{1, 2, 3});
        Path unrelated = Files.write(dir.resolve("unrelated-file"), new byte[]{4});

        try (var snapshots = SnapshotService.open(engine, source, dir)) {
            assertTrue(Files.notExists(stale), "stale spool swept when the directory is opened");
            assertTrue(Files.exists(unrelated), "unrelated files are untouched");

            assertTrue(snapshots.materialize());
            var snap = snapshots.current();
            assertEquals(dir, snap.file().getParent(), "new spools land in the wired dir");
            assertTrue(Files.exists(snap.file()));
        }
        assertTrue(Files.exists(unrelated), "close() deletes spools, not the directory's contents");
    }

    @Test
    void openCreatesTheDirectoryWhenItDoesNotExistYet() throws Exception {
        Path dir = tempDir.resolve("brand/new/snapshots");
        try (var snapshots = SnapshotService.open(engine, source, dir)) {
            assertTrue(Files.isDirectory(dir));
            assertTrue(snapshots.materialize());
            assertEquals(dir, snapshots.current().file().getParent());
        }
    }

    @Test
    void aServiceWithNoSourceAnswersNoSnapshotToEverything() {
        // A node with no state accumulator, or one that simply does not export, is a legal
        // configuration — not a half-built one. This is why no caller needs a null check.
        try (var snapshots = SnapshotService.none(engine)) {
            assertFalse(snapshots.materialize(), "nothing to export");
            assertNull(snapshots.current());
            assertEquals(0, snapshots.pivotHeight());
        }
    }

    @Test
    void aFailedCaptureKeepsThePreviousSnapshotServable() throws Exception {
        // The one branch where "the previous snapshot is kept" is a promise and not a comment:
        // materialize() catches the spool I/O failure, logs it and returns false. Verified by
        // removing the spool directory out from under the service — the next createTempFile fails,
        // and the live snapshot must still answer for every chunk it advertises.
        Path dir = tempDir.resolve("vanishing");
        var snapshots = SnapshotService.open(engine, source, dir);
        try {
            assertTrue(snapshots.materialize());
            var kept = snapshots.current();
            long pivot = snapshots.pivotHeight();
            byte[][] before = new byte[kept.chunkCount()][];
            for (int i = 0; i < kept.chunkCount(); i++) {
                before[i] = kept.chunkBytes(i);
            }
            assertTrue(kept.chunkCount() > 0);

            try (var entries = Files.list(dir)) {
                for (var p : entries.toList()) {
                    Files.delete(p);
                }
            }
            Files.delete(dir);

            assertFalse(snapshots.materialize(), "the capture cannot create its spool");
            assertSame(kept, snapshots.current(), "the previous snapshot is still the current one");
            assertEquals(pivot, snapshots.pivotHeight());
            assertNotNull(kept.stateRoot());
            for (int i = 0; i < kept.chunkCount(); i++) {
                // The channel outlives the unlinked file: a kept snapshot is a servable snapshot,
                // not just a retained reference.
                assertArrayEquals(before[i], kept.chunkBytes(i),
                    "chunk " + i + " still serves after the failed re-capture");
            }
        } finally {
            snapshots.close();
        }
    }
}
