package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.block.HeaderWire;
import rhizome.core.blockchain.Burn;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.SHA256Hash;

import static rhizome.crypto.Crypto.generateKeyPairTyped;

/**
 * US1 (009-native-coin-burn): a curve-active block whose parent supply sits above the live
 * target destroys {@code min(⌊share × pool⌋, debt)} and commits the reduced supply. Every block
 * here is stamped by the TEST from the flows it created — so the executor's own independent pool
 * accumulation must agree exactly, or the identity check rejects the block. The claims are
 * proven by falsification, not by reading back what the code computed.
 */
class BurnExecutionTest {

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private InMemoryChainStore store;
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private ChainEngine engine;

    private PublicKey senderKey;
    private PrivateKey senderPrivate;
    private PublicAddress sender;
    private final PublicAddress recipient = PublicAddress.random();
    private final PublicAddress miner = PublicAddress.random();

    /** Boots the curve-active fixture with {@code fundedTotal} premined to the sender. */
    private void bootFunded(long fundedTotal) {
        params = CurveActiveNetwork.curveActiveTestnet(); // S* = 1_000_000, active from height 1
        var pair = generateKeyPairTyped();
        senderKey = pair.publicKey();
        senderPrivate = pair.privateKey();
        sender = PublicAddress.of(senderKey);
        ledger = new InMemoryLedger();
        store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(fundedTotal));
        engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
    }

    private Transaction send(long amount, long fee, long nonce) {
        Transaction t = Transaction.of(sender, recipient, new TransactionAmount(amount), senderKey,
            new TransactionAmount(fee), clock.get(), params.chainId(), nonce);
        t.sign(senderPrivate);
        return t;
    }

    private long parentSupply() {
        return engine.headerAt(engine.height()).supply();
    }

    /** A signed, mined candidate at the engine's next height with an explicit committed supply. */
    private Block candidate(List<Transaction> txs, long supply) {
        long height = engine.height() + 1;
        long parent = parentSupply();
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .supply(supply)
            .build();
        b.addTransaction(Transaction.of(miner,
            new TransactionAmount(params.miningReward(height, parent))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void aBlockAboveTheTargetDestroysTheCappedShareOfItsFlow() {
        bootFunded(4_000_000L); // genesis supply 4M, live target 1M: debt is huge
        long parent = parentSupply();
        assertEquals(4_000_000L, parent);
        long minted = params.miningReward(2, parent);
        long debt = Burn.debt(params, 2, parent, minted);
        assertTrue(debt > 3_000_000, "sanity: the fixture sits far above the live target");

        // Two fee-paying transfers: pool 1000, share 1/2 -> burned 500 (flow-limited).
        long pool = 1_000;
        long expectedBurned = Burn.burned(params, 2, pool, debt);
        assertEquals(500, expectedBurned);
        Block burning = candidate(List.of(send(0, 500, 0), send(0, 500, 1)),
            parent + minted - expectedBurned);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(burning));
        assertEquals(parent + minted - 500, engine.headerAt(2).supply(),
            "the committed supply is parent + minted - burned");

        // The miner was credited the subsidy, both fees, and lost exactly the burn.
        assertEquals(minted + pool - 500, ledger.getWalletValue(miner).amount());
        // The recipient received the amounts (zero here) but no fee was ever theirs.
        assertEquals(0, ledger.balanceOrZero(recipient));
    }

    @Test
    void aBlockWhoseShareExceedsTheDebtDestroysExactlyTheDebt() {
        bootFunded(1_000_003L); // three base units above the live target
        long parent = parentSupply();
        long minted = params.miningReward(2, parent);
        long debt = Burn.debt(params, 2, parent, minted);
        assertEquals(3 + minted, debt, "the debt is the three-unit excess plus the minted term");

        // Four fat fees: pool 4000, share 2000 — the DEBT (803) is the lesser term, so the
        // supply must land EXACTLY on S*(h). Landing below it is illegal (B1); landing on it
        // is the legal terminal state of a debt-capped burn.
        long pool = 4_000;
        long expectedBurned = Burn.burned(params, 2, pool, debt);
        assertEquals(debt, expectedBurned, "the debt caps the burn");
        Block burning = candidate(List.of(send(0, 1_000, 0), send(0, 1_000, 1),
            send(0, 1_000, 2), send(0, 1_000, 3)), parent + minted - expectedBurned);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(burning));
        assertEquals(params.supplyTargetAt(2), engine.headerAt(2).supply(),
            "a debt-capped burn lands the supply exactly on the live target");
        assertEquals(params.supplyTargetAt(2), params.supplyTargetAt(3),
            "sanity: the fixture's target is not decaying under this profile");
    }

    @Test
    void anEmptyBlockAboveTheTargetDestroysNothing() {
        bootFunded(4_000_000L);
        long parent = parentSupply();
        long minted = params.miningReward(2, parent);
        long debt = Burn.debt(params, 2, parent, minted);
        assertTrue(debt > 0, "sanity: the debt exists — it is the pool that is zero");
        assertEquals(0, Burn.burned(params, 2, 0, debt), "an empty pool burns nothing");
        Block empty = candidate(List.of(), parent + minted);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(empty));
        assertEquals(parent + minted, engine.headerAt(2).supply(),
            "an empty block's supply identity is the pre-burn one, exactly");
    }

    @Test
    void theSupplyIdentityIsUnchangedAtOrBelowTheTargetAndBelowActivation() {
        // (a) At/below the target: debt 0, so even fee flow destroys nothing and the identity is
        // the pre-burn one.
        bootFunded(500_000L);
        long parent = parentSupply();
        long minted = params.miningReward(2, parent);
        assertEquals(0, Burn.debt(params, 2, parent, minted), "below the target nothing is owed");
        Block belowTarget = candidate(List.of(send(0, 500, 0)), parent + minted);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(belowTarget));
        assertEquals(parent + minted, engine.headerAt(2).supply());

        // (b) Below activation: the curve never governs on testnet — the identity is the pure
        // 002 form, fees or no fees.
        params = NetworkParameters.testnet();
        var pair = generateKeyPairTyped();
        senderKey = pair.publicKey();
        sender = PublicAddress.of(senderKey);
        senderPrivate = pair.privateKey();
        ledger = new InMemoryLedger();
        store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(2_000_000L));
        engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
        long geometricParent = parentSupply();
        long geometricMinted = params.miningReward(2);
        Block inactive = candidate(List.of(send(0, 500, 0)), geometricParent + geometricMinted);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(inactive));
        assertEquals(geometricParent + geometricMinted, engine.headerAt(2).supply(),
            "below activation the supply identity is the pre-burn form, exactly");
    }

    @Test
    void theSupplyIdentityHoldsAtEveryHeightOfALongChainAcrossTheCrossing() {
        // SC-002: at least 10 000 blocks spanning the target crossing, the identity asserted at
        // EVERY height with zero exceptions. The first blocks are mined through the REAL engine
        // (real executor, real pool accumulation, the crossing reached honestly); the run then
        // continues as an arithmetic simulation driven through the same Burn functions, with a
        // deterministic per-block fee flow so the burn term is live at every height.
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(rhizome.crypto.PowAlgorithm.SHA256)
            .genesisDifficulty(3).minDifficulty(3).maxDifficulty(16)
            .supplyTarget(4_000L).emissionCoefficient(3_000L).emissionTableSteps(2)
            .emissionCurveHeight(1)
            .build();
        ledger = new InMemoryLedger();
        store = new InMemoryChainStore();
        engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store),
                new LedgerSnapshot("t", 0, params.chainId()))
            .clock(clock::get)
            .build();

        long previous = parentSupply(); // genesis supply 0
        boolean crossed = false;
        // Real phase: empty coinbase-only blocks through the full engine, across S* = 4000.
        while (engine.height() < 8) {
            long height = engine.height() + 1;
            long minted = Issuance.minted(params, height, previous, engine.difficulty(), List.of());
            long debt = Burn.debt(params, height, previous, minted);
            long burned = Burn.burned(params, height, 0, debt); // empty block: pool 0
            Block b = candidate(List.of(), previous + minted - burned);
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b),
                "real chain broke the identity at height " + height);
            assertEquals(previous + minted - burned, engine.headerAt(height).supply());
            long committed = engine.headerAt(height).supply();
            assertTrue(committed >= Math.min(previous + minted, params.supplyTargetAt(height)),
                "the committed supply may never land below the live target once the ceiling "
                    + "passes it (B1), height " + height);
            if (committed >= params.supplyTargetAt(height)) {
                crossed = true;
            }
            previous = committed;
        }
        assertTrue(crossed, "the real phase must genuinely cross the live target");

        // Simulated phase: 10 000 further blocks, each with a live deterministic flow.
        long supply = previous;
        for (long h = engine.height() + 1; h <= engine.height() + 10_000; h++) {
            long minted = Issuance.minted(params, h, supply, params.minDifficulty(), List.of());
            long pool = 37 + (h * 91) % 960; // deterministic, always positive, never huge
            long debt = Burn.debt(params, h, supply, minted);
            long burned = Burn.burned(params, h, pool, debt);
            assertTrue(burned >= 0 && burned <= debt, "bound violated at height " + h);
            long next = supply + minted - burned;
            assertEquals(burned, Burn.rederive(supply, minted, next),
                "the header-recoverable burn must reproduce the simulated one at height " + h);
            assertTrue(next >= params.supplyTargetAt(h),
                "supply fell below the live target at height " + h + ": " + next);
            supply = next;
        }
        assertTrue(supply > previous, "the long run must have moved supply");
    }

    // ---- BurnNoWireChangeTest lives here as the FR-014 proof (T023) ----

    @Test
    void theBurnAddsNoHeaderFieldAndLeavesThePreimageAndBothCodecsUnchanged() {
        // FR-014, PROVEN rather than assumed, in three parts:
        // (1) the wire prefix is still the 002-era width — the burn added no bytes;
        assertEquals(160, HeaderWire.PREFIX_BYTES,
            "the burn adds arithmetic, never bytes — the header prefix must not move");

        // (2) the binary header codec round-trips a maximal preimage identically, and the
        // preimage fold rule is unchanged: a committed supply still enters the hash (a one-unit
        // change in the committed supply changes the hash), an absent one is still folded in
        // only-when-set and both shapes occupy the same fixed prefix width.
        SHA256Hash hash = SHA256Hash.of(new byte[32]);
        var committing = new rhizome.core.block.BlockHeader(123, 456L, 16, 1, hash, hash, hash,
            hash, 2, 4_000_300L, List.of());
        var decoded = HeaderCodec.decode(HeaderCodec.encode(committing));
        assertEquals(committing, decoded, "a committing (burn-carrying-shape) header round-trips");
        var withoutSupply = new rhizome.core.block.BlockHeader(123, 456L, 16, 1, hash, hash, hash,
            hash, 2, -1L, List.of());
        assertEquals(-1L, HeaderCodec.decode(HeaderCodec.encode(withoutSupply)).supply(),
            "absent still round-trips as absent (-1), never silently zeroed");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(HeaderWire.PREFIX_BYTES);
        HeaderWire.writePrefix(buffer, new HeaderWire.Prefix(123, 456L, 16, 1, hash, hash, hash,
            hash, 2, 4_000_300L));
        assertEquals(0, buffer.remaining(), "the prefix fill consumes exactly PREFIX_BYTES");
        SHA256Hash withBurn = SHA256Hash.of(HeaderCodec.encode(committing));
        var oneMore = new rhizome.core.block.BlockHeader(123, 456L, 16, 1, hash, hash, hash,
            hash, 2, 4_000_301L, List.of());
        assertNotEquals(withBurn, SHA256Hash.of(HeaderCodec.encode(oneMore)),
            "a committed supply is still folded into the preimage: changing it changes the hash");

        // (3) burned is RECOVERABLE from two headers — nothing about the burn is committed
        // anywhere, yet any observer can rederive it: reuse the debt-capped block from the
        // engine above and recover its burn from headers alone.
        bootFunded(1_000_003L);
        long parent = parentSupply();
        long minted = Issuance.minted(params, 2, parent, engine.difficulty(), List.of());
        long debt = Burn.debt(params, 2, parent, minted);
        Block burning = candidate(List.of(send(0, 1_000, 0), send(0, 1_000, 1)),
            parent + minted - debt);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(burning));
        long recovered = Burn.rederive(engine.headerAt(1).supply(), minted,
            engine.headerAt(2).supply());
        assertEquals(debt, recovered,
            "burned(h) == parent.supply + minted(h) - block.supply, from two headers alone");
    }
}
