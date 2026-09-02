package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.BlockAssembler;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.HeaderChain;
import rhizome.core.blockchain.HonestBlockMiner;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.crypto.SHA256Hash;

/**
 * 006-emission-fork-activation, User Story 2: a node crosses the emission activation height, in
 * both directions, without forking. Shaped like {@code ConsensusV2GateTest} — one profile, one
 * {@code ACTIVATION} constant well above genesis, so below/at/above assertions are meaningful
 * (unlike {@code CurveActiveNetwork.curveActiveTestnet()}'s own default, which activates from
 * height 1 and so never exercises a real boundary).
 */
class EmissionActivationGateTest {

    private static final long ACTIVATION = 20L;

    private static final NetworkParameters PARAMS = CurveActiveNetwork.curveActiveTestnet()
        .toBuilder().emissionCurveHeight(ACTIVATION).build();

    /** Mines and applies an honest next block: correct on both sides of the boundary, since
     *  {@code miningReward(height, parentSupply)} dispatches on the height being built. */
    private static BlockImpl mineOnto(NetworkParameters params, ChainEngine engine, AtomicLong clock,
            PublicAddress miner) {
        long height = engine.height() + 1;
        long parentSupply = engine.headerAt(engine.height()).supply();
        BlockImpl b = candidateWithReward(params, engine, clock, miner,
            params.miningReward(height, parentSupply));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        return b;
    }

    /** A mined (real PoW), unapplied candidate carrying the CORRECT honest committed supply but an
     *  explicit, possibly-wrong coinbase {@code reward} — isolates the coinbase-exactness check
     *  from the supply-identity check (mirrors {@code MinerRevenueFloorAttackTest#coinbaseBlock}). */
    private static BlockImpl candidateWithReward(NetworkParameters params, ChainEngine engine,
            AtomicLong clock, PublicAddress miner, long reward) {
        // Built by hand (not via HonestBlockMiner) deliberately: the stamp dry-run would refuse
        // the wrong-reward coinbase and leave the supply uncommitted, so the gate would report
        // INVALID_SUPPLY before the executor could report the INCORRECT_MINING_FEE this test
        // exists to pin. The supply here is the honest ceiling for the height; ONLY the coinbase
        // is wrong-rule.
        long height = engine.height() + 1;
        long ts = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(ts)
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty()))
            .build();
        b.addTransaction(coinbaseTx(miner, reward, ts));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** A coinbase-shaped transaction with an explicit, deterministic timestamp — {@code Transaction.of(to, amount)}
     *  stamps {@code System.currentTimeMillis()}, which would make two otherwise byte-identical
     *  blocks built moments apart hash differently (T013 needs true byte-identity). */
    private static Transaction coinbaseTx(PublicAddress miner, long amount, long timestamp) {
        return TransactionImpl.builder()
            .to(miner)
            .amount(new TransactionAmount(amount))
            .isTransactionFee(true)
            .timestamp(timestamp)
            .build();
    }

    private static ChainEngine bootEngine(NetworkParameters params, AtomicLong clock) {
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        return ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot).clock(clock::get).build();
    }

    // ---- T011: the boundary, both rules, both refusals ----

    @Test
    void theLastBlockBelowActivationIsPaidTheGeometricSubsidy() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ChainEngine engine = bootEngine(PARAMS, clock);
        PublicAddress miner = PublicAddress.random();

        for (long h = 2; h <= ACTIVATION - 2; h++) {
            mineOnto(PARAMS, engine, clock, miner);
        }
        assertEquals(ACTIVATION - 2, engine.height());

        // A curve-valued coinbase at ACTIVATION - 1 (still below activation) is wrong-rule. The
        // curve's raw value depends only on parentSupply, not on which height is passed, so
        // dispatching with height == ACTIVATION at this same parentSupply yields exactly the
        // (floored) value an attacker would forge one block early.
        long parentSupply = engine.headerAt(engine.height()).supply();
        long curveValue = PARAMS.miningReward(ACTIVATION, parentSupply);
        long geometricValue = PARAMS.miningReward(ACTIVATION - 1);
        assertNotEquals(geometricValue, curveValue,
            "sanity: the curve and geometric values must differ, or this test proves nothing");

        BlockImpl wrongRule = candidateWithReward(PARAMS, engine, clock, miner, curveValue);
        assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, engine.addBlock(wrongRule),
            "a curve-valued coinbase below activation must be rejected");
        assertEquals(ACTIVATION - 2, engine.height(), "the rejected block must not extend the chain");

        // The honest geometric block at ACTIVATION - 1 is accepted.
        BlockImpl honest = mineOnto(PARAMS, engine, clock, miner);
        assertEquals(ACTIVATION - 1, engine.height());
        assertEquals(geometricValue, honest.transactions().get(0).amount().amount(),
            "the last block below activation must pay exactly the geometric subsidy");
    }

    @Test
    void theFirstBlockAtActivationIsPaidTheCurveSubsidy() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ChainEngine engine = bootEngine(PARAMS, clock);
        PublicAddress miner = PublicAddress.random();

        for (long h = 2; h <= ACTIVATION - 1; h++) {
            mineOnto(PARAMS, engine, clock, miner);
        }
        assertEquals(ACTIVATION - 1, engine.height());

        // A geometric-valued (now stale) coinbase at ACTIVATION is wrong-rule.
        long parentSupply = engine.headerAt(engine.height()).supply();
        long geometricValue = PARAMS.miningReward(ACTIVATION);
        long curveValue = PARAMS.miningReward(ACTIVATION, parentSupply);
        assertNotEquals(geometricValue, curveValue,
            "sanity: the curve and geometric values must differ, or this test proves nothing");

        BlockImpl wrongRule = candidateWithReward(PARAMS, engine, clock, miner, geometricValue);
        assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, engine.addBlock(wrongRule),
            "a geometric-valued coinbase at the activation height must be rejected");
        assertEquals(ACTIVATION - 1, engine.height(), "the rejected block must not extend the chain");

        // The honest curve block at ACTIVATION is accepted.
        BlockImpl honest = mineOnto(PARAMS, engine, clock, miner);
        assertEquals(ACTIVATION, engine.height());
        assertEquals(curveValue, honest.transactions().get(0).amount().amount(),
            "the first block at activation must pay exactly the calibrated curve subsidy");
    }

    // ---- T012: reorg across the activation height ----

    /** A {@link PeerSource} backed by another engine (mirrors {@code SupplyCommitmentTest}). */
    private static final class EnginePeer implements PeerSource {
        private final ChainEngine engine;
        EnginePeer(ChainEngine engine) { this.engine = engine; }
        public long height() { return engine.height(); }
        public BigInteger totalWork() { return engine.totalWork(); }
        public SHA256Hash blockHash(long height) { return engine.blockAt(height).hash(); }
        public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= end; h++) {
                out.add(engine.blockAt(h));
            }
            return out;
        }
        public Block orphan(SHA256Hash hash) { return engine.orphanBlock(hash); }
    }

    @Test
    void aReorgAcrossTheActivationHeightPaysEachBlockByItsFinalPosition() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, PARAMS.chainId());
        ChainEngine local = ChainEngine.boot(PARAMS, TestNodeStores.inMemory(), snapshot)
            .clock(clock::get).build();

        // Shared prefix, well below activation.
        List<Block> prefix = new ArrayList<>();
        for (long h = 2; h <= ACTIVATION - 5; h++) {
            prefix.add(mineOnto(PARAMS, local, clock, PublicAddress.random()));
        }
        long forkHeight = local.height();
        long forkSupply = local.headerAt(forkHeight).supply();

        ChainEngine peer = ChainEngine.boot(PARAMS, TestNodeStores.inMemory(), snapshot)
            .clock(clock::get).build();
        for (Block b : prefix) {
            assertEquals(ExecutionStatus.SUCCESS, peer.addBlock(b));
        }
        assertEquals(forkHeight, peer.height());

        // Branch A (local): 3 more blocks, still below ACTIVATION.
        for (int i = 0; i < 3; i++) {
            mineOnto(PARAMS, local, clock, PublicAddress.random());
        }
        assertTrue(local.height() < ACTIVATION, "branch A must stay below activation");

        // Branch B (peer): 6 more blocks, crossing ACTIVATION.
        for (int i = 0; i < 6; i++) {
            mineOnto(PARAMS, peer, clock, PublicAddress.random());
        }
        assertTrue(peer.height() > ACTIVATION, "branch B must cross activation");
        assertTrue(peer.totalWork().compareTo(local.totalWork()) > 0, "the peer branch must be heavier");

        ChainSynchronizer.Result result = new ChainSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(ChainSynchronizer.Result.REORGED, result);
        assertEquals(peer.height(), local.height());
        assertEquals(peer.tipHash(), local.tipHash());

        // Every re-applied block, on both sides of the boundary, pays and commits exactly by the
        // rule of the height it finally occupies.
        long running = forkSupply;
        for (long h = forkHeight + 1; h <= local.height(); h++) {
            Block block = local.blockAt(h);
            long expectedReward = PARAMS.miningReward(h, running);
            assertEquals(expectedReward, block.transactions().get(0).amount().amount(),
                "height " + h + " must pay the reward of the rule it finally occupies");
            running = Math.addExact(running,
                Issuance.minted(PARAMS, h, running, block.difficulty(), block.uncles()));
            assertEquals(running, local.headerAt(h).supply(),
                "height " + h + " committed supply must equal the independently recomputed issuance sum");
        }
    }

    // ---- T013: below-activation prefix re-validates forever, byte-identical ----

    @Test
    void blocksBelowActivationReValidateUnderTheGeometricRuleForever() {
        NetworkParameters neverActivating = PARAMS.toBuilder().emissionCurveHeight(0L).build();
        PublicAddress miner = PublicAddress.of("00" + "AB".repeat(24));

        AtomicLong clockActivated = new AtomicLong(1_000_000L);
        ChainEngine activated = bootEngine(PARAMS, clockActivated);
        AtomicLong clockNever = new AtomicLong(1_000_000L);
        ChainEngine never = bootEngine(neverActivating, clockNever);

        for (long h = 2; h <= ACTIVATION - 1; h++) {
            mineOnto(PARAMS, activated, clockActivated, miner);
            mineOnto(neverActivating, never, clockNever, miner);
        }

        for (long h = 1; h <= ACTIVATION - 1; h++) {
            assertEquals(never.blockAt(h).hash(), activated.blockAt(h).hash(),
                "height " + h + "'s hash must be byte-identical regardless of a later-scheduled "
                    + "activation height that never governed this block");
        }

        // The activated profile must accept a prefix built entirely under the never-activating
        // twin -- both compute the identical geometric rule below the boundary.
        List<BlockHeader> candidates = new ArrayList<>();
        for (long h = 2; h <= ACTIVATION - 1; h++) {
            candidates.add(never.headerAt(h));
        }
        HeaderChain.Result result = HeaderChain.validate(PARAMS, never::headerAt, 1, candidates,
            clockNever.get() + 10_000_000L);
        assertTrue(result.valid(), "a below-activation prefix must re-validate under the activated "
            + "profile unchanged, got " + result.rejection() + " @" + result.rejectedHeight());
    }

    // ---- T014: supply commitment is independent of the activation height ----

    @Test
    void theSupplyCommitmentRuleIsIndependentOfTheActivationHeight() {
        // (a) A block below ACTIVATION still commits supply under the unchanged accounting
        // identity on a curve-scheduled profile -- the field is not forbidden below the height.
        AtomicLong clock = new AtomicLong(1_000_000L);
        ChainEngine engine = bootEngine(PARAMS, clock);
        PublicAddress miner = PublicAddress.random();
        BlockImpl belowActivation = mineOnto(PARAMS, engine, clock, miner);
        assertTrue(belowActivation.supply() >= 0, "a block below activation must still commit supply");
        assertEquals(engine.headerAt(1).supply() + PARAMS.miningReward(2), belowActivation.supply());

        // (b) Genesis commits S0 even on a profile whose activation height is 1.
        NetworkParameters activeFromGenesis = PARAMS.toBuilder().emissionCurveHeight(1L).build();
        PublicAddress funded = PublicAddress.random();
        long s0 = 5_000L;
        LedgerSnapshot funding = new LedgerSnapshot("t", 0, activeFromGenesis.chainId());
        funding.put(funded, new TransactionAmount(s0));
        ChainEngine fromGenesis = ChainEngine.boot(activeFromGenesis, TestNodeStores.inMemory(), funding)
            .clock(clock::get).build();
        assertEquals(s0, fromGenesis.headerAt(1).supply(),
            "genesis must commit S0 exactly, regardless of the activation height");

        // (c) A block that drops the commitment is rejected on both sides of the boundary.
        long tsBelow = clock.addAndGet(PARAMS.desiredBlockTimeSec() * 1000L);
        var droppedBelow = (BlockImpl) BlockImpl.builder().id((int) (engine.height() + 1))
            .timestamp(tsBelow).difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .supply(BlockImpl.SUPPLY_ABSENT).build();
        droppedBelow.addTransaction(Transaction.of(miner,
            new TransactionAmount(PARAMS.miningReward(engine.height() + 1, engine.headerAt(engine.height()).supply()))));
        var tree1 = new MerkleTree();
        tree1.setItems(droppedBelow.transactions());
        droppedBelow.merkleRoot(tree1.getRootHash());
        droppedBelow.nonce(Miner.mineNonce(droppedBelow.hash(), droppedBelow.difficulty(), PARAMS.powAlgorithm()));
        assertEquals(ExecutionStatus.INVALID_SUPPLY, engine.addBlock(droppedBelow),
            "dropping the commitment below activation must be rejected");

        for (long h = engine.height() + 1; h <= ACTIVATION; h++) {
            mineOnto(PARAMS, engine, clock, miner);
        }
        assertTrue(engine.height() >= ACTIVATION, "engine must now be at/above activation");

        long tsAbove = clock.addAndGet(PARAMS.desiredBlockTimeSec() * 1000L);
        var droppedAbove = (BlockImpl) BlockImpl.builder().id((int) (engine.height() + 1))
            .timestamp(tsAbove).difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .supply(BlockImpl.SUPPLY_ABSENT).build();
        droppedAbove.addTransaction(Transaction.of(miner,
            new TransactionAmount(PARAMS.miningReward(engine.height() + 1, engine.headerAt(engine.height()).supply()))));
        var tree2 = new MerkleTree();
        tree2.setItems(droppedAbove.transactions());
        droppedAbove.merkleRoot(tree2.getRootHash());
        droppedAbove.nonce(Miner.mineNonce(droppedAbove.hash(), droppedAbove.difficulty(), PARAMS.powAlgorithm()));
        assertEquals(ExecutionStatus.INVALID_SUPPLY, engine.addBlock(droppedAbove),
            "dropping the commitment at/above activation must be rejected");
    }

    // ---- T015: the producer at ACTIVATION - 1 builds for ACTIVATION ----

    @Test
    void theProducerAtTheBlockBeforeActivationBuildsForTheActivatedHeight() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ChainEngine engine = bootEngine(PARAMS, clock);
        PublicAddress miner = PublicAddress.random();

        for (long h = 2; h <= ACTIVATION - 1; h++) {
            mineOnto(PARAMS, engine, clock, miner);
        }
        assertEquals(ACTIVATION - 1, engine.height());

        MemPool mempool = new MemPool(PARAMS, new SignatureVerifier(), engine, 100);
        Block candidate = BlockAssembler.assemble(engine, mempool, miner, clock.get());

        assertEquals(ACTIVATION, candidate.id(),
            "the producer at ACTIVATION - 1 must build a candidate for height ACTIVATION");
        long expectedCurveReward = PARAMS.miningReward(ACTIVATION, engine.headerAt(engine.height()).supply());
        assertEquals(expectedCurveReward, candidate.transactions().get(0).amount().amount(),
            "the candidate for the activated height must carry the curve-derived coinbase, judged "
                + "by the height being built for -- not the confirmed tip");

        var b = (BlockImpl) candidate;
        engine.stampStateRoot(b); // producer order: assemble -> dry-run stamp (exact supply) -> mine
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b),
            "the mined candidate must be accepted");
        assertEquals(ACTIVATION, engine.height());
    }
}
