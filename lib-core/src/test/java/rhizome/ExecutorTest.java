package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static rhizome.core.common.Crypto.generateKeyPair;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.Executor;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.crypto.SHA256Hash;
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
        var pair = generateKeyPair();
        senderKey = PublicKey.of(pair.getPublic());
        senderPrivate = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
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
        var pair = generateKeyPair();
        var ghostKey = PublicKey.of(pair.getPublic());
        Transaction t = Transaction.of(PublicAddress.of(ghostKey), recipient, new TransactionAmount(100),
            ghostKey, new TransactionAmount(0), 1234L, params.chainId(), 0);
        t.sign(new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate()));
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
        var pair = generateKeyPair();
        var wKey = PublicKey.of(pair.getPublic());
        var wPriv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
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
}
