package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.Executor;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

import java.util.Set;

class ExecutorTest {

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
        var pair = generateKeyPairTyped();
        senderKey = pair.publicKey();
        senderPrivate = pair.privateKey();
        sender = PublicAddress.of(senderKey);
        recipient = PublicAddress.random();
        miner = PublicAddress.random();

        ledger.createWallet(sender);
        ledger.deposit(sender, new TransactionAmount(1_000_000L));
    }

    private Transaction signedSend(long amount, long fee, long nonce) {
        Transaction t = Transaction.of(sender, recipient, new TransactionAmount(amount), senderKey,
            new TransactionAmount(fee), 1234L, params.chainId(), nonce);
        t.sign(senderPrivate);
        return t;
    }

    private Transaction coinbase(long height) {
        return Transaction.of(miner, new TransactionAmount(params.miningReward(height)));
    }

    /** Like {@link #coinbase(long)}, but for an explicit amount -- for the curve-driven reward,
     *  which is a function of parent supply rather than height alone. */
    private Transaction coinbaseOf(long amount) {
        return Transaction.of(miner, new TransactionAmount(amount));
    }

    private Block block(long height, Transaction... transactions) {
        var b = BlockImpl.builder().id((int) height).timestamp(5000).difficulty(params.genesisDifficulty())
            .lastBlockHash(SHA256Hash.empty()).build();
        for (Transaction t : transactions) {
            b.addTransaction(t);
        }
        return b;
    }

    private ExecutionStatus execute(Block b) {
        return execute(b, Set.of());
    }

    private ExecutionStatus execute(Block b, Set<SHA256Hash> executed) {
        return Executor.executeBlock(b, ledger, executed::contains, params);
    }

    @Test
    void consensusFeeFloorRejectsFreeTransactionsEvenInAMinersOwnBlock() {
        // The minFee floor is a CONSENSUS rule, not only mempool admission (audit: the floor was
        // mempool-only, so a miner could include zero-fee transfers the relay policy refused —
        // free permanent ledger entries at amount 0, free gasPrice-0 compute). A network with a
        // floor rejects the block outright; the identical rule as the mempool keeps every
        // pool-admitted transaction consensus-valid.
        NetworkParameters floored = params.toBuilder().minFee(10L).build();
        assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW,
            Executor.executeBlock(block(2, coinbase(2), signedSend(100, 0, 0)), ledger, h -> false, floored),
            "fee 0 under a floor of 10 is a consensus rejection");
        assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW,
            Executor.executeBlock(block(2, coinbase(2), signedSend(100, 9, 0)), ledger, h -> false, floored),
            "strictly below the floor is rejected");
        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(block(2, coinbase(2), signedSend(100, 10, 0)), ledger, h -> false, floored),
            "exactly at the floor passes");
        // A zero floor (testnet) disables the rule entirely.
        assertEquals(ExecutionStatus.SUCCESS, execute(block(3, coinbase(3), signedSend(100, 0, 1))));
    }

    @Test
    void zeroAmountDepositDoesNotCreateTheRecipientWallet() {
        // Ledger-bloat fix: deposit(amount 0) is a no-op, so a free 0/0 transfer can no longer
        // mint a permanent ledger entry per call — and rollback stays a throw-free exact inverse
        // (the revertDeposit side is guarded on > 0 symmetrically).
        Transaction free = signedSend(0, 0, 0);
        Block b = block(2, coinbase(2), free);

        assertEquals(ExecutionStatus.SUCCESS, execute(b));
        assertFalse(ledger.hasWallet(recipient), "a 0-amount deposit must not create the wallet");
        long minerAfterApply = ledger.getWalletValue(miner).amount();

        Executor.rollbackBlock(b, ledger, null, 2, params);
        assertFalse(ledger.hasWallet(recipient), "rollback of a zero credit is a no-op too");
        assertEquals(0L, ledger.getWalletValue(miner).amount(),
            "miner back to the pre-block balance (reward reverted, no zero-credit residue)");
        assertEquals(minerAfterApply, params.miningReward(2));
    }

    @Test
    void appliesTransfersFeesAndReward() {
        var status = execute(block(2, coinbase(2), signedSend(100_000, 500, 0)));

        assertEquals(ExecutionStatus.SUCCESS, status);
        assertEquals(1_000_000L - 100_000L - 500L, ledger.getWalletValue(sender).amount());
        assertEquals(100_000L, ledger.getWalletValue(recipient).amount());
        assertEquals(params.miningReward(2) + 500L, ledger.getWalletValue(miner).amount());
    }

    @Test
    void rejectsMissingCoinbase() {
        assertEquals(ExecutionStatus.NO_MINING_FEE, execute(block(2, signedSend(100, 0, 0))));
    }

    @Test
    void rejectsDuplicateCoinbase() {
        assertEquals(ExecutionStatus.EXTRA_MINING_FEE,
            execute(block(2, coinbase(2), coinbase(2), signedSend(100, 0, 0))));
    }

    @Test
    void rejectsCoinbaseWithNonTransferKind() {
        // Poison-block vector (audit: coinbase kind never validated). The wire codec serializes
        // `kind` independently of the isTransactionFee flag, and the signature verifier passes a
        // fee tx unconditionally — so a coinbase carrying kind=CALL used to be accepted. The
        // apply pass skips fee txs before dispatching on kind, so it never executed; but the
        // reorg-time receipt walk counts receipts per non-fee tx while nothing ever emitted one
        // for it — any later popBlock then failed deterministically and the node could never
        // reorg past the block again. Pass 1 must reject it with the other coinbase defects.
        for (var kind : new rhizome.core.transaction.TransactionKind[] {
                rhizome.core.transaction.TransactionKind.CALL,
                rhizome.core.transaction.TransactionKind.DEPLOY,
                rhizome.core.transaction.TransactionKind.BOX_CREATE,
                rhizome.core.transaction.TransactionKind.TOKEN_MINT}) {
            var poisoned = TransactionImpl.builder()
                .to(miner).amount(new TransactionAmount(params.miningReward(2)))
                .isTransactionFee(true).kind(kind)
                .data(new byte[0]).build();
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE,
                execute(block(2, poisoned)), "coinbase kind " + kind + " must be rejected");
        }
        // Control: the ordinary TRANSFER coinbase still passes.
        assertEquals(ExecutionStatus.SUCCESS, execute(block(2, coinbase(2))));
    }

    @Test
    void rejectsWrongReward() {
        var badCoinbase = Transaction.of(miner, new TransactionAmount(params.miningReward(2) + 1));
        assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, execute(block(2, badCoinbase)));
    }

    @Test
    void rejectsWrongChainId() {
        Transaction t = Transaction.of(sender, recipient, new TransactionAmount(100), senderKey,
            new TransactionAmount(0), 1234L, params.chainId() + 100, 0);
        t.sign(senderPrivate);
        assertEquals(ExecutionStatus.INVALID_CHAIN_ID, execute(block(2, coinbase(2), t)));
    }

    @Test
    void rejectsDuplicateInBlock() {
        Transaction t = signedSend(100, 0, 0);
        assertEquals(ExecutionStatus.EXPIRED_TRANSACTION, execute(block(2, coinbase(2), t, t)));
    }

    @Test
    void rejectsAlreadyExecuted() {
        Transaction t = signedSend(100, 0, 0);
        assertEquals(ExecutionStatus.EXPIRED_TRANSACTION,
            execute(block(2, coinbase(2), t), Set.of(t.hashContents())));
    }

    @Test
    void rejectsTamperedSignature() {
        Transaction t = signedSend(100, 0, 0);
        ((TransactionImpl) t).nonce(99); // invalidate after signing
        assertEquals(ExecutionStatus.INVALID_SIGNATURE, execute(block(2, coinbase(2), t)));
    }

    @Test
    void rejectsSpoofedSender() {
        // Signed with the sender's key but claiming another wallet as 'from'.
        var other = PublicAddress.random();
        Transaction t = Transaction.of(other, recipient, new TransactionAmount(100), senderKey,
            new TransactionAmount(0), 1234L, params.chainId(), 0);
        t.sign(senderPrivate);
        assertEquals(ExecutionStatus.WALLET_SIGNATURE_MISMATCH, execute(block(2, coinbase(2), t)));
    }

    @Test
    void insufficientBalanceRollsBackTheWholeBlock() {
        // First transfer would succeed alone; the second overdraws. The ledger
        // must come back to its exact pre-block state (transactional apply).
        Transaction ok = signedSend(600_000, 0, 0);
        Transaction overdraw = signedSend(600_000, 0, 1);

        var status = execute(block(2, coinbase(2), ok, overdraw));

        assertEquals(ExecutionStatus.BALANCE_TOO_LOW, status);
        assertEquals(1_000_000L, ledger.getWalletValue(sender).amount());
        assertEquals(false, ledger.hasWallet(recipient) && ledger.getWalletValue(recipient).amount() > 0);
    }

    @Test
    void rejectsNegativeAmountThatWouldMintMoney() {
        // Exploit: a negative amount inverts the ledger math — withdrawing a negative
        // value credits the sender and the recipient's balance is driven negative.
        // Must be refused outright, ledger untouched.
        var status = execute(block(2, coinbase(2), signedSend(-1_000L, 0, 0)));

        assertEquals(ExecutionStatus.INVALID_TRANSACTION_AMOUNT, status);
        assertEquals(1_000_000L, ledger.getWalletValue(sender).amount());
        assertEquals(false, ledger.hasWallet(recipient));
    }

    @Test
    void rejectsNegativeFeeThatWouldMintMoney() {
        // Exploit twin: amount 0 but a negative fee also mints for the sender via the
        // withdraw of (amount + fee).
        var status = execute(block(2, coinbase(2), signedSend(0L, -1_000L, 0)));

        assertEquals(ExecutionStatus.INVALID_TRANSACTION_AMOUNT, status);
        assertEquals(1_000_000L, ledger.getWalletValue(sender).amount());
    }

    @Test
    void depositOverflowRollsBackCleanlyInsteadOfCorruptingState() {
        // A recipient near the 64-bit ceiling (reachable via a crafted snapshot seed):
        // a further deposit overflows. The block must be refused and the ledger left
        // exactly as before — not partially mutated with the sender already debited.
        ledger.createWallet(recipient);
        ledger.deposit(recipient, new TransactionAmount(Long.MAX_VALUE - 100L));

        var status = execute(block(2, coinbase(2), signedSend(500L, 0, 0)));

        assertEquals(ExecutionStatus.BALANCE_OVERFLOW, status);
        assertEquals(1_000_000L, ledger.getWalletValue(sender).amount());
        assertEquals(Long.MAX_VALUE - 100L, ledger.getWalletValue(recipient).amount());
        assertEquals(false, ledger.hasWallet(miner) && ledger.getWalletValue(miner).amount() > 0);
    }

    @Test
    void unknownSenderRejected() {
        var pair = generateKeyPairTyped();
        var ghostKey = pair.publicKey();
        Transaction t = Transaction.of(PublicAddress.of(ghostKey), recipient, new TransactionAmount(100),
            ghostKey, new TransactionAmount(0), 1234L, params.chainId(), 0);
        t.sign(pair.privateKey());
        // A funded (>0) spend from a sender with no confirmed balance is still rejected — now via
        // BALANCE_TOO_LOW (absent wallet == balance 0) rather than SENDER_DOES_NOT_EXIST. The
        // accept/reject decision is unchanged; only key-presence no longer drives it (consensus
        // Finding 1: validity is a pure function of balance).
        assertEquals(ExecutionStatus.BALANCE_TOO_LOW, execute(block(2, coinbase(2), t)));
    }

    @Test
    void zeroValueTransferIsValidRegardlessOfWhetherTheSenderWalletExists() {
        // Consensus Finding 1 (phantom-wallet fork): a 0-amount, 0-fee transfer's validity must NOT
        // depend on ledger key-presence. hasWallet returns true for a 0-balance "phantom" left behind
        // by any apply-then-rollback (failed block, popBlock reorg, stampStateRoot undo), while the
        // state root treats a 0 balance as absent. If validity keyed off hasWallet, the SAME canonical
        // block would be SUCCESS on a node that had reverted the sender into existence and rejected on
        // a node that synced the winning chain directly → permanent partition. Both cases must agree.
        var pair = generateKeyPairTyped();
        var wKey = pair.publicKey();
        var wPriv = pair.privateKey();
        var w = PublicAddress.of(wKey);

        // Case A — "clean" node: W was never created.
        InMemoryLedger clean = new InMemoryLedger();
        clean.createWallet(miner);
        Transaction tA = Transaction.of(w, recipient, new TransactionAmount(0), wKey,
            new TransactionAmount(0), 1234L, params.chainId(), 0);
        tA.sign(wPriv);
        ExecutionStatus a = Executor.executeBlock(block(2, coinbase(2), tA), clean, h -> false, params);

        // Case B — "phantom" node: W exists at balance 0 (exactly the post-rollback residue).
        InMemoryLedger phantom = new InMemoryLedger();
        phantom.createWallet(miner);
        phantom.createWallet(w); // balance 0 — the phantom
        Transaction tB = Transaction.of(w, recipient, new TransactionAmount(0), wKey,
            new TransactionAmount(0), 1234L, params.chainId(), 0);
        tB.sign(wPriv);
        ExecutionStatus b = Executor.executeBlock(block(2, coinbase(2), tB), phantom, h -> false, params);

        assertEquals(ExecutionStatus.SUCCESS, a, "clean node must accept the 0/0 transfer");
        assertEquals(ExecutionStatus.SUCCESS, b, "phantom node must accept the 0/0 transfer");
        assertEquals(a, b, "block validity must not depend on ledger key-presence (fork risk)");
    }

    @Test
    void rollbackOfZeroValueWalletlessTransferIsExactInverseAndDoesNotThrow() {
        // Regression for the apply/rollback asymmetry (audit S1). A 0-amount/0-fee transfer from a
        // never-funded key is a *valid* block (see the phantom-wallet test above), but rollbackBlock
        // used to call revertSend(from, 0) unconditionally → ledger.add → getWalletValue on the absent
        // wallet → LedgerException thrown mid-rollback. popBlock/reorg has no restore path, so a
        // planted block of this shape corrupted the ledger on the next reorg that popped it. Apply then
        // rollback must be an exact, throw-free inverse.
        var pair = generateKeyPairTyped();
        var wKey = pair.publicKey();
        var wPriv = pair.privateKey();
        var w = PublicAddress.of(wKey);

        InMemoryLedger clean = new InMemoryLedger();
        clean.createWallet(miner);
        clean.deposit(miner, new TransactionAmount(500L));
        long minerBefore = clean.getWalletValue(miner).amount();

        Transaction poison = Transaction.of(w, recipient, new TransactionAmount(0), wKey,
            new TransactionAmount(0), 1234L, params.chainId(), 0);
        poison.sign(wPriv);
        Block block = block(2, coinbase(2), poison);

        assertEquals(ExecutionStatus.SUCCESS, Executor.executeBlock(block, clean, h -> false, params));
        assertFalse(clean.hasWallet(w), "a 0/0 transfer must not create the walletless sender on apply");

        // The reorg path. Before the fix this threw LedgerException; it must now be a clean inverse.
        Executor.rollbackBlock(block, clean, null, 2, params);

        assertEquals(minerBefore, clean.getWalletValue(miner).amount(),
            "miner balance must be exactly restored after rollback");
        assertFalse(clean.hasWallet(w), "rollback must not create the walletless sender");
    }

    @Test
    void theCoinbaseMustPayExactlyTheCurveRewardForTheParentSupply() {
        // FR-008/FR-009 (research.md Decision 4): once the supply-driven curve is active, the
        // coinbase must pay exactly params.miningReward(height, parentSupply) -- the supply-aware
        // twin of the legacy height-only check exercised by rejectsWrongReward above -- not a
        // height-only guess, and not off by even one base unit in either direction.
        NetworkParameters curveParams = CurveActiveNetwork.curveActiveTestnet();
        long height = 1; // curve active from height 1 per CurveActiveNetwork
        long parentSupply = 0L; // in-domain: EmissionCurve extends flat below its first step
        long expectedReward = curveParams.miningReward(height, parentSupply);

        assertEquals(ExecutionStatus.SUCCESS,
            Executor.executeBlock(block(height, coinbaseOf(expectedReward)), ledger, h -> false,
                curveParams, parentSupply),
            "exactly the curve reward must be accepted");
        assertEquals(ExecutionStatus.INCORRECT_MINING_FEE,
            Executor.executeBlock(block(height, coinbaseOf(expectedReward + 1)), ledger, h -> false,
                curveParams, parentSupply),
            "one base unit over the curve reward must be rejected");
        assertEquals(ExecutionStatus.INCORRECT_MINING_FEE,
            Executor.executeBlock(block(height, coinbaseOf(expectedReward - 1)), ledger, h -> false,
                curveParams, parentSupply),
            "one base unit under the curve reward must be rejected");
    }

    @Test
    void theCurveRewardCheckRunsBeforeProofOfWork() {
        // Executor.executeBlock never verifies PoW at all -- that gate belongs to
        // ChainEngine.addBlock, ordered PoW-last (WHITEPAPER SS3.5). This test's block keeps
        // BlockImpl.builder()'s default nonce -- garbage nobody has run PoW verification
        // against -- to demonstrate the coinbase-amount check is a pure structural rejection
        // that fires unconditionally, independent of and without needing any valid
        // proof-of-work, exactly like the poisoned-coinbase-kind rejections in
        // rejectsCoinbaseWithNonTransferKind above reject on structure alone.
        NetworkParameters curveParams = CurveActiveNetwork.curveActiveTestnet();
        long height = 1;
        long parentSupply = 0L;
        long expectedReward = curveParams.miningReward(height, parentSupply);

        Block wrong = block(height, coinbaseOf(expectedReward + 1));
        assertEquals(ExecutionStatus.INCORRECT_MINING_FEE,
            Executor.executeBlock(wrong, ledger, h -> false, curveParams, parentSupply),
            "wrong coinbase amount must be rejected regardless of the block's (unverified) PoW");
    }

    @Test
    void aCurveActiveBlockWhoseParentCommitsNoSupplyIsRejected() {
        // Decision 4 (research.md): at a curve-active height, an absent parentSupply
        // (BlockImpl.SUPPLY_ABSENT) can never validate a coinbase -- the executor must fail
        // loud rather than silently falling back to the geometric per-height formula or any
        // other height-based guess. Unreachable on a well-formed chain (ChainEngine.checkSupply's
        // prefix closure rejects the absent-supply parent first), but the executor's own gate
        // must hold independently. The coinbase amount here is what a real parent supply of 0
        // would justify -- plausible-looking, not an obviously-wrong amount -- to prove the
        // rejection is about the missing parentSupply, not the coinbase value.
        NetworkParameters curveParams = CurveActiveNetwork.curveActiveTestnet();
        long height = 1;
        long plausibleReward = curveParams.miningReward(height, 0L);

        Block b = block(height, coinbaseOf(plausibleReward));
        assertEquals(ExecutionStatus.INCORRECT_MINING_FEE,
            Executor.executeBlock(b, ledger, h -> false, curveParams, BlockImpl.SUPPLY_ABSENT),
            "a curve-active height with no committed parent supply must fail loud");
    }
}
