package rhizome.crypto;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * The Ed25519 signature algorithm (RFC 8032), behind {@link SignatureAlgorithm} so the
 * scheme table can name it instead of the consensus path hard-coding a signer.
 *
 * <p>Stateless singleton: the signer is instantiated per call, exactly as the inline code
 * did, so the byte output is unchanged. Both implemented schemes ({@link
 * SignatureScheme#ED25519} and {@link SignatureScheme#ED25519_PQC}, which signs Ed25519
 * with a post-quantum commitment) map to this algorithm.
 */
public final class Ed25519Algorithm implements SignatureAlgorithm {

    public static final Ed25519Algorithm INSTANCE = new Ed25519Algorithm();

    private Ed25519Algorithm() {}

    @Override
    public byte[] sign(byte[] message, byte[] privateKeySeed) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(privateKeySeed, 0));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    @Override
    public boolean verify(byte[] message, byte[] signature, byte[] publicKey) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        signer.update(message, 0, message.length);
        return signer.verifySignature(signature);
    }
}
