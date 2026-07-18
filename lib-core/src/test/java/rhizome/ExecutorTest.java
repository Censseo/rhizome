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
    void unknownSenderRejected() {
        var pair = generateKeyPair();
        var ghostKey = PublicKey.of(pair.getPublic());
        Transaction t = Transaction.of(PublicAddress.of(ghostKey), recipient, new TransactionAmount(100),
            ghostKey, new TransactionAmount(0), 1234L, params.chainId(), 0);
        t.sign(new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate()));
        assertEquals(ExecutionStatus.SENDER_DOES_NOT_EXIST, execute(block(2, coinbase(2), t)));
    }
}
