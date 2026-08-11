package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.box.BoxProcessor;
import rhizome.core.token.TokenProcessor;

/**
 * The lifecycle every per-block reversible state domain shares: open a session, commit it under a
 * height (recording an undo journal), discard it, or undo a previously committed height.
 *
 * <p>{@link ContractProcessor}, {@link BoxProcessor} and {@link TokenProcessor} each declared these
 * four methods independently — one of them says "Mirrors ContractProcessor" in its javadoc — with no
 * common supertype. The consequence was not the duplicated declarations but the plumbing they
 * forced: {@code Executor} and {@code ChainEngine} spell the three domains out by hand at every
 * lifecycle site, so the order in which they are committed and the order in which they are reverted
 * exist as six parallel hand-written sequences related only by comments. Adding a fourth domain
 * meant editing all of them, in the two files that carry the consensus invariants, with no
 * compilation error for the one that was missed — only a state-root divergence later.
 *
 * <p>{@link #inCommitOrder} is the single definition of that order. Every site walks the list, so
 * the order is data rather than instruction sequence, which is strictly better for consensus code:
 * it can be asserted (see {@code StateDomainOrderTest}) instead of reviewed six times.
 *
 * <p>Deliberately NOT pulled up here:
 * <ul>
 *   <li>{@code changes(long)} — the element types differ ({@code ContractChange} /
 *       {@code BoxMutation} / {@code TokenOp}) and its only consumer,
 *       {@code ChainEngine.collectStateChanges}, needs the concrete type in every branch. A wildcard
 *       would force an unchecked cast into the most consensus-critical translation in the tree.</li>
 *   <li>{@code receipts(long)} — absent on tokens, and the two that have it feed two different
 *       reverse walks.</li>
 *   <li>The events accessor — named {@code logs} on contracts and {@code events} on the other two,
 *       again over different element types, so unifying the name would buy nothing at the type
 *       level.</li>
 * </ul>
 */
public interface BlockStateProcessor {

    /** Opens a fresh per-block state session. */
    void begin();

    /** Persists the open session under {@code blockHeight}, recording its undo journal. */
    void commit(long blockHeight);

    /** Drops the open session, committing nothing. */
    void discard();

    /** Undoes the changes committed for {@code blockHeight}; an unjournalled height is a no-op. */
    void revertBlock(long blockHeight);

    /**
     * Drops retained journals/receipts/changes the reorg window no longer covers: every height at
     * or below {@code chainTip - retainDepth}, keeping EXACTLY retainDepth heights.
     *
     * <p>Driven by the engine only AFTER a block is appended — never from {@link #commit(long)}.
     * A commit can still be reverted within the same engine critical section (the stampStateRoot
     * dry run over a candidate at tip+1, or an addBlock state-root rejection), so a watermark fed
     * by commit attempts runs one height ahead of the chain and prunes exactly the oldest
     * in-window height a max-depth reorg still needs; that reorg then dies on the rollback
     * receipt guard, for good, because the durable receipts were deleted with the RAM copy.
     * Keying the prune on the appended chain tip makes retention track blocks that STAND.
     */
    default void pruneToChainTip(long chainTip) {
    }

    /**
     * Whether this domain is wired on this node. False only for the absent singletons that stand in
     * for a missing processor, so the engine and executor need no null checks.
     *
     * <p>Absent does NOT mean permissive: a transaction in an unavailable domain is still rejected
     * in {@code Executor}'s first pass, exactly as it was when the field was null.
     */
    default boolean available() {
        return true;
    }

    /**
     * The per-block state domains in their canonical commit order — the one definition of an order
     * that {@code Executor} (begin / commit / discard) and {@code ChainEngine} (four revert sites)
     * previously each spelled out by hand.
     *
     * <p>Commit order and revert order are the SAME order here, not reverses of each other: the
     * domains are independent of one another, so reverting them in commit order is what the code
     * has always done and what {@code StateDomainOrderTest} pins.
     */
    static List<BlockStateProcessor> inCommitOrder(ContractProcessor contracts,
                                                   BoxProcessor boxes,
                                                   TokenProcessor tokens) {
        return List.of(contracts, boxes, tokens);
    }
}
