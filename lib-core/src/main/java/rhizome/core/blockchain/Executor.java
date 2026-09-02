package rhizome.core.blockchain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.box.BoxProcessor;
import rhizome.core.token.TokenProcessor;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

import static rhizome.core.mempool.ExecutionStatus.*;

/**
 * Validates and applies a block's transactions to the ledger.
 *
 * <p>Clean-chain rules (Pandanite parity where sound, fixed where it was not):
 * <ol>
 *   <li>Exactly one coinbase transaction; its amount must equal the expected
 *       mining reward for the block height ({@code NetworkParameters.miningReward},
 *       integer-only — no float comparison forks).</li>
 *   <li>Every other transaction must: target this network
 *       ({@code chainId}), have a valid signature whose key matches the sender
 *       address, and not be a duplicate (in-block or already executed —
 *       identified by the signature-free content hash, immune to Ed25519
 *       malleability).</li>
 *   <li>Balance checks and mutations go through the {@link Ledger}, whose
 *       checked arithmetic rejects underflow — the C++ ledger's unchecked
 *       {@code uint64} subtraction is what inflated balances in the
 *       invalid.json incident.</li>
 *   <li>Application is transactional: on any failure every applied operation is
 *       rolled back in reverse order and the failing status returned.</li>
 * </ol>
 *
 * <p>Account-nonce ordering (strictly increasing per sender) is enforced at the
 * engine level where the nonce store lives; the executor validates everything
 * that is ledger-local.
 */
public final class Executor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Executor.class);

    private Executor() {}

    /** One applied ledger mutation, recorded for rollback. */
    private record AppliedOp(Op op, PublicAddress wallet, TransactionAmount amount) {
        enum Op { WITHDRAW, DEPOSIT }
    }

    /**
     * The outcome of applying one contract/box/token transaction: its block-validity status and
     * the amount it credited the miner that was not freshly minted — the fee, the gas fee or the
     * storage rent — i.e. its contribution to the eligible burn pool (009 T028). Returning the
     * credit from the exact site that made it keeps the pool's accumulation in application order
     * (P-2) with no re-derivation anywhere else.
     */
    private record DomainOutcome(ExecutionStatus status, long credited) {
        static DomainOutcome of(ExecutionStatus status) {
            return new DomainOutcome(status, 0);
        }
    }

    /**
     * Validates and applies {@code block} to {@code ledger}.
     *
     * @param alreadyExecuted membership test over content hashes of transactions
     *                        the chain has already executed (backed by the txdb)
     * @return {@link ExecutionStatus#SUCCESS}, or the failure status with the
     *         ledger left exactly as it was
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params) {
        return executeBlock(block, ledger, alreadyExecuted, params, null, null, null);
    }

    /**
     * As above, with a {@link SignatureVerifier} for batch signature checks (skipped when
     * {@code verifier} is null), a {@link ContractProcessor} for the contract kinds, and a
     * {@link BoxProcessor} for the box transaction kinds (BOX_CREATE/UPDATE/SPEND/COLLECT).
     * A null processor rejects that domain's transactions. Box state is staged in a
     * per-block session that commits atomically with the block, exactly like contract state.
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params,
                                               SignatureVerifier verifier,
                                               ContractProcessor processor,
                                               BoxProcessor boxProcessor) {
        return executeBlock(block, ledger, alreadyExecuted, params, verifier, processor, boxProcessor, null);
    }

    /**
     * As above, with a {@link TokenProcessor} for the native-token kinds
     * (TOKEN_MINT/TRANSFER/BURN). When {@code tokenProcessor} is null, token
     * transactions are rejected. Token state is staged in a per-block session that
     * commits atomically with the block.
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params,
                                               SignatureVerifier verifier,
                                               ContractProcessor processor,
                                               BoxProcessor boxProcessor,
                                               TokenProcessor tokenProcessor) {
        return executeBlock(block, ledger, alreadyExecuted, params, verifier,
            processor, boxProcessor, tokenProcessor, null);
    }

    /**
     * As above, additionally collecting into {@code touchedLedger} (when non-null) every
     * ledger address this block credited or debited — so the caller can read their final
     * balances to feed the authenticated state accumulator.
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params,
                                               SignatureVerifier verifier,
                                               ContractProcessor processor,
                                               BoxProcessor boxProcessor,
                                               TokenProcessor tokenProcessor,
                                               Set<PublicAddress> touchedLedger) {
        return executeBlock(block, ledger, alreadyExecuted, params, verifier,
            processor, boxProcessor, tokenProcessor, touchedLedger, BlockImpl.SUPPLY_ABSENT);
    }

    /**
     * As above, with a caller-supplied convenience form that skips straight to the
     * supply-aware coinbase check (research.md Decision 4): {@code parentSupply} is the
     * parent block's committed circulating supply, or {@link BlockImpl#SUPPLY_ABSENT} when
     * unknown/uncommitted.
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params, long parentSupply) {
        return executeBlock(block, ledger, alreadyExecuted, params, null, null, null, null, null,
            parentSupply);
    }

    /**
     * As above, with the parent block's committed circulating supply ({@code parentSupply}, or
     * {@link BlockImpl#SUPPLY_ABSENT} when unknown/uncommitted) threaded through to the
     * supply-aware coinbase check (research.md Decision 4).
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params,
                                               SignatureVerifier verifier,
                                               ContractProcessor processor,
                                               BoxProcessor boxProcessor,
                                               TokenProcessor tokenProcessor,
                                               Set<PublicAddress> touchedLedger,
                                               long parentSupply) {
        // A null processor means "this node has no such domain". Normalize here so the body below
        // talks to a processor rather than guarding against a null one; absence is then expressed
        // by available(), and the pass-1 rejections keep their exact semantics.
        return runBlock(block, ledger, alreadyExecuted, params, verifier,
            processor == null ? ContractProcessor.NONE : processor,
            boxProcessor == null ? BoxProcessor.NONE : boxProcessor,
            tokenProcessor == null ? TokenProcessor.NONE : tokenProcessor,
            touchedLedger, parentSupply);
    }

    /** As {@link #executeBlock}, with the three domains guaranteed non-null. */
    private static ExecutionStatus runBlock(Block block, Ledger ledger,
                                            Predicate<SHA256Hash> alreadyExecuted,
                                            NetworkParameters params,
                                            SignatureVerifier verifier,
                                            ContractProcessor processor,
                                            BoxProcessor boxProcessor,
                                            TokenProcessor tokenProcessor,
                                            Set<PublicAddress> touchedLedger,
                                            long parentSupply) {
        List<BlockStateProcessor> domains =
            BlockStateProcessor.inCommitOrder(processor, boxProcessor, tokenProcessor);
        Block blockImpl = block;
        long height = blockImpl.id();
        long expectedReward;
        if (params.emissionCurveActiveAt(height)) {
            if (parentSupply == BlockImpl.SUPPLY_ABSENT) {
                return INCORRECT_MINING_FEE;
            }
            expectedReward = params.miningReward(height, parentSupply);
        } else {
            expectedReward = params.miningReward(height);
        }
        // Consensus-V2 gate (see NetworkParameters.consensusV2Height): below the activation
        // height the legacy rules apply — no consensus fee floor, and a zero deposit still
        // creates the recipient wallet — so pre-existing history re-verifies unchanged.
        boolean consensusV2 = params.consensusV2(height);

        // Batch-verify all signatures in parallel before the structural pass.
        if (verifier != null && !verifier.verifyAll(block.transactions())) {
            return INVALID_SIGNATURE;
        }

        // --- Pass 1: structural validation, no state touched ---
        Transaction coinbase = null;
        Set<SHA256Hash> seenInBlock = new HashSet<>();
        int boxCollects = 0;
        long blockGas = 0;
        for (Transaction t : block.transactions()) {
            Transaction tx = t;
            if (tx.isTransactionFee()) {
                if (coinbase != null) {
                    return EXTRA_MINING_FEE;
                }
                // The coinbase must be a plain TRANSFER. The wire codec (TransactionDto)
                // serializes `kind` independently of the isTransactionFee flag, and the
                // signature verifier passes a fee tx unconditionally — so without this check a
                // coinbase carrying kind=CALL/BOX_*/TOKEN_* sailed through validation. The apply
                // pass below (and the reorg walk) skips fee txs before dispatching on kind, so
                // the poisoned kind never executed — but any code that walks the block by kind
                // alone (e.g. counting expected contract/box receipts) then disagreed with the
                // receipt lists, and a popBlock blew up mid-reorg with the ledger half-reverted:
                // a permanent-fork poison block. Reject it on the cheap structural pass.
                if (tx.kind() != rhizome.core.transaction.TransactionKind.TRANSFER) {
                    return INCORRECT_MINING_FEE;
                }
                coinbase = t;
                continue;
            }
            if (tx.chainId() != params.chainId()) {
                return INVALID_CHAIN_ID;
            }
            // Contract transactions execute only when a processor is wired; without
            // one, consensus does not run them, so a block carrying one is rejected —
            // no contract tx can be mistaken for a transfer.
            if (tx.kind().isContract()) {
                if (!processor.available()) {
                    return CONTRACT_EXECUTION_UNAVAILABLE;
                }
                // Consensus gas ceiling. gasLimit is otherwise bounded only by affordability, and at
                // gasPrice 0 (valid here — min-fee is mempool-only) a free call can name an arbitrary
                // limit; the VM would then run that many instructions under the consensus lock on every
                // node. Cap one call, and cap the block's declared-gas total, BEFORE Pass 2 runs any
                // instruction — so a "poison block" is rejected on the cheap structural pass rather than
                // executed (audit: unbounded consensus gas). Checked identically on every node, so it is
                // a pure consensus rule; 0 disables either cap.
                long gasLimit = tx.gasLimit();
                if (gasLimit < 0) {
                    return INVALID_TRANSACTION_AMOUNT;
                }
                if (params.maxTxGas() > 0 && gasLimit > params.maxTxGas()) {
                    return GAS_LIMIT_EXCEEDED;
                }
                if (params.maxBlockGas() > 0) {
                    try {
                        blockGas = Math.addExact(blockGas, gasLimit);
                    } catch (ArithmeticException overflow) {
                        return GAS_LIMIT_EXCEEDED; // an unbounded-gasLimit sum can only be over the cap
                    }
                    if (blockGas > params.maxBlockGas()) {
                        return GAS_LIMIT_EXCEEDED;
                    }
                }
            }
            if (tx.kind().isBox()) {
                if (!boxProcessor.available() || !params.boxActiveAt(height)) {
                    return BOX_UNAVAILABLE;
                }
                // Box ops run no VM and cost no gas; the gas fields are reserved and must
                // be zero (else the signed preimage could carry hidden, unpriced data).
                if (tx.gasLimit() != 0 || tx.gasPrice() != 0) {
                    return BOX_PAYLOAD_INVALID;
                }
                if (tx.kind() == rhizome.core.transaction.TransactionKind.BOX_COLLECT) {
                    if (++boxCollects > params.maxBoxCollectsPerBlock()) {
                        return BOX_LIMIT_EXCEEDED;
                    }
                    // BOX_COLLECT is self-authorized: signatureValid() returns true unconditionally
                    // and the account-nonce rule is skipped (ChainEngine.isSelfAuthorized). Its only
                    // gate on `from` is PublicAddress.of(signingKey).equals(from) at pass end, which
                    // an attacker satisfies with the victim's PUBLIC key (from=victim, signingKey=
                    // victim's pubkey) — no private key, no signature. Without this guard applyBox
                    // would then debit `fee + debitFrom` from that `from`, letting any block producer
                    // mint an unsigned rent collector whose fee drains an arbitrary victim's balance
                    // into the miner's coinbase. An honest collector (BlockAssembler) always carries
                    // an empty `from` and zero value/fee, so require exactly that: a self-authorized
                    // tx may never name a funded sender or move sender value.
                    if (!tx.from().equals(PublicAddress.empty())
                        || tx.fee().amount() != 0 || tx.amount().amount() != 0) {
                        return BOX_PAYLOAD_INVALID;
                    }
                }
            }
            if (tx.kind().isToken()) {
                if (!tokenProcessor.available() || !params.tokenActiveAt(height)) {
                    return TOKEN_UNAVAILABLE;
                }
                // Token ops run no VM, cost no gas, and move no PDN — the token amount lives
                // in the payload, so the gas fields and the PDN amount field must be zero.
                if (tx.gasLimit() != 0 || tx.gasPrice() != 0 || tx.amount().amount() != 0) {
                    return TOKEN_PAYLOAD_INVALID;
                }
            }
            // A negative amount or fee would invert the ledger arithmetic: withdrawing
            // a negative value MINTS money for the sender and deposits drive the
            // recipient's balance negative. Amounts are conceptually unsigned, so any
            // negative long (including values with the high bit set) is illegal.
            if (tx.amount().amount() < 0 || tx.fee().amount() < 0) {
                return INVALID_TRANSACTION_AMOUNT;
            }
            // Consensus fee floor — the SAME rule MemPool.addTransaction applies at admission,
            // promoted into validation from consensusV2Height on (audit: fee floor was
            // mempool-only). The rule itself is one expression, shared with the mempool —
            // {@link FeePolicy#underMinFee}; only the activation gate differs (the mempool
            // applies the floor unconditionally, which is the stricter, safe direction).
            // Without the consensus copy a miner
            // could include zero-fee transfers the relay policy refuses: amount 0 + fee 0 minted
            // a permanent ledger entry per transfer (deposit created the recipient wallet), and
            // gasPrice-0 calls ran compute no honest node was paid for. The rule is identical to
            // the mempool's (miner revenue, so a contract call's declared gas budget counts), so
            // every mempool-admitted transaction stays consensus-valid. BOX_COLLECT is exempt: it
            // is self-authorized, minted by the block producer (never pooled), and must carry
            // fee 0 by the payload rule above. Networks keep the floor at 0 to disable it.
            // Below consensusV2Height the legacy rule applies: no consensus floor at all, so
            // blocks already accepted with under-floor fees re-verify unchanged.
            if (consensusV2 && FeePolicy.underMinFee(params, tx)) {
                return TRANSACTION_FEE_TOO_LOW;
            }
            SHA256Hash id = t.hashContents();
            if (!seenInBlock.add(id) || alreadyExecuted.test(id)) {
                return EXPIRED_TRANSACTION;
            }
            if (!tx.senderBindingValid()) {
                return WALLET_SIGNATURE_MISMATCH;
            }
            if (verifier == null && !t.signatureValid()) {
                return INVALID_SIGNATURE;
            }
        }
        if (coinbase == null) {
            return NO_MINING_FEE;
        }
        if (coinbase.amount().amount() != expectedReward) {
            return INCORRECT_MINING_FEE;
        }

        // --- Pass 2: transactional application ---
        PublicAddress miner = coinbase.to();
        List<AppliedOp> applied = new ArrayList<>();
        // The eligible burn pool (009-native-coin-burn): every base unit this block credits to
        // the miner that was NOT freshly minted, accumulated at the moment of each credit, in
        // application order — so it is identical on every node (P-2). Math.addExact: an overflow
        // rejects the block rather than wrapping into a plausible-looking burn (P-5/P-1). The
        // pool is one long, local to this execution: never persisted, never published, never in
        // the state root (P-4).
        long pool = 0;
        for (BlockStateProcessor domain : domains) {
            domain.begin();
        }
        try {
            for (Transaction t : block.transactions()) {
                Transaction tx = t;
                if (tx.isTransactionFee()) {
                    continue;
                }
                if (tx.kind().isContract()) {
                    DomainOutcome contractStatus = applyContract(tx, ledger, applied, miner, processor);
                    if (contractStatus.status() != SUCCESS) {
                        return abort(domains, ledger, applied, contractStatus.status());
                    }
                    pool = Math.addExact(pool, contractStatus.credited());
                    continue;
                }
                if (tx.kind().isBox()) {
                    DomainOutcome boxStatus = applyBox(tx, ledger, applied, miner, boxProcessor, height);
                    if (boxStatus.status() != SUCCESS) {
                        return abort(domains, ledger, applied, boxStatus.status());
                    }
                    pool = Math.addExact(pool, boxStatus.credited());
                    continue;
                }
                if (tx.kind().isToken()) {
                    DomainOutcome tokenStatus = applyToken(tx, ledger, applied, miner, tokenProcessor, height);
                    if (tokenStatus.status() != SUCCESS) {
                        return abort(domains, ledger, applied, tokenStatus.status());
                    }
                    pool = Math.addExact(pool, tokenStatus.credited());
                    continue;
                }
                long amount = tx.amount().amount();
                long fee = tx.fee().amount();
                long charged;
                try {
                    charged = Math.addExact(amount, fee);
                } catch (ArithmeticException e) {
                    return abort(domains, ledger, applied, BALANCE_TOO_LOW);
                }

                // Block validity must be a pure function of BALANCE, never of ledger key-presence.
                // hasWallet returns true for a "phantom" 0-balance key left behind by any apply-then-
                // rollback (a failed pass-2, popBlock reorg, stampStateRoot undo), whereas the state root
                // treats a 0 balance as absent (collectStateChanges emits delete). So gating on hasWallet
                // let a charged==0 (amount 0, fee 0) transfer be SUCCESS on a node that had reverted the
                // sender into existence but SENDER_DOES_NOT_EXIST on a node that synced the winning chain
                // directly — the same block valid on one honest node and invalid on another → permanent
                // fork. Treating an absent wallet as balance 0 makes both nodes agree (audit 5th-pass,
                // consensus Finding 1). charged>0 still requires balance>=charged>0, i.e. a real wallet,
                // so the withdraw below never touches a non-existent one.
                long available = ledger.balanceOrZero(tx.from()); // one store read (audit perf)
                if (available < charged) {
                    return abort(domains, ledger, applied, BALANCE_TOO_LOW);
                }

                if (charged > 0) {
                    withdraw(ledger, applied, tx.from(), new TransactionAmount(charged));
                }
                deposit(ledger, applied, tx.to(), tx.amount(), consensusV2);
                if (fee > 0) {
                    deposit(ledger, applied, miner, new TransactionAmount(fee));
                    pool = Math.addExact(pool, fee); // pool site 1: the transfer fee (P-2)
                }
            }
            deposit(ledger, applied, miner, coinbase.amount(), consensusV2);
            // GHOST uncle rewards: fresh issuance to each referenced uncle's miner, plus a
            // nephew bonus to this block's miner. Every uncle is a real PoW block, so no
            // reward is minted without matching work. Uncle validity (miner address, depth,
            // no double-crediting) was already enforced by the engine.
            payUncleRewards(blockImpl, ledger, applied, miner, params, parentSupply);
            // The burn (009-native-coin-burn, T029): ONE withdrawal from the miner, at this one
            // fixed point after every transaction and after payUncleRewards — so it is provably
            // exclusive of every minted term (the coinbase subsidy and the uncle/nephew rewards
            // are all behind us) and identical on every node (B-4). The `> 0` guard mirrors the
            // deposit convention: where nothing is owed or the pool rounds to nothing, no ledger
            // op is recorded at all, so the journal, the touched set and the state root all see
            // a block with no burn in it — exactly the pre-feature shape.
            if (params.emissionCurveActiveAt(height) && parentSupply != BlockImpl.SUPPLY_ABSENT) {
                long minted = Issuance.minted(params, height, parentSupply, blockImpl.difficulty(),
                    blockImpl.uncles());
                long debt = Burn.debt(params, height, parentSupply, minted);
                long burned = Burn.burned(params, height, pool, debt);
                if (burned > 0) {
                    // Cannot underflow: the miner was just credited the whole pool plus a strictly
                    // positive subsidy, and burned <= pool by construction (B-2).
                    withdraw(ledger, applied, miner, new TransactionAmount(burned));
                }
                // The EXACT supply identity (T030, FR-013/FR-016): beside the state-root check's
                // discipline — the block is fully rolled back (abort below) before the rejection
                // is returned. This is the post-execution half of the split validation: the
                // pre-PoW gate (SupplyGate) can only check the header-only bound, because the
                // pool is not known until the block has actually executed.
                long expectedSupply;
                try {
                    expectedSupply = Math.subtractExact(Math.addExact(parentSupply, minted), burned);
                } catch (ArithmeticException overflow) {
                    return abort(domains, ledger, applied, INVALID_SUPPLY);
                }
                if (blockImpl.supply() != BlockImpl.SUPPLY_ABSENT
                    && blockImpl.supply() != expectedSupply) {
                    return abort(domains, ledger, applied, INVALID_SUPPLY);
                }
            }
            for (BlockStateProcessor domain : domains) {
                domain.commit(blockImpl.id());
            }
            // Record the ledger undo journal for this block: every mutation just applied, in
            // application order, so a reorg can replay the exact inverses instead of re-deriving
            // them arithmetically from the transaction (audit: one undo protocol, and a journal
            // for the ledger). A ledger without a journal (the genesis ledger, tests) ignores it
            // and its reorg path falls back to the mirrors.
            ledger.applyBlock(height, toLedgerOps(applied));
            // Report every touched ledger address (each applied op names its wallet) so the
            // caller can read final balances for the state accumulator.
            if (touchedLedger != null) {
                for (AppliedOp op : applied) {
                    touchedLedger.add(op.wallet());
                }
            }
            return SUCCESS;
        } catch (LedgerException e) {
            return abort(domains, ledger, applied, BALANCE_TOO_LOW);
        } catch (ArithmeticException e) {
            // A deposit that would overflow a wallet's 64-bit balance (Math.addExact
            // in the ledger) must be rejected cleanly, not left as a partial mutation.
            // Underflow is already a LedgerException above; this is the overflow twin.
            return abort(domains, ledger, applied, BALANCE_OVERFLOW);
        } catch (RuntimeException | Error fatal) {
            // A fatal failure mid-block (the VM surfaces a node-level OOM as IllegalStateException
            // rather than heap-dependent gasUsed — see WasmVm) must not slip past abort(): without
            // this catch the contract/box/token sessions stay open with staged mutations and the
            // ledger keeps its partially applied ops — silent state corruption instead of the
            // intended fail-stop. Clean up exactly like a soft abort, then rethrow so the caller
            // still fails the block loudly.
            try {
                abort(domains, ledger, applied, BALANCE_OVERFLOW);
            } catch (RuntimeException | Error cleanupFailure) {
                fatal.addSuppressed(cleanupFailure);
            }
            throw fatal;
        }
    }

    /**
     * Mints the GHOST uncle and nephew rewards for a block's referenced uncles. {@code parentSupply}
     * must be the exact value the caller already used to compute this block's own coinbase reward
     * (see {@link NetworkParameters#miningReward(long, long)}) — so every reward this block mints,
     * its own coinbase and every uncle/nephew bonus alike, is dispatched against the identical curve
     * evaluation rather than risking a second, possibly-different one.
     */
    private static void payUncleRewards(Block block, Ledger ledger, List<AppliedOp> applied,
                                        PublicAddress miner, NetworkParameters params, long parentSupply) {
        List<rhizome.core.block.UncleRef> uncles = block.uncles();
        if (uncles.isEmpty()) {
            return;
        }
        long height = block.id();
        long baseUncleReward = params.uncleReward(height, parentSupply);
        long baseNephewReward = params.nephewReward(height, parentSupply);
        int nephewDifficulty = block.difficulty();
        for (rhizome.core.block.UncleRef ref : uncles) {
            // Scale each reward to the uncle's PROVEN work relative to the nephew's difficulty
            // (audit C1 residual). A flat reward let a miner attach cheap minDifficulty orphans
            // to a real high-difficulty block and mint ~half a block each — ~2x emission for
            // ~2^minDifficulty hashes. Here a same-difficulty uncle still earns the full reward,
            // but every bit of missing difficulty halves it (reward * 2^uncleDiff / 2^nephewDiff),
            // so a sub-difficulty orphan earns ~nothing. validateUncles guarantees
            // minDifficulty <= ref.difficulty() <= nephewDifficulty, so the deficit is >= 0.
            // Integer shift only — deterministic across nodes, never floating point.
            int deficit = nephewDifficulty - ref.difficulty();
            long uncleReward = scaleRewardToWork(baseUncleReward, deficit);
            long nephewReward = scaleRewardToWork(baseNephewReward, deficit);
            if (uncleReward > 0) {
                deposit(ledger, applied, ref.miner(), new TransactionAmount(uncleReward));
            }
            if (nephewReward > 0) {
                deposit(ledger, applied, miner, new TransactionAmount(nephewReward));
            }
        }
    }

    /**
     * Halves {@code base} once per bit of difficulty the uncle fell short of the nephew, i.e.
     * {@code base * 2^-difficultyDeficit}. A deficit of 0 pays in full; a deficit of 63 or more
     * pays nothing. Integer arithmetic so every node computes the identical reward.
     */
    static long scaleRewardToWork(long base, int difficultyDeficit) {
        if (difficultyDeficit <= 0) {
            return base;
        }
        if (difficultyDeficit >= Long.SIZE) {
            return 0;
        }
        return base >>> difficultyDeficit;
    }

    /** Rolls back applied ledger ops and discards the contract/box/token sessions, then returns the status. */
    private static ExecutionStatus abort(List<BlockStateProcessor> domains, Ledger ledger,
                                         List<AppliedOp> applied, ExecutionStatus status) {
        rollback(ledger, applied);
        for (BlockStateProcessor domain : domains) {
            domain.discard();
        }
        return status;
    }

    /**
     * Runs one native-token transaction. The token processor validates and stages the
     * token-state change (no ledger effect); this method moves only the fee to the miner.
     *
     * <p>A token-op <em>precondition</em> failure (unknown token, insufficient token balance, …)
     * is a soft revert, Ethereum-style: it does <em>not</em> invalidate the block. The processor
     * staged nothing ({@code run()} is failure-atomic), so the fee is still charged and the nonce
     * consumed, and only the token-state change is skipped. This is essential for liveness: the
     * mempool cannot check token preconditions (it holds no token state), so a tx transferring a
     * token the sender holds none of is admitted and selected into every candidate block. If it
     * aborted the block it would never be mined, never clear, and halt production network-wide —
     * a free, permanent poisoning DoS. The remaining affordability failures stay hard errors: the
     * mempool's cumulative-balance selection makes them unreachable in an honestly-produced block,
     * so they only arise in a malicious block, which must be rejected.
     */
    private static DomainOutcome applyToken(Transaction tx, Ledger ledger, List<AppliedOp> applied,
                                            PublicAddress miner, TokenProcessor tokenProcessor, long height) {
        // Success stages the token change; a precondition failure stages nothing. Either way the
        // only ledger effect is the fee below, so the block stays valid and revertToken (which
        // reverts exactly the fee) is an exact inverse in both cases.
        tokenProcessor.run(tx.kind(), tx.from(), tx.to(), tx.nonce(), tx.data(), height);
        long fee = tx.fee().amount();
        if (fee > 0) {
            if (!ledger.hasWallet(tx.from())) {
                return DomainOutcome.of(SENDER_DOES_NOT_EXIST);
            }
            if (ledger.getWalletValue(tx.from()).amount() < fee) {
                return DomainOutcome.of(BALANCE_TOO_LOW);
            }
            withdraw(ledger, applied, tx.from(), new TransactionAmount(fee));
            deposit(ledger, applied, miner, new TransactionAmount(fee));
            return new DomainOutcome(SUCCESS, fee); // pool site: the token-op fee
        }
        return DomainOutcome.of(SUCCESS);
    }

    /**
     * Runs one box transaction. The box processor validates and stages the box-state
     * change (no ledger access); this method then moves value: the fee to the miner,
     * the locked value out of the sender (CREATE/UPDATE) or the released value back to
     * the sender (SPEND/COLLECT).
     *
     * <p>Like a contract revert (and {@link #applyToken}), a box-op <em>precondition</em> failure
     * — wrong owner, missing/expired box, dust floor, malformed payload — is a soft revert that
     * does <em>not</em> invalidate the block: the box state and the value lock/release are skipped
     * and only the fee moves. The processor emitted a zero-delta receipt so the per-box-tx receipt
     * walk in {@code rollbackBlock} stays aligned. Aborting the block here would let anyone poison
     * the mempool with a box op on a box they do not own and halt production network-wide (audit:
     * mempool-poisoning halt). The affordability failures stay hard errors — unreachable in an
     * honestly-produced block (the mempool selects within the sender's confirmed balance), so they
     * signal a malicious block that must be rejected.
     */
    private static DomainOutcome applyBox(Transaction tx, Ledger ledger, List<AppliedOp> applied,
                                          PublicAddress miner, BoxProcessor boxProcessor, long height) {
        long amount = tx.amount().amount();
        long fee = tx.fee().amount();
        BoxProcessor.BoxResult result =
            boxProcessor.run(tx.kind(), tx.from(), tx.to(), amount, tx.nonce(), tx.data(), height);
        if (!result.success()) {
            if (fee > 0) {
                if (!ledger.hasWallet(tx.from())) {
                    return DomainOutcome.of(SENDER_DOES_NOT_EXIST);
                }
                if (ledger.getWalletValue(tx.from()).amount() < fee) {
                    return DomainOutcome.of(BALANCE_TOO_LOW);
                }
                withdraw(ledger, applied, tx.from(), new TransactionAmount(fee));
                deposit(ledger, applied, miner, new TransactionAmount(fee));
                // Pool site: a SOFT-REVERTED box op still contributed — the pool is what the
                // miner received, not what succeeded (P-3).
                return new DomainOutcome(SUCCESS, fee);
            }
            return DomainOutcome.of(SUCCESS);
        }
        long debit;
        try {
            debit = Math.addExact(fee, result.debitFrom());
        } catch (ArithmeticException e) {
            return DomainOutcome.of(INVALID_TRANSACTION_AMOUNT);
        }
        // Only a positive debit needs a funded sender; a rent collector taking value out
        // of a box (debit 0) may have no wallet yet — the deposit below creates it.
        if (debit > 0) {
            if (!ledger.hasWallet(tx.from())) {
                return DomainOutcome.of(SENDER_DOES_NOT_EXIST);
            }
            if (ledger.getWalletValue(tx.from()).amount() < debit) {
                return DomainOutcome.of(BALANCE_TOO_LOW);
            }
            withdraw(ledger, applied, tx.from(), new TransactionAmount(debit));
        }
        long credited = 0;
        if (fee > 0) {
            deposit(ledger, applied, miner, new TransactionAmount(fee));
            credited = Math.addExact(credited, fee); // pool site: the box-op fee (success path)
        }
        // Accrued storage rent charged out of the box's locked value (audit M7): it leaves the
        // box state in the processor and is paid to the block miner — never back to the owner,
        // so re-arming the rent clock always has a real cost.
        if (result.rentToMiner() > 0) {
            deposit(ledger, applied, miner, new TransactionAmount(result.rentToMiner()));
            credited = Math.addExact(credited, result.rentToMiner()); // pool site: the storage rent
        }
        // Released value goes to the box owner on a SPEND (the signer, tx.from), or to the
        // collector named in a permissionless BOX_COLLECT (tx.to, whose from is empty).
        // Deliberately NOT pooled: released value is not fresh credit to the miner (it belongs
        // to the box), and crediting it would make destruction reach into other people's coin.
        if (result.creditFrom() > 0) {
            PublicAddress creditTarget = boxCreditTarget(tx);
            deposit(ledger, applied, creditTarget, new TransactionAmount(result.creditFrom()));
        }
        return new DomainOutcome(SUCCESS, credited);
    }

    /** Who receives value released by a box op: the collector for BOX_COLLECT, else the sender. */
    private static PublicAddress boxCreditTarget(Transaction tx) {
        return tx.kind() == rhizome.core.transaction.TransactionKind.BOX_COLLECT ? tx.to() : tx.from();
    }

    /**
     * Runs one contract transaction. Gas is always charged to the miner (even on a
     * revert — the work was done); the attached value moves to the contract only on
     * success; the contract's state writes live in the processor's block session.
     *
     * <p>Returns SUCCESS for both a successful and a reverted call (a revert does not
     * invalidate the block, Ethereum-style). A non-SUCCESS status means the
     * transaction could not be afforded or applied and the block is invalid.
     */
    private static DomainOutcome applyContract(Transaction tx, Ledger ledger,
                                               List<AppliedOp> applied, PublicAddress miner,
                                               ContractProcessor processor) {
        long value = tx.amount().amount();
        long gasLimit = tx.gasLimit();
        long gasPrice = tx.gasPrice();
        if (value < 0 || gasLimit < 0 || gasPrice < 0) {
            return DomainOutcome.of(INVALID_TRANSACTION_AMOUNT);
        }
        long required;
        try {
            required = Math.addExact(value, Math.multiplyExact(gasLimit, gasPrice));
        } catch (ArithmeticException e) {
            return DomainOutcome.of(INVALID_TRANSACTION_AMOUNT);
        }
        // Balance-not-key-presence, exactly as the normal-transfer path (audit 5th-pass, consensus
        // Finding 1): a phantom 0-balance sender must not make a required==0 call (value 0, gasLimit or
        // gasPrice 0) valid on one node and SENDER_DOES_NOT_EXIST on another. required>0 still implies a
        // real, sufficiently funded wallet, so every withdraw below hits an existing wallet.
        long available = ledger.balanceOrZero(tx.from()); // one store read (audit perf)
        if (available < required) {
            return DomainOutcome.of(BALANCE_TOO_LOW);
        }

        ContractProcessor.ContractResult result =
            processor.run(tx.from(), tx.kind(), tx.to(), tx.data(), value, gasLimit, tx.nonce());

        long gasFee = Math.multiplyExact(result.gasUsed(), gasPrice);
        long credited = 0;
        if (gasFee > 0) {
            withdraw(ledger, applied, tx.from(), new TransactionAmount(gasFee));
            deposit(ledger, applied, miner, new TransactionAmount(gasFee));
            credited = gasFee; // pool site: the gas fee, success and revert alike (P-3)
        }
        // Native payouts the contract made from its own balance via transfer_value (audit T4).
        // The VM bounded each to the contract's committed balance, so these withdrawals succeed;
        // the list is empty on a revert. Applied before the attached value moves (which the VM
        // did not count as spendable), so a contract can never pay out coin it does not hold.
        // Deliberately NOT pooled: this is the contract's own coin moving, not a credit to the
        // miner — pooling it would make the burn reach into other people's balances.
        for (ContractProcessor.NativeTransfer nt : result.transfers()) {
            if (nt.amount() > 0) {
                withdraw(ledger, applied, nt.from(), new TransactionAmount(nt.amount()));
                deposit(ledger, applied, nt.to(), new TransactionAmount(nt.amount()));
            }
        }
        // The attached value moves to the contract only on success — and is deliberately NOT
        // pooled: it belongs to the contract now, it never became the miner's revenue.
        if (result.success() && value > 0) {
            PublicAddress target = result.contractAddress() != null ? result.contractAddress() : tx.to();
            withdraw(ledger, applied, tx.from(), new TransactionAmount(value));
            deposit(ledger, applied, target, new TransactionAmount(value));
        }
        return new DomainOutcome(SUCCESS, credited);
    }

    private static void withdraw(Ledger ledger, List<AppliedOp> applied,
                                 PublicAddress wallet, TransactionAmount amount) {
        ledger.withdraw(wallet, amount);
        applied.add(new AppliedOp(AppliedOp.Op.WITHDRAW, wallet, amount));
    }

    /** Deposit under the V2 rule (a zero credit is a strict no-op). */
    private static void deposit(Ledger ledger, List<AppliedOp> applied,
                                PublicAddress wallet, TransactionAmount amount) {
        deposit(ledger, applied, wallet, amount, true);
    }

    /**
     * Credits {@code amount} to {@code wallet}, creating the wallet if needed.
     *
     * <p>{@code skipZeroDeposit} is the consensus-V2 gate ({@code params.consensusV2(height)}):
     * under V2 a zero credit changes nothing and must NOT create the wallet (audit: ledger
     * bloat) — amount-0 transfers otherwise minted a permanent ledger entry per call, letting a
     * miner grow every node's state at no cost. Under the legacy rule (pre-activation height)
     * a zero credit still creates the wallet, exactly as the chain historically behaved, so
     * already-accepted blocks re-verify identically.
     *
     * <p>Rollback symmetry holds in BOTH modes. In-block abort replays the recorded ops, and a
     * legacy zero deposit DID record one: {@code revertDeposit(wallet, 0)} then runs against a
     * wallet the forward pass just created, subtracting 0 from a 0 balance — a safe no-op that
     * never throws. The reorg path ({@code rollbackBlock}) instead guards every revertDeposit on
     * {@code > 0}: under V2 that is the exact inverse (the zero deposit never happened); under
     * the legacy rule it leaves the created 0-balance wallet key behind — a harmless phantom
     * (block validity is a pure function of balance, never of key-presence, audit consensus
     * Finding 1), so both modes stay fork-safe.
     */
    private static void deposit(Ledger ledger, List<AppliedOp> applied,
                                PublicAddress wallet, TransactionAmount amount,
                                boolean skipZeroDeposit) {
        if (amount.amount() == 0 && skipZeroDeposit) {
            return;
        }
        if (!ledger.hasWallet(wallet)) {
            ledger.createWallet(wallet);
        }
        ledger.deposit(wallet, amount);
        applied.add(new AppliedOp(AppliedOp.Op.DEPOSIT, wallet, amount));
    }

    private static void rollback(Ledger ledger, List<AppliedOp> applied) {
        for (int i = applied.size() - 1; i >= 0; i--) {
            AppliedOp op = applied.get(i);
            switch (op.op()) {
                case WITHDRAW -> ledger.revertSend(op.wallet(), op.amount());
                case DEPOSIT -> ledger.revertDeposit(op.wallet(), op.amount());
            }
        }
    }

    /** The applied ledger mutations as journal entries, in application order. */
    private static List<rhizome.core.ledger.LedgerOp> toLedgerOps(List<AppliedOp> applied) {
        List<rhizome.core.ledger.LedgerOp> ops = new ArrayList<>(applied.size());
        for (AppliedOp op : applied) {
            ops.add(new rhizome.core.ledger.LedgerOp(
                op.op() == AppliedOp.Op.WITHDRAW
                    ? rhizome.core.ledger.LedgerOp.Op.WITHDRAW
                    : rhizome.core.ledger.LedgerOp.Op.DEPOSIT,
                op.wallet(), op.amount().amount()));
        }
        return ops;
    }

    /**
     * Undoes a previously applied block: the exact inverse of
     * {@link #executeBlock}'s mutations, in reverse order. The block must be the
     * most recently applied one (used by {@code popBlock} during reorgs).
     */
    public static void rollbackBlock(Block block, Ledger ledger, ContractProcessor processor,
                                     long height, NetworkParameters params) {
        rollbackBlock(block, ledger, processor, null, height, params);
    }

    /** As above, also reversing the block's box transactions via the {@link BoxProcessor}. */
    public static void rollbackBlock(Block block, Ledger ledger, ContractProcessor processor,
                                     BoxProcessor boxProcessor, long height, NetworkParameters params) {
        undoBlock(block, ledger,
            processor == null ? ContractProcessor.NONE : processor,
            boxProcessor == null ? BoxProcessor.NONE : boxProcessor, height, params);
    }

    /** As {@link #rollbackBlock}, with both domains guaranteed non-null. */
    private static void undoBlock(Block block, Ledger ledger, ContractProcessor processor,
                                  BoxProcessor boxProcessor, long height, NetworkParameters params) {
        // The ledger's own undo journal, recorded by executeBlock, is the exact inverse of every
        // ledger mutation the block applied — no arithmetic re-derivation from the transaction,
        // no receipts walk, no mirrors to keep in sync (audit: one undo protocol, and a journal
        // for the ledger; the four re-derivation mirrors have each diverged at least once). A
        // ledger without a journal (the genesis ledger; heights committed before the journal
        // existed; tests using a bare store) falls back to the re-derivation below.
        if (ledger.revertBlock(height)) {
            return;
        }
        List<Transaction> transactions = block.transactions();
        Transaction coinbase = transactions.stream()
            .filter(t -> t.isTransactionFee())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Block has no coinbase"));
        PublicAddress miner = coinbase.to();

        // Runtime receipts (gas used, success) for this block's contract txs, in block
        // order; consumed in reverse as we walk transactions backwards.
        List<ContractProcessor.ContractReceipt> receipts =
            processor.receipts(height);
        int ri = receipts.size() - 1;
        // Box receipts (ledger deltas) for this block's box txs, consumed in reverse.
        List<BoxProcessor.BoxReceipt> boxReceipts =
            boxProcessor.receipts(height);
        int bi = boxReceipts.size() - 1;

        // Fail-fast BEFORE any mutation on MISSING receipts (audit: mid-rollback
        // IndexOutOfBounds). The reverse walk below consumes exactly one receipt per
        // contract/box transaction (applyBlock emits one per tx, even for soft reverts).
        // Receipts are persisted per block and pruned on the journal schedule
        // (retainDepth >= maxReorgDepth), so within the reorg window they are always present;
        // if they are short anyway (store corruption, misconfigured retention),
        // receipts.get(ri--) used to throw IndexOutOfBoundsException MID-rollback — ledger
        // partially reverted, a planted-block state-corruption vector. Verify the counts up
        // front so the pop aborts cleanly with the ledger untouched and a diagnosable message.
        // A SURPLUS is only warned about: the pre-guard walk consumed one receipt per tx from
        // the back of the list and tolerated extras (they are left unconsumed), so turning it
        // into a hard failure would fork nodes that synced past such a block. The counting loop
        // skips the coinbase exactly like the apply pass and the reverse walk do — kind() is
        // independent of the fee flag only on rejected blocks (pass 1 pins the coinbase to
        // TRANSFER), but the three walks must agree structurally regardless.
        int expectedContract = 0;
        int expectedBox = 0;
        for (Transaction tx : transactions) {
            if (tx.isTransactionFee()) {
                continue;
            }
            if (tx.kind().isContract()) {
                expectedContract++;
            } else if (tx.kind().isBox()) {
                expectedBox++;
            }
        }
        if (receipts.size() < expectedContract) {
            throw new IllegalStateException("cannot roll back block at height " + height + ": it carries "
                + expectedContract + " contract transactions but only " + receipts.size()
                + " receipts were found (pruned or lost?) — the ledger is left untouched;"
                + " receipts must be retained at least maxReorgDepth deep");
        }
        if (receipts.size() > expectedContract) {
            log.warn("block at height {} carries {} contract transactions but {} receipts were "
                + "found — consuming the trailing {} and tolerating the surplus (legacy behavior)",
                height, expectedContract, receipts.size(), expectedContract);
        }
        if (boxReceipts.size() < expectedBox) {
            throw new IllegalStateException("cannot roll back block at height " + height + ": it carries "
                + expectedBox + " box transactions but only " + boxReceipts.size()
                + " box receipts were found (pruned or lost?) — the ledger is left untouched;"
                + " receipts must be retained at least maxReorgDepth deep");
        }
        if (boxReceipts.size() > expectedBox) {
            log.warn("block at height {} carries {} box transactions but {} box receipts were "
                + "found — consuming the trailing {} and tolerating the surplus (legacy behavior)",
                height, expectedBox, boxReceipts.size(), expectedBox);
        }

        long coinbaseAmount = coinbase.amount().amount();

        // Exact inverse of payUncleRewards. That path scales each reward to the uncle's PROVEN
        // work — base >>> (nephewDifficulty - ref.difficulty()) via scaleRewardToWork (audit C1) —
        // so the revert MUST recompute the identical per-uncle deficit and scale the same way.
        // Reverting the flat base instead (the pre-C1 amount) over-subtracts by base-(base>>>deficit)
        // per sub-difficulty uncle on every reorg/pop, which either throws LedgerException mid-revert
        // (leaving a partially reverted, corrupted ledger) or silently destroys coins and forks the
        // state root from nodes that only ever applied the block. Guards mirror the apply side's >0.
        //
        // The base amounts here are derived from coinbaseAmount rather than re-derived via a fresh
        // params.uncleReward(height, ...) call, because this method takes no parentSupply (research.md
        // Decision 4 — rollbackBlock needs no new input) and under the curve miningReward is not a pure
        // function of height alone. coinbaseAmount is exactly what miningReward(height[, parentSupply])
        // computed at apply time — pass 1's coinbase.amount() != expectedReward check already enforced
        // that equality before the block was ever accepted, under either rule. uncleReward/nephewReward
        // are themselves defined as exactly miningReward(...) * uncleRewardNum/uncleRewardDen and
        // miningReward(...) / nephewRewardDivisor, so substituting the block's own already-validated
        // coinbaseAmount for a fresh miningReward(...) call reproduces the identical base in both the
        // geometric and curve regimes, with no dispatch logic needed here at all.
        List<rhizome.core.block.UncleRef> uncles = block.uncles();
        if (!uncles.isEmpty()) {
            long baseUncleReward = Math.multiplyExact(coinbaseAmount, params.uncleRewardNum()) / params.uncleRewardDen();
            long baseNephewReward = coinbaseAmount / params.nephewRewardDivisor();
            int nephewDifficulty = block.difficulty();
            for (rhizome.core.block.UncleRef ref : uncles) {
                int deficit = nephewDifficulty - ref.difficulty();
                long uncleReward = scaleRewardToWork(baseUncleReward, deficit);
                long nephewReward = scaleRewardToWork(baseNephewReward, deficit);
                if (nephewReward > 0) {
                    ledger.revertDeposit(miner, new TransactionAmount(nephewReward));
                }
                if (uncleReward > 0) {
                    ledger.revertDeposit(ref.miner(), new TransactionAmount(uncleReward));
                }
            }
        }
        if (coinbaseAmount > 0) { // > 0 guard mirrors deposit's zero-credit no-op (exact inverse)
            ledger.revertDeposit(miner, coinbase.amount());
        }
        for (int i = transactions.size() - 1; i >= 0; i--) {
            Transaction tx = transactions.get(i);
            if (tx.isTransactionFee()) {
                continue;
            }
            if (tx.kind().isContract()) {
                revertContract(ledger, tx, receipts.get(ri--), miner);
                continue;
            }
            if (tx.kind().isBox()) {
                revertBox(ledger, tx, boxReceipts.get(bi--), miner);
                continue;
            }
            if (tx.kind().isToken()) {
                revertToken(ledger, tx, miner);
                continue;
            }
            long fee = tx.fee().amount();
            if (fee > 0) {
                ledger.revertDeposit(miner, new TransactionAmount(fee));
            }
            // Guarded like the forward path and compatible with BOTH consensus modes (see the
            // deposit helper): under V2 deposit(amount 0) is a no-op (it no longer creates the
            // recipient wallet), so reverting it must be a no-op too — an unconditional
            // revertDeposit(to, 0) would hit getWalletValue on a wallet that was never created
            // and throw mid-rollback, corrupting a reorg (same vector as the revertSend guard
            // below). Under the legacy rule a zero deposit created the wallet at balance 0;
            // skipping its revert here leaves that phantom key in place, which is still the
            // exact balance-level inverse (no coins moved) and fork-safe.
            if (tx.amount().amount() > 0) {
                ledger.revertDeposit(tx.to(), tx.amount());
            }
            // Exact inverse of the forward path (executeBlock): the sender is only debited when
            // `charged = amount + fee > 0` (a 0-amount/0-fee transfer from a never-funded key never
            // touches `from`, deliberately — validity is balance-based, audit consensus Finding 1).
            // Reverting `from` unconditionally called revertSend(from, 0) → ledger.add → getWalletValue
            // on an absent wallet → LedgerException thrown mid-rollback, corrupting a reorg (popBlock
            // has no restore path): ledger left partially reverted while store/nonces/processors/root
            // stayed applied — a planted-block state-corruption vector. Guard mirrors the forward
            // `charged > 0`, matching revertToken/revertBox/revertContract which already guard `> 0`.
            long charged = Math.addExact(tx.amount().amount(), fee); // exact symmetry with the forward path (audit F6)
            if (charged > 0) {
                ledger.revertSend(tx.from(), new TransactionAmount(charged));
            }
        }
    }

    /** Inverse of {@link #applyContract}'s ledger effects, using the block's receipt. */
    private static void revertContract(Ledger ledger, Transaction tx,
                                       ContractProcessor.ContractReceipt receipt, PublicAddress miner) {
        long gasFee = Math.multiplyExact(receipt.gasUsed(), tx.gasPrice());
        if (gasFee > 0) {
            ledger.revertDeposit(miner, new TransactionAmount(gasFee));
            ledger.revertSend(tx.from(), new TransactionAmount(gasFee));
        }
        // Reverse the contract's native payouts (transfer_value), inverse order — the exact
        // inverse of applyContract's forward application (audit T4).
        java.util.List<ContractProcessor.NativeTransfer> transfers = receipt.transfers();
        for (int i = transfers.size() - 1; i >= 0; i--) {
            ContractProcessor.NativeTransfer nt = transfers.get(i);
            if (nt.amount() > 0) {
                ledger.revertDeposit(nt.to(), new TransactionAmount(nt.amount()));
                ledger.revertSend(nt.from(), new TransactionAmount(nt.amount()));
            }
        }
        long value = tx.amount().amount();
        if (receipt.success() && value > 0) {
            PublicAddress target = tx.kind() == rhizome.core.transaction.TransactionKind.DEPLOY
                ? Contracts.deriveAddress(tx.from(), tx.nonce()) : tx.to();
            ledger.revertDeposit(target, new TransactionAmount(value));
            ledger.revertSend(tx.from(), new TransactionAmount(value));
        }
    }

    /** Inverse of {@link #applyBox}'s ledger effects, using the block's box receipt. */
    private static void revertBox(Ledger ledger, Transaction tx,
                                  BoxProcessor.BoxReceipt receipt, PublicAddress miner) {
        long fee = tx.fee().amount();
        long debit = Math.addExact(fee, receipt.debitFrom()); // exact symmetry with applyBox (audit F6)
        if (receipt.creditFrom() > 0) {
            ledger.revertDeposit(boxCreditTarget(tx), new TransactionAmount(receipt.creditFrom()));
        }
        if (receipt.rentToMiner() > 0) {
            ledger.revertDeposit(miner, new TransactionAmount(receipt.rentToMiner()));
        }
        if (fee > 0) {
            ledger.revertDeposit(miner, new TransactionAmount(fee));
        }
        if (debit > 0) {
            ledger.revertSend(tx.from(), new TransactionAmount(debit));
        }
    }

    /** Inverse of {@link #applyToken}'s ledger effects: a token op moves only the fee. */
    private static void revertToken(Ledger ledger, Transaction tx, PublicAddress miner) {
        long fee = tx.fee().amount();
        if (fee > 0) {
            ledger.revertDeposit(miner, new TransactionAmount(fee));
            ledger.revertSend(tx.from(), new TransactionAmount(fee));
        }
    }
}
