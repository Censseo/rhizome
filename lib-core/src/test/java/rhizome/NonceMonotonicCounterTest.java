package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.InMemoryNonceStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * Audit S7 ("nonce-trie griefing"). The premise of the finding — an unbounded set of seen
 * {@code (address, nonce)} pairs that grows one entry per transaction — does not hold: replay
 * protection is a per-account MONOTONIC COUNTER (next-expected nonce), keyed by address only. This
 * test locks that invariant so a future refactor cannot silently turn the nonce domain into a
 * per-transaction seen-set: one account sending many transactions must leave exactly ONE entry.
 */
class NonceMonotonicCounterTest {

    @Test
    void manyTransactionsFromOneAccountLeaveExactlyOneNonceEntry() {
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4)
            .minBlockTimeSec(0).build();

        var pair = generateKeyPair();
        PublicKey key = PublicKey.of(pair.getPublic());
        PrivateKey priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        PublicAddress sender = PublicAddress.of(key);
        PublicAddress miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        InMemoryNonceStore nonces = new InMemoryNonceStore();
        AtomicLong clock = new AtomicLong(0);
        ChainEngine engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            nonces, snapshot, null, clock::get, null, null, null, null, null);

        int k = 6;
        for (int n = 0; n < k; n++) {
            Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1_000),
                key, new TransactionAmount(0), clock.get(), params.chainId(), n);
            t.sign(priv);
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(t), clock)),
                "block carrying the sender's nonce " + n + " must apply");
        }

        // The counter advanced to k (strict sequential nonces 0..k-1 consumed)...
        assertEquals(k, engine.nextNonce(sender));
        // ...yet the store holds exactly ONE entry — the sender's counter, overwritten in place —
        // not one entry per (sender, nonce). The coinbase has no nonce, so nothing else is recorded.
        int[] entries = {0};
        nonces.forEach((addr, next) -> entries[0]++);
        assertEquals(1, entries[0], "the nonce domain is a per-account counter, not a growing seen-set");
    }

    private Block mine(ChainEngine engine, NetworkParameters params, PublicAddress miner,
                       List<Transaction> txs, AtomicLong clock) {
        long height = engine.height() + 1;
        var b = BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        ((BlockImpl) b).merkleRoot(tree.getRootHash());
        ((BlockImpl) b).nonce(Miner.mineNonce(b.hash(), ((BlockImpl) b).difficulty(), params.powAlgorithm()));
        return b;
    }
}
