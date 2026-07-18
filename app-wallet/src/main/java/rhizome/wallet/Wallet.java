package rhizome.wallet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.json.JSONObject;

import rhizome.core.common.Crypto;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * A key pair and the operations to spend from it: create/load/save and build a
 * signed transaction. The public key and address are derived from the private
 * key, so a key file need only hold the secret.
 */
public final class Wallet {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final PublicAddress address;

    private Wallet(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.address = PublicAddress.of(publicKey);
    }

    public static Wallet create() {
        var pair = Crypto.generateKeyPair();
        return fromPrivate(new PrivateKey(
            (org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters) pair.getPrivate()));
    }

    private static Wallet fromPrivate(PrivateKey privateKey) {
        Ed25519PublicKeyParameters pub = privateKey.key().generatePublicKey();
        return new Wallet(privateKey, PublicKey.of(pub));
    }

    public PublicAddress address() {
        return address;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    public JSONObject toJson() {
        return new JSONObject()
            .put("privateKey", privateKey.toHexString())
            .put("publicKey", publicKey.toHexString())
            .put("address", address.toHexString());
    }

    public void save(Path keyFile) throws IOException {
        Files.writeString(keyFile, toJson().toString(2), StandardCharsets.UTF_8);
    }

    public static Wallet load(Path keyFile) throws IOException {
        JSONObject json = new JSONObject(Files.readString(keyFile, StandardCharsets.UTF_8));
        return fromPrivate(PrivateKey.of(json.getString("privateKey")));
    }

    /**
     * Builds and signs a transfer. The account nonce and chain-id come from the
     * network (queried from a node); the signature covers both.
     */
    public Transaction signedSend(PublicAddress to, TransactionAmount amount, TransactionAmount fee,
                                  int chainId, long nonce, long timestamp) {
        Transaction t = Transaction.of(address, to, amount, publicKey, fee, timestamp, chainId, nonce);
        return t.sign(privateKey);
    }
}
