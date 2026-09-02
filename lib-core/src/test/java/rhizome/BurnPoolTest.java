package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.Burn;
import rhizome.core.blockchain.ContractProcessor;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.Executor;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.box.Box;
import rhizome.core.box.BoxProcessor;
import rhizome.core.box.BoxProcessor.BoxReceipt;
import rhizome.core.box.BoxProcessor.BoxResult;
import rhizome.core.box.BoxProcessor.ScanPage;
import rhizome.core.box.ScanPredicate;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.crypto.SHA256Hash;

import static rhizome.crypto.Crypto.generateKeyPairTyped;

/**
 * Pool membership (009-native-coin-burn, data-model.md §2 / contracts/native-coin-burn.md §2):
 * EVERY base unit a block credits to the miner that was not freshly minted is in the eligible
 * pool, and no minted term is. Driven at {@link Executor#executeBlock} with stub box/contract
 * processors (the {@code RollbackReceiptGuardTest} pattern), on a parent supply far above the
 * live target so the debt never binds and {@code burned = ⌊pool/2⌋} exactly. Each block's
 * committed supply is stamped by the TEST from the pool it believes the flows create — if the
 * executor's own accumulation disagrees, the exact identity check rejects the block and the
 * test fails. Membership is thereby proven by falsification at every site.
 */
class BurnPoolTest {

    /** Far above the live target: the debt is huge, so the burn is always the flow-limited case. */
    private static final long PARENT_SUPPLY = 10_000_000L;

    private final NetworkParameters params = CurveActiveNetwork.curveActiveTestnet();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final long height = 2;

    private static final rhizome.crypto.Crypto.KeyPair PAIR = generateKeyPairTyped();
    private final PublicKey senderKey = PAIR.publicKey();
    private final PrivateKey senderPrivate = PAIR.privateKey();
    private final PublicAddress sender = PublicAddress.of(senderKey);
    private final PublicAddress recipient = PublicAddress.random();
    private final PublicAddress miner = PublicAddress.random();
    private final PublicAddress collector = PublicAddress.random();

    private record Run(ExecutionStatus status, InMemoryLedger ledger, Set<PublicAddress> touched) {}

    /** A box processor whose run() returns the scripted result and serves an empty receipt list. */
    private static BoxProcessor boxReturning(BoxResult result) {
        return new BoxProcessor() {
            @Override public void begin() {}
            @Override public BoxResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                                           long amount, long nonce, byte[] data, long boxHeight) {
                return result;
            }
            @Override public void commit(long blockHeight) {}
            @Override public void discard() {}
            @Override public void revertBlock(long blockHeight) {}
            @Override public List<BoxReceipt> receipts(long blockHeight) { return List.of(); }
            @Override public Box get(byte[] boxId) { return null; }
            @Override public Box getCommitted(byte[] boxId) { return null; }
            @Override public List<byte[]> collectableBoxIds(long boxHeight, int limit) { return List.of(); }
            @Override public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
                return List.of();
            }
            @Override public ScanPage scan(ScanPredicate predicate, byte[] afterId, int limit, int window) {
                return new ScanPage(List.of(), null);
            }
        };
    }

    private static final BoxProcessor NO_BOX = boxReturning(BoxResult.fail(ExecutionStatus.BOX_NOT_FOUND));

    /** A token processor whose only job is to be available and succeed (the fee is all that moves). */
    private static final rhizome.core.token.TokenProcessor TOKEN_OK =
        new rhizome.core.token.TokenProcessor() {
            @Override public void begin() {}
            @Override public void commit(long blockHeight) {}
            @Override public void discard() {}
            @Override public void revertBlock(long blockHeight) {}
            @Override public TokenResult run(TransactionKind kind, PublicAddress from,
                    PublicAddress to, long nonce, byte[] data, long tokenHeight) {
                return new TokenResult(ExecutionStatus.SUCCESS, null);
            }
            @Override public rhizome.core.token.TokenMeta meta(rhizome.core.token.TokenId tokenId) {
                return null;
            }
            @Override public long balance(rhizome.core.token.TokenBalanceKey key) {
                return 0;
            }
            @Override public List<rhizome.core.token.TokenId> tokenIdsByMinter(byte[] minter,
                    rhizome.core.token.TokenId afterId, int limit) {
                return List.of();
            }
            @Override public List<rhizome.core.token.TokenId> tokenIdsByHolder(byte[] address,
                    rhizome.core.token.TokenId afterId, int limit) {
                return List.of();
            }
        };

    /** A contract processor that consumed {@code gasUsed} gas, succeeded or reverted as asked. */
    private static ContractProcessor contractReturning(long gasUsed, boolean success) {
        return new ContractProcessor() {
            @Override public void begin() {}
            @Override public ContractProcessor.ContractResult run(PublicAddress from,
                    TransactionKind kind, PublicAddress to, byte[] data, long value, long gasLimit,
                    long nonce) {
                return success
                    ? ContractProcessor.ContractResult.ok(gasUsed, new byte[0], null)
                    : ContractProcessor.ContractResult.reverted(gasUsed, "script failed");
            }
            @Override public void commit(long blockHeight) {}
            @Override public void discard() {}
            @Override public void revertBlock(long blockHeight) {}
            @Override public List<ContractProcessor.ContractReceipt> receipts(long blockHeight) {
                return List.of();
            }
        };
    }

    private Transaction transfer(long fee, long nonce) {
        Transaction t = Transaction.of(sender, recipient, new TransactionAmount(0), senderKey,
            new TransactionAmount(fee), clock.get(), params.chainId(), nonce);
        t.sign(senderPrivate);
        return t;
    }

    private Transaction boxTx(TransactionKind kind, PublicAddress to, long value, long fee, long nonce) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(to).signingKey(senderKey)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(fee))
            .chainId(params.chainId()).nonce(nonce).timestamp(clock.get())
            .kind(kind).data(new byte[0]).gasLimit(0).gasPrice(0)
            .build();
        t.sign(senderPrivate);
        return t;
    }

    private Transaction contractCall(long value, long gasLimit, long gasPrice, long nonce) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(PublicAddress.random()).signingKey(senderKey)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).timestamp(clock.get())
            .kind(TransactionKind.CALL).data(new byte[0]).gasLimit(gasLimit).gasPrice(gasPrice)
            .build();
        t.sign(senderPrivate);
        return t;
    }

    /**
     * A permissionless rent collect: empty from, zero fee and amount (the pass-1 payload rule).
     * Self-authorized: it names NO signing key at all — a set key would fail
     * {@code senderBindingValid()} against the empty {@code from} (the exact vector the pass-1
     * guard exists to close) — and no signature.
     */
    private Transaction rentCollect() {
        return TransactionImpl.builder()
            .from(PublicAddress.empty()).to(collector)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(0).timestamp(clock.get())
            .kind(TransactionKind.BOX_COLLECT).data(new byte[0]).gasLimit(0).gasPrice(0)
            .build();
    }

    /**
     * Executes one coinbase-plus-{@code txs} block against a fresh ledger funded with
     * {@code 50M} for the sender. {@code expectedPool} is what the TEST believes the flows
     * credit the miner beyond minted coin; the block is stamped
     * {@code parent + minted - ⌊expectedPool/2⌋}, so the executor must independently arrive at
     * the same pool or the identity check rejects the block.
     */
    private Run execute(List<Transaction> txs, long expectedPool, List<UncleRef> uncles,
                        BoxProcessor boxes, ContractProcessor contracts) {
        return execute(txs, expectedPool, uncles, boxes, contracts, null);
    }

    private Run execute(List<Transaction> txs, long expectedPool, List<UncleRef> uncles,
                        BoxProcessor boxes, ContractProcessor contracts,
                        rhizome.core.token.TokenProcessor tokens) {
        Set<PublicAddress> touched = new LinkedHashSet<>();
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(sender);
        ledger.deposit(sender, new TransactionAmount(50_000_000L));
        long minted = Issuance.minted(params, height, PARENT_SUPPLY, params.genesisDifficulty(),
            uncles);
        long expectedBurned = Burn.burned(params, height, expectedPool,
            Burn.debt(params, height, PARENT_SUPPLY, minted));
        BlockImpl b = stampedBlock(txs, uncles, minted, expectedBurned);
        ExecutionStatus status = Executor.executeBlock(b, ledger, hash -> false, params, null,
            contracts, boxes, tokens, touched, PARENT_SUPPLY).status();
        return new Run(status, ledger, touched);
    }

    private BlockImpl stampedBlock(List<Transaction> txs, List<UncleRef> uncles, long minted,
                                   long burned) {
        return stampedBlock(txs, uncles, minted, burned, miner);
    }

    /** As above, paying the coinbase to {@code coinbaseTo} — the miner the burn withdraws from. */
    private BlockImpl stampedBlock(List<Transaction> txs, List<UncleRef> uncles, long minted,
                                   long burned, PublicAddress coinbaseTo) {
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(5_000L))
            .difficulty(params.genesisDifficulty())
            .lastBlockHash(SHA256Hash.random())
            .uncles(new java.util.ArrayList<>(uncles))
            .supply(PARENT_SUPPLY + minted - burned)
            .build();
        b.addTransaction(Transaction.of(coinbaseTo,
            new TransactionAmount(params.miningReward(height, PARENT_SUPPLY))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        return b;
    }

    @Test
    void everyMinerCreditedNonMintedBaseUnitIsInThePoolAndNoMintedOneIs() {
        // Site 1 — the transfer fee.
        assertEquals(ExecutionStatus.SUCCESS,
            execute(List.of(transfer(500, 0)), 500, List.of(), NO_BOX,
                contractReturning(0, true)).status());

        // Site 2 — the box fee on a SUCCESSFUL op. The locked value (0 here) is the box's, and
        // only the fee is miner revenue.
        assertEquals(ExecutionStatus.SUCCESS,
            execute(List.of(boxTx(TransactionKind.BOX_CREATE, sender, 0, 300, 0)), 300,
                List.of(), boxReturning(new BoxResult(ExecutionStatus.SUCCESS, 0, 0, 0, null)),
                contractReturning(0, true)).status());

        // Site 3 lands in aSoftReverted..., site 4 here — the contract gas fee.
        assertEquals(ExecutionStatus.SUCCESS,
            execute(List.of(contractCall(0, 1_000, 2, 0)), 2_000, List.of(), NO_BOX,
                contractReturning(1_000, true)).status());

        // Site 5 — box storage rent (paid to the miner out of the box's locked value).
        assertEquals(ExecutionStatus.SUCCESS,
            execute(List.of(rentCollect()), 700, List.of(),
                boxReturning(new BoxResult(ExecutionStatus.SUCCESS, 0, 0, 700, null)),
                contractReturning(0, true)).status());

        // Site 6 — the token-op fee (contracts/native-coin-burn.md §2 lists TOKEN fees as
        // contributing). A TOKEN op moves only its fee.
        Transaction tokenTx = TransactionImpl.builder()
            .from(sender).to(recipient).signingKey(senderKey)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(150))
            .chainId(params.chainId()).nonce(0).timestamp(clock.get())
            .kind(TransactionKind.TOKEN_TRANSFER).data(new byte[0]).gasLimit(0).gasPrice(0)
            .build();
        tokenTx.sign(senderPrivate);
        assertEquals(ExecutionStatus.SUCCESS,
            execute(List.of(tokenTx), 150, List.of(), NO_BOX, contractReturning(0, true),
                TOKEN_OK).status());

        // Exclusion — the coinbase subsidy: a coinbase-only block pools NOTHING. (If the
        // subsidy were pooled, the test's 0-burn stamp would fail the identity check.)
        assertEquals(ExecutionStatus.SUCCESS, execute(List.of(), 0, List.of(), NO_BOX,
            contractReturning(0, true)).status());

        // Exclusion — uncle and nephew rewards: minted coin credited to the uncle's miner and
        // the nephew (this block's miner), never pooled. The terms must still be in `minted`
        // for the stamp, which Issuance.minted supplies from the block's own uncle refs.
        PublicAddress uncleMiner = PublicAddress.random();
        UncleRef ref = new UncleRef(SHA256Hash.random(), params.genesisDifficulty(), uncleMiner);
        long withUncle = Issuance.minted(params, height, PARENT_SUPPLY, params.genesisDifficulty(),
            List.of(ref));
        assertTrue(withUncle > params.miningReward(height, PARENT_SUPPLY),
            "sanity: the uncle contributes real minted terms");
        assertEquals(ExecutionStatus.SUCCESS,
            execute(List.of(), 0, List.of(ref), NO_BOX, contractReturning(0, true)).status(),
            "uncle/nephew credits must stay out of the pool");

        // Exclusion — contract native transfers(): the contract's OWN coin moving to a
        // recipient is not miner revenue; the gas fee alone is pooled.
        PublicAddress contractAccount = PublicAddress.random();
        PublicAddress payoutTarget = PublicAddress.random();
        Set<PublicAddress> touched = new LinkedHashSet<>();
        InMemoryLedger payoutLedger = new InMemoryLedger();
        payoutLedger.createWallet(sender);
        payoutLedger.deposit(sender, new TransactionAmount(50_000_000L));
        payoutLedger.createWallet(contractAccount);
        payoutLedger.deposit(contractAccount, new TransactionAmount(10_000L));
        long gasFee = 2_000;
        BlockImpl payout = stampedBlock(List.of(contractCall(0, 1_000, 2, 0)), List.of(),
            params.miningReward(height, PARENT_SUPPLY), gasFee / 2);
        ContractProcessor paying = new ContractProcessor() {
            @Override public void begin() {}
            @Override public ContractProcessor.ContractResult run(PublicAddress from,
                    TransactionKind kind, PublicAddress to, byte[] data, long value, long gasLimit,
                    long nonce) {
                return ContractProcessor.ContractResult.ok(1_000, new byte[0], null, List.of(),
                    List.of(new ContractProcessor.NativeTransfer(contractAccount, payoutTarget, 4_321)));
            }
            @Override public void commit(long blockHeight) {}
            @Override public void discard() {}
            @Override public void revertBlock(long blockHeight) {}
            @Override public List<ContractProcessor.ContractReceipt> receipts(long blockHeight) {
                return List.of();
            }
        };
        assertEquals(ExecutionStatus.SUCCESS, Executor.executeBlock(payout, payoutLedger,
            hash -> false, params, null, paying, NO_BOX, null, touched, PARENT_SUPPLY).status(),
            "the gas fee is pooled; the native payout is not");
        assertEquals(4_321, payoutLedger.getWalletValue(payoutTarget).amount(),
            "the payout reached its recipient untouched by the burn");

        // Exclusion — the attached value of a successful call: it belongs to the contract now.
        // Only the gas fee is pooled.
        assertEquals(ExecutionStatus.SUCCESS,
            execute(List.of(contractCall(3_000, 1_000, 2, 0)), 2_000, List.of(), NO_BOX,
                contractReturning(1_000, true)).status());
    }

    @Test
    void aSoftRevertedTransactionStillContributesItsFeeAndGas() {
        // P-3: the pool is what the miner RECEIVED, not what succeeded. A box op whose
        // precondition failed still charged its fee; a contract call that still REVERTED still
        // burned its gas. Both credits are pooled.
        assertEquals(ExecutionStatus.SUCCESS,
            execute(List.of(boxTx(TransactionKind.BOX_SPEND, sender, 0, 444, 0)), 444,
                List.of(), NO_BOX, contractReturning(0, true)).status());
        assertEquals(ExecutionStatus.SUCCESS,
            execute(List.of(contractCall(0, 1_000, 2, 0)), 2_000, List.of(), NO_BOX,
                contractReturning(1_000, false)).status(), "reverted gas is still pooled");
    }

    @Test
    void boxCollectRentIsPooledWhileTheCollectorsCreditIsUntouched() {
        // T026: one BOX_COLLECT that releases accrued rent (700, to the miner — pooled) and the
        // box's released value (5 000, to the collector — the box's own coin, NOT pooled).
        Run run = execute(List.of(rentCollect()), 700, List.of(),
            boxReturning(new BoxResult(ExecutionStatus.SUCCESS, 0, 5_000, 700, null)),
            contractReturning(0, true));
        assertEquals(ExecutionStatus.SUCCESS, run.status());
        assertEquals(5_000, run.ledger().getWalletValue(collector).amount(),
            "the collector received exactly the released value, untouched by the burn");
        assertTrue(run.touched().contains(collector),
            "the collector is a real ledger-touched address; the burned coin is in neither set");
    }

    @Test
    void destroyedCoinLeavesNoLedgerEntryAndNoStateRootRecord() {
        // FR-021: destruction is the ABSENCE of coin, never a transfer. After a block that
        // burns, every address's balance is exactly its flows and the ledger total dropped by
        // precisely the burned amount — no burn address, no sentinel wallet. And since the
        // state root commits ledger balances, the touched-ledger set — the exact address set
        // the state accumulator folds — proves the second half: only real flow participants
        // appear in it, never a destination for the destroyed coin.
        //
        // Flows (all ledger-internal): transfer fee 100 + box fee (success, value 0) 40
        // + box fee (soft revert) 60 + contract gas 800 (400 gas x 2) => pool 1 000,
        // burned 500. The soft-reverted SPEND's fee is pooled too (P-3).
        Set<PublicAddress> touched = new LinkedHashSet<>();
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(sender);
        ledger.deposit(sender, new TransactionAmount(50_000_000L));
        long before = 50_000_000L;

        long coinbase = params.miningReward(height, PARENT_SUPPLY);
        long minted = Issuance.minted(params, height, PARENT_SUPPLY, params.genesisDifficulty(),
            List.of());
        assertEquals(coinbase, minted, "sanity: no uncles, minted is the coinbase alone");
        long pool = 1_000;
        long burned = pool / 2;
        BlockImpl b = stampedBlock(List.of(
            transfer(100, 0),
            boxTx(TransactionKind.BOX_CREATE, sender, 0, 40, 1),
            boxTx(TransactionKind.BOX_SPEND, sender, 0, 60, 2),
            contractCall(0, 400, 2, 3)), List.of(), minted, burned);
        assertEquals(ExecutionStatus.SUCCESS, Executor.executeBlock(b, ledger, hash -> false,
            params, null, contractReturning(400, true),
            new BoxProcessor() {
                private int call;

                @Override public void begin() {}
                @Override public BoxResult run(TransactionKind kind, PublicAddress from,
                        PublicAddress to, long amount, long nonce, byte[] data, long boxHeight) {
                    return ++call == 1
                        ? new BoxResult(ExecutionStatus.SUCCESS, 0, 0, 0, null)  // CREATE succeeds
                        : BoxResult.fail(ExecutionStatus.BOX_NOT_FOUND);         // SPEND soft-reverts
                }
                @Override public void commit(long blockHeight) {}
                @Override public void discard() {}
                @Override public void revertBlock(long blockHeight) {}
                @Override public List<BoxReceipt> receipts(long blockHeight) { return List.of(); }
                @Override public Box get(byte[] boxId) { return null; }
                @Override public Box getCommitted(byte[] boxId) { return null; }
                @Override public List<byte[]> collectableBoxIds(long boxHeight, int limit) {
                    return List.of();
                }
                @Override public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
                    return List.of();
                }
                @Override public ScanPage scan(ScanPredicate predicate, byte[] afterId, int limit,
                        int window) {
                    return new ScanPage(List.of(), null);
                }
            }, null, touched, PARENT_SUPPLY).status());

        // Every address is exactly its flows — the soft-reverted SPEND's fee included (P-3).
        assertEquals(before - (100 + 40 + 60 + 800), ledger.getWalletValue(sender).amount(),
            "the sender paid exactly the four charges, nothing more");
        assertEquals(0, ledger.balanceOrZero(recipient),
            "amounts move, fees never do — the recipient's balance is unchanged");
        assertEquals(coinbase + pool - burned, ledger.getWalletValue(miner).amount(),
            "the miner keeps the subsidy and half the pool: the burn is one withdrawal");

        // The system total over every ledger balance: down by EXACTLY the burned amount once
        // minting is counted. The destroyed coin is in NO address.
        long[] total = {0};
        ledger.forEachBalance((address, balance) -> total[0] += balance);
        assertEquals(before + minted - burned, total[0],
            "minted coin minus destroyed coin: the burned 500 is nowhere in the ledger");

        // The state-root-facing set: only real flow participants. Any design that routed the
        // burn through a burn address or sentinel wallet would show up here as a third entry.
        assertEquals(Set.of(sender, miner), touched,
            "no ledger entry and therefore no state-root record represents the destroyed coin");
    }

    @Test
    void aMinerWhoSpendsItsPoolIncomeBeforeTheBurnSiteIsRejected() {
        // The burn withdrawal runs AFTER every transaction, and nothing stops the miner from
        // being the SENDER of a later in-block transaction: a wallet that sweeps its fee income
        // in the same block spends the very balance the burn is about to withdraw. There is no
        // underflow invariant at the site — the miner's balance at burn time is simply whatever
        // the miner left — so the ledger's checked withdrawal throws and the executor's
        // transactionality rejects the whole block BALANCE_TOO_LOW: deterministic on every node
        // (the identical body executes) and self-inflicted (the miner authored it), so no fork
        // and no third-party DoS. This pins the miner rule: keep at least ⌊pool × βₙ/β_d⌋ of the
        // block's fee income unspent, or spend it next block.
        var minerPair = generateKeyPairTyped();
        PublicAddress spendingMiner = PublicAddress.of(minerPair.publicKey());
        long fee = 100_000L;
        // The sweep: the miner sends its whole pool income onward at zero fee (testnet has no
        // fee floor), so its balance at the burn site is the coinbase alone — far below the burn.
        Transaction sweep = Transaction.of(spendingMiner, recipient,
            new TransactionAmount(fee), minerPair.publicKey(), new TransactionAmount(0),
            clock.get(), params.chainId(), 0);
        sweep.sign(minerPair.privateKey());

        InMemoryLedger ledger = new InMemoryLedger();
        ledger.createWallet(sender);
        ledger.deposit(sender, new TransactionAmount(50_000_000L));
        long coinbase = params.miningReward(height, PARENT_SUPPLY);
        long minted = coinbase; // no uncles
        long burned = fee / 2; // the flow-limited share; the debt never binds this far above S*
        assertTrue(burned > coinbase,
            "sanity: the burn must exceed what the swept miner holds at the burn site");
        BlockImpl b = stampedBlock(List.of(transfer(fee, 0), sweep), List.of(), minted, burned,
            spendingMiner);

        Set<PublicAddress> touched = new LinkedHashSet<>();
        ExecutionStatus status = Executor.executeBlock(b, ledger, hash -> false, params, null,
            contractReturning(0, true), NO_BOX, null, touched, PARENT_SUPPLY).status();
        assertEquals(ExecutionStatus.BALANCE_TOO_LOW, status,
            "the burn cannot be funded from coin the miner already spent");
        assertEquals(50_000_000L, ledger.getWalletValue(sender).amount(),
            "the rejection rolled the fee withdrawal back exactly");
        assertEquals(0L, ledger.balanceOrZero(spendingMiner),
            "the rejection rolled the fee credit and the sweep back exactly");
    }
}
