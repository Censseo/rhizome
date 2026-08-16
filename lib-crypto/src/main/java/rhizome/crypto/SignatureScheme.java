package rhizome.crypto;

/**
 * The signature scheme authorising a transaction, and the address family it derives.
 *
 * <p>This is the chain's <em>cryptographic agility</em> hinge. Rhizome signs with Ed25519, which
 * Shor's algorithm breaks outright on a cryptographically relevant quantum computer; NIST IR 8547
 * deprecates elliptic-curve signatures after 2030 and disallows them after 2035. The scheme itself
 * is not being replaced today — post-quantum signatures are 10-50x larger and would cut block
 * throughput by the same factor (see WHITEPAPER §5.9) — but the <em>format</em> must be able to
 * name a different scheme before the wire form is frozen by a live chain. Retrofitting a
 * discriminant onto a deployed fixed-width format is a hard fork with no clean migration; adding
 * one now costs a single byte.
 *
 * <p>The code is simultaneously:
 * <ul>
 *   <li>the leading byte of every key-derived {@link rhizome.crypto Rhizome address} (the byte
 *       Bitcoin spends on a network prefix), so an address states which scheme may spend it, and</li>
 *   <li>the first byte of a transaction's wire form, so the decoder knows the signature and public
 *       key widths before it reads them.</li>
 * </ul>
 *
 * <p>Because the address commits to the scheme, and the signed preimage commits to the sender's
 * address, <strong>the signature transitively commits to the scheme</strong>: re-encoding a
 * transaction under a different scheme changes the address the sender binding recomputes, and the
 * transaction is rejected. No separate scheme field in the preimage is needed, and none must be
 * added — see {@code TransactionImpl.hashContents()}.
 *
 * <h2>Field widths are scheme-derived, never wire-supplied</h2>
 * A length prefix for the signature/key would be an attacker-controlled allocation size on the
 * hottest untrusted decode path. Widths are looked up from the scheme code instead, so a hostile
 * transaction can only pick a scheme, never a length.
 *
 * <h2>Reserved codes</h2>
 * Values {@code 0x02..0x0F} are reserved for native post-quantum schemes so a future activation can
 * claim one without colliding with anything already on-chain. The sizes are recorded here as the
 * planning basis; none is implemented, and {@link #fromCode(byte)} rejects them:
 * <pre>
 *   0x02  ML-DSA-44   (FIPS 204)  sig 2420, pk 1312   — primary candidate
 *   0x03  FN-DSA-512  (FIPS 206)  sig  666, pk  897   — 2.3x the throughput of ML-DSA; draft standard
 *   0x04  SLH-DSA-128s(FIPS 205)  sig 7856, pk   32   — hash-only assumptions, conservative fallback
 * </pre>
 */
public enum SignatureScheme {

    /**
     * Ed25519. Address body is {@code RIPEMD160(SHA256(publicKey))} — the classical scheme, and the
     * default for every transaction that does not opt in to anything else.
     */
    ED25519((byte) 0x00, 64, 32, 0, Ed25519Algorithm.INSTANCE),

    /**
     * Ed25519 whose address additionally commits to the hash of a future post-quantum public key:
     * {@code RIPEMD160(SHA256(publicKey || pqCommitment))}, where {@code pqCommitment} is a 32-byte
     * digest of the holder's post-quantum public key.
     *
     * <p>Spending still uses Ed25519, so this costs 32 bytes per transaction and no new cryptography
     * — the commitment is a hash. What it buys is the ability to migrate <em>after</em> the fact:
     * when a post-quantum scheme activates, the holder reveals a public key that hashes to the
     * commitment recorded at address-creation time, and the chain can verify the new key was chosen
     * before any quantum adversary existed. This is the Pay-to-Merkle-Root idea from Bitcoin's
     * BIP-360, reduced to the single spending path Rhizome actually needs.
     *
     * <p>The commitment's contents are deliberately unconstrained by consensus: it is an opaque
     * 32-byte digest. Fixing the post-quantum key format now would defeat the purpose of committing
     * before the format is chosen.
     */
    ED25519_PQC((byte) 0x01, 64, 32, 32, Ed25519Algorithm.INSTANCE);

    /** 32-byte commitment: one SHA-256 digest. Fixed so the wire width is scheme-derived. */
    public static final int COMMITMENT_SIZE = 32;

    private final byte code;
    private final int signatureBytes;
    private final int publicKeyBytes;
    private final int commitmentBytes;
    /** The signing primitive this scheme authorises — named explicitly in the table above. */
    private final SignatureAlgorithm algorithm;

    SignatureScheme(byte code, int signatureBytes, int publicKeyBytes, int commitmentBytes,
                    SignatureAlgorithm algorithm) {
        this.code = code;
        this.signatureBytes = signatureBytes;
        this.publicKeyBytes = publicKeyBytes;
        this.commitmentBytes = commitmentBytes;
        this.algorithm = algorithm;
    }

    /**
     * The algorithm that verifies this scheme's signatures (see {@link SignatureAlgorithm}).
     * Transaction verification dispatches through this entry ({@code TransactionImpl.signatureValid}
     * passes the transaction's scheme to {@code Crypto.checkSignature}), so a scheme shipping a
     * different primitive changes this one row, not the consensus path. Signing and public-key
     * derivation still route through the Ed25519 row explicitly: a {@link PrivateKey} is
     * scheme-less, and threading the scheme through those call sites is part of what adding a
     * non-Ed25519 scheme entails.
     */
    public SignatureAlgorithm algorithm() {
        return algorithm;
    }

    /** Consensus-visible discriminant: address version byte and transaction wire prefix. */
    public byte code() {
        return code;
    }

    public int signatureBytes() {
        return signatureBytes;
    }

    public int publicKeyBytes() {
        return publicKeyBytes;
    }

    /** Bytes of post-quantum commitment carried on the wire; 0 when the scheme commits to nothing. */
    public int commitmentBytes() {
        return commitmentBytes;
    }

    /** Whether an address of this scheme binds a hash of a future post-quantum public key. */
    public boolean commitsToPostQuantumKey() {
        return commitmentBytes > 0;
    }

    /**
     * Total scheme-dependent bytes a transaction spends on authorisation:
     * {@code scheme(1) || signature || publicKey || commitment}.
     */
    public int wireAuthBytes() {
        return 1 + signatureBytes + publicKeyBytes + commitmentBytes;
    }

    /**
     * The widest {@link #wireAuthBytes()} across all implemented schemes — used to size buffers and
     * request-body caps so they stay correct as schemes are added. Computed once, not hard-coded, so
     * adding a scheme cannot leave a stale bound behind.
     */
    public static final int MAX_WIRE_AUTH_BYTES = maxWireAuthBytes();

    private static int maxWireAuthBytes() {
        int max = 0;
        for (SignatureScheme scheme : values()) {
            max = Math.max(max, scheme.wireAuthBytes());
        }
        return max;
    }

    private static final SignatureScheme[] BY_CODE = byCode();

    private static SignatureScheme[] byCode() {
        // Dense lookup over the implemented range; reserved/unknown codes stay null and are rejected
        // by fromCode. Codes are explicit rather than ordinal because the reserved post-quantum block
        // (0x02..0x0F) is deliberately non-contiguous with whatever is implemented next.
        int max = 0;
        for (SignatureScheme scheme : values()) {
            max = Math.max(max, scheme.code & 0xFF);
        }
        SignatureScheme[] table = new SignatureScheme[max + 1];
        for (SignatureScheme scheme : values()) {
            table[scheme.code & 0xFF] = scheme;
        }
        return table;
    }

    /**
     * Decodes a wire/address scheme byte, failing closed on anything not implemented.
     *
     * <p>Unknown codes — including the reserved post-quantum block — must be rejected rather than
     * defaulted to {@link #ED25519}: a node that silently treated an unrecognised scheme as Ed25519
     * would read the wrong field widths and could accept a transaction a scheme-aware node rejects,
     * which is a consensus split. Rejecting is also what makes a future scheme activation a clean
     * coordinated fork rather than a silent divergence.
     */
    public static SignatureScheme fromCode(byte code) {
        int i = code & 0xFF;
        if (i >= BY_CODE.length || BY_CODE[i] == null) {
            throw new IllegalArgumentException("unknown signature scheme: 0x" + Integer.toHexString(i));
        }
        return BY_CODE[i];
    }

    /** Whether {@code code} names an implemented scheme, without throwing. */
    public static boolean isKnown(byte code) {
        int i = code & 0xFF;
        return i < BY_CODE.length && BY_CODE[i] != null;
    }
}
