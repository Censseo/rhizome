package rhizome.core.block;

import java.nio.ByteBuffer;
import java.util.List;

import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PowCosts;
import rhizome.crypto.SHA256Hash;

import static rhizome.crypto.Crypto.verifyHash;

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
        Block b = block;
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
        // Single contiguous preimage buffer + one digest call: the previous incremental form
        // paid a MessageDigest.getInstance plus several ByteBuffer allocations per hash, and a
        // header is hashed at every sync/checkpoint step (audit perf). The byte layout is
        // identical to the incremental preimage — consensus-critical, do not reorder.
        boolean withStateRoot = stateRoot != null && !stateRoot.equals(SHA256Hash.empty());
        boolean withVote = vote != 0;
        boolean withUncles = uncles != null && !uncles.isEmpty();
        int size = 2 * SHA256Hash.SIZE + 3 * Integer.BYTES + Long.BYTES
            + (withStateRoot ? SHA256Hash.SIZE : 0)
            + (withVote ? Integer.BYTES : 0)
            + (withUncles ? uncles.size() * (SHA256Hash.SIZE + rhizome.core.ledger.PublicAddress.SIZE)
                + uncles.size() * Integer.BYTES : 0);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        // raw() below: ByteBuffer.put copies each array into the preimage buffer immediately and
        // nothing is retained — a header is hashed at every sync/checkpoint step, so the
        // per-field clone of toBytes() was pure churn on this hot path (audit perf).
        buffer.put(merkleRoot.raw());
        buffer.put(lastBlockHash.raw());
        buffer.putInt(id);
        buffer.putInt(difficulty);
        buffer.putInt(numTransactions);
        buffer.putLong(timestamp);
        if (withStateRoot) {
            buffer.put(stateRoot.raw());
        }
        if (withVote) {
            buffer.putInt(vote);
        }
        if (withUncles) {
            ByteBuffer uncleBuf = ByteBuffer.allocate(uncles.size() * Integer.BYTES);
            for (UncleRef uncle : uncles) {
                buffer.put(uncle.hash().raw());
                uncleBuf.putInt(uncle.difficulty());
                buffer.put(uncle.miner().toBytes());
            }
            buffer.put(uncleBuf.array());
        }
        return rhizome.crypto.Crypto.SHA256(buffer.array());
    }

    /**
     * Checks the proof-of-work nonce under the chain's PoW algorithm — without
     * needing the block body, since the header carries the whole preimage.
     * Uses the genesis cost parameters ({@link PowCosts#DEFAULT}).
     */
    public boolean verifyNonce(PowAlgorithm powAlgorithm) {
        return verifyNonce(powAlgorithm, PowCosts.DEFAULT);
    }

    /**
     * Checks the proof-of-work nonce under the chain's PoW algorithm and the cost
     * parameters in force at this header's height (see
     * {@code NetworkParameters#powCostsAt}) — without needing the block body.
     */
    public boolean verifyNonce(PowAlgorithm powAlgorithm, PowCosts costs) {
        boolean usePufferfish = powAlgorithm == PowAlgorithm.PUFFERFISH2;
        return verifyHash(hash(), nonce, difficulty, usePufferfish, true, costs);
    }
}
