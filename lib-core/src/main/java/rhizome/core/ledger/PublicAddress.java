package rhizome.core.ledger;

import static rhizome.crypto.Hex.bytesToHex;
import static rhizome.crypto.Hex.hexStringToByteArray;

import java.util.Arrays;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SignatureScheme;
import rhizome.crypto.SimpleHashType;

/**
 * A 25-byte address: {@code version(1) || body(20) || checksum(4)}.
 *
 * <p>The leading byte is the {@link SignatureScheme} discriminant — it states which signature
 * scheme may spend the address and, for key-derived addresses, how the body was computed. It is
 * covered by the checksum (see {@link #isValidChecksum()}), so a flipped version byte is a
 * detectably invalid address rather than a different-but-plausible one.
 *
 * <p>Contract, box and token addresses are hash-derived instead (first 25 bytes of a digest); they
 * carry no meaningful version byte and no checksum, which is why checksum validation is a
 * capability callers opt into rather than a parse-time rule.
 */
public record PublicAddress(byte[] address) implements SimpleHashType {
    public PublicAddress {
        checkSize(address);
        // Own the array on the way IN, mirroring toBytes()'s defensive copy on the way OUT
        // (audit B-3): a caller retaining the passed reference could otherwise mutate this
        // address, silently corrupting equals/hashCode of every map keyed on it. The clone is
        // redundant where the caller hands over a fresh array (RocksDB JNI key()/value() already
        // allocate per call), so hot iteration loops pay one extra 25-byte copy per entry —
        // accepted: a record cannot distinguish trusted from untrusted arrays, and on those
        // paths (state-root export, balance scans) the copy is dwarfed by the JNI crossing and
        // the per-entry hashing (audit 17th pass, perf note).
        address = address.clone();
    }

    public static PublicAddress empty() {
        return new PublicAddress(SimpleHashType.empty(SIZE));
    }

    public static PublicAddress random() {
        return new PublicAddress(SimpleHashType.random(SIZE));
    }

    /** Classical Ed25519 address: {@code 0x00 || RIPEMD160(SHA256(pubkey)) || checksum}. */
    public static PublicAddress of(PublicKey publicKey){
        return of(publicKey, SignatureScheme.ED25519, null);
    }

    /**
     * Derives the address for {@code publicKey} under {@code scheme}.
     *
     * <p>The body is {@code RIPEMD160(SHA256(publicKey))}, and for a scheme that
     * {@link SignatureScheme#commitsToPostQuantumKey() commits to a post-quantum key} the digest
     * additionally absorbs the 32-byte {@code pqCommitment}. That commitment is what makes a
     * post-quantum migration possible for an address created today: the holder can later reveal a
     * post-quantum public key hashing to it and prove the key was chosen before any quantum
     * adversary existed, without the address paying for a large signature now (WHITEPAPER §5.9).
     *
     * <p>The scheme is folded into the address twice — as the version byte and, through the
     * checksum, into the last four bytes — so an address cannot be reinterpreted under a different
     * scheme. Since the signed preimage commits to the sender's address, that binding is what makes
     * the transaction signature transitively cover the scheme.
     *
     * @param pqCommitment 32-byte digest of the holder's future post-quantum public key; must be
     *                     absent (null/empty) for a scheme that commits to nothing
     */
    public static PublicAddress of(PublicKey publicKey, SignatureScheme scheme, byte[] pqCommitment) {
        if (!publicKey.key().isPresent()) {
            // Fail closed exactly as before: an absent key has no address, and callers compare the
            // result against a transaction's `from` — empty() matches nothing key-derived.
            return PublicAddress.empty();
        }
        int commitmentLength = pqCommitment == null ? 0 : pqCommitment.length;
        if (commitmentLength != scheme.commitmentBytes()) {
            // Reject rather than pad/ignore: a wrong-length commitment would silently derive a
            // different address, and the caller would only find out as a rejected transaction.
            throw new IllegalArgumentException(scheme + " requires a " + scheme.commitmentBytes()
                + "-byte post-quantum commitment, got " + commitmentLength);
        }

        byte[] publicKeyBytes = publicKey.toBytes();

        SHA256Digest sha256 = new SHA256Digest();
        byte[] hash1 = new byte[32];
        sha256.update(publicKeyBytes, 0, publicKeyBytes.length);
        if (commitmentLength > 0) {
            sha256.update(pqCommitment, 0, commitmentLength);
        }
        sha256.doFinal(hash1, 0);

        RIPEMD160Digest ripemd160 = new RIPEMD160Digest();
        byte[] body = new byte[20];
        ripemd160.update(hash1, 0, hash1.length);
        ripemd160.doFinal(body, 0);

        byte[] out = new byte[SIZE];
        out[0] = scheme.code();
        System.arraycopy(body, 0, out, 1, 20);
        System.arraycopy(checksum(out), 0, out, 21, CHECKSUM_SIZE);

        return new PublicAddress(out);
    }

    public static PublicAddress of(byte[] address) {
        return new PublicAddress(address);
    }

    public static PublicAddress of(String hexString) {
        if (hexString.length() != 50) {
            throw new IllegalArgumentException("Invalid wallet address string");
        }
        return PublicAddress.of(hexStringToByteArray(hexString));
    }

    /**
     * Verifies the 4-byte trailing checksum of a key-derived (wallet) address, recomputing
     * {@code SHA256(SHA256(version || body))[0:4]} exactly as {@link #of(PublicKey, SignatureScheme,
     * byte[])} does.
     *
     * <p>The checksum covers the <em>version byte as well as the body</em>. Covering only the body
     * would make two addresses with the same body but different schemes — say the same Ed25519 key
     * read as {@code 0x00} and as {@code 0x01} — both check out, so a flipped or substituted version
     * byte would present as a valid address of another scheme. That is a downgrade primitive the
     * moment the byte means anything, so it is closed here rather than when a post-quantum scheme
     * actually ships.
     *
     * <p>Not enforced on parse: contract, box and token addresses are hash-derived and carry
     * no checksum, so rejecting unchecked addresses in {@code of()} would break them. This is a
     * capability a UI can use to warn on a mistyped <em>wallet</em> recipient (audit M10),
     * where funds sent to a typo'd-but-well-formed address would otherwise be unspendable.
     */
    public boolean isValidChecksum() {
        byte[] a = address;
        if (a.length != SIZE) {
            return false;
        }
        byte[] expected = checksum(a);
        for (int i = 0; i < CHECKSUM_SIZE; i++) {
            if (a[BODY_OFFSET + BODY_SIZE + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The address's leading discriminant byte — a {@link SignatureScheme#code()} for key-derived
     * addresses, and an artefact of the digest for hash-derived (contract/box/token) ones.
     */
    public byte version() {
        return address[0];
    }

    /**
     * Whether this looks like a key-derived wallet address of an implemented scheme: a known version
     * byte and a checksum that validates.
     *
     * <p>A hash-derived contract address can satisfy both by chance (probability ~2^-32 given a
     * scheme-shaped leading byte). That is acceptable because the predicate only gates UI warnings
     * and never authorisation — spending authority comes from the sender-binding check, which
     * recomputes the address from the actual public key.
     */
    public boolean isKeyDerived() {
        return SignatureScheme.isKnown(address[0]) && isValidChecksum();
    }

    /**
     * {@code SHA256(SHA256(version || body))}; the caller takes the leading {@link #CHECKSUM_SIZE}
     * bytes. Reads only the first {@code BODY_OFFSET + BODY_SIZE} bytes of {@code versionAndBody},
     * so it can be handed either a full address or an address under construction.
     */
    private static byte[] checksum(byte[] versionAndBody) {
        SHA256Digest sha = new SHA256Digest();
        byte[] first = new byte[32];
        sha.update(versionAndBody, 0, BODY_OFFSET + BODY_SIZE);
        sha.doFinal(first, 0);
        byte[] second = new byte[32];
        sha.reset();
        sha.update(first, 0, first.length);
        sha.doFinal(second, 0);
        return second;
    }

    public String toHexString() {
        return bytesToHex(address);
    }

    public byte[] toBytes() {
        // Defensive copy: exposing the internal array let any caller mutate it, silently
        // corrupting equals/hashCode of every map keyed on this address (audit B-3).
        return address.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PublicAddress)) {
            return false;
        }
        return Arrays.equals(address, ((PublicAddress) other).address());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(address);
    }

    public static final int SIZE = 25;

    /** Offset of the RIPEMD-160 body: one version byte precedes it. */
    private static final int BODY_OFFSET = 1;
    /** RIPEMD-160 body width. */
    private static final int BODY_SIZE = 20;
    /** Trailing checksum width — {@code SIZE == BODY_OFFSET + BODY_SIZE + CHECKSUM_SIZE}. */
    private static final int CHECKSUM_SIZE = 4;

    @Override
    public int getSize() {
        return SIZE;
    }
}
