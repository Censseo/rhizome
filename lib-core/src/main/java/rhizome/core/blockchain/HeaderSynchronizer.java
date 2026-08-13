package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.common.Constants;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.crypto.SHA256Hash;

/**
 * Headers-first synchroniser. It subsumes {@link ChainSynchronizer}: before any
 * local state is touched, it downloads and validates the peer's <em>headers</em>
 * over the contested range ({@link HeaderChain}) and requires their proven
 * cumulative work to strictly exceed our own branch. A peer that merely
 * <em>claims</em> huge work therefore costs us a bounded header download
 * (~150 B/block) instead of full blocks (up to 4 MiB each) — the anti-DoS gate,
 * far cheaper than before. Only once the headers prove the work do we download
 * bodies, each verified against its validated header before execution.
 *
 * <p>A peer that predates the {@code /headers} endpoint makes {@link PeerSource#headers}
 * throw {@link UnsupportedOperationException}; the synchroniser transparently
 * falls back to the full-block {@link ChainSynchronizer} for that peer (D7).
 */
public final class HeaderSynchronizer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HeaderSynchronizer.class);

    /** Max heights advanced in one round, so a hostile "I'm at height 10^9" peer costs a bounded download. */
    static final long MAX_HEADER_WINDOW = 20_000;

    private final ChainEngine engine;
    private final ChainSynchronizer fallback;
    /** Retarget memo for the stateless header-chain gate — see {@link DifficultyMemo}, shared with
     *  ChainSynchronizer (self-invalidating by boundary-header hash, guarded by its own monitor). */
    private final DifficultyMemo difficultyMemo = new DifficultyMemo();

    public HeaderSynchronizer(ChainEngine engine) {
        this.engine = engine;
        this.fallback = new ChainSynchronizer(engine);
    }

    public ChainSynchronizer.Result syncFrom(PeerSource peer) {
        try {
            return syncFromOrThrow(peer);
        } catch (LocalSaturationException e) {
            // A LOCAL bound (transport backpressure) stopped the exchange before it reached the
            // peer — not misbehaviour: no ban score, no PEER_INVALID. Retried next round.
            log.debug("sync deferred: local exchange saturated; will retry next round");
            return ChainSynchronizer.Result.NO_CHANGE;
        }
    }

    private ChainSynchronizer.Result syncFromOrThrow(PeerSource peer) {
        // Prefilter against our BASE work, not our uncle-inclusive total. The adoption gate below
        // ranks branches by base-only work (localWorkAboveFork, the M4 rule); ranking this early-out
        // by the uncle-inflated total instead let a node with heavy local uncle work refuse to even
        // look at a peer whose base work would win adoption — the two gates disagreeing produced a
        // stable partition after a healed split (audit 5th-pass, reorg-gate metric). peer.totalWork()
        // is self-reported but is an upper bound on the peer's base work, so skipping only when it
        // cannot beat our base work never skips a peer the adoption gate would accept — and a peer
        // that over-reports still can't force a pop/restore, which stays gated on validated base
        // work. A STRICT skip (peerTotal < baseWork): a peer at exactly our base work may still win
        // the adoption gate's deterministic tiebreak (equal base, equal total, smaller tip hash),
        // so it must reach the gate, which re-checks everything cheaply on downloaded headers.
        BigInteger peerTotal = peer.totalWork();
        if (peerTotal.compareTo(engine.baseWork()) < 0) {
            return ChainSynchronizer.Result.NO_CHANGE;
        }
        try {
            // peerTotal is read ONCE per round and carried down: the tiebreak below compares it
            // against our total, and a second HTTP read here could race our own chain advancing
            // between the two calls. One read, one consistent decision (and no extra round-trip).
            return headersFirstSync(peer, peerTotal);
        } catch (UnsupportedOperationException headersUnsupported) {
            // Older peer without /headers: fall back to the full-block path.
            return fallback.syncFrom(peer);
        }
    }

    private ChainSynchronizer.Result headersFirstSync(PeerSource peer, BigInteger peerTotal) {
        long forkHeight;
        try {
            forkHeight = findCommonAncestor(peer); // first call touches peer.headers → may fall back
        } catch (UnsupportedOperationException headersUnsupported) {
            throw headersUnsupported; // peer lacks /headers: let syncFrom fall back to full blocks
        } catch (LocalSaturationException e) {
            throw e; // local backpressure, not a peer fault: syncFrom maps it to NO_CHANGE
        } catch (PeerUnavailableException e) {
            // Transport failure while probing (e.g. the peer answered 503 because it is itself
            // mid-reorg and has nothing coherent to serve): NOT invalid — the peer gave us no
            // data to judge. Re-throw so the sync round logs it at DEBUG and retries next
            // round, never a ban (testnet campaign S5: a transiently-truncated chain used to
            // read as PEER_INVALID and eclipse an honest peer).
            throw e;
        } catch (RuntimeException e) {
            // A peer that throws or returns an empty/garbage response while we probe for the common
            // ancestor is invalid, exactly like the fetch phases below — it must not propagate out of
            // the sync pass (audit V6c). (UnsupportedOperationException is re-thrown above.)
            return ChainSynchronizer.Result.PEER_INVALID;
        }
        if (forkHeight < GenesisBlock.GENESIS_ID) {
            return ChainSynchronizer.Result.INCOMPATIBLE; // genesis mismatch: different network
        }
        long depth = engine.height() - forkHeight;
        if (depth > engine.params().maxReorgDepth()) {
            return ChainSynchronizer.Result.REORG_TOO_DEEP;
        }

        long windowEnd = Math.min(peer.height(), forkHeight + MAX_HEADER_WINDOW);
        if (windowEnd <= forkHeight) {
            return ChainSynchronizer.Result.NO_CHANGE;
        }

        // --- Header gate: no local mutation until the peer has PROVEN more work in valid headers ---
        List<BlockHeader> branch = fetchHeaders(peer, forkHeight + 1, windowEnd);
        if (branch == null) {
            return ChainSynchronizer.Result.PEER_INVALID;
        }
        HeaderChain.Result validated = difficultyMemo.validate(
            engine.params(), engine::headerAt, forkHeight, branch, engine.nowMillis());
        if (!validated.valid()) {
            return ChainSynchronizer.Result.PEER_INVALID;
        }
        int cmp = validated.work().compareTo(ReorgSupport.localWorkAboveFork(engine, forkHeight));
        if (cmp < 0) {
            // Claimed heavy, proved light: the branch is structurally valid (PoW/continuity/difficulty
            // all held) but not base-heavier, so it simply loses the fork race — NOT a protocol
            // violation. Returning PEER_INVALID here banned honest total-heavier/base-lighter peers on
            // the first strike, entrenching a split; NO_CHANGE leaves the peer connected (audit 5th-pass).
            return ChainSynchronizer.Result.NO_CHANGE;
        }
        if (cmp == 0) {
            // A base-work TIE (the metastable-split scenario, testnet campaign S7): descend to phase 3
            // only when there is an arbitration worth rendering. With a difficulty floor and
            // synchronized miners, two branches can hold EXACTLY equal base work above the fork while
            // differing only in real uncle work (2^difficulty per uncle, genuine PoW); the GHOST vote
            // in phase 3 — the one place validated uncle work is authoritative — is the arbiter. So:
            //  - unequal totals: descend when the peer's self-reported total (uncles included, an
            //    upper bound) could win — the GHOST vote then confirms or restores on validated data;
            //  - EQUAL totals: the asymmetry alone would leave the tie forever unbroken — measured in
            //    the replay as two equal-rate mining camps stuck for hours, and once past finality the
            //    loser could never rejoin at all. Break exact ties DETERMINISTICALLY instead: adopt the
            //    branch whose tip hash is lexicographically smaller. Every node computes the same
            //    verdict, so the camps converge in one round; the outcome cannot oscillate (the winner
            //    is canonical everywhere afterwards), and only an exactly-equal branch — real PoW at
            //    base parity — can force even this pop/restore, so M4's bar is unchanged.
            int totalCmp = peerTotal.compareTo(engine.totalWork());
            if (totalCmp < 0) {
                return ChainSynchronizer.Result.NO_CHANGE;
            }
            if (totalCmp == 0 && !peerTipWinsTiebreak(branch, peer)) {
                return ChainSynchronizer.Result.NO_CHANGE;
            }
        }

        // The headers prove the peer is heavier, but if it has pruned the bodies we need there
        // is nothing to download here — leave it for an archive peer rather than banning it.
        if (forkHeight + 1 < peer.prunedBelow()) {
            return ChainSynchronizer.Result.PEER_PRUNED;
        }

        // --- Bodies: fetch, verify each against its validated header, apply ---
        if (forkHeight == engine.height()) {
            return applyBodies(peer, forkHeight, branch)
                ? ChainSynchronizer.Result.EXTENDED : ChainSynchronizer.Result.PEER_INVALID;
        }
        return reorg(peer, forkHeight, branch);
    }

    /**
     * Highest height at which our header and the peer's agree — the shared block-locator search
     * (exponential probes to bracket the fork, then a binary search inside, O(log height) peer
     * round-trips instead of one per block — audit M2), with the peer's hash read through the
     * headers transport, ~150 B per probe. The full-block fallback runs the same locator over
     * peer.blockHash — see {@link AncestorLocator}. A slow/hostile peer can no longer tie up the
     * sync thread for height×timeout by never matching.
     */
    private long findCommonAncestor(PeerSource peer) {
        return AncestorLocator.findCommonAncestor(engine.height(), peer.height(),
            h -> engine.headerAt(h).hash(), h -> peerHeaderHash(peer, h));
    }

    private static rhizome.crypto.SHA256Hash peerHeaderHash(PeerSource peer, long h) {
        List<BlockHeader> one = peer.headers(h, h);
        if (one.isEmpty()) {
            throw new IllegalStateException("peer returned no header at " + h);
        }
        return one.get(0).hash();
    }

    /** Fetches headers [from..to] in bounded batches; null on any transport/decode failure. */
    private List<BlockHeader> fetchHeaders(PeerSource peer, long from, long to) {
        List<BlockHeader> out = new ArrayList<>();
        try {
            for (long start = from; start <= to; start += Constants.BLOCK_HEADERS_PER_FETCH) {
                long end = Math.min(to, start + Constants.BLOCK_HEADERS_PER_FETCH - 1);
                out.addAll(peer.headers(start, end));
            }
        } catch (UnsupportedOperationException e) {
            throw e; // let syncFrom fall back to full-block sync
        } catch (LocalSaturationException e) {
            throw e; // local backpressure, not a peer fault: null would read as PEER_INVALID
        } catch (PeerUnavailableException e) {
            throw e; // transport failure: retried next round, never PEER_INVALID (see headersFirstSync)
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    private boolean applyBodies(PeerSource peer, long forkHeight, List<BlockHeader> branch) {
        return BodyPipeline.run("rhizome-body-fetch", peer, forkHeight + 1, forkHeight + branch.size(),
            cause -> {
                if (cause instanceof LocalSaturationException saturated) {
                    throw saturated; // local backpressure, not a peer fault (see fetchHeaders)
                }
                if (cause instanceof PeerUnavailableException unavailable) {
                    // Transport failure mid-body (the peer 503'd because it is itself reorging):
                    // not invalid, retried next round — never a ban.
                    throw unavailable;
                }
                // Anything else is a transport/decode failure fetching this window: abort the
                // sync with a false verdict rather than propagate (was a bare RuntimeException).
            },
            blocks -> {
                for (Block block : blocks) {
                    long idx = block.id() - forkHeight - 1;
                    if (idx < 0 || idx >= branch.size()
                        || !block.hash().equals(branch.get((int) idx).hash())) {
                        return false; // body does not match its validated header
                    }
                    // The header at this index was PoW-verified by HeaderChain.validate and the body's
                    // hash equals it (checked just above), so the body's work is already proven — apply
                    // it without re-running the memory-hard PoW hash (audit P4). Every other check runs.
                    // On INVALID_UNCLES the missing orphan bodies are fetched from the peer and the
                    // apply retried once (audit: uncle-sync blocker); applyBodies holds no lock, so
                    // the fetch is legal network I/O here.
                    ExecutionStatus applyStatus =
                        ChainSynchronizer.applyWithUncleFetch(engine, peer, block, engine::addValidatedBody);
                    if (applyStatus != ExecutionStatus.SUCCESS) {
                        log.warn("body apply rejected at height {}: {}", block.id(), applyStatus);
                        return false;
                    }
                }
                return true;
            });
    }

    private ChainSynchronizer.Result reorg(PeerSource peer, long forkHeight, List<BlockHeader> branch) {
        // Unlike ChainSynchronizer's small bounded reorg, the header path applies up to
        // MAX_HEADER_WINDOW bodies with interleaved network I/O (applyBodies pipelines fetch+apply), so
        // the whole sequence cannot run under the engine lock — that would stall every lock-guarded API
        // read and the producer for the entire multi-thousand-block download. Instead the two MUTATION
        // phases each run atomically under withConsistentView, with the I/O apply between them holding no
        // lock. The block producer (own thread) and /submit (event loop) both add at engine.height()+1 —
        // where we pop to — so an interleave during the forward apply just fails an addValidatedBody and
        // drops us to the restore path; making capture+pop and restore/adopt each atomic means restore
        // re-adds the local branch with no interleave and so can never throw "full resync required" and
        // silently truncate the chain (audit: reorg atomicity). A forward-apply race is thus self-healing:
        // the reorg aborts, the original tip is restored intact, and the next round retries.
        //
        // Reorg window (audit: non-atomic reorg window): phase 2 applies bodies OUTSIDE the lock
        // while the chain sits truncated at forkHeight. The engine guard (beginReorgWindow) makes
        // new-tip addBlock from gossip//submit and local production fail fast (IS_SYNCING) for the
        // whole window instead of accepting a block at forkHeight+1 that restore() would destroy —
        // honest PoW is never sacrificed to an aborted reorg. The window is opened UNDER the engine
        // lock, atomically with phase 1's capture+pop below: between "window opens" and "chain
        // truncated" no new-tip block can land (previously the CAS ran outside the lock, so a block
        // accepted in that gap had to be rescued by the restore — the very race the guard exists to
        // prevent). It is closed in finally AFTER the restore/adopt view completes, and never held
        // across the header download, which already completed above (only the body apply streams,
        // by design).
        ReorgSupport.LocalBranch[] captured = new ReorgSupport.LocalBranch[1];
        boolean[] opened = new boolean[1];
        ChainSynchronizer.Result early;
        try {
            early = engine.withConsistentView(() -> {
                // The maxReorgDepth check is RE-DONE here, inside the same atomic view as the pop:
                // the earlier check ran outside the lock, and a concurrent local extension (the block
                // producer or /submit adding k blocks between the two) would otherwise make the actual
                // reorg k blocks deeper than the finality window we validated (audit review: finality
                // TOCTOU).
                if (engine.height() - forkHeight > engine.params().maxReorgDepth()) {
                    return ChainSynchronizer.Result.REORG_TOO_DEEP; // window never opened
                }
                if (!engine.beginReorgWindow()) {
                    return ChainSynchronizer.Result.NO_CHANGE; // a window is already open (defensive)
                }
                opened[0] = true;
                // The capture-and-pop shared with the full-block path — one sequence, one order:
                // the captured tip is what the phase-3 GHOST tiebreak must compare against, and
                // it must be the state as it was atomically with the pop, not a raced tip.
                captured[0] = ReorgSupport.captureAndPop(engine, forkHeight);
                return null; // phase 1 complete, window open — phases 2/3 run outside this view
            });
        } catch (RuntimeException | Error phase1Failure) {
            // A pop that throws mid-phase-1 must not leave the just-opened window armed forever
            // (every new-tip addBlock would fail fast IS_SYNCING from then on). Close it before
            // propagating; the partially popped chain is the same state a failed reorg's restore
            // recovers from.
            if (opened[0]) {
                engine.endReorgWindow();
            }
            throw phase1Failure;
        }
        if (early != null) {
            return early;
        }
        try {
            return applyAndAdopt(peer, forkHeight, branch, captured[0]);
        } finally {
            engine.endReorgWindow();
        }
    }

    private ChainSynchronizer.Result applyAndAdopt(PeerSource peer, long forkHeight,
                                                   List<BlockHeader> branch,
                                                   ReorgSupport.LocalBranch local) {
        // Phase 2 — fetch and apply the peer bodies. Network I/O, so deliberately OUTSIDE the lock.
        boolean applied;
        try {
            applied = applyBodies(peer, forkHeight, branch);
        } catch (RuntimeException | Error abandoned) {
            // applyBodies RE-THROWS the failures that are not the peer's protocol fault — local
            // transport saturation, and (since the reorg-window 503) a peer that goes mid-reorg
            // while we stream its bodies. Those escape phase 2 with the chain already popped to
            // the fork and the local branch held only in this frame: without the restore below
            // the node would keep a PARTIAL peer branch that never faced the phase-3 GHOST vote,
            // and would silently drop its own branch — locally mined blocks no peer holds
            // included — with no degraded marker to show for it. Put the chain back exactly as
            // phase 1 found it, then let the failure propagate (retried next round, never a ban).
            try {
                engine.withConsistentView(() -> {
                    ReorgSupport.restore(engine, forkHeight, local.blocks());
                    return null;
                });
            } catch (RuntimeException | Error restoreFailure) {
                // restore() already marked the engine degraded and logged; keep the original
                // cause as the thrown one so the round still reads "unavailable, retry".
                abandoned.addSuppressed(restoreFailure);
            }
            throw abandoned;
        }

        // Phase 3 — adopt or restore, atomically so restore cannot race a producer/submit add.
        return engine.withConsistentView(() -> {
            if (!applied) {
                ReorgSupport.restore(engine, forkHeight, local.blocks());
                return ChainSynchronizer.Result.PEER_INVALID;
            }
            // GHOST fork choice (§3.7, audit S4). The base-only header gate is the anti-DoS PREFILTER —
            // base-only because a header's claimed uncle work is unverifiable and could otherwise be
            // inflated with fake in-range refs to force a pop/restore (M4). The AUTHORITATIVE choice, made
            // here after the bodies applied, weights genuine uncle work: engine.totalWork() now folds in
            // each uncle addBlock proved eligible. Adopt only if the peer's uncle-inclusive total strictly
            // exceeds ours — a branch with more BASE work but a lighter subtree must not displace the
            // heavier subtree. The uncle work counted here is validated, not the gate's claim, so no M4
            // lever. An EXACT total tie — the metastable-camp case the gate already recognised — is
            // decided by the same deterministic tip-hash tiebreak the gate applied (smaller hex wins),
            // so the adopted side is the one every node agrees on; a liar whose validated total came
            // out equal but whose tip loses is restored exactly as before.
            int totalCmp = engine.totalWork().compareTo(local.totalWork());
            if (totalCmp < 0 || (totalCmp == 0 && engine.tipHash().toHexString()
                    .compareTo(local.tipHash().toHexString()) >= 0)) {
                ReorgSupport.restore(engine, forkHeight, local.blocks());
                return ChainSynchronizer.Result.NO_CHANGE;
            }
            // The branch we replaced is valid work that lost the fork race; keep its blocks as
            // orphans so a later block can reference them as uncles (GHOST).
            for (Block block : local.blocks()) {
                engine.registerOrphan(block);
            }
            return ChainSynchronizer.Result.REORGED;
        });
    }

    /**
     * The deterministic tiebreak for an EXACT tie — equal base work above the fork AND equal
     * uncle-inclusive totals: the branch whose tip hash is lexicographically smaller wins
     * (testnet campaign S5/S7 replay: equal-rate mining camps held equal base AND equal totals
     * for hours; the asymmetric total-only tiebreak never fired, and once past finality the
     * loser could never rejoin). Every node computes the same verdict, so the camps converge in
     * one round and the outcome cannot oscillate — the winner is canonical everywhere afterwards.
     * Only applied when both branches reach the same height (the tie's own shape: equal base work
     * above a constant-difficulty fork implies equal heights); otherwise the peer loses, keeping
     * the gate's decision cheap and the comparison meaningful.
     */
    private boolean peerTipWinsTiebreak(List<BlockHeader> branch, PeerSource peer) {
        BlockHeader branchTip = branch.get(branch.size() - 1);
        return branchTip.id() == peer.height()
            && branchTip.hash().toHexString().compareTo(engine.tipHash().toHexString()) < 0;
    }
}
