package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rhizome.core.block.UncleRef;
import rhizome.crypto.SHA256Hash;

/**
 * The structural bound on the work a block may claim from its uncle references.
 *
 * <p>Three paths need it and must agree exactly: header-first sync scores a peer's headers before
 * it has any bodies, {@code ChainEngine.restoreBlock} re-scores a block it already trusted, and
 * full validation checks the same range against the orphan pool. Two of them carried byte-identical
 * copies differing only in whether they read a {@code BlockHeader} or a {@code BlockImpl}.
 *
 * <p>The difficulty range is the load-bearing part. Without an upper bound a peer could commit
 * uncles at {@code maxDifficulty} on a cheaply-mined {@code minDifficulty} branch and inflate its
 * headers-only claimed work toward 2^255 per header, defeating the anti-DoS work gate that
 * headers-first sync relies on. The nephew's own difficulty is that bound, so a value outside
 * {@code [minDifficulty, nephewDifficulty]} is rejected by every path and can never split the
 * chain.
 *
 * <p>Eligibility against the orphan pool — that an uncle is a real, recent, not-already-credited
 * block — is NOT here: it needs the bodies, so header validation defers it to full validation.
 */
final class UncleWeight {

    private UncleWeight() {
    }

    /**
     * Summed committed work of {@code uncles}, or {@code null} if the references are structurally
     * invalid: too many for one block, a hash repeated within the block, or a difficulty outside
     * {@code [minDifficulty, nephewDifficulty]}.
     *
     * <p>{@code null} rather than an exception because every caller treats a malformed reference
     * set as "reject this block", not as an error to propagate.
     */
    static BigInteger structuralWork(List<UncleRef> uncles, int nephewDifficulty,
                                     NetworkParameters params) {
        if (uncles.size() > params.maxUnclesPerBlock()) {
            return null;
        }
        BigInteger work = BigInteger.ZERO;
        Set<SHA256Hash> seen = new HashSet<>();
        for (UncleRef ref : uncles) {
            if (!seen.add(ref.hash())) {
                return null; // duplicate uncle within one block
            }
            int d = ref.difficulty();
            if (d < params.minDifficulty() || d > nephewDifficulty) {
                return null; // no free or inflated work, even from committed refs
            }
            work = work.add(BlockWork.of(d));
        }
        return work;
    }
}
