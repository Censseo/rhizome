package rhizome.core.user;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.json.JSONObject;

import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

import static rhizome.core.common.Helpers.PDN;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

public interface User {

    public static User create() {
        var kp = generateKeyPairTyped();
        return UserImpl.builder()
                .publicKey(kp.publicKey())
                .privateKey(kp.privateKey())
                .build();
    }

    public static User of(JSONObject json){
        return serializer().fromJson(json);
    }

    public PublicKey publicKey();
    public PrivateKey privateKey();

    default PublicAddress getAddress() {
        return PublicAddress.of(publicKey());
    }

    default Transaction mine() {
        return Transaction.of(getAddress(), PDN(50));
    }

    default Transaction send(User receiver, double i) {
        return send(receiver, PDN(i));
    }

    default Transaction send(User to, TransactionAmount amount) {
        return Transaction.of(getAddress(), to.getAddress(), amount, publicKey())
            .sign(privateKey());
    }

    default void signTransaction(Transaction transaction) {
        transaction.sign(privateKey());
    }

    public JSONObject toJson();
    default JSONObject toJson(User transaction) {
        return serializer().toJson(transaction);
    }

    static UserSerializer serializer(){
        return UserSerializer.instance;
    }

    /**
     * Serializes the Transaction
     */
    static class UserSerializer {

        static final String PUBLIC_KEY = "publicKey";
        static final String PRIVATE_KEY = "privateKey";

        static UserSerializer instance = new UserSerializer();

        public User fromJson(JSONObject json) {
            // The private key is REQUIRED here: the default toJson omits it, so accepting the
            // public form would silently build a user whose privateKey() is null and NPE later
            // in signTransaction/equals (audit review). Any JSON meant to be parsed back into a
            // usable User must come from toJsonWithPrivateKey; a missing key now fails loudly
            // with a JSONException instead of a deferred NullPointerException.
            return UserImpl.builder()
                    .publicKey(PublicKey.of(json.getString(PUBLIC_KEY)))
                    .privateKey(PrivateKey.of(json.getString(PRIVATE_KEY)))
                    .build();
        }
        /**
         * Public-key-only form — the safe default for {@link User#toJson()}: a serialized user
         * must never leak its secret into logs/JSON dumps unless the caller explicitly asks for
         * {@link #toJsonWithPrivateKey(User)} (audit: private key serialized in plaintext).
         */
        public JSONObject toJson(User user) {
            var userImpl = (UserImpl) user;
            JSONObject result = new JSONObject();
            result.put(PUBLIC_KEY, userImpl.publicKey().toHexString());
            return result;
        }
        /** Explicit opt-in form that includes the private key (tests, encrypted keystores). */
        public JSONObject toJsonWithPrivateKey(User user) {
            var userImpl = (UserImpl) user;
            JSONObject result = toJson(user);
            result.put(PRIVATE_KEY, userImpl.privateKey().toHexString());
            return result;
        }
    }
}
