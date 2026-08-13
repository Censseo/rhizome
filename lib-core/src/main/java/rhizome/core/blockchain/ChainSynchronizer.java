package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.common.Constants;
import rhizome.crypto.SHA256Hash;
import rhizome.core.mempool.ExecutionStatus;

/**
 * Synchronises the local chain toward a peer, adopting the peer's chain only if
 * it has strictly greater cumulative work — the objective fork-choice rule
 * (Pandanite forked repeatedly for lack of one).
 *
 * <p>Hardened against hostile peers:
 * <ul>
 *   <li><b>Finality window</b> — a reorg deeper than
 *       {@code NetworkParameters.maxReorgDepth} is refused outright; buried
 *       blocks cannot be rewritten no matter the claimed work.</li>
 *   <li><b>No free rollbacks</b> — before any local state is touched, a bounded
 *       prefix of the peer branch is fetched and validated statelessly:
 *       id continuity, hash chaining from the fork point, per-block
 *       proof-of-work, and total verified work strictly above our own branch.
 *       A peer that merely <em>claims</em> huge work can therefore cost us a
 *       bounded download and some hash checks — never a pop/restore cycle.
 *       Passing this gate requires actually spending more PoW than our chain
 *       carries.</li>
 *   <li><b>Restore on failure</b> — if the stateful apply still fails (e.g.
 *       wrong derived difficulty), the local chain is restored exactly.</li>
 * </ul>
 */
public final class ChainSynchronizer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChainSynchronizer.class);

    public enum Result { NO_CHANGE, EXTENDED, REORGED, REORG_TOO_DEEP, INCOMPATIBLE, PEER_INVALID, PEER_PRUNED }

    /** Extra blocks fetched beyond the fork depth during pre-validation. */
    static final int PREFETCH_EXTRA = 2 * Constants.BLOCKS_PER_FETCH;

    private final ChainEngine engine;
    /** Retarget memo for the stateless header-chain gate — see {@link DifficultyMemo}, shared with
     *  HeaderSynchronizer (self-invalidating by boundary-header hash, guarded by its own monitor). */
    private final DifficultyMemo difficultyMemo = new DifficultyMemo();

    public ChainSynchronizer(ChainEngine engine) {
        this.engine = engine;
    }

    public Result syncFrom(PeerSource peer) {
        try {
            return syncFromOrThrow(peer);
        } catch (LocalSaturationException e) {
            // A LOCAL bound (transport backpressure) stopped the exchange before it reached the
            // peer — not misbehaviour: no ban score, no PEER_INVALID. Retried next round.
            log.debug("sync deferred: local exchange saturated; will retry next round");
            return Result.NO_CHANGE;
        }
    }

    private Result syncFromOrThrow(PeerSource peer) {
        // Prefilter against BASE work (not the uncle-inclusive total), matching the base-only adoption
        // gate below — see HeaderSynchronizer.syncFrom for the full rationale (audit 5th-pass,
        // reorg-gate metric). peer.totalWork() bounds the peer's base work from above, so this never
        // skips a peer the adoption gate would accept, and over-reporting still can't force a reorg.
        // STRICT skip: a peer at exactly our base work may still win the gate's deterministic
        // tiebreak (equal base, equal total, smaller tip hash), so it must reach the gate.
        BigInteger peerTotal = peer.totalWork();
        if (peerTotal.compareTo(engine.baseWork()) < 0) {
            return Result.NO_CHANGE;
        }

        long forkHeight;
        try {
            forkHeight = findCommonAncestor(peer);
        } catch (LocalSaturationException e) {
            throw e; // local backpressure, not a peer fault: syncFrom maps it to NO_CHANGE
        } catch (PeerUnavailableException e) {
            // Transport failure while probing (the peer 503'd — e.g. itself mid-reorg): no data
            // to judge, so not invalid. Re-throw so the round logs DEBUG and retries next round,
            // never a ban (testnet campaign S5; same rule as HeaderSynchronizer).
            throw e;
        } catch (RuntimeException e) {
            // A peer that throws or returns an empty/garbage response while we probe for the common
            // ancestor must be treated as invalid, exactly like the bulk fetch phases below — never
            // allowed to propagate out of syncFrom and disrupt the whole sync pass (audit V6c).
            return Result.PEER_INVALID;
        }
        if (forkHeight < GenesisBlock.GENESIS_ID) {
            return Result.INCOMPATIBLE; // genesis mismatch: different network
        }

        if (forkHeight == engine.height()) {
            // Bound the extension window exactly like the headers-first path
            // (HeaderSynchronizer.MAX_HEADER_WINDOW): peer.height() is self-reported, and a
            // peer that forced this fallback (by refusing /headers) could otherwise declare a
            // near-infinite height and pin the sync thread on empty/timing-out fetches forever
            // — the round budget is only checked BETWEEN peers, never mid-sync (audit: sync
            // window). Returning EXTENDED after a capped window lets the next round continue.
            long to = Math.min(peer.height(), forkHeight + HeaderSynchronizer.MAX_HEADER_WINDOW);
            return applyRange(peer, forkHeight + 1, to) ? Result.EXTENDED : Result.PEER_INVALID;
        }
        return reorg(peer, forkHeight, peerTotal);
    }

    /**
     * Highest height at which our block and the peer's agree — the shared block-locator search
     * (exponential probes to bracket the fork, then a binary search inside, O(log height) peer
     * round-trips — audit M6), with the peer's hash read through the full-block transport: a
     * FULL block per probe (peer.blockHash → GET /block, up to ~1 MiB), because this fallback
     * path exists precisely for peers that do not serve /headers. The headers-first path runs
     * the same locator over peer.headers — see {@link AncestorLocator}.
     */
    private long findCommonAncestor(PeerSource peer) {
        // headerAt, not blockAt (audit F4): headers survive body pruning and hash identically
        // (BlockImpl.hash() delegates to BlockHeader), so the fork probe still works on a pruned
        // node — blockAt would throw below the prune watermark and an honest archive peer would be
        // misjudged as PEER_INVALID instead of simply diverging below the reorg horizon.
        // peer::blockHash never answers null (junk throws PeerProtocolException from the adapter);
        // a null here would mean the adapter lied about the transport — junk either way.
        Long ancestor = AncestorLocator.findCommonAncestor(engine.height(), peer.height(),
            h -> engine.headerAt(h).hash(), peer::blockHash);
        if (ancestor == null) {
            throw new PeerProtocolException("peer block probes answered nothing");
        }
        return ancestor;
    }

    /**
     * Applies [from..to] in {@link Constants#BLOCKS_PER_FETCH} windows, overlapping the download
     * of window K+1 with the apply of window K — the same pipeline
     * {@link HeaderSynchronizer#applyBodies} runs on the headers-first path, mirrored here so the
     * full-block fallback (a peer without {@code /headers}) is not left strictly alternating
     * network and CPU. Measured split on a loopback peer with 16 KiB blocks: fetch and apply are
     * near enough to even that hiding one behind the other is worth ~1.7x
     * ({@code SyncThroughputBenchmark}).
     *
     * <p>Consensus is untouched: application stays strictly serial and in order on this thread,
     * so the applied sequence — and therefore every {@code addBlock} verdict and the state root —
     * is byte-for-byte what the serial loop produced. Only read-only network I/O moves off-thread.
     * Exactly one fetch is ever outstanding, so peak memory is two windows instead of one.
     *
     * <p>Failure semantics are preserved exactly: this method propagated every
     * {@link RuntimeException} from {@code peer.blocks} to its caller (which maps
     * {@link PeerUnavailableException} and {@link LocalSaturationException} differently from a
     * malformed response), so the cause is rethrown unwrapped rather than folded into a boolean.
     */
    private boolean applyRange(PeerSource peer, long from, long to) {
        return BodyPipeline.run("rhizome-block-fetch", peer, from, to,
            cause -> {
                if (cause instanceof RuntimeException runtime) {
                    throw runtime; // exactly what the un-pipelined loop let through
                }
                throw new IllegalStateException("block fetch failed", cause);
            },
            blocks -> {
                for (Block block : blocks) {
                    if (applyWithUncleFetch(engine, peer, block, engine::addBlock) != ExecutionStatus.SUCCESS) {
                        return false;
                    }
                }
                return true;
            });
    }

    /**
     * Applies {@code block} through {@code apply}, with a single uncle-fetch retry on
     * {@link ExecutionStatus#INVALID_UNCLES} (audit: uncle-sync blocker). A block carries only
     * {@link UncleRef}s — never the orphan bodies — so a node syncing an uncle-bearing chain
     * (fresh node, empty orphan pool) cannot pass {@code validateUncles} on its own. On that
     * failure each missing body is fetched from the serving peer and pooled —
     * {@link ChainEngine#registerOrphan} re-checks the structural eligibility AND the
     * memory-hard PoW, so a peer cannot smuggle in a fake orphan — then the apply is retried
     * exactly once. A peer that predates the orphan endpoint, serves nothing, or serves junk
     * leaves the failure standing and the caller treats the peer as invalid, exactly as before.
     *
     * <p>Caller contract: the fetch is network I/O, so this must run OUTSIDE the consensus
     * lock (each engine call inside is individually locked). The reorg path, which applies
     * under {@code withConsistentView}, prefetches via {@link #prefetchUncles} instead.
     */
    static ExecutionStatus applyWithUncleFetch(ChainEngine engine, PeerSource peer, Block block,
                                               java.util.function.Function<Block, ExecutionStatus> apply) {
        ExecutionStatus status = apply.apply(block);
        if (status != ExecutionStatus.INVALID_UNCLES) {
            return status;
        }
        for (UncleRef ref : block.uncles()) {
            if (engine.orphanBlock(ref.hash()) != null) {
                continue; // already resolvable from the pool or the persisted uncles
            }
            Block orphan = fetchOrphan(peer, ref.hash());
            if (orphan != null) {
                engine.registerOrphan(orphan);
            }
        }
        return apply.apply(block);
    }

    /**
     * Fetches the uncle bodies {@code blocks} reference that we do not already hold (orphan
     * pool or persisted store), keyed by hash. Runs BEFORE the reorg's lock-held apply so no
     * network I/O happens under the consensus lock; the in-lock apply pools the prefetched
     * bodies on an {@code INVALID_UNCLES} failure and retries once (see the reorg path).
     */
    static Map<SHA256Hash, Block> prefetchUncles(ChainEngine engine, PeerSource peer, List<Block> blocks) {
        Map<SHA256Hash, Block> fetched = new HashMap<>();
        for (Block block : blocks) {
            for (UncleRef ref : block.uncles()) {
                SHA256Hash hash = ref.hash();
                if (fetched.containsKey(hash) || engine.orphanBlock(hash) != null) {
                    continue;
                }
                Block orphan = fetchOrphan(peer, hash);
                if (orphan != null) {
                    fetched.put(hash, orphan);
                }
            }
        }
        return fetched;
    }

    /** One orphan body from the peer, or {@code null} (not served / endpoint unsupported / transport error). */
    static Block fetchOrphan(PeerSource peer, SHA256Hash hash) {
        try {
            return peer.orphan(hash);
        } catch (LocalSaturationException e) {
            throw e; // local backpressure must not degrade into a peer-invalid verdict
        } catch (PeerUnavailableException e) {
            throw e; // transport failure: retried next round, never PEER_INVALID
        } catch (RuntimeException e) { // malformed body: leave the block unverifiable, retried elsewhere
            return null;
        }
    }

    private Result reorg(PeerSource peer, long forkHeight, BigInteger peerTotal) {
        long depth = engine.height() - forkHeight;
        if (depth > engine.params().maxReorgDepth()) {
            return Result.REORG_TOO_DEEP;
        }

        // --- Stateless gate: no local mutation until the peer has PROVEN more work ---
        long prefetchEnd = Math.min(peer.height(), forkHeight + depth + PREFETCH_EXTRA);
        List<Block> branch = fetchRange(peer, forkHeight + 1, prefetchEnd);
        if (branch == null) {
            return Result.PEER_INVALID;
        }
        // Validate the branch through the SAME stateless header-chain rule the headers-first path
        // uses (audit: fallback gate under-validates). The old gate checked only id continuity,
        // hash chaining and per-block PoW, so a branch carrying a WRONG recomputed difficulty, a
        // median-time-past violation or a far-future timestamp passed it — every such block is one
        // addBlock rejects, buying the attacker a pop/restore cycle per round. HeaderChain.validate
        // recomputes the expected difficulty from header timestamps, enforces MTP, the
        // min-block-time and future bounds, the checkpoint and the structural uncle limits BEFORE
        // any local mutation, and returns the branch's base-only work in the same pass.
        List<BlockHeader> branchHeaders = new ArrayList<>(branch.size());
        for (Block block : branch) {
            branchHeaders.add(BlockHeader.of(block));
        }
        HeaderChain.Result validated = difficultyMemo.validate(
            engine.params(), engine::headerAt, forkHeight, branchHeaders, engine.nowMillis());
        if (!validated.valid()) {
            return Result.PEER_INVALID;
        }
        int cmp = validated.work().compareTo(ReorgSupport.localWorkAboveFork(engine, forkHeight));
        if (cmp < 0) {
            // Claimed heavy, proved light: a structurally valid branch that merely loses the fork race
            // is not a protocol violation — return NO_CHANGE so an honest total-heavier/base-lighter
            // peer is not banned on the first strike (audit 5th-pass, reorg-gate metric).
            return Result.NO_CHANGE;
        }
        if (cmp == 0) {
            // Base-work tie: descend to the GHOST vote only when there is an arbitration worth
            // rendering (see HeaderSynchronizer for the full rationale — the S7 metastable-split
            // fix, extended with the deterministic tip-hash tiebreak for EXACT total ties so two
            // equal-rate mining camps cannot stay split forever).
            int totalCmp = peerTotal.compareTo(engine.totalWork());
            if (totalCmp < 0) {
                return Result.NO_CHANGE;
            }
            if (totalCmp == 0) {
                Block branchTip = branch.get(branch.size() - 1);
                if (branchTip.id() != peer.height()
                        || branchTip.hash().toHexString()
                            .compareTo(engine.tipHash().toHexString()) >= 0) {
                    return Result.NO_CHANGE;
                }
            }
        }

        // --- Stateful apply, with exact restore on failure ---
        // The whole pop→apply→(restore|adopt) sequence runs under ONE engine-lock hold via
        // withConsistentView. Each ChainEngine call is individually locked, but a reorg is a
        // multi-call sequence, and the block producer (its own thread) and /submit (the event loop)
        // both call engine.addBlock at engine.height()+1 — exactly where we pop to and re-apply. An
        // interleaved add between our pop and re-apply collided on block-id continuity and, worst case,
        // made restore() throw "full resync required", silently truncating the chain to forkHeight
        // (the exception is swallowed by the per-peer syncRound guard). Holding the lock across the
        // sequence restores the single-writer guarantee (WHITEPAPER §3.5/§4.9); the branch is already
        // prefetched, so no network I/O happens under the lock (audit: reorg atomicity). The reentrant
        // lock lets the inner pop/add/restore calls re-acquire it freely.
        // Prefetch the uncle bodies the branch references (fresh nodes hold none), OUTSIDE the
        // lock so the lock-held apply below does no network I/O (audit: uncle-sync blocker).
        Map<SHA256Hash, Block> branchUncles = prefetchUncles(engine, peer, branch);
        Result outcome = engine.withConsistentView(() -> {
            // The maxReorgDepth check is RE-DONE here, atomically with the pop below: the earlier
            // check ran outside the lock, and a concurrent local extension (producer or /submit)
            // since would otherwise make the actual reorg deeper than the finality window we
            // validated (audit review: finality TOCTOU — same fix as HeaderSynchronizer).
            if (engine.height() - forkHeight > engine.params().maxReorgDepth()) {
                return Result.REORG_TOO_DEEP;
            }
            // The capture-and-pop shared with the header path — one sequence, one order.
            ReorgSupport.LocalBranch local = ReorgSupport.captureAndPop(engine, forkHeight);

            for (Block block : branch) {
                // addValidatedBody, not addBlock: this exact block's header passed HeaderChain
                // validation (memory-hard PoW included) moments ago on this thread, and the block
                // is unmodified since — the addValidatedBody caller contract (hash equals a
                // PoW-validated header at this height) is satisfied exactly, so re-hashing under
                // the lock is pure waste (audit P4 pattern). Every other check runs in full.
                ExecutionStatus status = engine.addValidatedBody(block);
                if (status == ExecutionStatus.INVALID_UNCLES && !block.uncles().isEmpty()) {
                    // Pool the prefetched orphan bodies (registerOrphan re-checks PoW) and retry
                    // once — the fetch itself happened before the lock was taken.
                    for (UncleRef ref : block.uncles()) {
                        Block orphan = branchUncles.get(ref.hash());
                        if (orphan != null) {
                            engine.registerOrphan(orphan);
                        }
                    }
                    status = engine.addValidatedBody(block);
                }
                if (status != ExecutionStatus.SUCCESS) {
                    ReorgSupport.restore(engine, forkHeight, local.blocks());
                    return Result.PEER_INVALID;
                }
            }
            // GHOST fork choice (§3.7, audit S4): the base-only gate above is the anti-DoS prefilter;
            // the authoritative choice, now that the bodies applied and addBlock validated every uncle
            // ref, weights genuine uncle work via engine.totalWork(). Adopt the peer branch only if its
            // uncle-inclusive total strictly exceeds ours — a base-heavier but subtree-lighter branch must
            // not displace our heavier GHOST subtree. Validated uncle work only, so no M4 inflation lever.
            // An EXACT total tie is decided by the same deterministic tip-hash tiebreak the gate applied
            // (smaller hex wins) — the equal-rate-camp case that would otherwise stay split forever
            // (testnet campaign S5/S7 replay).
            int totalCmp = engine.totalWork().compareTo(local.totalWork());
            if (totalCmp < 0 || (totalCmp == 0
                    && engine.tipHash().toHexString().compareTo(local.tipHash().toHexString()) >= 0)) {
                ReorgSupport.restore(engine, forkHeight, local.blocks());
                return Result.NO_CHANGE;
            }
            // The branch we just replaced is valid work that lost the fork race; keep its
            // blocks as orphans so a later block can reference them as uncles (GHOST).
            for (Block block : local.blocks()) {
                engine.registerOrphan(block);
            }
            return Result.REORGED;
        });
        if (outcome == Result.REORGED) {
            // Branch prefix applied and (by the gate) already heavier than what it replaced; keep
            // extending toward the peer tip, best effort — but capped like the direct-extension
            // path above: peer.height() is self-reported and must never size an unbounded fetch
            // loop (audit: sync window). Network I/O, so deliberately OUTSIDE the lock —
            // each applyRange addBlock is individually locked and an interleaved peer/producer block just
            // ends the best-effort extension early.
            try {
                applyRange(peer, prefetchEnd + 1,
                    Math.min(peer.height(), prefetchEnd + HeaderSynchronizer.MAX_HEADER_WINDOW));
            } catch (PeerUnavailableException unavailable) {
                // The reorg above is COMMITTED and adopted; this tail is best-effort only. Letting a
                // transport failure here (the peer going 503 mid-extension, likely now that it reorgs
                // onto what we just adopted) propagate would discard the REORGED verdict — the round
                // would log a failure, never count the progress, and the stall counter would climb on
                // a node that just advanced. Stop extending; the next round resumes from the new tip.
                log.debug("post-reorg extension stopped: peer became unavailable; resuming next round");
            }
        }
        return outcome;
    }

    /** Fetches [from..to] in bounded batches; null on any transport/decode failure. */
    private List<Block> fetchRange(PeerSource peer, long from, long to) {
        List<Block> out = new ArrayList<>();
        try {
            for (long start = from; start <= to; start += Constants.BLOCKS_PER_FETCH) {
                long end = Math.min(to, start + Constants.BLOCKS_PER_FETCH - 1);
                out.addAll(peer.blocks(start, end));
            }
        } catch (LocalSaturationException e) {
            throw e; // local backpressure, not a peer fault: null would read as PEER_INVALID
        } catch (PeerUnavailableException e) {
            throw e; // transport failure: retried next round, never PEER_INVALID (see the probe)
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }
}
