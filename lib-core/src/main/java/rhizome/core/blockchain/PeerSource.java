package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.crypto.SHA256Hash;

/**
 * A peer's chain as seen by the synchronizer. An HTTP adapter maps these onto
 * {@code /block_count}, {@code /total_work}, {@code /block} and {@code /sync}.
 */
public interface PeerSource {

    long height();

    BigInteger totalWork();

    /** Header hash of the peer's block at {@code height} (for fork detection). */
    SHA256Hash blockHash(long height);

    /** Peer blocks in the inclusive range (the caller keeps ranges bounded). */
    List<Block> blocks(long start, long end);
}
