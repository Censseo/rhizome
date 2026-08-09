package rhizome.core.transaction;

import org.json.JSONObject;

import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;
import rhizome.core.serialization.Serializable;
import rhizome.core.transaction.dto.TransactionDto;

public sealed interface Transaction permits TransactionImpl {

    public static Transaction empty() {
        return TransactionImpl.builder().build();
    }

    public static Transaction of(JSONObject json){
        return serializer().fromJson(json);
    }

    public static Transaction of(Transaction transaction) {
        var transactionImpl = (TransactionImpl) transaction;
        return TransactionImpl.builder()
                .from(transactionImpl.from())
                .to(transactionImpl.to())
                .amount(transactionImpl.amount())
                .isTransactionFee(transactionImpl.isTransactionFee())
                .timestamp(transactionImpl.timestamp())
                .fee(transactionImpl.fee())
                .chainId(transactionImpl.chainId())
                .nonce(transactionImpl.nonce())
                .signingKey(transactionImpl.signingKey())
                .signature(transactionImpl.signature())
                .kind(transactionImpl.kind())
                .data(transactionImpl.data())
                .gasLimit(transactionImpl.gasLimit())
                .gasPrice(transactionImpl.gasPrice())
                .build();
    }

    public static Transaction of(TransactionDto transactionDto) {
        return serializer().deserialize(transactionDto);
    }

    public static Transaction of(PublicAddress from, PublicAddress to, TransactionAmount amount, PublicKey signingKey, TransactionAmount fee) {
        return TransactionImpl.builder()
                .from(from)
                .to(to)
                .amount(amount)
                .isTransactionFee(false)
                .timestamp(System.currentTimeMillis())
                .fee(fee)
                .signingKey(signingKey)
                .build();
    }

    public static Transaction of(PublicAddress from, PublicAddress to, TransactionAmount amount, PublicKey signingKey, TransactionAmount fee, long timestamp) {
        return TransactionImpl.builder()
                .from(from)
                .to(to)
                .amount(amount)
                .isTransactionFee(false)
                .timestamp(timestamp)
                .fee(fee)
                .signingKey(signingKey)
                .build();
    }

    public static Transaction of(PublicAddress from, PublicAddress to, TransactionAmount amount, PublicKey signingKey) {
        return TransactionImpl.builder()
                .from(from)
                .to(to)
                .amount(amount)
                .isTransactionFee(false)
                .timestamp(System.currentTimeMillis())
                .signingKey(signingKey)
                .build();
    }

    public static Transaction of(PublicAddress to, TransactionAmount amount) {
        return TransactionImpl.builder()
                .to(to)
                .amount(amount)
                .isTransactionFee(true)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /** Full clean-chain factory: chain-id and account nonce are part of the signed preimage. */
    public static Transaction of(PublicAddress from, PublicAddress to, TransactionAmount amount, PublicKey signingKey,
            TransactionAmount fee, long timestamp, int chainId, long nonce) {
        return TransactionImpl.builder()
                .from(from)
                .to(to)
                .amount(amount)
                .isTransactionFee(false)
                .timestamp(timestamp)
                .fee(fee)
                .chainId(chainId)
                .nonce(nonce)
                .signingKey(signingKey)
                .build();
    }

    public TransactionDto serialize();
    default TransactionDto serialize(Transaction transaction) {
        return serializer().serialize(transaction);
    }

    /** Exact serialized byte length without building the wire form (see TransactionImpl#sizeBytes). */
    public int sizeBytes();

    public JSONObject toJson();
    default JSONObject toJson(Transaction transaction) {
        return serializer().toJson(transaction);
    }

    /** Key/value pairs only — no surrounding braces. See {@link #writeJson(JsonSink)}. */
    public void writeJsonBody(JsonSink sink);
    default void writeJsonBody(JsonSink sink, Transaction transaction) {
        serializer().writeJsonBody(sink, transaction);
    }

    /**
     * Same field set, types and conditions as {@link #toJson()}, written directly into
     * {@code sink} instead of building an {@code org.json} tree first — see {@code JsonSink}'s
     * class Javadoc for why. {@code toJson()} stays: {@link #of(JSONObject)} still parses the
     * tree form, and {@code JsonWriterEquivalenceTest} uses it as the equivalence-test oracle
     * this writer is checked against.
     */
    default void writeJson(JsonSink sink) {
        sink.beginObject();
        writeJsonBody(sink);
        sink.endObject();
    }

    public Transaction sign(PrivateKey privateKey);
    public boolean signatureValid();

    /**
     * Whether {@code from} really is the address of {@code signingKey} under the declared signature
     * scheme — see {@link TransactionImpl#senderBindingValid()}. Enforced identically by mempool
     * admission and by consensus validation; the two must never diverge, which is why the derivation
     * lives in one place rather than being open-coded at each call site.
     */
    public boolean senderBindingValid();
    public SHA256Hash hashContents();
    public SHA256Hash hash();
    public PublicAddress from();
    public PublicAddress to();

    /**
     * Get instance of the serializer
     * @return
     */
    static TransactionSerializer serializer(){
        return TransactionSerializer.instance;
    }

    /**
     * Serializes the Transaction
     */
    static class TransactionSerializer implements Serializable<TransactionDto, Transaction> {

        static final String TO = "to";
        static final String AMOUNT = "amount";
        static final String TIMESTAMP = "timestamp";
        static final String FEE = "fee";
        static final String TXID = "txid";
        static final String FROM = "from";
        static final String SIGNING_KEY = "signingKey";
        static final String SIGNATURE = "signature";
        static final String CHAIN_ID = "chainId";
        static final String NONCE = "accountNonce";
        static final String KIND = "kind";
        static final String DATA = "data";
        static final String GAS_LIMIT = "gasLimit";
        static final String GAS_PRICE = "gasPrice";
        static final String SIG_SCHEME = "sigScheme";
        static final String PQ_COMMITMENT = "pqCommitment";

        // Pre-encoded "name": keys for the JsonSink writer (see JsonSink's class Javadoc for why
        // these are built once per call site rather than encoded per response). Names mirror the
        // String constants above field-for-field, so NONCE's key stays "accountNonce".
        static final Key K_TO = Key.of(TO);
        static final Key K_AMOUNT = Key.of(AMOUNT);
        static final Key K_TIMESTAMP = Key.of(TIMESTAMP);
        static final Key K_FEE = Key.of(FEE);
        static final Key K_TXID = Key.of(TXID);
        static final Key K_FROM = Key.of(FROM);
        static final Key K_SIGNING_KEY = Key.of(SIGNING_KEY);
        static final Key K_SIGNATURE = Key.of(SIGNATURE);
        static final Key K_CHAIN_ID = Key.of(CHAIN_ID);
        static final Key K_NONCE = Key.of(NONCE);
        static final Key K_KIND = Key.of(KIND);
        static final Key K_DATA = Key.of(DATA);
        static final Key K_GAS_LIMIT = Key.of(GAS_LIMIT);
        static final Key K_GAS_PRICE = Key.of(GAS_PRICE);
        static final Key K_SIG_SCHEME = Key.of(SIG_SCHEME);
        static final Key K_PQ_COMMITMENT = Key.of(PQ_COMMITMENT);

        static TransactionSerializer instance = new TransactionSerializer();

        @Override
        public TransactionDto serialize(Transaction transaction) {
            var transactionImpl = (TransactionImpl) transaction;
            return new TransactionDto(
                transactionImpl.scheme(),
                transactionImpl.signature(),
                transactionImpl.signingKey(),
                transactionImpl.pqCommitment(),
                transactionImpl.timestamp(),
                transactionImpl.to(),
                transactionImpl.amount().amount(),
                transactionImpl.fee().amount(),
                transactionImpl.isTransactionFee(),
                transactionImpl.chainId(),
                transactionImpl.nonce(),
                transactionImpl.kind().code(),
                transactionImpl.gasLimit(),
                transactionImpl.gasPrice(),
                transactionImpl.data()
            );
        }

        @Override
        public Transaction deserialize(TransactionDto transactionDto) {
            return TransactionImpl.builder()
                // `from` is derived, never read off the wire: under scheme agility it is the only
                // field that binds the public key, the scheme and the post-quantum commitment
                // together, so deriving it here makes a binary-decoded transaction consistent by
                // construction (the JSON path reads `from` and is checked by senderBindingValid).
                .from(transactionDto.isTransactionFee ? PublicAddress.empty()
                    : PublicAddress.of(transactionDto.signingKey, transactionDto.scheme, transactionDto.pqCommitment))
                .scheme(transactionDto.scheme)
                .pqCommitment(transactionDto.pqCommitment)
                .to(transactionDto.to)
                .amount(new TransactionAmount(transactionDto.amount))
                .isTransactionFee(transactionDto.isTransactionFee)
                .timestamp(transactionDto.timestamp)
                .fee(new TransactionAmount(transactionDto.fee))
                .chainId(transactionDto.chainId)
                .nonce(transactionDto.nonce)
                .signingKey(transactionDto.signingKey)
                .signature(transactionDto.signature)
                .kind(rhizome.core.transaction.TransactionKind.fromCode(transactionDto.kind))
                .gasLimit(transactionDto.gasLimit)
                .gasPrice(transactionDto.gasPrice)
                .data(transactionDto.data)
                .build();
        }
    
        @Override
        public JSONObject toJson(Transaction transaction) {
            var transactionImpl = (TransactionImpl) transaction;    
            JSONObject result = new JSONObject();
            result.put(TO, transactionImpl.to().toHexString());
            result.put(AMOUNT, transactionImpl.amount().amount());
            result.put(TIMESTAMP, Long.toString(transactionImpl.timestamp()));
            result.put(FEE, transactionImpl.fee().amount());
            result.put(CHAIN_ID, transactionImpl.chainId());
            result.put(NONCE, transactionImpl.nonce());

            if (transactionImpl.kind().hasPayload()) {
                result.put(KIND, transactionImpl.kind().name());
                result.put(GAS_LIMIT, transactionImpl.gasLimit());
                result.put(GAS_PRICE, transactionImpl.gasPrice());
                result.put(DATA, rhizome.core.common.Utils.bytesToHex(transactionImpl.data()));
            }

            if (!transactionImpl.isTransactionFee()) {
                result.put(TXID, transactionImpl.hashContents().toHexString());
                result.put(FROM, transactionImpl.from().toHexString());
                result.put(SIGNING_KEY, transactionImpl.signingKey().toHexString());
                result.put(SIGNATURE, transactionImpl.signature().toHexString());
                // Emitted only when non-default, so the JSON of an ordinary Ed25519 transaction is
                // byte-for-byte what it was before scheme agility existed and no API consumer
                // (dashboard, explorer, wallet CLI) has to learn a new field to keep working.
                if (transactionImpl.scheme() != rhizome.crypto.SignatureScheme.ED25519) {
                    result.put(SIG_SCHEME, transactionImpl.scheme().name());
                    result.put(PQ_COMMITMENT,
                        rhizome.core.common.Utils.bytesToHex(transactionImpl.pqCommitment()));
                }
            } else {
                result.put(TXID, transactionImpl.hashContents().toHexString());
                result.put(FROM, "");
            }

            return result;
        }

        /**
         * Same field set, types and conditions as {@link #toJson(Transaction)} above, written
         * directly into {@code sink} instead of building an {@code org.json} tree — see
         * {@code JsonWriterEquivalenceTest} for the byte-level equivalence proof.
         */
        public void writeJsonBody(JsonSink sink, Transaction transaction) {
            var transactionImpl = (TransactionImpl) transaction;
            sink.hexUpper(K_TO, transactionImpl.to().toBytes());
            sink.field(K_AMOUNT, transactionImpl.amount().amount());
            sink.fieldLongAsString(K_TIMESTAMP, transactionImpl.timestamp());
            sink.field(K_FEE, transactionImpl.fee().amount());
            sink.field(K_CHAIN_ID, transactionImpl.chainId());
            sink.field(K_NONCE, transactionImpl.nonce());

            if (transactionImpl.kind().hasPayload()) {
                sink.field(K_KIND, transactionImpl.kind().name());
                sink.field(K_GAS_LIMIT, transactionImpl.gasLimit());
                sink.field(K_GAS_PRICE, transactionImpl.gasPrice());
                sink.hexUpper(K_DATA, transactionImpl.data());
            }

            if (!transactionImpl.isTransactionFee()) {
                sink.hexUpper(K_TXID, transactionImpl.hashContents().toBytes());
                sink.hexUpper(K_FROM, transactionImpl.from().toBytes());
                // PublicKey.toHexString() returns "" when no key is present (the unsigned
                // BOX_COLLECT minted by BlockAssembler) rather than hex of the zero-filled
                // encoding toBytes() falls back to — the two disagree in that one case, so
                // toBytes() cannot be fed to hexUpper() unconditionally here.
                if (transactionImpl.signingKey().key().isPresent()) {
                    sink.hexUpper(K_SIGNING_KEY, transactionImpl.signingKey().toBytes());
                } else {
                    sink.field(K_SIGNING_KEY, "");
                }
                sink.hexUpper(K_SIGNATURE, transactionImpl.signature().toBytes());
                // Emitted only when non-default, so the JSON of an ordinary Ed25519 transaction is
                // byte-for-byte what it was before scheme agility existed and no API consumer
                // (dashboard, explorer, wallet CLI) has to learn a new field to keep working.
                if (transactionImpl.scheme() != rhizome.crypto.SignatureScheme.ED25519) {
                    sink.field(K_SIG_SCHEME, transactionImpl.scheme().name());
                    sink.hexUpper(K_PQ_COMMITMENT, transactionImpl.pqCommitment());
                }
            } else {
                sink.hexUpper(K_TXID, transactionImpl.hashContents().toBytes());
                sink.field(K_FROM, "");
            }
        }

        public Transaction fromJson(JSONObject json) {
            var builder = TransactionImpl.builder()
                .timestamp(json.getLong(TIMESTAMP))
                .fee(new TransactionAmount(json.getLong(FEE)))
                .chainId(json.optInt(CHAIN_ID, 0))
                .nonce(json.optLong(NONCE, 0))
                .to(PublicAddress.of(json.getString(TO)));

            if (json.has(KIND)) {
                byte[] data = rhizome.core.common.Utils.hexStringToByteArray(json.optString(DATA, ""));
                // Canonicality parity with TransactionDto.readFrom (audit F5): the payload cap the
                // binary codec enforces on the wire must hold on the JSON path too, so a JSON-sourced
                // transaction cannot smuggle a payload a binary peer would have rejected.
                if (data.length > TransactionDto.MAX_DATA) {
                    throw new IllegalArgumentException("contract data length out of range: " + data.length);
                }
                builder.kind(rhizome.core.transaction.TransactionKind.valueOf(json.getString(KIND)))
                    .gasLimit(json.optLong(GAS_LIMIT, 0))
                    .gasPrice(json.optLong(GAS_PRICE, 0))
                    .data(data);
            }

        
            if (json.getString("from").isEmpty()) {
                builder.amount(new TransactionAmount(json.getLong(AMOUNT)))
                    .isTransactionFee(true);
            } else {
                // Scheme agility on the JSON path, with the same canonicality parity the payload cap
                // gets (audit F5): an absent field means Ed25519, an unknown scheme name is rejected
                // rather than defaulted, and the commitment width must match the scheme — otherwise a
                // JSON-sourced transaction could name a shape a binary peer would have refused.
                rhizome.crypto.SignatureScheme scheme = json.has(SIG_SCHEME)
                    ? parseScheme(json.getString(SIG_SCHEME))
                    : rhizome.crypto.SignatureScheme.ED25519;
                byte[] commitment = rhizome.core.common.Utils.hexStringToByteArray(
                    json.optString(PQ_COMMITMENT, ""));
                if (commitment.length != scheme.commitmentBytes()) {
                    throw new IllegalArgumentException(scheme + " requires a "
                        + scheme.commitmentBytes() + "-byte post-quantum commitment, got " + commitment.length);
                }
                builder.from(PublicAddress.of(json.getString(FROM)))
                    .signature(TransactionSignature.of(json.getString(SIGNATURE)))
                    .amount(new TransactionAmount(json.getLong(AMOUNT)))
                    .isTransactionFee(false)
                    .scheme(scheme)
                    .pqCommitment(commitment)
                    .signingKey(PublicKey.of(json.getString(SIGNING_KEY)));
            }
            
            return builder.build();
        }

        /**
         * Resolves a scheme name from JSON, failing closed on anything unrecognised.
         * {@code valueOf} would throw {@link IllegalArgumentException} anyway; this wraps it so the
         * message names the offending value the way the binary decoder's does.
         */
        private static rhizome.crypto.SignatureScheme parseScheme(String name) {
            try {
                return rhizome.crypto.SignatureScheme.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown signature scheme: " + name, e);
            }
        }
    }
}
