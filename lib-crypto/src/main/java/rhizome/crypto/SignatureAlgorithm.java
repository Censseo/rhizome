package rhizome.crypto;

/**
 * The signing primitive behind a {@link SignatureScheme} — the cryptographic agility
 * hinge: when a scheme ships with a different algorithm (e.g. a post-quantum one), its
 * {@code SignatureScheme} constant names that algorithm here, and signing/verification
 * route through the scheme instead of a hard-coded {@code Ed25519Signer} (constat 43b).
 *
 * <p>Byte-based on purpose: the interface never touches BouncyCastle types, so the
 * consensus path depends on nothing but bytes and the scheme. Implementations are
 * stateless singletons registered explicitly in {@code SignatureScheme}'s constructor —
 * no reflection, so the table is native-image friendly.
 */
public interface SignatureAlgorithm {

    /** Signs {@code message} with the {@code privateKeySeed}; returns the signature. */
    byte[] sign(byte[] message, byte[] privateKeySeed);

    /** Verifies {@code signature} over {@code message} under {@code publicKey}. */
    boolean verify(byte[] message, byte[] signature, byte[] publicKey);
}
