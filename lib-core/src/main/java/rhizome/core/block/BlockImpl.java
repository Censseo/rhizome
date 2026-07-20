package rhizome.core.block;

import static rhizome.core.common.Constants.*;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

import org.json.JSONObject;

import lombok.Builder;
import lombok.Data;
import rhizome.core.block.dto.BlockDto;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.transaction.Transaction;

@Data @Builder
public final class BlockImpl implements Block {

    /**
     * Variable Definitions
     */
    @Builder.Default
    private int id = 1;

    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    @Builder.Default
    private int difficulty = MIN_DIFFICULTY;

    @Builder.Default
    private List<Transaction> transactions = new ArrayList<>();

    @Builder.Default
    private SHA256Hash merkleRoot = SHA256Hash.empty();

    @Builder.Default
    private SHA256Hash lastBlockHash = SHA256Hash.empty();

    @Builder.Default
    private SHA256Hash nonce = SHA256Hash.empty();

    /**
     * Authenticated state root after this block (§ state root): a sparse-Merkle commitment
     * to the ledger, box and token state, so a light client can prove any single entry.
     * Empty when the producing node runs without the state accumulator; committed in the
     * header hash only when non-empty, so a stateless block hashes exactly as before.
     */
    @Builder.Default
    private SHA256Hash stateRoot = SHA256Hash.empty();

    /**
     * The miner's parameter vote for this block (§ miner voting): {@code 0} abstains,
     * {@code ±1} votes on {@code storageFeeFactor}, {@code ±2} on {@code minValuePerByte}.
     * Committed in the header hash only when non-zero, so an abstaining block hashes as before.
     */
    @Builder.Default
    private int vote = 0;

    /**
     * Hashes of referenced uncle blocks (valid orphans sharing a recent ancestor).
     * Empty for a plain block; the basis of the GHOST fork choice. Committed in the
     * header hash only when non-empty, so an uncle-less block hashes exactly as it
     * did before uncles existed.
     */
    @Builder.Default
    private List<UncleRef> uncles = new ArrayList<>();

    /**
     * Serialization
     */
    public BlockDto serialize() {
       return serialize(this);
    }

    public JSONObject toJson() {
        return toJson(this);
    }

    /**
     * Block header hash.
     *
     * <p>Delegates to {@link BlockHeader}, the single canonical preimage
     * definition, so the block and its logical header can never hash
     * differently. The preimage commits to
     * {@code merkleRoot || lastBlockHash || id || difficulty || numTransactions || timestamp}
     * (integers big-endian), then the optional {@code stateRoot}, {@code vote}
     * and uncle references only when set. Pandanite's C++ omitted {@code id} and
     * the transaction count, which left the PoW-algorithm switch keyed on a value
     * the PoW itself did not commit to; the clean chain closes that hole.
     */
    public SHA256Hash hash() {
        return BlockHeader.of(this).hash();
    }

    /**
     * Checks the proof-of-work nonce under the chain's PoW algorithm (from
     * {@code NetworkParameters}). The clean chain uses Pufferfish2 from genesis;
     * there is no height-based algorithm switch.
     */
    public boolean verifyNonce(PowAlgorithm powAlgorithm) {
        return BlockHeader.of(this).verifyNonce(powAlgorithm);
    }

    /**
     * Utils
     */
    public void addTransaction(Transaction t) {
        transactions.add(t);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlockImpl block = (BlockImpl) o;
        return id == block.id && difficulty == block.difficulty && timestamp == block.timestamp &&
            nonce.equals(block.nonce) && merkleRoot.equals(block.merkleRoot) &&
            lastBlockHash.equals(block.lastBlockHash) && transactions.equals(block.transactions) &&
            Objects.equals(stateRoot, block.stateRoot) && vote == block.vote;
    }

    @Override
    public int hashCode() {
        return Objects.hash(nonce, id, difficulty, timestamp, merkleRoot, lastBlockHash, transactions,
            stateRoot, vote);
    }
}
