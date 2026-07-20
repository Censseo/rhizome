package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static rhizome.core.common.Crypto.generateKeyPair;

import java.util.List;
import java.util.Set;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.Executor;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.box.Box;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxProcessor;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

class BoxOpTest {

    // Small storage period so rent tests reach expiry at reasonable heights.
    private final NetworkParameters params = NetworkParameters.testnet().toBuilder()
        .storagePeriodBlocks(10).storageFeeFactor(1).minValuePerByte(1).build();

    private InMemoryLedger ledger;
    private InMemoryBoxStore boxStore;
    private BoxProcessor boxes;
    private PublicKey senderKey;
    private PrivateKey senderPrivate;
    private PublicAddress sender;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        boxStore = new InMemoryBoxStore();
        boxes = new DefaultBoxProcessor(boxStore, params);
        var pair = generateKeyPair();
        senderKey = PublicKey.of(pair.getPublic());
        senderPrivate = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(senderKey);
        miner = PublicAddress.random();
        ledger.createWallet(sender);
        ledger.deposit(sender, new TransactionAmount(1_000_000L));
    }

    // ---- helpers ----

    private Transaction signedBox(TransactionKind kind, PublicAddress to, byte[] data,
                                  long value, long fee, long nonce) {
        var tx = TransactionImpl.builder()
            .from(sender).to(to).signingKey(senderKey)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(fee))
            .chainId(params.chainId()).nonce(nonce).timestamp(1234L)
            .kind(kind).data(data).gasLimit(0).gasPrice(0)
            .build();
        tx.sign(senderPrivate);
        return tx;
    }

    private Transaction create(long value, long nonce, BoxRegister... regs) {
        return signedBox(TransactionKind.BOX_CREATE, sender, BoxPayload.encodeCreate(List.of(regs)),
            value, 0, nonce);
    }

    private Transaction update(byte[] boxId, long topup, long nonce, BoxRegister... regs) {
        return signedBox(TransactionKind.BOX_UPDATE, sender, BoxPayload.encodeUpdate(boxId, List.of(regs)),
            topup, 0, nonce);
    }

    private Transaction spend(byte[] boxId, long nonce) {
        return signedBox(TransactionKind.BOX_SPEND, sender, BoxPayload.encodeTarget(boxId), 0, 0, nonce);
    }

    private Transaction collect(byte[] boxId) {
        return TransactionImpl.builder()
            .kind(TransactionKind.BOX_COLLECT).from(PublicAddress.empty()).to(miner)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0)).isTransactionFee(false)
            .chainId(params.chainId()).nonce(0).timestamp(1234L)
            .data(BoxPayload.encodeTarget(boxId)).build();
    }

    private Transaction coinbase(long height) {
        return Transaction.of(miner, new TransactionAmount(params.miningReward(height)));
    }

    private Block block(long height, Transaction... txs) {
        var b = BlockImpl.builder().id((int) height).timestamp(5000)
            .difficulty(params.genesisDifficulty()).lastBlockHash(SHA256Hash.empty()).build();
        for (Transaction t : txs) {
            b.addTransaction(t);
        }
        return b;
    }

    private ExecutionStatus execute(Block b) {
        return Executor.executeBlock(b, ledger, (SHA256Hash h) -> false, params, null, null, boxes);
    }

    private ExecutionStatus executeWith(Block b, Set<SHA256Hash> executed) {
        return Executor.executeBlock(b, ledger, executed::contains, params, null, null, boxes);
    }

    // ---- create ----

    @Test
    void createLocksValueAndStoresBox() {
        long start = ledger.getWalletValue(sender).amount();
        assertEquals(ExecutionStatus.SUCCESS,
            execute(block(2, coinbase(2), create(5000, 0, BoxRegister.string("hello")))));

        Box box = boxes.getCommitted(Box.deriveId(sender, 0));
        assertNotNull(box);
        assertEquals(5000, box.value());
        assertEquals(List.of(BoxRegister.string("hello")), box.registers());
        // Value left the ledger into the box.
        assertEquals(start - 5000, ledger.getWalletValue(sender).amount());
    }

    @Test
    void createRejectsValueBelowDustFloor() {
        // A box with one 8-byte register serializes to 82 (header) + 11 = 93 bytes; min value = 93.
        assertEquals(ExecutionStatus.BOX_VALUE_TOO_LOW,
            execute(block(2, coinbase(2), create(10, 0, BoxRegister.i64(1)))));
    }

    @Test
    void createRejectedBeforeActivationHeight() {
        NetworkParameters gated = params.toBuilder().boxActivationHeight(100).build();
        var status = Executor.executeBlock(block(2, coinbase(2), create(5000, 0)),
            ledger, (SHA256Hash h) -> false, gated, null, null, boxes);
        assertEquals(ExecutionStatus.BOX_UNAVAILABLE, status);
    }

    @Test
    void createRejectedWithoutBoxProcessor() {
        var status = Executor.executeBlock(block(2, coinbase(2), create(5000, 0)),
            ledger, (SHA256Hash h) -> false, params, null, null, null);
        assertEquals(ExecutionStatus.BOX_UNAVAILABLE, status);
    }

    @Test
    void createRejectsNonzeroGas() {
        var tx = TransactionImpl.builder()
            .from(sender).to(sender).signingKey(senderKey)
            .amount(new TransactionAmount(5000)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(0).timestamp(1L)
            .kind(TransactionKind.BOX_CREATE).data(BoxPayload.encodeCreate(List.of()))
            .gasLimit(1).gasPrice(1).build();
        tx.sign(senderPrivate);
        assertEquals(ExecutionStatus.BOX_PAYLOAD_INVALID, execute(block(2, coinbase(2), tx)));
    }

    // ---- update ----

    @Test
    void updateByOwnerReplacesRegistersToppingUpValueAndResetsRentClock() {
        assertEquals(ExecutionStatus.SUCCESS, execute(block(2, coinbase(2), create(5000, 0))));
        byte[] id = Box.deriveId(sender, 0);
        assertEquals(ExecutionStatus.SUCCESS,
            execute(block(7, coinbase(7), update(id, 1000, 1, BoxRegister.i64(99)))));

        Box box = boxes.getCommitted(id);
        assertEquals(6000, box.value());
        assertEquals(List.of(BoxRegister.i64(99)), box.registers());
        assertEquals(7, box.rentPaidHeight()); // clock reset
        assertEquals(2, box.createdHeight());  // creation preserved
    }

    @Test
    void updateByNonOwnerRejected() {
        assertEquals(ExecutionStatus.SUCCESS, execute(block(2, coinbase(2), create(5000, 0))));
        byte[] id = Box.deriveId(sender, 0);
        // A different signer tries to update the box.
        var otherPair = generateKeyPair();
        PublicKey otherKey = PublicKey.of(otherPair.getPublic());
        PrivateKey otherPriv = new PrivateKey((Ed25519PrivateKeyParameters) otherPair.getPrivate());
        PublicAddress other = PublicAddress.of(otherKey);
        ledger.createWallet(other);
        ledger.deposit(other, new TransactionAmount(10_000));
        var tx = TransactionImpl.builder().from(other).to(other).signingKey(otherKey)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(0).timestamp(1L)
            .kind(TransactionKind.BOX_UPDATE).data(BoxPayload.encodeUpdate(id, List.of()))
            .build();
        tx.sign(otherPriv);
        assertEquals(ExecutionStatus.BOX_NOT_OWNER, execute(block(3, coinbase(3), tx)));
    }

    // ---- spend ----

    @Test
    void spendReturnsValueAndDeletesBox() {
        assertEquals(ExecutionStatus.SUCCESS, execute(block(2, coinbase(2), create(5000, 0))));
        byte[] id = Box.deriveId(sender, 0);
        long before = ledger.getWalletValue(sender).amount();
        assertEquals(ExecutionStatus.SUCCESS, execute(block(3, coinbase(3), spend(id, 1))));
        assertNull(boxes.getCommitted(id));
        assertEquals(before + 5000, ledger.getWalletValue(sender).amount());
    }

    // ---- rent collection ----

    @Test
    void collectRejectedBeforeExpiry() {
        assertEquals(ExecutionStatus.SUCCESS, execute(block(2, coinbase(2), create(5000, 0))));
        byte[] id = Box.deriveId(sender, 0);
        // storagePeriod = 10; at height 11 the box (rentPaid=2) is only 9 blocks old.
        assertEquals(ExecutionStatus.BOX_NOT_EXPIRED, execute(block(11, coinbase(11), collect(id))));
    }

    @Test
    void collectChargesRentPreservingBoxAndPayingMiner() {
        BoxRegister reg = BoxRegister.string("keep-me");
        assertEquals(ExecutionStatus.SUCCESS, execute(block(2, coinbase(2), create(5000, 0, reg))));
        byte[] id = Box.deriveId(sender, 0);
        long size = boxes.getCommitted(id).serializedSize();
        long minerBefore = ledger.getWalletValue(miner).amount(); // earned the create block's coinbase
        assertEquals(ExecutionStatus.SUCCESS, execute(block(50, coinbase(50), collect(id))));

        Box box = boxes.getCommitted(id);
        assertNotNull(box); // partial charge: box survives
        assertEquals(5000 - size, box.value());
        assertEquals(List.of(reg), box.registers()); // registers preserved
        assertEquals(50, box.rentPaidHeight());       // rent clock advanced
        // Miner received the rent on top of this block's reward.
        assertEquals(minerBefore + params.miningReward(50) + size, ledger.getWalletValue(miner).amount());
    }

    @Test
    void collectTakesWholeBoxWhenValueBelowFloor() {
        // Value just above the floor so a single rent charge would drop it below: full collection.
        // size for 0 registers = 82; floor = 82; rent = 82; value 100 -> 100-82=18 < 82 => full.
        assertEquals(ExecutionStatus.SUCCESS, execute(block(2, coinbase(2), create(100, 0))));
        byte[] id = Box.deriveId(sender, 0);
        long minerBefore = ledger.getWalletValue(miner).amount();
        assertEquals(ExecutionStatus.SUCCESS, execute(block(50, coinbase(50), collect(id))));
        assertNull(boxes.getCommitted(id)); // box destroyed
        assertEquals(minerBefore + params.miningReward(50) + 100, ledger.getWalletValue(miner).amount());
    }

    @Test
    void collectsCappedPerBlock() {
        NetworkParameters capped = params.toBuilder().maxBoxCollectsPerBlock(1).build();
        // Create two expired boxes.
        assertEquals(ExecutionStatus.SUCCESS, execute(block(2, coinbase(2), create(200, 0), create(200, 1))));
        byte[] id0 = Box.deriveId(sender, 0);
        byte[] id1 = Box.deriveId(sender, 1);
        var status = Executor.executeBlock(block(50, coinbase(50), collect(id0), collect(id1)),
            ledger, (SHA256Hash h) -> false, capped, null, null, boxes);
        assertEquals(ExecutionStatus.BOX_LIMIT_EXCEEDED, status);
    }

    // ---- conservation & reorg ----

    @Test
    void monetaryValueIsConservedAcrossCreateAndSpend() {
        long total = totalMonetary();
        execute(block(2, coinbase(2), create(5000, 0)));
        // Coinbase minted new money; account for it.
        assertEquals(total + params.miningReward(2), totalMonetary());
        byte[] id = Box.deriveId(sender, 0);
        execute(block(3, coinbase(3), spend(id, 1)));
        assertEquals(total + params.miningReward(2) + params.miningReward(3), totalMonetary());
    }

    @Test
    void rollbackRestoresLedgerAndBoxState() {
        long ledgerBefore = ledger.getWalletValue(sender).amount();
        Block b = block(2, coinbase(2), create(5000, 0, BoxRegister.string("data")));
        assertEquals(ExecutionStatus.SUCCESS, execute(b));
        byte[] id = Box.deriveId(sender, 0);
        assertNotNull(boxes.getCommitted(id));

        Executor.rollbackBlock(b, ledger, null, boxes, 2, params);
        boxes.revertBlock(2);

        assertNull(boxes.getCommitted(id));                          // box gone
        assertEquals(ledgerBefore, ledger.getWalletValue(sender).amount()); // value returned
    }

    /** Sum of all ledger balances plus all box values — the invariant collateral. */
    private long totalMonetary() {
        long sum = ledger.getWalletValue(sender).amount();
        if (ledger.hasWallet(miner)) {
            sum += ledger.getWalletValue(miner).amount();
        }
        for (byte[] id : boxes.boxIdsByOwner(sender.toBytes(), null, 1000)) {
            sum += boxes.getCommitted(id).value();
        }
        return sum;
    }
}
