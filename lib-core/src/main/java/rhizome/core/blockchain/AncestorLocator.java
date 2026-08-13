package rhizome.core.blockchain;

import java.util.function.LongFunction;

import rhizome.crypto.SHA256Hash;

/**
 * The block-locator ancestor search both synchronizers run to find the fork point.
 *
 * <p>{@link ChainSynchronizer} and {@link HeaderSynchronizer} kept byte-identical copies of
 * the probe — exponential steps down from the shared tip to bracket the fork, then a binary
 * search inside the bracket — differing only in how the peer's hash at a height is read
 * ({@code /block} on the full-block fallback, {@code /headers} on the headers-first path).
 * They diverge on exactly the point that makes the two copies drift: one copy can pick up a
 * probe cap or an off-by-one and the other not, and the two paths would then find different
 * forks for the same peer — a branch-choice split. The search is shared; the transport is a
 * parameter.
 *
 * <p>The search costs O(log height) peer round-trips instead of one per block (audit M6/M2).
 * The full-block fallback fetches a FULL block per probe (peer.blockHash → GET /block, up to
 * ~1 MiB), so the linear walk let a peer that 404s /headers (forcing this fallback) tie up a
 * sync thread for height×latency by never matching; the logarithmic locator caps that at
 * ~O(log height) fetches. Agreement is monotonic on a coherent chain (blocks match up to the
 * fork and diverge after), which makes the search exact.
 */
final class AncestorLocator {

    /** Hard cap on exponential-probe steps in the ancestor search, so it stays O(log height). */
    static final int MAX_ANCESTOR_PROBES = 64;

    private AncestorLocator() {
    }

    /**
     * Highest height at which the local chain and the peer's agree, or
     * {@code GenesisBlock.GENESIS_ID - 1} when not even genesis matches, or {@code null} when
     * {@code peerHashAt} returned null — the caller's signal that the peer does not serve the
     * transport being probed (headers-first against a pre-{@code /headers} peer), which
     * aborts the search so the caller can fall back.
     *
     * @param localHeight  the local chain height
     * @param peerHeight   the peer's self-reported height
     * @param localHashAt  our header hash at a height (headers survive body pruning and hash
     *                     identically to the block — audit F4 — so a pruned node probes exactly
     *                     like an archive node)
     * @param peerHashAt   the peer's header hash at a height, via whichever transport the caller
     *                     runs (blockHash for the full-block fallback, headers otherwise); null
     *                     aborts the search as "transport unsupported"
     */
    static Long findCommonAncestor(long localHeight, long peerHeight,
                                   LongFunction<SHA256Hash> localHashAt,
                                   LongFunction<SHA256Hash> peerHashAt) {
        long top = Math.min(localHeight, peerHeight);
        if (top < GenesisBlock.GENESIS_ID) {
            return (long) GenesisBlock.GENESIS_ID - 1;
        }
        SHA256Hash peerTop = peerHashAt.apply(top);
        if (peerTop == null) {
            return null; // peer does not serve this transport: caller falls back
        }
        if (localHashAt.apply(top).equals(peerTop)) {
            return top; // peer simply extends our chain
        }
        // Phase 1: exponential backoff to bracket the fork between a known match (low) and a
        // known mismatch (high).
        long high = top;   // known mismatch
        long low = -1;     // known match (none yet)
        long step = 1;
        long h = top - 1;
        int probes = 0;
        while (h >= GenesisBlock.GENESIS_ID && probes < MAX_ANCESTOR_PROBES) {
            probes++;
            SHA256Hash peerHash = peerHashAt.apply(h);
            if (peerHash == null) {
                return null; // peer does not serve this transport: caller falls back
            }
            if (localHashAt.apply(h).equals(peerHash)) {
                low = h;
                break;
            }
            high = h;
            if (h == GenesisBlock.GENESIS_ID) {
                break; // genesis itself differs: no common block, not even genesis
            }
            long next = h - step;
            step <<= 1;
            h = Math.max(next, GenesisBlock.GENESIS_ID);
        }
        if (low < 0) {
            return (long) GenesisBlock.GENESIS_ID - 1; // no common block, not even genesis
        }
        // Phase 2: binary search for the highest match in (low, high).
        while (high - low > 1) {
            long mid = low + (high - low) / 2;
            SHA256Hash peerHash = peerHashAt.apply(mid);
            if (peerHash == null) {
                return null; // peer does not serve this transport: caller falls back
            }
            if (localHashAt.apply(mid).equals(peerHash)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
