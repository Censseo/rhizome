package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.Burn;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainEngineTestAccess;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;

import static rhizome.crypto.Crypto.generateKeyPairTyped;

/**
 * US2 (009-native-coin-burn): apply and rollback are exact inverses at every reorg depth up to
 * {@code maxReorgDepth}. The burn is ONE withdrawal recorded in the existing ledger undo journal,
 * so the primary path reverses it with everything else; the journal-less fallback re-derives the
 * identical amount from values the block already carries, with {@code rollbackBlock} gaining no
 * public parameter (the uncle/nephew reversal's established pattern).
 */
class BurnReversalTest {

    /** Curve-active with a funded genesis far above the live target: every fee-carrying block burns. */
    private NetworkParameters params = CurveActiveNetwork.curveActiveTestnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final rhizome.crypto.Crypto.KeyPair pair = generateKeyPairTyped();
    private final PublicKey senderKey = pair.publicKey();
    private final PrivateKey senderPrivate = pair.privateKey();
    private final PublicAddress sender = PublicAddress.of(senderKey);
    private long nonceCounter;

    /** A shared funded snapshot so two engines start ledger-identical. */
    private LedgerSnapshot fundedSnapshot() {
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(20_000_000L));
        return snapshot;
    }

    private ChainEngine boot(Ledger ledger, InMemoryChainStore store, LedgerSnapshot snapshot) {
        return ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
    }

    /** A fee-carrying block whose supply is stamped from the burn the TEST computes from the flow. */
    private Block mineBurning(ChainEngine engine, PublicAddress miner, long fee) {
        return mineBurning(engine, miner, fee, null);
    }

    /** As above, optionally referencing one uncle (the supply stamp accounts for its issuance). */
    private Block mineBurning(ChainEngine engine, PublicAddress miner, long fee, UncleRef uncle) {
        long height = engine.height() + 1;
        long parentSupply = engine.headerAt(engine.height()).supply();
        List<UncleRef> refs = uncle == null ? List.of() : List.of(uncle);
        long minted = Issuance.minted(params, height, parentSupply, engine.difficulty(), refs);
        long debt = Burn.debt(params, height, parentSupply, minted);
        long burned = Burn.burned(params, height, fee, debt);
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .uncles(new ArrayList<>(refs))
            .supply(parentSupply + minted - burned)
            .build();
        // The coinbase is the block reward ALONE (uncle/nephew rewards are separate deposits);
        // the supply stamp carries the full minted term including the uncle issuance.
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height, parentSupply))));
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(0),
            senderKey, new TransactionAmount(fee), clock.get(), params.chainId(), nonceCounter++);
        t.sign(senderPrivate);
        b.addTransaction(t);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    private Block mineCoinbaseOnly(ChainEngine engine, PublicAddress miner) {
        long height = engine.height() + 1;
        long parentSupply = engine.headerAt(engine.height()).supply();
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .supply(rhizome.core.blockchain.SupplyStamp.next(engine, height, engine.difficulty()))
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height, parentSupply))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    private long burnedOf(Block b, long parentSupply) {
        long minted = Issuance.minted(params, b.id(), parentSupply, b.difficulty(), b.uncles());
        return rhizome.core.blockchain.Burn.rederive(parentSupply, minted, b.supply());
    }

    /** The whole ledger as a comparable value (the LedgerReversalExactnessTest discipline). */
    private Map<String, Long> balancesOf(ChainEngine engine, PublicAddress... addresses) {
        Map<String, Long> out = new HashMap<>();
        for (PublicAddress a : addresses) {
            out.put(a.toHexString(), engine.confirmedBalance(a));
        }
        return out;
    }

    @Test
    void aBurnReversesExactlyWhenItsBlockIsPopped() {
        InMemoryChainStore store = new InMemoryChainStore();
        ChainEngine engine = boot(new rhizome.core.ledger.InMemoryLedger(), store, fundedSnapshot());
        PublicAddress miner = PublicAddress.random();
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineCoinbaseOnly(engine, miner)));

        long supplyBefore = engine.headerAt(engine.height()).supply();
        Block burning = mineBurning(engine, miner, 1_000);
        long parentSupply = engine.headerAt(engine.height()).supply();
        long expectedBurned = burnedOf(burning, parentSupply);
        assertTrue(expectedBurned > 0, "sanity: the block really burns");
        Map<String, Long> beforeBurn = balancesOf(engine, sender, miner);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(burning));
        Map<String, Long> afterBurn = balancesOf(engine, sender, miner);
        assertEquals(supplyBefore + mintedDelta(parentSupply, burning) - expectedBurned,
            engine.headerAt(engine.height()).supply());

        // The pop: the burn must unwind exactly through the ledger undo journal (T040 — no new
        // journal, no feature-specific code; the withdrawal is one WITHDRAW op among the rest).
        ChainEngineTestAccess.popBlock(engine);
        assertEquals(supplyBefore, engine.headerAt(engine.height()).supply(),
            "the popped tip restores the parent's committed supply");
        assertEquals(beforeBurn, balancesOf(engine, sender, miner),
            "popping the burning block restores every balance exactly — the burn unwinds with it");
    }

    private long mintedDelta(long parentSupply, Block atHeight) {
        return Issuance.minted(params, atHeight.id(), parentSupply, atHeight.difficulty(),
            atHeight.uncles());
    }

    @Test
    void aReorgAcrossBurningBlocksRestoresSupplyAndLedgerExactly() {
        // SC-003: swept across depths 1..maxReorgDepth (120). Each depth: two branches of the
        // SAME length from a common fork, the local suffix coinbase-only, the peer suffix
        // fee-carrying (and therefore burning) and heavier via one included uncle — the local
        // node adopts the burning branch, and its ledger must end byte-identical to the engine
        // that applied that branch natively (the peer itself).
        int maxDepth = params.maxReorgDepth();
        assertEquals(120, maxDepth, "the sweep must cover the shipped finality window");
        for (int depth = 1; depth <= maxDepth; depth++) {
            nonceCounter = 0; // every depth boots a fresh peer: the fee sender restarts at nonce 0
            InMemoryChainStore localStore = new InMemoryChainStore();
            ChainEngine local = boot(new rhizome.core.ledger.InMemoryLedger(), localStore,
                fundedSnapshot());
            InMemoryChainStore peerStore = new InMemoryChainStore();
            ChainEngine peer = boot(new rhizome.core.ledger.InMemoryLedger(), peerStore,
                fundedSnapshot());

            // Shared prefix: one coinbase-only block (the fork point is not genesis).
            PublicAddress miner = PublicAddress.random();
            Block prefix = mineCoinbaseOnly(local, miner);
            assertEquals(ExecutionStatus.SUCCESS, local.addBlock(prefix));
            assertEquals(ExecutionStatus.SUCCESS, peer.addBlock(prefix));
            long forkHeight = local.height();
            long forkSupply = local.headerAt(forkHeight).supply();

            // An orphan sibling of the prefix block itself (height forkHeight, parent genesis),
            // registered on BOTH engines — the peer's first suffix block (height forkHeight+1)
            // references it, making that branch heavier at equal length (the same-shape setup
            // SupplyCommitmentTest's uncle round-trip uses).
            var orphan = (BlockImpl) BlockImpl.builder()
                .id((int) forkHeight)
                .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
                .difficulty(params.minDifficulty())
                .lastBlockHash(local.blockAt(forkHeight - 1).hash())
                .supply(BlockImpl.SUPPLY_ABSENT)
                .build();
            PublicAddress orphanMiner = PublicAddress.random();
            orphan.addTransaction(Transaction.of(orphanMiner,
                new TransactionAmount(params.miningReward(forkHeight))));
            var orphanTree = new MerkleTree();
            orphanTree.setItems(orphan.transactions());
            orphan.merkleRoot(orphanTree.getRootHash());
            orphan.nonce(Miner.mineNonce(orphan.hash(), orphan.difficulty(), params.powAlgorithm()));
            local.registerOrphan(orphan);
            peer.registerOrphan(orphan);
            UncleRef ref = new UncleRef(orphan.hash(), orphan.difficulty(), orphanMiner);

            // Branch A — local: `depth` coinbase-only blocks.
            for (int i = 0; i < depth; i++) {
                assertEquals(ExecutionStatus.SUCCESS, local.addBlock(mineCoinbaseOnly(local, miner)));
            }

            // Branch B — peer: `depth` fee-carrying (burning) blocks, the first with the uncle.
            for (int i = 0; i < depth; i++) {
                Block burning = mineBurning(peer, miner, 500, i == 0 ? ref : null);
                assertEquals(ExecutionStatus.SUCCESS, peer.addBlock(burning));
            }
            assertTrue(peer.totalWork().compareTo(local.totalWork()) > 0,
                "the peer branch must be heavier at depth " + depth);

            ChainSynchronizer.Result result = new ChainSynchronizer(local).syncFrom(new EnginePeer(peer));
            assertEquals(ChainSynchronizer.Result.REORGED, result, "depth " + depth);
            assertEquals(peer.height(), local.height(), "depth " + depth);
            assertEquals(peer.tipHash(), local.tipHash(), "depth " + depth);

            // Supply and ledger byte-identical to the natively-applied branch, at every depth.
            assertEquals(forkSupply, local.headerAt(forkHeight).supply(), "depth " + depth);
            for (long h = forkHeight + 1; h <= peer.height(); h++) {
                assertEquals(peer.headerAt(h).supply(), local.headerAt(h).supply(),
                    "depth " + depth + ": adopted supply at height " + h);
            }
            assertEquals(peer.confirmedBalance(sender), local.confirmedBalance(sender),
                "depth " + depth + ": the fee sender's balance is exactly the native one");
            assertEquals(peer.confirmedBalance(miner), local.confirmedBalance(miner),
                "depth " + depth + ": the miner's balance (burns included) is exactly the native one");
        }
    }

    @Test
    void aPoppedAndReAddedBurningBlockDestroysTheIdenticalAmount() {
        // The trusted-restore path: the SAME block object, popped and re-added, must burn the
        // identical amount — the burn is a pure function of the block's committed values, never
        // of mutable chain state.
        InMemoryChainStore store = new InMemoryChainStore();
        ChainEngine engine = boot(new rhizome.core.ledger.InMemoryLedger(), store, fundedSnapshot());
        PublicAddress miner = PublicAddress.random();
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineCoinbaseOnly(engine, miner)));

        Block burning = mineBurning(engine, miner, 1_000);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(burning));
        long supplyWithBurn = engine.headerAt(engine.height()).supply();
        Map<String, Long> balancesWithBurn = balancesOf(engine, sender, miner);

        ChainEngineTestAccess.popBlock(engine);
        long supplyAfterPop = engine.headerAt(engine.height()).supply();

        // Re-add the identical block object (the trusted-restore path: a proven nonce, the same
        // header, the same everything).
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(burning));
        assertEquals(supplyWithBurn, engine.headerAt(engine.height()).supply(),
            "the re-added block commits the identical burned amount");
        assertEquals(balancesWithBurn, balancesOf(engine, sender, miner),
            "the ledger is byte-identical after pop + re-add");
        assertEquals(supplyAfterPop + mintedDelta(supplyAfterPop, burning)
                - burnedOf(burning, supplyAfterPop),
            engine.headerAt(engine.height()).supply());
    }

    @Test
    void aBlockThatBurnsOnOneBranchBurnsNothingWhereItSitsBelowActivation() {
        // The same fee flow is burning where the curve governs and inert one height below the
        // activation boundary: the rule is judged by the block's OWN height, so a reorg that
        // moves a block across the boundary re-judges it — it never carries a burn across.
        NetworkParameters late = CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3)
            .emissionCurveHeight(3) // heights 1-2 geometric, height >= 3 curve-governed
            .build();
        NetworkParameters originalParams = params;
        try {
            params = late;
            InMemoryChainStore store = new InMemoryChainStore();
            ChainEngine engine = boot(new rhizome.core.ledger.InMemoryLedger(), store, fundedSnapshot());
            PublicAddress miner = PublicAddress.random();
            // Height 2 (curve-inactive): a fee-carrying block, stamped with NO burn. Built by
            // the standard burning-block helper but committed at a height the curve does not
            // govern: its supply stamp is the pre-burn form, and the executor must agree.
            Block below = mineCoinbaseOnlyWithFee(engine, miner, 1_000);
            long parent = engine.headerAt(1).supply();
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(below));
            assertEquals(parent + Issuance.minted(late, 2, parent, engine.difficulty(), List.of()),
                engine.headerAt(2).supply(),
                "below activation the fee flow burns nothing and the identity is the pre-burn form");

            // Height 3 (the activation boundary): the SAME flow now burns.
            Block atActivation = mineBurning(engine, miner, 1_000);
            assertTrue(burnedOf(atActivation, engine.headerAt(2).supply()) > 0,
                "the same flow burns once the curve governs");
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(atActivation));
        } finally {
            params = originalParams;
        }
    }

    /** A coinbase-plus-one-fee block whose supply is stamped WITHOUT a burn (curve-inactive height). */
    private Block mineCoinbaseOnlyWithFee(ChainEngine engine, PublicAddress miner, long fee) {
        long height = engine.height() + 1;
        long parentSupply = engine.headerAt(engine.height()).supply();
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .supply(parentSupply + Issuance.minted(params, height, parentSupply, engine.difficulty(), List.of()))
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height, parentSupply))));
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(0),
            senderKey, new TransactionAmount(fee), clock.get(), params.chainId(), nonceCounter++);
        t.sign(senderPrivate);
        b.addTransaction(t);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void theJournalLessFallbackRederivesTheIdenticalBurnWithoutANewArgument() {
        // T042: a ledger that keeps NO journal (the Ledger interface defaults) forces
        // undoBlock's re-derivation fallback. The burn must be recovered exactly — from the
        // block's own committed values and the parent supply the popping engine already holds —
        // with the public rollbackBlock signature untouched.
        InMemoryChainStore store = new InMemoryChainStore();
        ChainEngine engine = boot(new JournalLessLedger(), store, fundedSnapshot());
        PublicAddress miner = PublicAddress.random();
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineCoinbaseOnly(engine, miner)));

        long supplyBefore = engine.headerAt(engine.height()).supply();
        long senderBefore = engine.confirmedBalance(sender);
        long minerBefore = engine.confirmedBalance(miner);

        Block burning = mineBurning(engine, miner, 1_000);
        long parentSupply = engine.headerAt(engine.height()).supply();
        long expectedBurned = burnedOf(burning, parentSupply);
        assertTrue(expectedBurned > 0);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(burning));
        assertEquals(supplyBefore - expectedBurned + mintedDelta(parentSupply, burning),
            engine.headerAt(engine.height()).supply());

        // Pop through the journal-less ledger: the journal returns false, the fallback
        // re-derives the identical burn, and the ledger is restored exactly.
        ChainEngineTestAccess.popBlock(engine);
        assertEquals(supplyBefore, engine.headerAt(engine.height()).supply());
        assertEquals(senderBefore, engine.confirmedBalance(sender),
            "the sender is back to its pre-burn balance: the fee returned exactly");
        assertEquals(minerBefore, engine.confirmedBalance(miner),
            "the miner is back to exactly the coinbase-only balance: the re-derived burn "
                + "is the identical amount the forward pass withdrew");
    }

    /**
     * A ledger that keeps no undo journal — every mutation is final until re-derived by hand.
     * The shape the Ledger interface's defaults describe (the genesis ledger, bare stores).
     */
    private static final class JournalLessLedger implements Ledger {
        private final Map<PublicAddress, Long> balances = new HashMap<>();

        @Override public boolean hasWallet(PublicAddress wallet) {
            return balances.containsKey(wallet);
        }

        @Override public void createWallet(PublicAddress wallet) {
            balances.putIfAbsent(wallet, 0L);
        }

        @Override public TransactionAmount getWalletValue(PublicAddress wallet) {
            return new TransactionAmount(balances.getOrDefault(wallet, 0L));
        }

        @Override public void withdraw(PublicAddress wallet, TransactionAmount amt) {
            long next = balances.getOrDefault(wallet, 0L) - amt.amount();
            if (next < 0) {
                throw new rhizome.core.ledger.LedgerException("insufficient balance");
            }
            balances.put(wallet, next);
        }

        @Override public void revertSend(PublicAddress wallet, TransactionAmount amt) {
            balances.put(wallet, balances.getOrDefault(wallet, 0L) + amt.amount());
        }

        @Override public void deposit(PublicAddress wallet, TransactionAmount amt) {
            balances.put(wallet, Math.addExact(balances.getOrDefault(wallet, 0L), amt.amount()));
        }

        @Override public void revertDeposit(PublicAddress wallet, TransactionAmount amt) {
            long next = balances.getOrDefault(wallet, 0L) - amt.amount();
            if (next < 0) {
                throw new rhizome.core.ledger.LedgerException("cannot revert deposit below zero");
            }
            balances.put(wallet, next);
        }

        @Override public void forEachBalance(java.util.function.ObjLongConsumer<PublicAddress> consumer) {
            balances.forEach((address, amount) -> consumer.accept(address, amount));
        }
    }

    /** A {@link PeerSource} backed by another engine (the SupplyCommitmentTest pattern). */
    private static final class EnginePeer implements PeerSource {
        private final ChainEngine engine;

        EnginePeer(ChainEngine engine) {
            this.engine = engine;
        }

        public long height() {
            return engine.height();
        }

        public java.math.BigInteger totalWork() {
            return engine.totalWork();
        }

        public SHA256Hash blockHash(long height) {
            return engine.blockAt(height).hash();
        }

        public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= end; h++) {
                out.add(engine.blockAt(h));
            }
            return out;
        }

        public Block orphan(SHA256Hash hash) {
            return engine.orphanBlock(hash);
        }
    }
}
