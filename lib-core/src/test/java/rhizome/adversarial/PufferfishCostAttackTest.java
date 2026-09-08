package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PowCosts;
import rhizome.crypto.SHA256Hash;

/**
 * A validator must never pay the memory-hard Pufferfish2 cost for a block a cheap, pre-PoW
 * structural check already refuses — {@code ChainEngine.addBlock} orders id, transaction count,
 * vote range, size, checkpoint, parent linkage, timestamps, difficulty, supply and merkle-root
 * checks strictly before the PoW gate (WHITEPAPER.md §3.5; CLAUDE.md "DoS armor, not style").
 * {@code BlockUnclesTest#blocksOwnPowIsVerifiedBeforeUncleWork} (CONS-10) locks that ordering by
 * status code alone, which cannot distinguish "checked first, essentially free" from "checked
 * first, but still expensive" — both return the same rejection either way. This suite pins the
 * same ordering with an actual, hugely amplified cost signal instead: with the PoW cost
 * parameters cranked well above genesis (so one real Pufferfish2 hash measurably costs on the
 * order of a hundred milliseconds — see the {@code EXPENSIVE_COSTS} comment), a structurally
 * invalid block must be rejected orders of magnitude faster than one whose only fault is its
 * nonce. That gap is only possible if the cheap-reject path never runs the memory-hard hash at
 * all, which a wrong-status-only assertion cannot show.
 */
class PufferfishCostAttackTest {

    /**
     * Genesis costs are {@code (cost_t=0, cost_m=8)}. Measured on reference hardware (see
     * {@code lib-crypto}'s {@code Pufferfish2Benchmark}, which reports ~18-28 ms/hash at
     * {@code cost_m=8}), {@code cost_m=11} costs on the order of 150-250 ms/hash — comfortably
     * past any JVM-noise floor (a method call, a few dozen SHA-256 calls over a one-transaction
     * block, a HashSet allocation) while still keeping this suite's total run time well under a
     * second: every block below is submitted with a single, directly-assigned nonce rather than
     * mined, so no brute-force PoW search ever runs — the only expensive operations this test
     * performs are the handful of genuine verification hashes it explicitly times.
     */
    private static final PowCosts EXPENSIVE_COSTS = new PowCosts(0, 11);
    private static final long START = 1_000_000_000L; // ms

    /**
     * Deliberately far above what mining is ever exercised at here (no nonce is ever searched
     * for — see {@link #EXPENSIVE_COSTS}): the only purpose of a high difficulty in this suite is
     * to make a single directly-assigned random nonce satisfy PoW by chance (probability
     * {@code 2^-DIFFICULTY}) so vanishingly unlikely that the "PoW-only-invalid" fixture below
     * never needs to pre-check its own nonce. A pre-check would call {@code verifyNonce} itself —
     * which populates {@code Crypto}'s bounded Pufferfish2 result cache (keyed on the exact
     * (target, nonce, costs) triple) — so the very {@code addBlock} call this test times would
     * then hit that cache instead of paying the memory-hard cost, silently collapsing the signal
     * this test exists to measure.
     */
    private static final int DIFFICULTY = 40;

    private NetworkParameters params;
    private AtomicLong clock;
    private ChainEngine engine;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.PUFFERFISH2)
            .powCostT(EXPENSIVE_COSTS.costT())
            .powCostM(EXPENSIVE_COSTS.costM())
            .genesisDifficulty(DIFFICULTY)
            .minDifficulty(DIFFICULTY)
            .build();
        clock = new AtomicLong(START);
        miner = PublicAddress.random();
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        engine = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot).clock(clock::get).build();
    }

    /** A structurally valid, correctly-merkled block template (nonce left at its zero default). */
    private BlockImpl template() {
        long height = engine.height() + 1;
        var b = BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty()))
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        return b;
    }

    /**
     * {@link #template()} with a single random nonce assigned and NEVER pre-verified (see
     * {@link #DIFFICULTY}): every other field, including the merkle root, stays valid, so this is
     * a block whose only fault is PoW, and the {@code addBlock} call that discovers that is the
     * first and only Pufferfish2 evaluation of this exact (target, nonce) pair.
     */
    private BlockImpl withUncheckedRandomNonce() {
        BlockImpl b = template();
        b.nonce(SHA256Hash.random());
        return b;
    }

    /**
     * POW-08 — Flood a validator with structurally invalid blocks (a forged merkle root, in this
     * proof) hoping the node still pays the full memory-hard PoW hash before refusing each one —
     * a volumetric CPU-exhaustion amplifier if the cheap check ran after PoW instead of before
     * it. Proven with an actual cost measurement rather than only a status code: rejecting a
     * block on the cheap pre-PoW merkle check is timed against rejecting one whose only fault is
     * its nonce, under PoW costs high enough that a single genuine Pufferfish2 hash is
     * unmistakably slow. The cheap path must stay reliably (>=20x) faster, which it can only do
     * by never running the memory-hard hash at all.
     */
    @Test
    void cheapGateRejectionIsOrdersOfMagnitudeFasterThanPayingForMemoryHardPow() {
        // Genesis itself occupies GenesisBlock.GENESIS_ID (1), so this is the height every
        // rejection below must leave untouched — not 0.
        long tipHeight = engine.height();

        // Warm up both paths once, untimed, so class loading / JIT compilation of the merkle,
        // serialization and Pufferfish2 code themselves never land inside a measurement. Each
        // warmup block is its own fresh (target, nonce) pair, so it cannot pre-populate the cache
        // entry any later timed call depends on.
        BlockImpl warmCheap = template();
        warmCheap.merkleRoot(SHA256Hash.random());
        assertEquals(ExecutionStatus.INVALID_MERKLE_ROOT, engine.addBlock(warmCheap));
        assertEquals(ExecutionStatus.INVALID_NONCE, engine.addBlock(withUncheckedRandomNonce()));
        assertEquals(tipHeight, engine.height(), "a rejected block must never move the chain (refusal is free)");

        long[] cheapNs = new long[7];
        for (int i = 0; i < cheapNs.length; i++) {
            BlockImpl bad = template();
            bad.merkleRoot(SHA256Hash.random()); // wrong on purpose: fails before PoW ever runs
            long t0 = System.nanoTime();
            ExecutionStatus status = engine.addBlock(bad);
            cheapNs[i] = System.nanoTime() - t0;
            assertEquals(ExecutionStatus.INVALID_MERKLE_ROOT, status);
        }

        long[] powNs = new long[3];
        for (int i = 0; i < powNs.length; i++) {
            BlockImpl bad = withUncheckedRandomNonce();
            long t0 = System.nanoTime();
            ExecutionStatus status = engine.addBlock(bad);
            powNs[i] = System.nanoTime() - t0;
            assertEquals(ExecutionStatus.INVALID_NONCE, status);
        }

        // Refusal must be free (spec.md protocol rule 3): every rejection above must have left
        // the chain exactly where it started, cheap-gate and PoW-gate rejections alike.
        assertEquals(tipHeight, engine.height(), "every submission above was rejected; the chain never moved");

        long cheapMedianNs = median(cheapNs);
        long powMedianNs = median(powNs);
        assertTrue(cheapMedianNs * 20 < powMedianNs,
            "cheap-gate rejection (" + (cheapMedianNs / 1e6) + " ms median) was not >=20x faster than "
                + "paying for the memory-hard PoW hash (" + (powMedianNs / 1e6) + " ms median) - "
                + "the pre-PoW gate ordering may have regressed");
    }

    private static long median(long[] samplesNs) {
        long[] sorted = samplesNs.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
