package rhizome.core.blockchain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.box.Box;
import rhizome.core.box.BoxProcessor;
import rhizome.core.box.BoxStore;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.box.ScanPredicate;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.StateAccumulator;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenProcessor;
import rhizome.core.token.TokenStore;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionKind;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The per-block state domains must be reverted in the order they were committed.
 *
 * <p>Nothing in the type system enforces that today: {@code Executor} opens, commits and discards
 * contract/box/token in one hand-written sequence, and {@code ChainEngine} reverts them in four
 * more hand-written sequences (popBlock, the two addBlock state-root rejections, and the
 * stampStateRoot dry-run undo). Six copies of one ordering, related only by comments.
 *
 * <p>These tests DERIVE both sequences from a single run and compare them, rather than restating
 * the expected order — a test that hard-codes {@code [contract, box, token]} in two places is just
 * a seventh copy of the comment. Written before the ordering is extracted into a shared type, so
 * it locks the current behaviour rather than the refactoring's.
 */
class StateDomainOrderTest {

    private final List<String> journal = new ArrayList<>();

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private ChainEngine engine;
    private AtomicLong clock;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        ledger = new InMemoryLedger();
        clock = new AtomicLong(1_000_000L);
        miner = PublicAddress.random();

        var boxes = new RecordingBoxProcessor(new DefaultBoxProcessor(new InMemoryBoxStore(), params));
        var tokens = new RecordingTokenProcessor(
            new DefaultTokenProcessor(new InMemoryTokenStore(), params));
        var contracts = new RecordingContractProcessor();
        var accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(),
            params.maxReorgDepth());

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        engine = ChainEngine.init(params, ledger, new InMemoryChainStore(), snapshot, null,
            clock::get, null, contracts, boxes, tokens, accumulator);
    }

    private BlockImpl mine() {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        engine.stampStateRoot(b);
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** The domain names in the order the given lifecycle phase touched them, oldest first. */
    private List<String> orderOf(String phase) {
        return journal.stream()
            .filter(e -> e.endsWith("." + phase))
            .map(e -> e.substring(0, e.indexOf('.')))
            .toList();
    }

    @Test
    void popBlockRevertsTheDomainsInTheOrderExecutorCommittedThem() {
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine()));
        journal.clear();
        ChainEngineTestAccess.popBlock(engine);

        List<String> reverted = orderOf("revert");
        assertFalse(reverted.isEmpty(), "the pop must have reverted the state domains");

        // Mine first, THEN clear: stampStateRoot runs its own commit/revert cycle inside mine(),
        // so clearing afterwards leaves only the real commit that addBlock performs.
        BlockImpl next = mine();
        journal.clear();
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(next));
        List<String> committed = orderOf("commit");

        assertEquals(committed, reverted,
            "popBlock must revert the state domains in the order Executor committed them; both "
            + "orders are hand-written in separate files and nothing but this test relates them");
    }

    @Test
    void everyLifecyclePhaseWalksTheDomainsInTheSameOrder() {
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine()));

        List<String> begun = orderOf("begin");
        List<String> committed = orderOf("commit");
        assertFalse(begun.isEmpty(), "the block must have opened a session per domain");
        assertEquals(begun, committed, "sessions must be committed in the order they were opened");
    }

    @Test
    void theStampStateRootDryRunUndoesInTheSameOrderToo() {
        // stampStateRoot applies the candidate, stamps the root, then rolls the whole thing back.
        // It has no dedicated test today, and its undo is a fifth hand-written copy of the order.
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine()));
        journal.clear();

        long height = engine.height() + 1;
        var candidate = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash()).build();
        candidate.addTransaction(
            Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(candidate.transactions());
        candidate.merkleRoot(tree.getRootHash());
        engine.stampStateRoot(candidate);

        assertEquals(orderOf("commit"), orderOf("revert"),
            "the dry-run undo must walk the domains in the order the dry-run committed them");
    }

    // ---- recording decorators: delegate everything, journal the lifecycle -----------------------

    private final class RecordingBoxProcessor implements BoxProcessor {
        private final BoxProcessor delegate;

        RecordingBoxProcessor(BoxProcessor delegate) {
            this.delegate = delegate;
        }

        @Override public void begin() {
            journal.add("box.begin");
            delegate.begin();
        }

        @Override public void commit(long blockHeight) {
            journal.add("box.commit");
            delegate.commit(blockHeight);
        }

        @Override public void discard() {
            journal.add("box.discard");
            delegate.discard();
        }

        @Override public void revertBlock(long blockHeight) {
            journal.add("box.revert");
            delegate.revertBlock(blockHeight);
        }

        @Override public BoxResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                                       long amount, long nonce, byte[] data, long height) {
            return delegate.run(kind, from, to, amount, nonce, data, height);
        }

        @Override public List<BoxReceipt> receipts(long h) {
            return delegate.receipts(h);
        }

        @Override public List<BoxEvent> events(long h) {
            return delegate.events(h);
        }

        @Override public List<BoxStore.BoxMutation> changes(long h) {
            return delegate.changes(h);
        }

        @Override public VoteableParams voteableParams() {
            return delegate.voteableParams();
        }

        @Override public Box get(byte[] boxId) {
            return delegate.get(boxId);
        }

        @Override public Box getCommitted(byte[] boxId) {
            return delegate.getCommitted(boxId);
        }

        @Override public List<byte[]> collectableBoxIds(long height, int limit) {
            return delegate.collectableBoxIds(height, limit);
        }

        @Override public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
            return delegate.boxIdsByOwner(owner, afterId, limit);
        }

        @Override public ScanPage scan(ScanPredicate predicate, byte[] afterId, int limit, int window) {
            return delegate.scan(predicate, afterId, limit, window);
        }
    }

    private final class RecordingTokenProcessor implements TokenProcessor {
        private final TokenProcessor delegate;

        RecordingTokenProcessor(TokenProcessor delegate) {
            this.delegate = delegate;
        }

        @Override public void begin() {
            journal.add("token.begin");
            delegate.begin();
        }

        @Override public void commit(long blockHeight) {
            journal.add("token.commit");
            delegate.commit(blockHeight);
        }

        @Override public void discard() {
            journal.add("token.discard");
            delegate.discard();
        }

        @Override public void revertBlock(long blockHeight) {
            journal.add("token.revert");
            delegate.revertBlock(blockHeight);
        }

        @Override public TokenResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                                         long nonce, byte[] data, long height) {
            return delegate.run(kind, from, to, nonce, data, height);
        }

        @Override public List<TokenEvent> events(long h) {
            return delegate.events(h);
        }

        @Override public List<TokenStore.TokenOp> changes(long h) {
            return delegate.changes(h);
        }

        @Override public TokenMeta meta(byte[] tokenId) {
            return delegate.meta(tokenId);
        }

        @Override public long balance(byte[] tokenId, byte[] address) {
            return delegate.balance(tokenId, address);
        }

        @Override public List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit) {
            return delegate.tokenIdsByMinter(minter, afterId, limit);
        }

        @Override public List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit) {
            return delegate.tokenIdsByHolder(address, afterId, limit);
        }
    }

    /** No real contract processor lives in lib-core, so this one only journals. */
    private final class RecordingContractProcessor implements ContractProcessor {

        @Override public void begin() {
            journal.add("contract.begin");
        }

        @Override public void commit(long blockHeight) {
            journal.add("contract.commit");
        }

        @Override public void discard() {
            journal.add("contract.discard");
        }

        @Override public void revertBlock(long blockHeight) {
            journal.add("contract.revert");
        }

        @Override public ContractResult run(PublicAddress from, TransactionKind kind, PublicAddress to,
                                            byte[] data, long value, long gasLimit, long nonce) {
            throw new UnsupportedOperationException("no contract transactions in this test");
        }

        @Override public List<ContractReceipt> receipts(long blockHeight) {
            return List.of();
        }
    }

    /** Unused, but keeps the block helper honest if a future test adds a real tip hash check. */
    @SuppressWarnings("unused")
    private static SHA256Hash unusedTip(Block b) {
        return b.hash();
    }
}
