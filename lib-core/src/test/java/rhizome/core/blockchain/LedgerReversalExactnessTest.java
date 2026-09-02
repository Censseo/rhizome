package rhizome.core.blockchain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxProcessor;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenPayload;
import rhizome.core.token.TokenProcessor;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

/**
 * {@code rollbackBlock} must restore the ledger to exactly the state {@code executeBlock} found.
 *
 * <p>The forward path records every ledger mutation as an {@code AppliedOp} and can invert itself
 * mechanically — but only within one call. Across blocks the journal is gone, so the reorg path
 * re-derives the inverse arithmetically from the transaction and its receipts, through four
 * hand-written mirrors. That is two implementations of one thing, and the code documents four
 * occasions on which they disagreed: an uncle-reward mirror that reverted the flat base instead of
 * the work-scaled amount, a {@code charged > 0} guard added after a LedgerException left the ledger
 * half-reverted mid-rollback, and two zero-value guard disagreements.
 *
 * <p>Every one of those four is a balance that fails to come back. None of them is visible as a
 * crash, an exception or a failing unit test of either side on its own — only as this node's
 * balances differing from the network's after a reorg. So this test asserts the property directly,
 * over the WHOLE ledger rather than a few addresses it thought to check: apply, roll back, and
 * demand the balance map be identical.
 *
 * <p>It does not remove the duplication. It makes the duplication observable, which is what the
 * four incidents actually needed.
 */
class LedgerReversalExactnessTest {

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private BoxProcessor boxes;
    private TokenProcessor tokens;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress bob;
    private PublicAddress miner;
    private PublicAddress uncleMiner;

    /**
     * Curve-active twin of {@link #params}, used only by the US3 uncle/nephew tests below.
     * Derived from {@link CurveActiveNetwork#curveActiveTestnet()} (curve active from height 1,
     * small test-scale table) with {@code genesisDifficulty}/{@code minDifficulty} widened to 8/4
     * — the same gap {@link #params} uses elsewhere in this file — so a block built at
     * {@code genesisDifficulty} and an uncle referencing {@code minDifficulty} exercise a real
     * {@code scaleRewardToWork} deficit, not the identity case.
     */
    private final NetworkParameters curveParams = CurveActiveNetwork.curveActiveTestnet().toBuilder()
        .genesisDifficulty(8).minDifficulty(4).build();

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(8).minDifficulty(4)
            .storagePeriodBlocks(10).storageFeeFactor(1).minValuePerByte(1).build();
        ledger = new InMemoryLedger();
        boxes = new DefaultBoxProcessor(new InMemoryBoxStore(), params);
        tokens = new DefaultTokenProcessor(new InMemoryTokenStore(), params);

        var pair = generateKeyPairTyped();
        key = pair.publicKey();
        priv = pair.privateKey();
        sender = PublicAddress.of(key);
        bob = PublicAddress.random();
        miner = PublicAddress.random();
        uncleMiner = PublicAddress.random();

        ledger.createWallet(sender);
        ledger.deposit(sender, new TransactionAmount(10_000_000L));
        ledger.createWallet(bob);
        ledger.deposit(bob, new TransactionAmount(5_000L));
    }

    /**
     * The entire ledger as a comparable value — not a hand-picked subset of addresses.
     *
     * <p>Zero balances are omitted, deliberately. A reverted credit to a wallet that did not exist
     * leaves the key behind at zero: the forward path creates the wallet, the inverse subtracts the
     * amount, and nothing deletes the key. That phantom is documented as fork-safe, because block
     * validity is a pure function of BALANCE and never of key presence (audit consensus Finding 1),
     * so an address at zero and an absent address are the same state as far as consensus is
     * concerned. Comparing key sets instead of balances would fail every one of these tests for a
     * reason the network does not care about.
     */
    private Map<String, Long> balances() {
        Map<String, Long> out = new LinkedHashMap<>();
        ledger.forEachBalance((address, amount) -> {
            if (amount != 0) {
                out.put(address.toHexString(), amount);
            }
        });
        return out;
    }

    private Transaction transfer(long amount, long fee, long nonce) {
        Transaction t = Transaction.of(sender, bob, new TransactionAmount(amount), key,
            new TransactionAmount(fee), 1234L, params.chainId(), nonce);
        t.sign(priv);
        return t;
    }

    private Transaction boxCreate(long value, long fee, long nonce) {
        var tx = TransactionImpl.builder().from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(fee))
            .chainId(params.chainId()).nonce(nonce).timestamp(1234L)
            .kind(TransactionKind.BOX_CREATE)
            .data(BoxPayload.encodeCreate(List.of(BoxRegister.string("memory"))))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(priv);
        return tx;
    }

    private Transaction tokenMint(long fee, long nonce) {
        var tx = TransactionImpl.builder().from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(fee))
            .chainId(params.chainId()).nonce(nonce).timestamp(1234L)
            .kind(TransactionKind.TOKEN_MINT)
            .data(TokenPayload.encodeMint(1_000_000L, 2, "PNDA", "Panda"))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(priv);
        return tx;
    }

    private BlockImpl block(long height, List<UncleRef> uncles, Transaction... txs) {
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(5000)
            .difficulty(params.genesisDifficulty()).lastBlockHash(SHA256Hash.empty())
            .uncles(new ArrayList<>(uncles)).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        for (Transaction t : txs) {
            b.addTransaction(t);
        }
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        return b;
    }

    /** Applies {@code block}, then rolls it back, and demands the ledger is byte-identical. */
    private void assertRoundTripIsExact(BlockImpl b, String what) {
        Map<String, Long> before = balances();

        boxes.begin();
        tokens.begin();
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(b, ledger, (SHA256Hash h) -> false, params, null, null, boxes, tokens, null),
            what + ": the block must apply, or the reversal is not what is under test");
        boxes.commit(b.id());
        tokens.commit(b.id());

        Executor.rollbackBlock(b, ledger, null, boxes, b.id(), params);
        boxes.revertBlock(b.id());
        tokens.revertBlock(b.id());

        assertEquals(before, balances(),
            what + ": rollbackBlock must restore every balance executeBlock touched");
    }

    /**
     * Curve-aware twin of {@link #block(long, List, Transaction...)}: the coinbase is stamped via
     * the two-arg {@link NetworkParameters#miningReward(long, long)} against {@code curveParams}
     * and an explicit {@code parentSupply}, instead of {@link #block}'s one-arg, curve-unaware
     * form. Uses {@link #curveParams} exclusively — never {@link #params} — so the curve is
     * actually active at {@code height}.
     */
    private BlockImpl blockAt(long height, long parentSupply, List<UncleRef> uncles, Transaction... txs) {
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(5000)
            .difficulty(curveParams.genesisDifficulty()).lastBlockHash(SHA256Hash.empty())
            .uncles(new ArrayList<>(uncles)).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(curveParams.miningReward(height, parentSupply))));
        for (Transaction t : txs) {
            b.addTransaction(t);
        }
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        return b;
    }

    /**
     * Curve-aware twin of {@link #assertRoundTripIsExact(BlockImpl, String)}: applies {@code b}
     * via the 10-arg {@link Executor#executeBlock} overload with an explicit {@code parentSupply}
     * against {@link #curveParams}, then rolls it back, and demands the ledger is byte-identical.
     *
     * <p>Unlike {@link #assertRoundTripIsExact(BlockImpl, String)}, this prunes {@code ledger}'s
     * undo journal for {@code b}'s height before rolling back. {@link InMemoryLedger#revertBlock}
     * replays that journal — the exact inverse of whatever was applied, right or wrong — so with
     * it intact a round trip is trivially exact regardless of whether the uncle/nephew amounts
     * {@code payUncleRewards} paid were curve-aware at all; verified empirically (temporarily
     * reverting the recompute mirror to the pre-fix flat-base bug left every round-trip test in
     * this file green). Pruning forces {@code undoBlock} onto its receipts-and-formula
     * recomputation fallback — the same path a reorg takes once a block's journal has aged past
     * {@code maxReorgDepth} — which is the path that must be curve-aware for this test to mean
     * anything (FR-005; spec US3 scenario 3).
     */
    private void assertRoundTripIsExact(BlockImpl b, long parentSupply, String what) {
        Map<String, Long> before = balances();

        boxes.begin();
        tokens.begin();
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(b, ledger, (SHA256Hash h) -> false, curveParams, null, null, boxes, tokens,
                null, parentSupply),
            what + ": the block must apply, or the reversal is not what is under test");
        boxes.commit(b.id());
        tokens.commit(b.id());
        ledger.pruneJournals(b.id() + 1); // force undoBlock's recompute-mirror fallback, not the journal replay

        Executor.rollbackBlock(b, ledger, null, boxes, b.id(), curveParams);
        boxes.revertBlock(b.id());
        tokens.revertBlock(b.id());

        assertEquals(before, balances(),
            what + ": rollbackBlock must restore every balance executeBlock touched");
    }

    @Test
    void aPlainTransferReversesExactly() {
        assertRoundTripIsExact(block(2, List.of(), transfer(1_000, 7, 0)), "transfer with a fee");
    }

    @Test
    void aZeroFeeAndZeroAmountTransferReverseExactly() {
        // Two of the four documented drifts were zero-value guard disagreements: the forward path
        // skips a zero credit, the mirror must skip the matching debit, and a `revertSend(_, 0)`
        // against a wallet that was never created throws mid-rollback.
        assertRoundTripIsExact(block(2, List.of(), transfer(1_000, 0, 0)), "zero fee");
        assertRoundTripIsExact(block(3, List.of(), transfer(0, 5, 1)), "zero amount");
        assertRoundTripIsExact(block(4, List.of(), transfer(0, 0, 2)), "zero amount and zero fee");
    }

    @Test
    void aZeroValueTransferFromANeverFundedKeyReversesExactly() {
        // The case the `charged > 0` revert guard exists for, and the only one that exercises it.
        // A 0-amount, 0-fee transfer withdraws nothing, so the forward path never creates the
        // sender's wallet; an unguarded revertSend(from, 0) then throws LedgerException against a
        // wallet that does not exist — mid-rollback, leaving the ledger partially reverted while
        // the stores, nonces, processors and state root stayed applied. A funded sender cannot
        // reach it: its wallet exists, so subtracting zero is a harmless no-op.
        var pair = generateKeyPairTyped();
        PublicKey strangerKey = pair.publicKey();
        var strangerPriv = pair.privateKey();
        PublicAddress stranger = PublicAddress.of(strangerKey);
        assertEquals(false, ledger.hasWallet(stranger), "the sender must be unknown to the ledger");

        Transaction t = Transaction.of(stranger, bob, new TransactionAmount(0), strangerKey,
            new TransactionAmount(0), 1234L, params.chainId(), 0);
        t.sign(strangerPriv);
        assertRoundTripIsExact(block(2, List.of(), t), "zero-value transfer from a never-funded key");
    }

    @Test
    void aTransferToAWalletThatDidNotExistReversesExactly() {
        // The credit creates the wallet; the reversal must leave no funded phantom behind.
        PublicAddress fresh = PublicAddress.random();
        Transaction t = Transaction.of(sender, fresh, new TransactionAmount(2_500), key,
            new TransactionAmount(3), 1234L, params.chainId(), 0);
        t.sign(priv);
        assertRoundTripIsExact(block(2, List.of(), t), "credit to a new wallet");
        assertEquals(0L, balances().getOrDefault(fresh.toHexString(), 0L),
            "a wallet created by the reverted credit must hold nothing");
    }

    @Test
    void uncleRewardsReverseAtTheirWorkScaledAmount() {
        // The first documented drift: the mirror reverted the FLAT base reward while the forward
        // path paid an amount scaled to the uncle's proven work. Every uncle at a difficulty below
        // the nephew's makes the two disagree.
        // Difficulty 4 under a difficulty-8 nephew: scaleRewardToWork is then NOT the identity,
        // so a mirror reverting the flat base instead of the scaled amount leaves a balance behind.
        // At equal difficulty the two are the same number and the drift is invisible.
        var uncles = List.of(
            new UncleRef(SHA256Hash.of(new byte[32]), params.minDifficulty(), uncleMiner),
            new UncleRef(SHA256Hash.of(hashOf(1)), params.minDifficulty(), uncleMiner));
        assertRoundTripIsExact(block(2, uncles, transfer(1_000, 7, 0)), "two under-difficulty uncles");
    }

    /**
     * FR-005 / spec US3 scenario 1: uncle and nephew issuance must derive from the
     * <em>supply-aware</em> base reward under a curve-active profile, not the geometric
     * height-only base — {@code Executor.payUncleRewards} takes {@code parentSupply} exactly
     * like the coinbase check already does. An equal-difficulty uncle makes
     * {@code scaleRewardToWork} the identity, so the paid amounts are exactly
     * {@code curveParams.uncleReward(height, parentSupply)} /
     * {@code curveParams.nephewReward(height, parentSupply)} with no scaling to obscure a wrong
     * base.
     */
    @Test
    void uncleAndNephewRewardsDeriveFromTheSupplyAwareBaseReward() {
        long height = 1; // curveParams activates the curve at height 1
        long parentSupply = 0L; // in-domain via EmissionCurve's below-floor extension
        var uncles = List.of(
            new UncleRef(SHA256Hash.of(hashOf(30)), curveParams.genesisDifficulty(), uncleMiner));
        BlockImpl b = blockAt(height, parentSupply, uncles);

        Map<String, Long> before = balances();
        boxes.begin();
        tokens.begin();
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(b, ledger, (SHA256Hash h) -> false, curveParams, null, null, boxes, tokens,
                null, parentSupply),
            "the block must apply for its paid uncle/nephew amounts to be observable");
        boxes.commit(b.id());
        tokens.commit(b.id());
        Map<String, Long> after = balances();

        long expectedUncleReward = curveParams.uncleReward(height, parentSupply);
        long expectedNephewBonus = curveParams.nephewReward(height, parentSupply);
        assertEquals(before.getOrDefault(uncleMiner.toHexString(), 0L) + expectedUncleReward,
            after.get(uncleMiner.toHexString()),
            "an equal-difficulty uncle must be paid exactly the curve-aware uncle reward");
        assertEquals(before.getOrDefault(miner.toHexString(), 0L)
                + curveParams.miningReward(height, parentSupply) + expectedNephewBonus,
            after.get(miner.toHexString()),
            "the nephew (including block) miner must be paid the coinbase plus the curve-aware "
                + "nephew bonus");
    }

    /**
     * FR-005 / spec US3 scenario 1: work scaling composes on top of the curve-aware base exactly
     * as it already does on top of the geometric base — a difficulty deficit still halves the
     * reward once per missing bit ({@code base >>> deficit}), it just starts from
     * {@code curveParams.uncleReward(height, parentSupply)} instead of the one-arg form.
     */
    @Test
    void uncleWorkScalingIsUnchangedUnderTheCurve() {
        long height = 1;
        long parentSupply = 0L;
        int deficit = curveParams.genesisDifficulty() - curveParams.minDifficulty();
        assertTrue(deficit > 0, "the profile must carry a real difficulty gap for this test to mean anything");
        var uncles = List.of(
            new UncleRef(SHA256Hash.of(hashOf(31)), curveParams.minDifficulty(), uncleMiner));
        BlockImpl b = blockAt(height, parentSupply, uncles);

        Map<String, Long> before = balances();
        boxes.begin();
        tokens.begin();
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(b, ledger, (SHA256Hash h) -> false, curveParams, null, null, boxes, tokens,
                null, parentSupply),
            "the block must apply for its paid uncle/nephew amounts to be observable");
        boxes.commit(b.id());
        tokens.commit(b.id());
        Map<String, Long> after = balances();

        long expectedUncleReward =
            Executor.scaleRewardToWork(curveParams.uncleReward(height, parentSupply), deficit);
        long expectedNephewBonus =
            Executor.scaleRewardToWork(curveParams.nephewReward(height, parentSupply), deficit);
        assertEquals(before.getOrDefault(uncleMiner.toHexString(), 0L) + expectedUncleReward,
            after.get(uncleMiner.toHexString()),
            "a sub-difficulty uncle's curve-aware base must still be scaled by scaleRewardToWork");
        assertEquals(before.getOrDefault(miner.toHexString(), 0L)
                + curveParams.miningReward(height, parentSupply) + expectedNephewBonus,
            after.get(miner.toHexString()),
            "the nephew bonus must be the curve-aware base, scaled by the same work deficit");
    }

    /**
     * FR-005 / spec US3 scenario 2, re-premised to the floored regime (research.md Decision 6,
     * feature 05): in what was the curve's truncation region the base reward is no longer zero —
     * the floor holds it at exactly R_min, and the uncle/nephew rewards are that floored base's
     * fractions derived through the single clamp site (contracts/miner-revenue-floor.md §3). No
     * second clamp, and no off-curve fallback issuance: the amounts are exactly the floored
     * base's derived fractions.
     */
    @Test
    void aFlooredBaseRewardMintsTheFlooredUncleAndNephewRewards() {
        long height = 1;
        long parentSupply = curveParams.supplyTarget() * 2; // strictly above S* -> floored to R_min
        long floor = curveParams.minerRevenueFloor();
        assertEquals(floor, curveParams.miningReward(height, parentSupply),
            "sanity: the curve-aware base reward must be exactly R_min at this parentSupply");
        var uncles = List.of(
            new UncleRef(SHA256Hash.of(hashOf(32)), curveParams.genesisDifficulty(), uncleMiner));
        BlockImpl b = blockAt(height, parentSupply, uncles);

        Map<String, Long> before = balances();
        boxes.begin();
        tokens.begin();
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(b, ledger, (SHA256Hash h) -> false, curveParams, null, null, boxes, tokens,
                null, parentSupply),
            "a valid block may legitimately carry a floored coinbase");
        boxes.commit(b.id());
        tokens.commit(b.id());
        Map<String, Long> after = balances();

        // The uncle references the nephew's own difficulty (deficit 0), so scaleRewardToWork is
        // the identity here — the floored base's fractions in full: uncle R_min/2, nephew R_min/32.
        long expectedUncleReward = curveParams.uncleReward(height, parentSupply);
        long expectedNephewBonus = curveParams.nephewReward(height, parentSupply);
        // getOrDefault on BOTH sides: balances() omits zero balances, so a regression that minted
        // nothing here must fail as "expected 400 but was 0", not as an unboxing NPE that hides
        // which side of the identity broke.
        assertEquals(before.getOrDefault(uncleMiner.toHexString(), 0L) + expectedUncleReward,
            after.getOrDefault(uncleMiner.toHexString(), 0L),
            "above S* an uncle must earn the floored base's uncle fraction -- no second clamp, no zero");
        assertEquals(before.getOrDefault(miner.toHexString(), 0L)
                + curveParams.miningReward(height, parentSupply) + expectedNephewBonus,
            after.getOrDefault(miner.toHexString(), 0L),
            "above S* the miner must earn the floored base plus the floored nephew bonus -- no off-curve fallback");
    }

    /**
     * FR-005 / spec US3 scenario 3: reorg reversal of curve-era uncle/nephew issuance must be
     * exact, mirroring {@link #uncleRewardsReverseAtTheirWorkScaledAmount()} under
     * {@link #curveParams} — two uncles, one strictly below the nephew's difficulty and one equal
     * to it, so {@code scaleRewardToWork} is actually exercised on the curve-aware base.
     *
     * <p>A pure round trip cannot fail on its own here: today {@code payUncleRewards} and
     * {@code undoBlock}'s rollback mirror share the identical curve-<em>unaware</em> one-arg
     * formula, so a wrong-but-symmetric payout still cancels out to an exact round trip (verified
     * empirically — temporarily reverting the mirror to the pre-fix flat-base bug this file's
     * class Javadoc documents left every round-trip test in this file green, this one included).
     * So this test first pins the amounts actually paid against the curve-aware two-arg formula
     * — the assertion that must fail before T018 — at a height applied and reverted once so it
     * cannot pollute the round-trip height below, then separately exercises
     * {@link #assertRoundTripIsExact(BlockImpl, long, String)} for its own protective value
     * (forcing {@code undoBlock}'s receipts-and-formula fallback via {@code pruneJournals}, the
     * path a real reorg takes once a block's journal has aged past {@code maxReorgDepth}).
     */
    @Test
    void reorgReversesCurveEraUncleRewardsExactly() {
        long parentSupply = 0L;
        var uncles = List.of(
            new UncleRef(SHA256Hash.of(hashOf(33)), curveParams.minDifficulty(), uncleMiner),
            new UncleRef(SHA256Hash.of(hashOf(34)), curveParams.genesisDifficulty(), uncleMiner));

        long amountCheckHeight = 1;
        BlockImpl amountCheckBlock = blockAt(amountCheckHeight, parentSupply, uncles);
        Map<String, Long> beforeAmountCheck = balances();
        boxes.begin();
        tokens.begin();
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(amountCheckBlock, ledger, (SHA256Hash h) -> false, curveParams, null, null,
                boxes, tokens, null, parentSupply),
            "the block must apply for its paid uncle/nephew amounts to be observable");
        boxes.commit(amountCheckBlock.id());
        tokens.commit(amountCheckBlock.id());

        int deficit = curveParams.genesisDifficulty() - curveParams.minDifficulty();
        long expectedUncleCredit =
            Executor.scaleRewardToWork(curveParams.uncleReward(amountCheckHeight, parentSupply), deficit)
                + curveParams.uncleReward(amountCheckHeight, parentSupply); // 2nd uncle: nephew's own difficulty
        assertEquals(beforeAmountCheck.getOrDefault(uncleMiner.toHexString(), 0L) + expectedUncleCredit,
            balances().get(uncleMiner.toHexString()),
            "both uncles' credits must derive from the curve-aware base, one of them work-scaled");

        Executor.rollbackBlock(amountCheckBlock, ledger, null, boxes, amountCheckBlock.id(), curveParams);
        boxes.revertBlock(amountCheckBlock.id());
        tokens.revertBlock(amountCheckBlock.id());
        assertEquals(beforeAmountCheck, balances(), "the amount check itself must leave no residue behind");

        long roundTripHeight = 2;
        assertRoundTripIsExact(blockAt(roundTripHeight, parentSupply, uncles), parentSupply,
            "curve-era uncles, one strictly under-difficulty, reverted via the pruned-journal fallback");
    }

    @Test
    void aBlockCarryingEveryDomainReversesExactly() {
        // The realistic case: coinbase, uncle rewards, a transfer, a box create and a token mint
        // in one block, so every mirror runs and the reverse walk consumes both receipt lists.
        var uncles = List.of(new UncleRef(SHA256Hash.of(hashOf(2)), params.minDifficulty(), uncleMiner));
        assertRoundTripIsExact(
            block(2, uncles, transfer(1_000, 7, 0), boxCreate(5_000, 3, 1), tokenMint(11, 2)),
            "transfer + box + token + uncles");
    }

    @Test
    void aBoxAndTokenBlockWithZeroFeesReversesExactly() {
        // Box and token ops move value through their own receipts; at zero fee the mirrors take
        // their `> 0` guards, which is where the third and fourth drifts lived.
        assertRoundTripIsExact(block(2, List.of(), boxCreate(5_000, 0, 0), tokenMint(0, 1)),
            "box + token at zero fee");
    }

    @Test
    void aBurnCarryingBlockReversesExactlyThroughTheRecomputeFallback() {
        // 009 T039: the burn is ONE more ledger op the inverse must undo. With the journal
        // pruned (the aged-past-maxReorgDepth regime), undoBlock's recompute fallback must
        // re-derive the identical burn — parent.supply + minted - block.supply from the block's
        // own committed values and the caller's parent supply — and re-deposit it, or the
        // miner's balance comes back wrong by exactly the burned amount. The parent supply
        // rides the caller's chain context (the engine's), not a new public parameter.
        long parentSupply = 4_000_000L; // far above curveActiveTestnet's live target: debt never binds
        long fee = 500;
        long minted = curveParams.miningReward(2, parentSupply);
        long burned = Burn.burned(curveParams, 2, fee,
            Burn.debt(curveParams, 2, parentSupply, minted));
        assertTrue(burned > 0, "sanity: the fixture really burns");
        var b = (BlockImpl) BlockImpl.builder().id(2).timestamp(5000)
            .difficulty(curveParams.genesisDifficulty()).lastBlockHash(SHA256Hash.empty())
            .supply(parentSupply + minted - burned)
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(minted)));
        b.addTransaction(transfer(0, fee, 0));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());

        Map<String, Long> before = balances();
        boxes.begin();
        tokens.begin();
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(b, ledger, (SHA256Hash h) -> false, curveParams, null, null,
                boxes, tokens, null, parentSupply),
            "the burning block must apply, or the reversal is not what is under test");
        boxes.commit(b.id());
        tokens.commit(b.id());
        // The forward burn: the miner keeps the coinbase and the fee minus exactly the burn.
        // getOrDefault on BOTH sides: balances() omits zero balances (the miner starts unfunded).
        assertEquals(before.getOrDefault(miner.toHexString(), 0L) + minted + fee - burned,
            ledger.getWalletValue(miner).amount(),
            "the forward burn withdraws exactly min(share, debt) from the miner");
        ledger.pruneJournals(b.id() + 1); // force undoBlock's recompute fallback, not the journal replay

        Executor.rollbackBlock(b, ledger, null, boxes, b.id(), curveParams, parentSupply);
        boxes.revertBlock(b.id());
        tokens.revertBlock(b.id());

        assertEquals(before, balances(),
            "the re-derived burn must be the identical amount the forward pass withdrew");
    }

    private static byte[] hashOf(int seed) {
        byte[] out = new byte[32];
        out[0] = (byte) seed;
        return out;
    }
}
