package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.Executor;
import rhizome.core.blockchain.HeaderChain;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * The consensus-V2 activation gate ({@code NetworkParameters.consensusV2Height}): the three
 * audit fixes that changed validation rules — median-of-3 retarget bounds, the consensus fee
 * floor, and the zero-deposit wallet-creation skip — must apply ONLY from the scheduled height
 * on, so a chain with pre-activation history re-verifies its past under the legacy rules
 * (no unplanned hard fork). Existing tests all run with the default height 0 (V2 from genesis).
 */
class ConsensusV2GateTest {

    private static final long ACTIVATION = 100;

    private final NetworkParameters params = NetworkParameters.testnet();

    private InMemoryLedger ledger;
    private PublicKey senderKey;
    private PrivateKey senderPrivate;
    private PublicAddress sender;
    private PublicAddress recipient;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        var pair = generateKeyPair();
        senderKey = PublicKey.of(pair.getPublic());
        senderPrivate = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(senderKey);
        recipient = PublicAddress.random();
        miner = PublicAddress.random();
        ledger.createWallet(sender);
        ledger.deposit(sender, new TransactionAmount(1_000_000L));
    }

    private Transaction signedSend(NetworkParameters p, long amount, long fee, long nonce) {
        Transaction t = Transaction.of(sender, recipient, new TransactionAmount(amount), senderKey,
            new TransactionAmount(fee), 1234L, p.chainId(), nonce);
        t.sign(senderPrivate);
        return t;
    }

    private Block block(NetworkParameters p, long height, Transaction... transactions) {
        var b = BlockImpl.builder().id((int) height).timestamp(5000).difficulty(p.genesisDifficulty())
            .lastBlockHash(SHA256Hash.empty()).build();
        for (Transaction t : transactions) {
            b.addTransaction(t);
        }
        return b;
    }

    private Transaction coinbase(NetworkParameters p, long height) {
        return Transaction.of(miner, new TransactionAmount(p.miningReward(height)));
    }

    /** Mines the next block on {@code e} with an explicit timestamp. */
    private static void mineOnEngineAt(ChainEngine e, NetworkParameters p, PublicAddress miner, long ts) {
        long h = e.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(ts)
            .difficulty(e.difficulty()).lastBlockHash(e.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(p.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), p.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, e.addBlock(b));
    }

    @Test
    void retargetUsesRawBoundsBelowActivationAndMedianOf3FromActivation() {
        // Lookback 10 on a 1 s target, activation at boundary 20. Boundary 10 (< activation)
        // retargets under the LEGACY rule: inflating only the boundary block's timestamp by
        // +15 s/interval stretches the observed window (144 s vs 8 s desired) and crashes the
        // difficulty by the full step (12 → 8). Boundary 20 (>= activation) retargets under V2:
        // the median-of-3 bound ignores the single inflated point and the difficulty holds.
        NetworkParameters p = params.toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256)
            .genesisDifficulty(12).minDifficulty(4).maxDifficulty(64)
            .difficultyLookback(10).desiredBlockTimeSec(1).minBlockTimeSec(0)
            .maxFutureBlockTimeSec(1_000_000)
            .consensusV2Height(20)
            .build();
        AtomicLong clock = new AtomicLong(1_000_000L);
        ChainEngine e = ChainEngine.init(p, new InMemoryLedger(), new InMemoryChainStore(),
            new LedgerSnapshot("t", 0, p.chainId()), null, clock::get);
        long base = 1_000L;
        long inflatedTs = 0;
        for (int h = 2; h <= 10; h++) {
            long onTarget = base + h * 1000L;
            inflatedTs = h == 10 ? onTarget + 9 * 15_000L : onTarget;
            mineOnEngineAt(e, p, miner, inflatedTs);
        }
        assertEquals(8, e.difficulty(),
            "pre-activation boundary must retarget on raw timestamps: the inflated bound drags 12 → 8");
        // Timestamps must stay non-decreasing (engine rule), so blocks 11..19 continue on-target
        // FROM the inflated boundary, then boundary 20 is inflated the same way.
        for (int h = 11; h <= 20; h++) {
            long onTarget = inflatedTs + (h - 10) * 1000L;
            mineOnEngineAt(e, p, miner, h == 20 ? onTarget + 9 * 15_000L : onTarget);
        }
        assertEquals(8, e.difficulty(),
            "from the activation boundary the median-of-3 bound absorbs the same manipulation");

        // Mirror coherence: the header-only gate must accept the very same chain — it would
        // reject with WRONG_DIFFICULTY at the first boundary where the two sides disagreed.
        List<BlockHeader> candidates = new ArrayList<>();
        for (long h = 2; h <= 20; h++) {
            candidates.add(e.headerAt(h));
        }
        HeaderChain.Result r = HeaderChain.validate(p, e::headerAt, 1, candidates, clock.get() + 10_000_000L);
        assertTrue(r.valid(), "HeaderChain and ChainEngine must retarget identically across the "
            + "activation height, got " + r.rejection() + " @" + r.rejectedHeight());
    }

    @Test
    void feeFloorAppliesOnlyFromActivationHeight() {
        NetworkParameters gated = params.toBuilder().minFee(10L).consensusV2Height(ACTIVATION).build();

        // Below the activation height the legacy rule applies: no consensus floor, a zero-fee
        // transfer in a block is valid even though minFee is configured.
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(block(gated, 2, coinbase(gated, 2), signedSend(gated, 100, 0, 0)),
                ledger, h -> false, gated),
            "pre-activation block with fee 0 must stay valid (legacy rule)");

        // From the activation height the floor is a consensus rule.
        assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW,
            Executor.executeBlock(block(gated, ACTIVATION, coinbase(gated, ACTIVATION), signedSend(gated, 100, 0, 1)),
                ledger, h -> false, gated),
            "post-activation block with fee 0 under a floor of 10 must be rejected");
        assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW,
            Executor.executeBlock(block(gated, ACTIVATION, coinbase(gated, ACTIVATION), signedSend(gated, 100, 9, 1)),
                ledger, h -> false, gated),
            "strictly below the floor is rejected post-activation");
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(block(gated, ACTIVATION, coinbase(gated, ACTIVATION), signedSend(gated, 100, 10, 1)),
                ledger, h -> false, gated),
            "exactly at the floor passes post-activation");
    }

    @Test
    void zeroDepositCreatesWalletBelowActivationButNotFromIt() {
        NetworkParameters gated = params.toBuilder().consensusV2Height(ACTIVATION).build();

        // Legacy mode: a 0-amount deposit still creates the recipient wallet, as the chain
        // historically behaved.
        Block pre = block(gated, 2, coinbase(gated, 2), signedSend(gated, 0, 0, 0));
        assertEquals(ExecutionStatus.SUCCESS, Executor.executeBlock(pre, ledger, h -> false, gated));
        assertTrue(ledger.hasWallet(recipient), "legacy zero deposit must create the wallet");
        assertEquals(0L, ledger.getWalletValue(recipient).amount());

        // Its reorg rollback is exact and throw-free: the guarded (> 0) revertDeposit skips the
        // zero credit, leaving the created 0-balance key behind — a harmless phantom, since block
        // validity keys off balance, never key-presence.
        Executor.rollbackBlock(pre, ledger, null, 2, gated);
        assertTrue(ledger.hasWallet(recipient), "legacy rollback leaves the phantom 0-balance key (fork-safe)");
        assertEquals(0L, ledger.getWalletValue(recipient).amount());

        // The in-block abort path DID record the legacy zero deposit, so it replays
        // revertDeposit(recipient, 0) — against the just-created wallet that is a safe no-op.
        InMemoryLedger abortLedger = new InMemoryLedger();
        abortLedger.createWallet(sender);
        abortLedger.deposit(sender, new TransactionAmount(1_000_000L));
        Block aborting = block(gated, 2, coinbase(gated, 2),
            signedSend(gated, 0, 0, 0), signedSend(gated, 2_000_000L, 0, 1));
        assertEquals(ExecutionStatus.BALANCE_TOO_LOW,
            Executor.executeBlock(aborting, abortLedger, h -> false, gated),
            "abort must survive replaying a legacy revertDeposit(0)");
        assertFalse(abortLedger.hasWallet(miner) && abortLedger.getWalletValue(miner).amount() > 0,
            "abort rolled every applied op back");

        // V2 mode: a 0-amount deposit is a strict no-op.
        InMemoryLedger v2Ledger = new InMemoryLedger();
        v2Ledger.createWallet(sender);
        v2Ledger.deposit(sender, new TransactionAmount(1_000_000L));
        Block post = block(gated, ACTIVATION, coinbase(gated, ACTIVATION), signedSend(gated, 0, 0, 0));
        assertEquals(ExecutionStatus.SUCCESS, Executor.executeBlock(post, v2Ledger, h -> false, gated));
        assertFalse(v2Ledger.hasWallet(recipient), "post-activation zero deposit must not create the wallet");
    }

    @Test
    void activationHeightIsValidatedAndDefaultsToGenesis() {
        assertEquals(0L, params.consensusV2Height(), "testnet activates V2 from genesis");
        assertTrue(params.consensusV2(0));
        assertThrows(IllegalArgumentException.class,
            () -> params.toBuilder().consensusV2Height(-1).build());
        NetworkParameters gated = params.toBuilder().consensusV2Height(ACTIVATION).build();
        assertFalse(gated.consensusV2(ACTIVATION - 1));
        assertTrue(gated.consensusV2(ACTIVATION));
    }
}
