package rhizome.core.transaction;

import org.json.JSONObject;

import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
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

    public JSONObject toJson();
    default JSONObject toJson(Transaction transaction) {
        return serializer().toJson(transaction);
    }

    public Transaction sign(PrivateKey privateKey);
    public boolean signatureValid();
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

        static TransactionSerializer instance = new TransactionSerializer();

        @Override
        public TransactionDto serialize(Transaction transaction) {
            var transactionImpl = (TransactionImpl) transaction;
            return new TransactionDto(
                transactionImpl.signature(),
                transactionImpl.signingKey(),
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
                .from(transactionDto.isTransactionFee ? PublicAddress.empty() : PublicAddress.of(transactionDto.signingKey))
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
            } else {
                result.put(TXID, transactionImpl.hashContents().toHexString());
                result.put(FROM, "");
            }
            
            return result;
        }
    
        public Transaction fromJson(JSONObject json) {
            var builder = TransactionImpl.builder()
                .timestamp(json.getLong(TIMESTAMP))
                .fee(new TransactionAmount(json.getLong(FEE)))
                .chainId(json.optInt(CHAIN_ID, 0))
                .nonce(json.optLong(NONCE, 0))
                .to(PublicAddress.of(json.getString(TO)));

            if (json.has(KIND)) {
                builder.kind(rhizome.core.transaction.TransactionKind.valueOf(json.getString(KIND)))
                    .gasLimit(json.optLong(GAS_LIMIT, 0))
                    .gasPrice(json.optLong(GAS_PRICE, 0))
                    .data(rhizome.core.common.Utils.hexStringToByteArray(json.optString(DATA, "")));
            }

        
            if (json.getString("from").isEmpty()) {
                builder.amount(new TransactionAmount(json.getLong(AMOUNT)))
                    .isTransactionFee(true);
            } else {
                builder.from(PublicAddress.of(json.getString(FROM)))
                    .signature(TransactionSignature.of(json.getString(SIGNATURE)))
                    .amount(new TransactionAmount(json.getLong(AMOUNT)))
                    .isTransactionFee(false)
                    .signingKey(PublicKey.of(json.getString(SIGNING_KEY)));
            }   
            
            return builder.build();
        }
    }
}
