package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.UncleRef;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.crypto.SHA256Hash;

/**
 * The GHOST uncle machinery extracted from {@link ChainEngine} (archi-review lot L19): the
 * orphan pool, the orphan-PoW verify-once cache, and the admission, selection and validation
 * rules over them.
 *
 * <p>NOT thread-safe by itself, and deliberately without a lock: every method must be called
 * with the engine lock held — the engine's public {@code registerOrphan} / {@code orphanBlock}
 * / {@code selectUncles} wrappers and every {@code addBlock} / {@code restoreBlock} path already
 * hold it, so a collaborator called from those already-locked methods preserves the engine's
 * single-lock discipline without re-entering it. The {@link ChainStore} is passed per call
 * (never held), so the caller decides which store the rules read and the "current difficulty"
 * a selection is capped by is handed in explicitly: the engine passes the tip's
 * {@code currentDifficulty} at the moment of the call, and a stale value is exactly what the
 * lock test {@code selectUnclesUsesTheTipDifficultyOfTheMoment} would expose.
 *
 * <p>If a future caller ever invokes these from an unlocked path, the extracted code must grow
 * its own guard: the lock-assertions the moved methods used to carry live on the engine's lock
 * and cannot be asserted here.
 */
final class UncleRegistry {

    private final NetworkParameters params;

    private final OrphanPool orphans = new OrphanPool(256);

    /**
     * Verify-once cache of orphan-header proof of work (audit: uncle re-hash). Every production
     * round's {@link #selectUncles} scans the whole orphan pool under the engine lock, and each
     * eligible orphan's memory-hard Pufferfish2 hash was re-run per candidate block — up to 256
     * hashes per round. PoW validity is a pure function of the header: the hash commits every
     * header field including the id (which selects the PoW cost via {@code powCostsAt}), so a
     * cached positive verdict can never go stale — it does NOT depend on the tip, hence needs no
     * invalidation on pop/reorg. Bounded LRU (access-order); only successes are cached, so an
     * attacker cannot evict useful entries with junk headers. Engine-lock-guarded like every
     * caller ({@link #registerOrphan}, {@link #uncleEligible}).
     */
    private final LinkedHashMap<SHA256Hash, Boolean> verifiedOrphanPow =
        new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SHA256Hash, Boolean> eldest) {
                return size() > 1024;
            }
        };

    UncleRegistry(NetworkParameters params) {
        this.params = params;
    }

    /**
     * Remembers a valid off-chain block so a later block may reference it as an uncle.
     * Only blocks with valid proof of work are retained (no free pool spam).
     */
    void registerOrphan(Block block, ChainStore store) {
        Block b = block;
        // Cheap, allocation-free pre-checks BEFORE the memory-hard verifyNonce (audit H3): the
        // Pufferfish2 hash is expensive by design, so `/submit` must not let an attacker force one
        // per throwaway block. A block can only ever become a valid uncle if it is a recent
        // sibling — its height within the uncle-depth window of our tip, and its parent our known
        // canonical block at height-1 (exactly what uncleEligible later requires). Garbage with a
        // random parent or an out-of-window height is dropped here for a few comparisons instead
        // of a hash. This also removes the double-hash: a would-be next block (id = tip+1) that
        // failed addBlock's own verifyNonce has id > tip and is rejected below without re-hashing.
        long tip = store.height();
        int depth = params.uncleMaxDepth();
        int uid = b.id();
        if (b.difficulty() < params.minDifficulty()) {
            return; // worthless as an uncle; also forgeable free "work"
        }
        // Same serialized-size bound addBlock enforces, checked BEFORE the PoW hash: the
        // pool retains full bodies, so without it a spray of max-size orphans (each with a
        // real but cheap minDifficulty PoW) could pin ~maxSize × maxBlockSizeBytes of heap
        // (audit: orphan body retention).
        if (block.serializedSize() > params.maxBlockSizeBytes()) {
            return; // too large to ever be accepted or referenced
        }
        if (uid <= GenesisBlock.GENESIS_ID || uid > tip || uid < tip - depth + 1) {
            return; // not a recent past sibling of a block we could still build on
        }
        if (!store.headerAt(uid - 1).hash().equals(b.lastBlockHash())) {
            return; // must fork from our known main-chain parent at height uid-1
        }
        // Only now the memory-hard proof-of-work check, on a block that is at least a
        // structurally-plausible recent sibling — verify-once: a sibling already proven (e.g.
        // re-gossiped, or scanned by selectUncles on a previous round) is not re-hashed.
        if (verifyOrphanPowOnce(block, uid)) {
            orphans.put(block);
        }
    }

    /** The full body of a known orphan by hash: the live pool first, then the store's
     *  persisted uncle bodies (surviving a restart or an LRU eviction — audit:
     *  uncle-sync blocker). {@code null} when unknown. */
    Block orphanBlock(SHA256Hash hash, ChainStore store) {
        Block orphan = orphans.get(hash);
        return orphan != null ? orphan : store.uncleAt(hash);
    }

    /** The pooled orphan body by hash (pool only — used to persist referenced uncles). */
    Block pooled(SHA256Hash hash) {
        return orphans.get(hash);
    }

    /**
     * The uncle work committed by {@code block}'s references, summed as {@code Σ 2^difficulty} — the
     * same total {@link #validateUncles} returns, but read straight from the (already-validated)
     * references without consulting the orphan pool. Used only by the engine's trusted-restore
     * path.
     *
     * <p>Even on the trusted path the pool-free structural bounds are enforced from the refs alone
     * (audit F2): at most {@code maxUnclesPerBlock} references, distinct hashes, and
     * {@code minDifficulty <= ref.difficulty() <= block.difficulty()} — the SAME range
     * {@link #uncleEligible} and {@code HeaderChain.uncleWork} enforce (nephewDifficulty = the
     * including block's own difficulty), so every path agrees on the bound. Returns {@code null}
     * when a bound fails, exactly like {@link #validateUncles}.
     */
    BigInteger uncleWorkFromRefs(Block block) {
        return UncleWeight.structuralWork(block.uncles(), block.difficulty(), params);
    }

    /**
     * Full uncle validation (GHOST): bounded count and distinct; each uncle must be a
     * known orphan with valid PoW, recent (its id strictly below this block and within
     * {@code uncleMaxDepth}), forked from a main-chain block (its parent is on our
     * chain), not itself the canonical block at its height, and not already referenced
     * by a recent block. Returns the summed uncle work (2^difficulty over the
     * referenced uncles) to fold into the chain weight, or {@code null} if any
     * check fails.
     */
    BigInteger validateUncles(Block block, ChainStore store) {
        List<UncleRef> uncles = block.uncles();
        if (uncles.isEmpty()) {
            return BigInteger.ZERO;
        }
        if (uncles.size() > params.maxUnclesPerBlock()) {
            return null;
        }
        int h = block.id();
        int depth = params.uncleMaxDepth();
        long tipHeight = store.height();
        UncleContext ctx = uncleContext(h, depth, tipHeight, store);

        BigInteger uncleWork = BigInteger.ZERO;
        Set<SHA256Hash> seen = new HashSet<>();
        for (UncleRef ref : uncles) {
            SHA256Hash u = ref.hash();
            if (!seen.add(u)) {
                return null; // distinct
            }
            Block uncle = orphans.get(u);
            if (uncle == null) {
                // The sync paths' uncle-resolve skips a fetch when the body is already known, and
                // "known" includes the PERSISTED uncle bodies (addBlock persists referenced uncles
                // so fresh nodes can fetch them later). A node that applied the referencing block
                // before a restart therefore holds the body in the store but NOT in the in-memory
                // pool: the retry after the resolve would fail right here — INVALID_UNCLES every
                // round, a PEER_INVALID ban of an honest peer (campaign 2, S7: a wedged cluster
                // that froze at the first block referencing a persisted uncle). The persisted body
                // was fully validated when first applied and its eligibility is re-checked below
                // against the live context, so falling back to it is exact.
                uncle = store.uncleAt(u);
            }
            if (uncle == null) {
                return null; // unknown orphan
            }
            if (uncle.difficulty() != ref.difficulty()) {
                return null; // committed difficulty must match the real orphan (no work inflation)
            }
            PublicAddress uncleMiner = blockMiner(uncle);
            if (uncleMiner == null || !uncleMiner.equals(ref.miner())) {
                return null; // committed miner must match the real orphan (no reward redirection)
            }
            // The nephew's difficulty caps how much work any uncle it references can claim,
            // and minDifficulty floors it — see uncleEligible. block.difficulty() is the
            // including block's own difficulty.
            if (!uncleEligible(uncle, h, depth, tipHeight, ctx, block.difficulty(), store)) {
                return null;
            }
            uncleWork = uncleWork.add(BlockWork.of(ref.difficulty()));
        }
        return uncleWork;
    }

    /**
     * The uncle references a block at height {@code height} would include when
     * produced now: eligible orphans from the pool, up to {@code maxUnclesPerBlock},
     * each committing the orphan's real difficulty. Empty when nothing qualifies.
     *
     * @param currentDifficulty the difficulty the produced block will carry (the tip's at the
     *                          moment of the call) — the eligibility cap is exactly this value,
     *                          so the produced block passes its own {@link #validateUncles}.
     */
    List<UncleRef> selectUncles(ChainStore store, int currentDifficulty) {
        // Block ids are int on the wire (BlockDto/HeaderCodec), so the chain is protocol-capped
        // at 2^31-1 blocks (~340 years at 5 s) — an accepted protocol limit, frozen by the format.
        int h = (int) (store.height() + 1);
        int depth = params.uncleMaxDepth();
        long tipHeight = store.height();
        UncleContext ctx = uncleContext(h, depth, tipHeight, store);
        List<UncleRef> out = new ArrayList<>();
        for (Block orphan : orphans.snapshot()) {
            if (out.size() >= params.maxUnclesPerBlock()) {
                break;
            }
            PublicAddress orphanMiner = blockMiner(orphan);
            // The block being produced at height h will carry the current difficulty;
            // only reference orphans whose difficulty fits [minDifficulty, currentDifficulty]
            // so the produced block passes its own validateUncles check.
            if (orphanMiner != null
                    && uncleEligible(orphan, h, depth, tipHeight, ctx, currentDifficulty, store)) {
                out.add(new UncleRef(orphan.hash(), orphan.difficulty(), orphanMiner));
            }
        }
        return out;
    }

    /** Test hook: current size of the orphan-PoW verify-once cache (bounded-LRU assertions). */
    int verifiedOrphanPowCacheSizeForTest() {
        return verifiedOrphanPow.size();
    }

    /** The coinbase recipient (miner) of a block, or {@code null} if it has no coinbase. */
    private static PublicAddress blockMiner(Block block) {
        for (Transaction tx : block.transactions()) {
            if (tx.isTransactionFee()) {
                return tx.to();
            }
        }
        return null;
    }

    /** Recent main-chain hashes an uncle may fork from, and uncle hashes already referenced. */
    private UncleContext uncleContext(int h, int depth, long tipHeight, ChainStore store) {
        Set<SHA256Hash> recentChain = new HashSet<>();
        Set<SHA256Hash> alreadyReferenced = new HashSet<>();
        for (long ancestor = Math.max(GenesisBlock.GENESIS_ID, h - depth - 1L); ancestor <= tipHeight; ancestor++) {
            BlockHeader onChain = store.headerAt(ancestor);
            recentChain.add(onChain.hash());
            if (ancestor >= h - depth) {
                for (UncleRef ref : onChain.uncles()) {
                    alreadyReferenced.add(ref.hash());
                }
            }
        }
        return new UncleContext(recentChain, alreadyReferenced);
    }

    /**
     * Whether {@code uncle} is a valid uncle for a block at height {@code h} whose own
     * difficulty is {@code nephewDifficulty}. The uncle's difficulty must lie in
     * {@code [minDifficulty, nephewDifficulty]}: no zero-/sub-minimum-work uncle earns a
     * reward, and none can be credited more work than the contemporaneous chain difficulty.
     * The same bound is enforced in HeaderChain.uncleWork so mining, block validation and
     * headers-first sync all agree.
     */
    private boolean uncleEligible(Block uncle, int h, int depth, long tipHeight, UncleContext ctx,
                                  int nephewDifficulty, ChainStore store) {
        // Cheapest-first, PoW last — the same DoS-ordering doctrine as addBlock: the memory-hard
        // verifyNonce runs only for orphans every cheap check already accepts, so a pool full of
        // already-referenced or out-of-range orphans costs map lookups, not Pufferfish2 hashes,
        // on every production round (selectUncles scans the whole orphan pool under the engine
        // lock). Pure checks only, so the verdict is unchanged by the reordering.
        int ud = uncle.difficulty();
        if (ud < params.minDifficulty() || ud > nephewDifficulty) {
            return false; // work must be real and not inflated beyond the nephew's difficulty
        }
        int uid = uncle.id();
        if (uid >= h || uid < h - depth) {
            return false; // recent and strictly before this block
        }
        if (!ctx.recentChain().contains(uncle.lastBlockHash())) {
            return false; // must fork from a recent main-chain block
        }
        if (ctx.alreadyReferenced().contains(uncle.hash())) {
            return false; // not already credited
        }
        if (uid <= tipHeight && store.headerAt(uid).hash().equals(uncle.hash())) {
            return false; // that is the canonical block, not an orphan
        }
        // Real PoW, last — verify-once: the memory-hard hash is deterministic per header, so an
        // orphan already proven (registration, an earlier production round's scan, or a previous
        // block's uncle validation) is a cache hit instead of a fresh Pufferfish2 run. selectUncles
        // scans the whole pool under the engine lock on EVERY production round; without the cache
        // that is up to 256 Pufferfish2 hashes per candidate block.
        return verifyOrphanPowOnce(uncle, uid);
    }

    /**
     * {@code verifyNonce} with a bounded verify-once cache keyed by the header hash (see
     * {@link #verifiedOrphanPow}). Only successful verifications are cached; failures re-run
     * (they follow the same deterministic verdict, so caching them would only let junk evict
     * useful entries). Callers hold the engine lock.
     */
    private boolean verifyOrphanPowOnce(Block block, int id) {
        SHA256Hash key = block.hash();
        if (verifiedOrphanPow.containsKey(key)) {
            return true;
        }
        if (!block.verifyNonce(params.powAlgorithm(), params.powCostsAt(id))) {
            return false;
        }
        verifiedOrphanPow.put(key, Boolean.TRUE);
        return true;
    }

    private record UncleContext(Set<SHA256Hash> recentChain,
                                Set<SHA256Hash> alreadyReferenced) {}
}
