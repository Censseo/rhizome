package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.crypto.SHA256Hash;

/**
 * A peer's chain as seen by the synchronizer. An HTTP adapter maps these onto
 * {@code /block_count}, {@code /total_work}, {@code /block}, {@code /sync} and
 * {@code /headers}.
 */
public interface PeerSource {

    long height();

    BigInteger totalWork();

    /** Header hash of the peer's block at {@code height} (for fork detection). */
    SHA256Hash blockHash(long height);

    /** Peer blocks in the inclusive range (the caller keeps ranges bounded). */
    List<Block> blocks(long start, long end);

    /**
     * Peer block headers in the inclusive range — the cheap path the headers-first
     * synchronizer validates before downloading any body. A peer (or adapter) that
     * predates the {@code /headers} endpoint throws {@link UnsupportedOperationException},
     * which the synchronizer treats as a signal to fall back to full-block sync (D7).
     */
    default List<BlockHeader> headers(long start, long end) {
        throw new UnsupportedOperationException("peer does not support headers-first sync");
    }
}
