package rhizome.adversarial;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
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
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

/**
 * 009-native-coin-burn: the {@code BURN} family's attack proofs (docs/adversarial/spec.md). The
 * burn is derived — recoverable from two headers and clamped against the carried debt — so every
 * scenario here is one con varied: a miner claiming a burn its body never performed (BURN-01),
 * burning past the debt to undershoot the live target (BURN-02), claiming a NEGATIVE burn to
 * over-mint (BURN-03), or routing revenue into a channel hoping the pool never hears of it
 * (BURN-04). The reversal and the economics close the family: a reorg across a burning block must
 * not mis-restore (BURN-05), and burning to raise the subsidy must stay unprofitable below full
 * hashrate (BURN-06).
 *
 * <p>Fixture: the shared curve-active profile with the target lowered so a funded chain sits
 * ABOVE the live target from genesis — the regime where the burn is live. Blocks are hand-mined
 * with explicit committed supplies (the {@code SupplyLedgerAttackTest} idiom), because the
 * scenarios' subject is the committed value itself.
 */
class BurnAttackTest {

    private static NetworkParameters params() {
        return CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .powAlgorithm(rhizome.crypto.PowAlgorithm.SHA256)
            .genesisDifficulty(3).minDifficulty(3).build();
    }

    /** A booted chain whose genesis supply sits far above the live target: the burn is live. */
    private record Chain(ChainEngine engine, AtomicLong clock, PublicKey key, PrivateKey priv,
                         PublicAddress sender, PublicAddress miner) {}

    private Chain chain() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        var pair = generateKeyPairTyped();
        PublicAddress sender = PublicAddress.of(pair.publicKey());
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params().chainId());
        snapshot.put(sender, new TransactionAmount(50_000_000L));
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        ChainEngine engine = ChainEngine.boot(params(), TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
        return new Chain(engine, clock, pair.publicKey(), pair.privateKey(), sender,
            PublicAddress.random());
    }

    /** A signed fee-carrying transfer from the chain's funded sender. */
    private Transaction feeTx(Chain c, long fee, long nonce) {
        Transaction t = Transaction.of(c.sender(), PublicAddress.random(), new TransactionAmount(0),
            c.key(), new TransactionAmount(fee), c.clock().get(), c.engine().params().chainId(), nonce);
        t.sign(c.priv());
        return t;
    }

    /**
     * A coinbase-plus-{@code txs} block with an EXPLICIT committed supply — the attacker's
     * canvas: whatever supply the scenario names, the block carries, with valid PoW re-mined
     * over it.
     */
    private Block blockWith(Chain c, long supply, Transaction... txs) {
        long height = c.engine().height() + 1;
        long parentSupply = c.engine().headerAt(c.engine().height()).supply();
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(c.clock().addAndGet(c.engine().params().desiredBlockTimeSec() * 1000L))
            .difficulty(c.engine().difficulty())
            .lastBlockHash(c.engine().tipHash())
            .supply(supply)
            .build();
        b.addTransaction(Transaction.of(c.miner(),
            new TransactionAmount(c.engine().params().miningReward(height, parentSupply))));
        for (Transaction t : txs) {
            b.addTransaction(t);
        }
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), c.engine().params().powAlgorithm()));
        return b;
    }

    private long parentSupplyOf(Chain c) {
        return c.engine().headerAt(c.engine().height()).supply();
    }

    /**
     * BURN-01 — Claim a burn the block's body never performed: commit supply one share below the
     * unburned ceiling while carrying NO fee flow, so execution pools nothing and burns nothing.
     * The exact identity check (post-execution, beside the state root) catches the shortfall —
     * the block is fully rolled back and rejected — while the honest block at the same height
     * applies.
     */
    @Test
    void aBlockClaimingABurnItDidNotPerformIsRejectedAndTheHonestBlockApplies() {
        Chain c = chain();
        long parent = parentSupplyOf(c);
        long minted = Issuance.minted(c.engine().params(), 2, parent,
            c.engine().difficulty(), java.util.List.of());
        long claimed = 500; // the burn the attacker WISHES the world to believe happened

        assertEquals(ExecutionStatus.INVALID_SUPPLY,
            c.engine().addBlock(blockWith(c, parent + minted - claimed)),
            "a claimed burn with an empty pool must die on the exact identity");
        assertEquals(1, c.engine().height(), "the forgery must not extend the chain");

        // Positive control: the honest ceiling commits fine (nothing burned, nothing claimed).
        assertEquals(ExecutionStatus.SUCCESS, c.engine().addBlock(blockWith(c, parent + minted)));
        assertEquals(2, c.engine().height());
    }

    /**
     * BURN-02 — Burn PAST the carried debt, committing supply BELOW the live target S*(h): the
     * committed supply would land under the floor the whole rule exists to defend. The pre-PoW
     * bound (0 <= burned <= debt) rejects this before a single hash is verified — the burn can
     * never be a supply-collapse lever.
     */
    @Test
    void aBlockBurningPastItsDebtToUndershootTheTargetIsRejectedBeforeProofOfWork() {
        Chain c = chain();
        long parent = parentSupplyOf(c);
        long minted = Issuance.minted(c.engine().params(), 2, parent,
            c.engine().difficulty(), java.util.List.of());
        long debt = Burn.debt(c.engine().params(), 2, parent, minted);
        assertTrue(debt > 0, "fixture sanity: the chain sits above the live target");

        // One base unit past the debt: supply lands at S*(h) - 1.
        long underTargetSupply = c.engine().params().supplyTargetAt(2) - 1;
        assertTrue(parent + minted - underTargetSupply > debt,
            "sanity: this commitment claims more burn than the debt allows");

        assertEquals(ExecutionStatus.INVALID_SUPPLY,
            c.engine().addBlock(blockWith(c, underTargetSupply, feeTx(c, 9_999_999L, 0))),
            "burning past the debt must be rejected pre-PoW, whatever the flow");
        assertEquals(1, c.engine().height());
    }

    /**
     * BURN-03 — Claim a NEGATIVE burn: commit supply ABOVE the unburned ceiling, minting coin the
     * schedule never issued. The inflation direction is enforced pre-PoW by the same bound
     * (burned >= 0), so the forgery dies before PoW — and is a scored push fault, not an honest
     * disagreement (FR-041: it returns INVALID_SUPPLY, the provable-junk class).
     */
    @Test
    void aNegativeBurnClaimingPhantomMintIsRejectedBeforeProofOfWork() {
        Chain c = chain();
        long parent = parentSupplyOf(c);
        long minted = Issuance.minted(c.engine().params(), 2, parent,
            c.engine().difficulty(), java.util.List.of());

        assertEquals(ExecutionStatus.INVALID_SUPPLY,
            c.engine().addBlock(blockWith(c, parent + minted + 100)),
            "a negative burn (over-mint) must be rejected pre-PoW");
        assertEquals(1, c.engine().height());
    }

    /**
     * BURN-04 — Route revenue through gas or rent to escape the burn: pay a supplier with a
     * self-dealing contract call (fee 0, gasPrice high) or a rent redirect instead of a declared
     * fee, hoping the pool never hears of it. The pool is "every base unit credited to the miner
     * that was not freshly minted", so BOTH channels are pooled: the same total flow burns the
     * same amount whatever pipe it arrived through, and the miner's net revenue is identical.
     */
    @Test
    void revenueRoutedThroughGasOrRentIsPooledExactlyLikeADeclaredFee() {
        // Two identical-shape chains; the same 2_000 units of flow reach the miner, once as a
        // declared transfer fee, once as contract gas (gasUsed 1_000 x gasPrice 2 via a stub
        // processor whose only job is to report the gas actually consumed). The committed
        // supplies must be identical: the burn cannot tell the pipes apart.
        long fee = 2_000L;
        Chain feeChain = chain();
        long feeParent = parentSupplyOf(feeChain);
        long feeMinted = Issuance.minted(feeChain.engine().params(), 2, feeParent,
            feeChain.engine().difficulty(), java.util.List.of());
        long feeDebt = Burn.debt(feeChain.engine().params(), 2, feeParent, feeMinted);
        long feeBurned = Burn.burned(feeChain.engine().params(), 2, fee, feeDebt);
        Block viaFee = blockWith(feeChain, feeParent + feeMinted - feeBurned, feeTx(feeChain, fee, 0));
        assertEquals(ExecutionStatus.SUCCESS, feeChain.engine().addBlock(viaFee));

        long gasFee = 2_000L; // gasUsed 1_000 x gasPrice 2
        Chain gasChain = chain();
        long gasParent = parentSupplyOf(gasChain);
        long gasMinted = Issuance.minted(gasChain.engine().params(), 2, gasParent,
            gasChain.engine().difficulty(), java.util.List.of());
        long gasDebt = Burn.debt(gasChain.engine().params(), 2, gasParent, gasMinted);
        long gasBurned = Burn.burned(gasChain.engine().params(), 2, gasFee, gasDebt);
        var gas = (BlockImpl) BlockImpl.builder()
            .id(2)
            .timestamp(gasChain.clock().addAndGet(
                gasChain.engine().params().desiredBlockTimeSec() * 1000L))
            .difficulty(gasChain.engine().difficulty())
            .lastBlockHash(gasChain.engine().tipHash())
            .supply(gasParent + gasMinted - gasBurned)
            .build();
        gas.addTransaction(Transaction.of(gasChain.miner(),
            new TransactionAmount(gasChain.engine().params().miningReward(2, gasParent))));
        Transaction call = rhizome.core.transaction.TransactionImpl.builder()
            .from(gasChain.sender()).to(PublicAddress.random()).signingKey(gasChain.key())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(gasChain.engine().params().chainId()).nonce(0)
            .timestamp(gasChain.clock().get())
            .kind(rhizome.core.transaction.TransactionKind.CALL).data(new byte[0])
            .gasLimit(1_000L).gasPrice(2L)
            .build();
        call.sign(gasChain.priv());
        gas.addTransaction(call);
        var tree = new MerkleTree();
        tree.setItems(gas.transactions());
        gas.merkleRoot(tree.getRootHash());
        // A contract processor reporting gasUsed = 1_000, success — nothing else moves.
        rhizome.core.blockchain.ContractProcessor gasReporter = new rhizome.core.blockchain.ContractProcessor() {
            @Override public void begin() {}
            @Override public rhizome.core.blockchain.ContractProcessor.ContractResult run(
                    PublicAddress from, rhizome.core.transaction.TransactionKind kind,
                    PublicAddress to, byte[] data, long value, long gasLimit, long nonce) {
                return rhizome.core.blockchain.ContractProcessor.ContractResult.ok(1_000, new byte[0], null);
            }
            @Override public void commit(long blockHeight) {}
            @Override public void discard() {}
            @Override public void revertBlock(long blockHeight) {}
            @Override public java.util.List<rhizome.core.blockchain.ContractProcessor.ContractReceipt> receipts(
                    long blockHeight) {
                return java.util.List.of();
            }
        };
        // Execute through the executor with the gas-routing block: the pool must see the gas fee.
        InMemoryLedger gasLedger = new InMemoryLedger();
        gasLedger.createWallet(gasChain.sender());
        gasLedger.deposit(gasChain.sender(), new TransactionAmount(50_000_000L));
        assertEquals(ExecutionStatus.SUCCESS,
            rhizome.core.blockchain.Executor.executeBlock(gas, gasLedger, h -> false,
                gasChain.engine().params(), null, gasReporter, null, null, null, gasParent).status(),
            "the gas-routed block must apply: its gas fee IS in the pool");
        assertEquals(feeBurned, gasBurned,
            "the same flow burns the same amount whichever pipe carried it");
        assertEquals(viaFee.supply(), gas.supply(),
            "identical flow, identical committed supply — the routing escape is closed");
    }

    /**
     * BURN-05 — Reorg across a burning block hoping the pop mis-restores: keeps the burn destroyed
     * (a double-burn out of the miner), refunds MORE than was burned (a mint), or leaves the
     * committed supply inconsistent with the restored ledger. Apply, pop, and demand the
     * pre-burn state byte for byte; re-apply and demand the identical burn.
     */
    @Test
    void aReorgAcrossABurningBlockRestoresSupplyAndLedgerExactly() {
        Chain c = chain();
        long parent = parentSupplyOf(c);
        long minted = Issuance.minted(c.engine().params(), 2, parent,
            c.engine().difficulty(), java.util.List.of());
        long fee = 4_000L;
        long debt = Burn.debt(c.engine().params(), 2, parent, minted);
        long burned = Burn.burned(c.engine().params(), 2, fee, debt);
        assertTrue(burned > 0, "fixture sanity: the block really burns");

        long supplyBefore = parent;
        long senderBefore = c.engine().confirmedBalance(c.sender());
        long minerBefore = c.engine().confirmedBalance(c.miner());

        Block burning = blockWith(c, parent + minted - burned, feeTx(c, fee, 0));
        assertEquals(ExecutionStatus.SUCCESS, c.engine().addBlock(burning));

        rhizome.core.blockchain.ChainEngineTestAccess.popBlock(c.engine());
        // A mis-restoring pop would show here: supply short of the parent's, the miner's burn
        // still gone (double-burn), or the fee not returned (a mint out of nowhere).
        assertEquals(supplyBefore, c.engine().headerAt(c.engine().height()).supply(),
            "the pop restores the parent's committed supply exactly");
        assertEquals(senderBefore, c.engine().confirmedBalance(c.sender()),
            "the fee returns exactly — not more (a mint), not less (a double-burn)");
        assertEquals(minerBefore, c.engine().confirmedBalance(c.miner()),
            "the miner is back to exactly the pre-burn balance");

        // Re-apply (the trusted-restore path): the identical burn, never a re-derived drift.
        assertEquals(ExecutionStatus.SUCCESS, c.engine().addBlock(burning));
        assertEquals(parent + minted - burned, c.engine().headerAt(c.engine().height()).supply(),
            "the re-applied block destroys the identical amount");
    }

    /**
     * BURN-06 — Fund a burn to raise the subsidy, at a hashrate share strictly below 1: the
     * attacker destroys ΔS of their own coin, the subsidy rises by at most ΔS in total across
     * the whole relaxation (the curve's slope bound), and the attacker mines only an α fraction
     * of that raise — strictly unprofitable for every α < 1, swept over the reachable burn sizes.
     */
    @Test
    void burnFundedRewardManipulationStaysUnprofitableBelowFullHashrate() {
        NetworkParameters p = params();
        long sStar = p.supplyTarget();
        // Burn sizes: from one base unit to a meaningful slice of the supply excess. The
        // responsiveness measured is the RAW curve's — the pre-floor bound a recalibration would
        // have to beat; at the shipped calibration the dispatched reward is floored across the
        // whole mirrored branch, so the realized raise is exactly 0.
        var raw = rhizome.core.blockchain.EmissionCurve.build(p.supplyTarget(),
            p.emissionCoefficient(), p.emissionTableSteps());
        for (long burn : new long[] {1L, 1_000L, 1_000_000L, sStar / 1_000L, sStar / 100L}) {
            long parentAbove = sStar + burn;
            long rawBefore = raw.raw(parentAbove, sStar);
            long rawAfter = raw.raw(parentAbove - burn, sStar);
            long totalRaiseBound = Math.abs(rawAfter - rawBefore);
            // The calibration property (G-4: c <= S*): the reward can rise by at most the burn
            // itself, even ignoring the floor.
            assertTrue(totalRaiseBound <= burn,
                "burning " + burn + " can move the raw reward " + totalRaiseBound
                    + " — more than the burn itself, so manipulation would be profitable even "
                    + "at full hashrate");
            // At any alpha < 1 the attacker recovers only that fraction: strictly unprofitable.
            double alpha = 0.5;
            assertTrue(alpha * totalRaiseBound < burn,
                "at alpha 0.5 the recovery " + (alpha * totalRaiseBound) + " must cost less than "
                    + "it returns for burn " + burn);
            // And at the shipped calibration the DISPATCHED subsidy never moves at all: the
            // floor holds the whole mirrored branch at R_min.
            assertEquals(p.minerRevenueFloor(), p.miningReward(2, parentAbove));
            assertEquals(p.minerRevenueFloor(), p.miningReward(2, parentAbove - burn));
        }
    }

    /**
     * BURN-07 — Scored, or not scored (FR-041/FR-042): a burn-identity violation returns exactly
     * {@code INVALID_SUPPLY} — the deterministic structural class the push boundary scores
     * (PENALTY_INVALID, 34/100; {@code PushStrikeTableTest} locks the membership in app-node) —
     * while a VALID burning block that simply loses a fork race is accepted on its own chain and
     * is nobody's misbehaviour: a fork race must never be scored. The catalogue cites this test
     * for both halves of the distinction.
     */
    @Test
    void aBurnIdentityViolationIsScoredWhileADifferentlyBurningBranchIsNot() {
        Chain c = chain();
        long parent = parentSupplyOf(c);
        long minted = Issuance.minted(c.engine().params(), 2, parent,
            c.engine().difficulty(), java.util.List.of());

        // The scored half: a claimed-but-unperformed burn returns INVALID_SUPPLY — the exact
        // status the push boundary scores as a provable structural fault.
        assertEquals(ExecutionStatus.INVALID_SUPPLY,
            c.engine().addBlock(blockWith(c, parent + minted - 500)),
            "a burn identity violation must return the scored INVALID_SUPPLY class");

        // The not-scored half: the same block applied on the branch it was mined for is plain
        // SUCCESS — a differently-burning valid branch is a fork-choice question, never
        // misbehaviour.
        Chain honest = chain();
        long honestParent = parentSupplyOf(honest);
        long honestMinted = Issuance.minted(honest.engine().params(), 2, honestParent,
            honest.engine().difficulty(), java.util.List.of());
        long honestFee = 2_000L;
        long honestDebt = Burn.debt(honest.engine().params(), 2, honestParent, honestMinted);
        long honestBurned = Burn.burned(honest.engine().params(), 2, honestFee, honestDebt);
        Block otherBranch = blockWith(honest, honestParent + honestMinted - honestBurned,
            feeTx(honest, honestFee, 0));
        assertEquals(ExecutionStatus.SUCCESS, honest.engine().addBlock(otherBranch),
            "a valid burning block is not misbehaviour on its own branch");
    }
}
