package rhizome.core.ledger;

import java.util.List;

import rhizome.core.transaction.TransactionAmount;

/**
 * Wallet balance store. Implementations live in lib-persistence so that
 * lib-core stays free of any storage backend dependency.
 *
 * <p>The per-block undo journal ({@link #applyBlock}/{@link #revertBlock}) is part of the
 * same protocol the box, token and contract stores speak: the executor records the ledger
 * mutations a block applies, and a reorg replays them back through the journal instead of
 * re-deriving each inverse arithmetically from the transaction (audit: one undo protocol, and
 * a journal for the ledger). Stores that keep no journal (e.g. the genesis ledger, which is
 * never reverted) leave the defaults; their reorg path falls back to the arithmetic mirrors.
 */
public interface Ledger {

    boolean hasWallet(PublicAddress wallet);

    void createWallet(PublicAddress wallet);

    TransactionAmount getWalletValue(PublicAddress wallet);

    /**
     * The wallet's balance, or {@code 0} when absent — ONE store read where the implementation
     * supports it (the default reads twice: probe + get). Block validity is a pure function of
     * balance, never of key-presence (audit consensus Finding 1), and the executor reads this
     * per transfer — halving the point-gets on the hot path (audit perf).
     */
    default long balanceOrZero(PublicAddress wallet) {
        return hasWallet(wallet) ? getWalletValue(wallet).amount() : 0L;
    }

    void withdraw(PublicAddress wallet, TransactionAmount amt);

    void revertSend(PublicAddress wallet, TransactionAmount amt);

    void deposit(PublicAddress wallet, TransactionAmount amt);

    void revertDeposit(PublicAddress wallet, TransactionAmount amt);

    /**
     * Visits every stored {@code (wallet, balance)} pair — the state-snapshot export path.
     * Optional: stores that never serve snapshots may leave the unsupported default. Note the
     * fresh-chain guard in {@code GenesisBlock.initChain} also probes emptiness through this
     * method, so a ledger that cannot enumerate cannot boot a chain.
     */
    default void forEachBalance(java.util.function.ObjLongConsumer<PublicAddress> consumer) {
        throw new UnsupportedOperationException("this ledger does not support enumeration");
    }

    /**
     * Records the undo journal for one block: every ledger mutation {@code executeBlock}
     * applied, in application order. The default keeps nothing (the reorg path re-derives
     * the inverses arithmetically); a durable ledger persists the journal so a reorg after
     * a restart can still reverse the block exactly.
     *
     * <p>Journal-keeping implementations speak the same protocol as the box/token/contract
     * stores: a mutation-less apply persists no journal (an op-less block therefore stays
     * re-appliable), and a height that already has a journal MUST be refused with
     * {@link IllegalStateException} — a double-apply would journal the already-mutated state
     * as the "prior", so a later revert would restore the wrong values (audit F10).
     */
    default void applyBlock(long height, List<LedgerOp> ops) {
        // no journal kept — see the class javadoc
    }

    /**
     * Reverts the ledger changes of the block at {@code height} from its undo journal, by
     * replaying each entry's exact inverse through the store's own checked arithmetic
     * ({@link LedgerOp#revert}). A journal that disagrees with the committed state — it names
     * an absent wallet, or its inverse over/underflows — is corrupt and must throw, never be
     * written back as a balance the consensus path then reads. The default does nothing —
     * callers must fall back to their arithmetic mirrors.
     *
     * @return true if a journal was found and applied
     */
    default boolean revertBlock(long height) {
        return false;
    }

    /** Drops journals for heights below {@code minHeight}, matching the store prune schedule. */
    default void pruneJournals(long minHeight) {
        // nothing kept — see the class javadoc
    }

    /**
     * Opens a bulk-load window: until the matching {@link #endBulkLoad()}, an implementation MAY
     * buffer {@link #createWallet}/{@link #deposit}/{@link #withdraw} writes in memory and commit
     * them with far fewer fsyncs than one per call — {@link GenesisLedger#seed} is the only
     * caller, and a large genesis allocation was paying one synchronous, synced write PER WALLET
     * (measured ~3.3-3.6 ms each — a multi-hour startup DoS at a large-but-valid wallet count).
     * Reads made inside the window still see every write made so far (read-your-writes), so the
     * result is byte-identical to running the same calls with no window open at all — this is
     * purely a durability/batching optimization, never a behavior change. Not safe to call while
     * a block commit ({@link #applyBlock}/{@link #revertBlock}) is open.
     *
     * <p>One honesty caveat on "byte-identical": that holds for a window that RUNS TO
     * COMPLETION. A durable implementation commits its buffered writes in more than one batch
     * (chunking an unbounded window into bounded ones), so a crash MID-FLUSH can leave a strict
     * prefix of the writes durable with nothing recording how far it got — a state an unbatched
     * run, which pays one synced write per call, could also produce but at much finer grain.
     * Crash recovery is therefore NOT the window's job: {@code GenesisBlock.initChain} refuses
     * to seed over a non-empty ledger at height 0 (the only shape a torn genesis seed can
     * take), turning a silent double-deposit into a loud boot refusal — see its javadoc.
     *
     * <p>The default is a no-op: an implementation with nothing to batch (or an in-memory
     * reference implementation, which already pays no I/O per call) needs no override.
     */
    default void beginBulkLoad() {}

    /**
     * Closes a bulk-load window opened by {@link #beginBulkLoad()}, durably committing every
     * buffered write. MUST be called — in a {@code finally} — after every {@link #beginBulkLoad()};
     * an implementation that buffers writes has nothing else that flushes them.
     */
    default void endBulkLoad() {}
}
