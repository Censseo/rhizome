package rhizome.core.block;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import rhizome.core.block.dto.BlockDto;
import rhizome.crypto.SHA256Hash;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;
import rhizome.core.serialization.Serializable;
import rhizome.core.transaction.Transaction;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public sealed interface Block permits BlockImpl {

    public static Block empty() {
        return BlockImpl.builder().build();
    }

    public static Block of(JSONObject json){
        return serializer().fromJson(json);
    }

    public static Block of(Block block) {
        var blockImpl = (BlockImpl) block;
        return BlockImpl.builder()
                .id(blockImpl.id())
                .timestamp(blockImpl.timestamp())
                .difficulty(blockImpl.difficulty())
                .merkleRoot(blockImpl.merkleRoot())
                .lastBlockHash(blockImpl.lastBlockHash())
                .nonce(blockImpl.nonce())
                .stateRoot(blockImpl.stateRoot())
                .vote(blockImpl.vote())
                .transactions(blockImpl.transactions())
                .uncles(blockImpl.uncles())
                .build();
    }

    public static Block of(BlockDto blockDto, List<Transaction> transactions) {
        return of(blockDto, transactions, List.of());
    }

    public static Block of(BlockDto blockDto, List<Transaction> transactions, List<UncleRef> uncles) {
        return BlockImpl.builder()
                .id(blockDto.id())
                .timestamp(blockDto.timestamp())
                .difficulty(blockDto.difficulty())
                .merkleRoot(blockDto.merkleRoot())
                .lastBlockHash(blockDto.lastBlockHash())
                .nonce(blockDto.nonce())
                .stateRoot(blockDto.stateRoot())
                .vote(blockDto.vote())
                .transactions(transactions)
                .uncles(new java.util.ArrayList<>(uncles))
                .build();
    }

    public BlockDto serialize();
    default BlockDto serialize(Block block) {
        return serializer().serialize(block);
    }

    public JSONObject toJson();
    default JSONObject toJson(Block block) {
        return serializer().toJson(block);
    }

    /** Key/value pairs only — no surrounding braces. See {@link #writeJson(JsonSink)}. */
    public void writeJsonBody(JsonSink sink);
    default void writeJsonBody(JsonSink sink, Block block) {
        serializer().writeJsonBody(sink, block);
    }

    /**
     * Same field set, types and conditions as {@link #toJson()}, written directly into
     * {@code sink} instead of building an {@code org.json} tree first — see {@code JsonSink}'s
     * class Javadoc for why. {@code toJson()} stays: {@link #of(JSONObject)} still parses the
     * tree form (including {@code HttpPeerSource}'s peer-JSON fallback path), and
     * {@code JsonWriterEquivalenceTest} uses it as the equivalence-test oracle this writer is
     * checked against.
     */
    default void writeJson(JsonSink sink) {
        sink.beginObject();
        writeJsonBody(sink);
        sink.endObject();
    }

    public int id();
    public Block id(int id);
    public void addTransaction(Transaction t);
    public List<Transaction> transactions();
    public List<UncleRef> uncles();
    public boolean verifyNonce(rhizome.crypto.PowAlgorithm powAlgorithm);
    public boolean verifyNonce(rhizome.crypto.PowAlgorithm powAlgorithm, rhizome.crypto.PowCosts costs);
    public SHA256Hash hash();
    public SHA256Hash lastBlockHash();
    public int difficulty();

    /**
     * Get instance of the serializer
     * @return
     */
    static BlockSerializer serializer(){
        return BlockSerializer.instance;
    }

    /**
     * Serializes the block
     */
    static class BlockSerializer implements Serializable<BlockDto, Block> {

        static final String ID = "id";
        static final String HASH = "hash";
        static final String TIMESTAMP = "timestamp";
        static final String DIFFICULTY = "difficulty";
        static final String NONCE = "nonce";
        static final String STATE_ROOT = "stateRoot";
        static final String VOTE = "vote";
        static final String MERKLE_ROOT = "merkleRoot";
        static final String LAST_BLOCK_HASH = "lastBlockHash";
        static final String TRANSACTIONS = "transactions";
        static final String UNCLES = "uncles";
        static final String MINER = "miner";

        // Pre-encoded "name": keys for the JsonSink writer (see JsonSink's class Javadoc for why
        // these are built once per call site rather than encoded per response).
        static final Key K_ID = Key.of(ID);
        static final Key K_HASH = Key.of(HASH);
        static final Key K_TIMESTAMP = Key.of(TIMESTAMP);
        static final Key K_DIFFICULTY = Key.of(DIFFICULTY);
        static final Key K_NONCE = Key.of(NONCE);
        static final Key K_STATE_ROOT = Key.of(STATE_ROOT);
        static final Key K_VOTE = Key.of(VOTE);
        static final Key K_MERKLE_ROOT = Key.of(MERKLE_ROOT);
        static final Key K_LAST_BLOCK_HASH = Key.of(LAST_BLOCK_HASH);
        static final Key K_TRANSACTIONS = Key.of(TRANSACTIONS);
        static final Key K_UNCLES = Key.of(UNCLES);
        static final Key K_MINER = Key.of(MINER);

        static BlockSerializer instance = new BlockSerializer();

        @Override
        public BlockDto serialize(Block block) {
            var blockImpl = (BlockImpl) block;
            return new BlockDto(
                blockImpl.id(),
                blockImpl.timestamp(),
                blockImpl.difficulty(),
                blockImpl.transactions().size(),
                blockImpl.lastBlockHash(),
                blockImpl.merkleRoot(),
                blockImpl.nonce(),
                blockImpl.stateRoot(),
                blockImpl.vote()
            );
        }
    
        @Override
        public Block deserialize(BlockDto object) {
            throw new UnsupportedOperationException("Not implemented");
        }
    
        @Override
        public JSONObject toJson(Block block) {
            var blockImpl = (BlockImpl) block;
            JSONObject result = new JSONObject();
            result.put(ID, blockImpl.id());
            try {
                result.put(HASH, blockImpl.hash().toHexString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            result.put(DIFFICULTY, blockImpl.difficulty());
            result.put(NONCE, blockImpl.nonce().toHexString());
            result.put(TIMESTAMP, Long.toString(blockImpl.timestamp()));
            result.put(MERKLE_ROOT, blockImpl.merkleRoot().toHexString());
            result.put(LAST_BLOCK_HASH, blockImpl.lastBlockHash().toHexString());
            // Committed only when set, mirroring the header hash, so a stateless block's
            // JSON (and the hash a peer recomputes from it) is unchanged.
            if (!blockImpl.stateRoot().equals(SHA256Hash.empty())) {
                result.put(STATE_ROOT, blockImpl.stateRoot().toHexString());
            }
            if (blockImpl.vote() != 0) {
                result.put(VOTE, blockImpl.vote());
            }
            JSONArray transactionsArray = new JSONArray();
            for (Transaction transaction : blockImpl.transactions()) {
                transactionsArray.put(transaction.toJson());
            }
            result.put(TRANSACTIONS, transactionsArray);
            if (!blockImpl.uncles().isEmpty()) {
                JSONArray unclesArray = new JSONArray();
                for (UncleRef uncle : blockImpl.uncles()) {
                    unclesArray.put(new JSONObject()
                        .put("hash", uncle.hash().toHexString())
                        .put(DIFFICULTY, uncle.difficulty())
                        .put("miner", uncle.miner().toHexString()));
                }
                result.put(UNCLES, unclesArray);
            }
            return result;
        }

        /**
         * Same field set, types and conditions as {@link #toJson(Block)} above, written
         * directly into {@code sink} instead of building an {@code org.json} tree — see
         * {@code JsonWriterEquivalenceTest} for the byte-level equivalence proof, including the
         * hash round-trip invariant this shape must preserve for {@code ChainSynchronizer}'s
         * fork-point bisection.
         */
        public void writeJsonBody(JsonSink sink, Block block) {
            var blockImpl = (BlockImpl) block;
            sink.field(K_ID, blockImpl.id());
            sink.hexUpper(K_HASH, blockImpl.hash().toBytes());
            sink.field(K_DIFFICULTY, blockImpl.difficulty());
            sink.hexUpper(K_NONCE, blockImpl.nonce().toBytes());
            sink.fieldLongAsString(K_TIMESTAMP, blockImpl.timestamp());
            sink.hexUpper(K_MERKLE_ROOT, blockImpl.merkleRoot().toBytes());
            sink.hexUpper(K_LAST_BLOCK_HASH, blockImpl.lastBlockHash().toBytes());
            // Committed only when set, mirroring the header hash, so a stateless block's
            // JSON (and the hash a peer recomputes from it) is unchanged.
            if (!blockImpl.stateRoot().equals(SHA256Hash.empty())) {
                sink.hexUpper(K_STATE_ROOT, blockImpl.stateRoot().toBytes());
            }
            if (blockImpl.vote() != 0) {
                sink.field(K_VOTE, blockImpl.vote());
            }
            sink.name(K_TRANSACTIONS);
            sink.beginArray();
            for (Transaction transaction : blockImpl.transactions()) {
                transaction.writeJson(sink);
            }
            sink.endArray();
            if (!blockImpl.uncles().isEmpty()) {
                sink.name(K_UNCLES);
                sink.beginArray();
                for (UncleRef uncle : blockImpl.uncles()) {
                    sink.beginObject();
                    sink.hexUpper(K_HASH, uncle.hash().toBytes());
                    sink.field(K_DIFFICULTY, uncle.difficulty());
                    sink.hexUpper(K_MINER, uncle.miner().toBytes());
                    sink.endObject();
                }
                sink.endArray();
            }
        }

        public Block fromJson(JSONObject json) {
            // Canonicality parity with the binary codecs (audit F5, F1): the JSON decode path must
            // reject exactly what BlockDto/HeaderCodec/BlockCodec reject on the wire, so a
            // JSON-sourced block cannot carry fields a binary peer would never have accepted.
            int difficulty = json.getInt(DIFFICULTY);
            if (difficulty < 0 || difficulty > rhizome.core.common.Constants.MAX_DIFFICULTY) {
                throw new IllegalArgumentException("difficulty out of range: " + difficulty);
            }
            // Canonical votes are 0 (abstain) or ±paramId (VoteableParams 1/2) — the same bound as
            // BlockDto/HeaderCodec and the ChainEngine.addBlock consensus gate. Long abs guards
            // Integer.MIN_VALUE.
            int vote = json.optInt(VOTE, 0);
            if (Math.abs((long) vote) > 2) {
                throw new IllegalArgumentException("vote out of range: " + vote);
            }
            JSONArray transactionsJson = json.getJSONArray(TRANSACTIONS);
            if (transactionsJson.length() > rhizome.core.common.Constants.MAX_TRANSACTIONS_PER_BLOCK) {
                throw new IllegalArgumentException(
                    "numTransactions out of range: " + transactionsJson.length());
            }
            java.util.List<UncleRef> uncles = new java.util.ArrayList<>();
            JSONArray unclesJson = json.optJSONArray(UNCLES);
            if (unclesJson != null) {
                if (unclesJson.length() > rhizome.core.common.Constants.MAX_UNCLES_PER_BLOCK) {
                    throw new IllegalArgumentException("numUncles out of range: " + unclesJson.length());
                }
                for (int i = 0; i < unclesJson.length(); i++) {
                    JSONObject u = unclesJson.getJSONObject(i);
                    int uncleDifficulty = u.getInt(DIFFICULTY);
                    // Same bound as the binary codecs: uncle difficulty feeds BigInteger.TWO.pow in
                    // the work sums, so an unbounded JSON int must never reach it.
                    if (uncleDifficulty < 0
                        || uncleDifficulty > rhizome.core.common.Constants.MAX_DIFFICULTY) {
                        throw new IllegalArgumentException(
                            "uncleDifficulty out of range: " + uncleDifficulty);
                    }
                    uncles.add(new UncleRef(SHA256Hash.of(u.getString("hash")), uncleDifficulty,
                        rhizome.core.ledger.PublicAddress.of(u.getString("miner"))));
                }
            }
            return BlockImpl.builder()
                .id(json.getInt(ID))
                .timestamp(json.getLong(TIMESTAMP))
                .difficulty(difficulty)
                .merkleRoot(SHA256Hash.of(json.getString(MERKLE_ROOT)))
                .lastBlockHash(SHA256Hash.of(json.getString(LAST_BLOCK_HASH)))
                .nonce(SHA256Hash.of(json.getString(NONCE)))
                .stateRoot(json.has(STATE_ROOT) ? SHA256Hash.of(json.getString(STATE_ROOT)) : SHA256Hash.empty())
                .vote(vote)
                .transactions(
                    IntStream.range(0, transactionsJson.length())
                        .mapToObj(i -> Transaction.of(transactionsJson.getJSONObject(i)))
                        .collect(Collectors.toList())
                )
                .uncles(uncles)
                .build();
        }
    }
}
