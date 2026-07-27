package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.box.Box;
import rhizome.core.box.BoxProcessor;
import rhizome.core.box.BoxProcessor.BoxResult;
import rhizome.core.box.BoxProcessor.ScanPage;
import rhizome.core.box.ScanPredicate;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;

/**
 * The rollback receipt guard (audit: mid-rollback IndexOutOfBounds). {@code rollbackBlock}
 * consumes exactly one contract/box receipt per contract/box transaction in reverse; when the
 * receipts are missing (pruned or lost), the walk used to throw IndexOutOfBoundsException
 * MID-rollback — ledger partially reverted, a state-corruption vector. The guard must fail fast
 * BEFORE any mutation, with a clear message. A surplus, by contrast, is tolerated with a warn
 * log: the pre-guard walk consumed one receipt per tx from the back of the list and ignored
 * extras, so a hard failure there would fork nodes that had already popped such blocks.
 */
class RollbackReceiptGuardTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();

    private static BlockImpl blockWith(List<Transaction> txs) {
        var b = (BlockImpl) BlockImpl.builder().id(2).timestamp(2_000L)
            .difficulty(3).lastBlockHash(SHA256Hash.random()).build();
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        return b;
    }

    private static Transaction coinbase(PublicAddress miner, long amount) {
        return Transaction.of(miner, new TransactionAmount(amount));
    }

    private static Transaction txOfKind(TransactionKind kind, PublicAddress from) {
        return TransactionImpl.builder()
            .kind(kind).from(from).to(PublicAddress.random())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(PARAMS.chainId()).nonce(0).timestamp(1_000L)
            .data(new byte[0]).build();
    }

    @Test
    void missingBoxReceiptsAbortBeforeAnyMutation() {
        PublicAddress miner = PublicAddress.random();
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(miner);
        ledger.deposit(miner, new TransactionAmount(PARAMS.miningReward(2)));

        // A block carrying a BOX_CREATE but rolled back WITHOUT a box processor (no receipts).
        BlockImpl block = blockWith(List.of(
            coinbase(miner, PARAMS.miningReward(2)),
            txOfKind(TransactionKind.BOX_CREATE, PublicAddress.random())));

        var ex = assertThrows(IllegalStateException.class,
            () -> Executor.rollbackBlock(block, ledger, null, null, 2, PARAMS));
        assertTrue(ex.getMessage().contains("box receipts"), ex.getMessage());
        assertTrue(ex.getMessage().contains("untouched"), ex.getMessage());
        // Fail-fast BEFORE any mutation: the miner's coinbase is NOT reverted.
        assertEquals(PARAMS.miningReward(2), ledger.getWalletValue(miner).amount());
    }

    @Test
    void missingContractReceiptsAbortBeforeAnyMutation() {
        PublicAddress miner = PublicAddress.random();
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(miner);
        ledger.deposit(miner, new TransactionAmount(PARAMS.miningReward(2)));

        // A block carrying a CALL but rolled back WITHOUT a contract processor (no receipts).
        BlockImpl block = blockWith(List.of(
            coinbase(miner, PARAMS.miningReward(2)),
            txOfKind(TransactionKind.CALL, PublicAddress.random())));

        var ex = assertThrows(IllegalStateException.class,
            () -> Executor.rollbackBlock(block, ledger, null, null, 2, PARAMS));
        assertTrue(ex.getMessage().contains("contract transactions"), ex.getMessage());
        assertEquals(PARAMS.miningReward(2), ledger.getWalletValue(miner).amount());
    }

    @Test
    void receiptFreeBlocksRollBackNormally() {
        // Control: a coinbase-only block has no contract/box txs, the guards see 0 == 0 and the
        // ordinary rollback path runs (coinbase reverted).
        PublicAddress miner = PublicAddress.random();
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(miner);
        ledger.deposit(miner, new TransactionAmount(PARAMS.miningReward(2)));

        BlockImpl block = blockWith(List.of(coinbase(miner, PARAMS.miningReward(2))));
        Executor.rollbackBlock(block, ledger, null, null, 2, PARAMS);
        assertEquals(0L, ledger.getWalletValue(miner).amount());
    }

    /** A contract processor whose only job is to serve a fixed receipt list. */
    private static ContractProcessor stubContractReceipts(List<ContractProcessor.ContractReceipt> receipts) {
        return new ContractProcessor() {
            @Override public void begin() {}
            @Override public ContractResult run(PublicAddress from, TransactionKind kind, PublicAddress to,
                                                byte[] data, long value, long gasLimit, long nonce) {
                return ContractResult.ok(0, new byte[0], null);
            }
            @Override public void commit(long blockHeight) {}
            @Override public void discard() {}
            @Override public void revertBlock(long blockHeight) {}
            @Override public List<ContractReceipt> receipts(long blockHeight) {
                return receipts;
            }
        };
    }

    /** A box processor whose only job is to serve a fixed receipt list. */
    private static BoxProcessor stubBoxReceipts(List<BoxProcessor.BoxReceipt> receipts) {
        return new BoxProcessor() {
            @Override public void begin() {}
            @Override public BoxResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                                           long amount, long nonce, byte[] data, long height) {
                return BoxResult.fail(ExecutionStatus.BOX_NOT_FOUND);
            }
            @Override public void commit(long blockHeight) {}
            @Override public void discard() {}
            @Override public void revertBlock(long blockHeight) {}
            @Override public List<BoxReceipt> receipts(long blockHeight) {
                return receipts;
            }
            @Override public Box get(byte[] boxId) { return null; }
            @Override public Box getCommitted(byte[] boxId) { return null; }
            @Override public List<byte[]> collectableBoxIds(long height, int limit) { return List.of(); }
            @Override public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) { return List.of(); }
            @Override public ScanPage scan(ScanPredicate predicate, byte[] afterId, int limit, int window) {
                return new ScanPage(List.of(), null);
            }
        };
    }

    @Test
    void surplusContractReceiptsAreToleratedAndConsumedFromTheBack() {
        PublicAddress miner = PublicAddress.random();
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(miner);
        ledger.deposit(miner, new TransactionAmount(PARAMS.miningReward(2)));

        // One CALL (gasPrice 0, value 0 — nothing moves on revert) but TWO receipts on record:
        // the pre-guard walk consumed the trailing one and ignored the surplus, so rollback
        // must log a warn and succeed, exactly reverting the coinbase.
        BlockImpl block = blockWith(List.of(
            coinbase(miner, PARAMS.miningReward(2)),
            txOfKind(TransactionKind.CALL, PublicAddress.random())));
        var processor = stubContractReceipts(List.of(
            new ContractProcessor.ContractReceipt(0, false),
            new ContractProcessor.ContractReceipt(0, true)));

        Executor.rollbackBlock(block, ledger, processor, null, 2, PARAMS);
        assertEquals(0L, ledger.getWalletValue(miner).amount(),
            "surplus receipts tolerated: rollback succeeds and the coinbase is reverted");
    }

    @Test
    void surplusBoxReceiptsAreToleratedAndConsumedFromTheBack() {
        PublicAddress miner = PublicAddress.random();
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(miner);
        ledger.deposit(miner, new TransactionAmount(PARAMS.miningReward(2)));

        BlockImpl block = blockWith(List.of(
            coinbase(miner, PARAMS.miningReward(2)),
            txOfKind(TransactionKind.BOX_CREATE, PublicAddress.random())));
        var boxProcessor = stubBoxReceipts(List.of(
            new BoxProcessor.BoxReceipt(TransactionKind.BOX_CREATE, 0, 0, 0),
            new BoxProcessor.BoxReceipt(TransactionKind.BOX_CREATE, 0, 0, 0)));

        Executor.rollbackBlock(block, ledger, null, boxProcessor, 2, PARAMS);
        assertEquals(0L, ledger.getWalletValue(miner).amount(),
            "surplus box receipts tolerated: rollback succeeds and the coinbase is reverted");
    }

    @Test
    void shortContractReceiptsStillFailBeforeAnyMutation() {
        // One CALL on the block but a receipt list shorter than the walk needs (here: the block
        // carries two CALLs, one receipt is on record) — a hard failure with the ledger intact,
        // exactly like the zero-receipt case above.
        PublicAddress miner = PublicAddress.random();
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(miner);
        ledger.deposit(miner, new TransactionAmount(PARAMS.miningReward(2)));

        BlockImpl block = blockWith(List.of(
            coinbase(miner, PARAMS.miningReward(2)),
            txOfKind(TransactionKind.CALL, PublicAddress.random()),
            txOfKind(TransactionKind.CALL, PublicAddress.random())));
        var processor = stubContractReceipts(List.of(new ContractProcessor.ContractReceipt(0, true)));

        var ex = assertThrows(IllegalStateException.class,
            () -> Executor.rollbackBlock(block, ledger, processor, null, 2, PARAMS));
        assertTrue(ex.getMessage().contains("only 1 receipts"), ex.getMessage());
        assertEquals(PARAMS.miningReward(2), ledger.getWalletValue(miner).amount());
    }
}
