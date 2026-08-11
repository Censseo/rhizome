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
import static rhizome.crypto.Crypto.generateKeyPair;

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

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(8).minDifficulty(4)
            .storagePeriodBlocks(10).storageFeeFactor(1).minValuePerByte(1).build();
        ledger = new InMemoryLedger();
        boxes = new DefaultBoxProcessor(new InMemoryBoxStore(), params);
        tokens = new DefaultTokenProcessor(new InMemoryTokenStore(), params);

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
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
        var pair = generateKeyPair();
        PublicKey strangerKey = PublicKey.of(pair.getPublic());
        var strangerPriv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
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

    private static byte[] hashOf(int seed) {
        byte[] out = new byte[32];
        out[0] = (byte) seed;
        return out;
    }
}
