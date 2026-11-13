package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.ledger.LedgerOp;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.StateAccumulator;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.persistence.rocksdb.RocksDbNodeStore;

/**
 * Attack suite for the persistence layer against the REAL RocksDB node store -- the production
 * path the in-memory stores (which the engine tests run on) cannot exercise.
 */
class PersistenceAttackTest {

    @TempDir
    Path dir;

    /**
     * PERS-07 — Submit a block whose state root is wrong. The rejection reverts the ledger through
     * its undo journal, which on RocksDB is still only STAGED in the open commit: the store must
     * find and replay it (one reversal path) rather than report "no journal" and push the
     * rejection onto the arithmetic re-derivation mirrors the journal exists to replace.
     */
    @Test
    void stateRootRejectionRevertsTheStagedLedgerJournalOnRocksDb() throws Exception {
        try (var store = new RocksDbNodeStore(dir.toString())) {
            var ledger = store.ledger();
            var a = PublicAddress.random();
            var b = PublicAddress.random();
            ledger.createWallet(a);
            ledger.deposit(a, new TransactionAmount(1_000));

            // The commit the block opens: apply stages the mutation AND its journal.
            store.chainStore().beginBlockCommit();
            ledger.withdraw(a, new TransactionAmount(400));
            ledger.createWallet(b);
            ledger.deposit(b, new TransactionAmount(400));
            ledger.applyBlock(2, List.of(
                new LedgerOp(LedgerOp.Op.WITHDRAW, a, 400), new LedgerOp(LedgerOp.Op.DEPOSIT, b, 400)));

            assertTrue(ledger.revertBlock(2), "the staged journal must be found, not reported absent");
            assertEquals(1_000, ledger.getWalletValue(a).amount());
            assertEquals(0, ledger.getWalletValue(b).amount());
            // The staged journal must not survive into the append batch of the discarded block.
            assertFalse(ledger.revertBlock(2), "reverted journal is consumed");
            store.chainStore().discardBlockCommit();
            assertEquals(1_000, ledger.getWalletValue(a).amount());
        }
    }

    /** PERS-07 — The same rejection end to end through the engine, over RocksDB. */
    @Test
    void tamperedStateRootIsRejectedAndTheNextValidBlockLandsOnRocksDb() throws Exception {
        var params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        var clock = new AtomicLong(1_000_000L);
        var pair = generateKeyPairTyped();
        var sender = PublicAddress.of(pair.publicKey());
        var miner = PublicAddress.random();
        var snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));
        var acc = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(), params.maxReorgDepth());
        try (var store = new RocksDbNodeStore(dir.toString())) {
            ChainEngine engine = ChainEngine.boot(params, store, snapshot)
                .clock(clock::get).stateAccumulator(acc).build();
            long senderBefore = store.ledger().getWalletValue(sender).amount();
            Transaction tx = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1_000),
                pair.publicKey(), new TransactionAmount(0), clock.get(), params.chainId(), 0);
            tx.sign(pair.privateKey());

            BlockImpl bad = candidate(engine, params, miner, clock, tx);
            bad.stateRoot(SHA256Hash.of(new byte[] {7}));
            bad.nonce(Miner.mineNonce(bad.hash(), bad.difficulty(), params.powAlgorithm()));
            assertEquals(ExecutionStatus.INVALID_STATE_ROOT, engine.addBlock(bad));
            assertEquals(senderBefore, store.ledger().getWalletValue(sender).amount());
            assertArrayEquals(acc.rootAt(1), engine.stateRoot());

            BlockImpl good = candidate(engine, params, miner, clock, tx);
            engine.stampStateRoot(good);
            good.nonce(Miner.mineNonce(good.hash(), good.difficulty(), params.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(good));
        }
    }

    private static BlockImpl candidate(ChainEngine engine, NetworkParameters params, PublicAddress miner,
                                       AtomicLong clock, Transaction tx) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        b.addTransaction(tx);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        return b;
    }
}
