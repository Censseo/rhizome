package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;

/**
 * The headers-first body fast path (audit P4): {@code addValidatedBody} skips ONLY the memory-hard
 * proof-of-work re-check — which the header the body matches has already passed — while the normal
 * {@code addBlock} still enforces it. In-package so it can reach the package-private fast path.
 */
class TrustedPowBodyTest {

    @Test
    void addValidatedBodySkipsThePowCheckThatAddBlockEnforces() {
        // High difficulty with an UNMINED (all-zero) nonce, so proof of work fails deterministically —
        // no mining needed, and the block is otherwise fully valid (right merkle root / difficulty /
        // parent / empty state root), so the only thing addBlock can reject on is the PoW.
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256)
            .genesisDifficulty(32).minDifficulty(32).maxDifficulty(255).minBlockTimeSec(0)
            .build();
        InMemoryLedger ledger = new InMemoryLedger();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        ChainEngine engine = ChainEngine.init(params, ledger, new InMemoryChainStore(),
            snapshot, null, () -> 100_000_000_000L);

        PublicAddress miner = PublicAddress.random();
        long h = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(5_000_000L)
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(h))));
        MerkleTree tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(SHA256Hash.empty()); // unmined: the nonce does not satisfy difficulty 32

        assertFalse(b.verifyNonce(params.powAlgorithm()), "the empty nonce must not satisfy the difficulty");

        // Normal path enforces PoW and rejects it without mutating the chain.
        assertEquals(ExecutionStatus.INVALID_NONCE, engine.addBlock(b));
        assertEquals(1, engine.height());

        // The trusted-body path (the caller has already proven PoW via the matching header) skips only
        // that check and applies the otherwise-valid block.
        assertEquals(ExecutionStatus.SUCCESS, engine.addValidatedBody(b));
        assertEquals(2, engine.height());
    }
}
