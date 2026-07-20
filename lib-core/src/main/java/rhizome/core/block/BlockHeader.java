package rhizome.core.block;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import rhizome.core.common.PowAlgorithm;
import rhizome.core.crypto.SHA256Hash;

import static rhizome.core.common.Crypto.verifyHash;

/**
 * The logical block header: exactly the fields committed by the proof-of-work,
 * plus the uncle references (which the header hash also commits to, even though
 * {@link BlockCodec} serialises them after the transaction body). It is the
 * <b>canonical hash carrier</b> — {@link BlockImpl#hash()} delegates here, so
 * there is a single preimage definition and no risk of two implementations
 * drifting apart in consensus.
 *
 * <p>Because a header carries the whole PoW preimage, its nonce can be verified
 * without ever downloading the block body — the basis of headers-first sync.
 *
 * @param id             block height / identifier
 * @param timestamp      producer timestamp (ms)
 * @param difficulty     PoW difficulty (leading-zero bits)
 * @param numTransactions number of transactions in the body this header commits to
 * @param lastBlockHash  hash of the parent header
 * @param merkleRoot     Merkle root of the transaction body
 * @param nonce          PoW solution
 * @param stateRoot      authenticated state root after the block (empty ⇒ not committed)
 * @param vote           miner parameter vote (0 ⇒ abstain, not committed)
 * @param uncles         referenced uncle blocks (empty ⇒ not committed)
 */
public record BlockHeader(
        int id,
        long timestamp,
        int difficulty,
        int numTransactions,
        SHA256Hash lastBlockHash,
        SHA256Hash merkleRoot,
        SHA256Hash nonce,
        SHA256Hash stateRoot,
        int vote,
        List<UncleRef> uncles) {

    /** Extracts the logical header of a block (its full PoW preimage + uncle refs). */
    public static BlockHeader of(Block block) {
        var b = (BlockImpl) block;
        return new BlockHeader(
            b.id(),
            b.timestamp(),
            b.difficulty(),
            b.transactions().size(),
            b.lastBlockHash(),
            b.merkleRoot(),
            b.nonce(),
            b.stateRoot(),
            b.vote(),
            b.uncles());
    }

    /**
     * Block header hash — the single canonical preimage for the whole system.
     *
     * <p>Commits to {@code merkleRoot || lastBlockHash || id || difficulty ||
     * numTransactions || timestamp} (integers big-endian), then folds in the
     * optional fields only when set, so a block that uses none of them hashes
     * byte-for-byte as it did before those fields existed:
     * {@code stateRoot} (when non-empty), {@code vote} (when non-zero), and the
     * uncle references (when present: each uncle's hash and miner interleaved,
     * followed by all uncle difficulties).
     */
    public SHA256Hash hash() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

            sha256.update(merkleRoot.hash().getArray());
            sha256.update(lastBlockHash.hash().getArray());

            ByteBuffer buffer = ByteBuffer.allocate(3 * Integer.BYTES + Long.BYTES);
            buffer.putInt(id);
            buffer.putInt(difficulty);
            buffer.putInt(numTransactions);
            buffer.putLong(timestamp);
            sha256.update(buffer.array());

            if (stateRoot != null && !stateRoot.equals(SHA256Hash.empty())) {
                sha256.update(stateRoot.hash().getArray());
            }

            if (vote != 0) {
                sha256.update(ByteBuffer.allocate(Integer.BYTES).putInt(vote).array());
            }

            if (uncles != null && !uncles.isEmpty()) {
                ByteBuffer uncleBuf = ByteBuffer.allocate(uncles.size() * Integer.BYTES);
                for (UncleRef uncle : uncles) {
                    sha256.update(uncle.hash().hash().getArray());
                    uncleBuf.putInt(uncle.difficulty());
                    sha256.update(uncle.miner().toBytes());
                }
                sha256.update(uncleBuf.array());
            }

            return SHA256Hash.of(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new BlockException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Checks the proof-of-work nonce under the chain's PoW algorithm — without
     * needing the block body, since the header carries the whole preimage.
     */
    public boolean verifyNonce(PowAlgorithm powAlgorithm) {
        boolean usePufferfish = powAlgorithm == PowAlgorithm.PUFFERFISH2;
        return verifyHash(hash(), nonce, difficulty, usePufferfish, true);
    }
}
