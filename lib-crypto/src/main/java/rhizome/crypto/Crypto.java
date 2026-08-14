package rhizome.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.math.BigInteger;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;


public class Crypto {

    private Crypto() {}

    /** One shared, thread-safe CSPRNG for key generation; avoids a reseed on every {@code new SecureRandom()}. */
    private static final SecureRandom KEYGEN_RNG = new SecureRandom();

    /** {@code size} zero bytes — the canonical "empty" value for a fixed-width hash/address/key. */
    public static byte[] emptyBytes(int size) {
        return new byte[size];
    }

    /** {@code size} random bytes, off the shared CSPRNG above (not a fresh {@code SecureRandom} per call). */
    public static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        KEYGEN_RNG.nextBytes(bytes);
        return bytes;
    }

    /**
     * Signs a raw digest. RESERVED for transaction hashes (consensus): it applies no domain
     * separation, so it must never be exposed as a general signing oracle over attacker-chosen
     * messages — a signature over a chosen message could be replayed as a transaction signature
     * (audit F9). Use {@link #signMessage} for anything that is not a transaction hash.
     */
    public static byte[] signWithPrivateKey(String content, PrivateKey privateKey) {
        // Pin UTF-8: the platform default charset varies by host, so the same string could sign
        // different bytes on different machines (audit F7).
        return signWithPrivateKey(content.getBytes(StandardCharsets.UTF_8), privateKey);
    }

    /**
     * Signs a raw digest. RESERVED for transaction hashes (consensus); see the note on
     * {@link #signWithPrivateKey(String, PrivateKey)}. Use {@link #signMessage} for general
     * message signing. Signed through the scheme's algorithm table, not a hard-coded signer
     * (constat 43b): the scheme names which primitive produces its signatures.
     */
    public static byte[] signWithPrivateKey(byte[] message, PrivateKey privateKey) {
        try {
            return SignatureScheme.ED25519.algorithm().sign(message, privateKey.toBytes());
        } catch (Exception e) {
            // Never return an empty/garbage signature on failure: that used to let a
            // signing fault surface only much later as a rejected transaction, masking
            // key-corruption bugs and risking broadcast of an unsigned tx (audit M11).
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    public static boolean checkSignature(String content, byte[] signature, PublicKey publicKey) {
        // Pin UTF-8 exactly like signWithPrivateKey(String, ...): with the platform default
        // charset a non-ASCII message would verify only on hosts whose charset matches the
        // signer's (audit F7).
        return checkSignature(content.getBytes(StandardCharsets.UTF_8), signature, publicKey);
    }
    
    public static boolean checkSignature(byte[] bytes, byte[] signature, PublicKey publicKey) {
        // An absent/empty public key (e.g. an all-zero signing key decoded to PublicKey.empty())
        // has no Ed25519 parameters: signer.init(false, null) would NPE inside BouncyCastle. A
        // missing key is definitionally an invalid signature, so return false rather than throw —
        // the security primitive must fail closed on attacker-controlled input (audit M4).
        if (publicKey == null || publicKey.isEmpty() || signature == null) {
            return false;
        }
        // Verified through the scheme's algorithm table, not a hard-coded signer: the scheme
        // names which primitive verifies its signatures (constat 43b; SignatureAlgorithm).
        return SignatureScheme.ED25519.algorithm().verify(bytes, signature, publicKey.toBytes());
    }

    public static AsymmetricCipherKeyPair generateKeyPair() {
        Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
        keyGen.init(new Ed25519KeyGenerationParameters(KEYGEN_RNG));
        return keyGen.generateKeyPair();
    }

    /**
     * A typed key pair: the private and public keys as Rhizome's own types, so callers never
     * cast the raw BouncyCastle {@code AsymmetricCipherKeyPair} halves (the L28 key factory).
     */
    public record KeyPair(PrivateKey privateKey, PublicKey publicKey) {}

    /** Generates an Ed25519 key pair in the typed form callers actually use. */
    public static KeyPair generateKeyPairTyped() {
        AsymmetricCipherKeyPair pair = generateKeyPair();
        return new KeyPair(PrivateKey.of(pair.getPrivate()),
            PublicKey.of(pair.getPublic()));
    }

    /**
     * Domain separator for general-purpose message signing. Transaction signatures deliberately
     * stay raw (consensus), so off-chain message signing frames the message with this prefix to
     * keep the two domains apart: a message signature can never be replayed as a transaction
     * signature, and vice versa (audit F9).
     */
    private static final byte[] MESSAGE_DOMAIN = "RHIZOME_MSG\u0000".getBytes(StandardCharsets.UTF_8);

    /** Signs {@code "RHIZOME_MSG\x00" ‖ message}; verify with {@link #verifyMessage}. */
    public static byte[] signMessage(PrivateKey privateKey, byte[] message) {
        return signWithPrivateKey(domainFramed(message), privateKey);
    }

    /** Verifies a signature produced by {@link #signMessage}. */
    public static boolean verifyMessage(PublicKey publicKey, byte[] message, byte[] signature) {
        return checkSignature(domainFramed(message), signature, publicKey);
    }

    private static byte[] domainFramed(byte[] message) {
        byte[] framed = new byte[MESSAGE_DOMAIN.length + message.length];
        System.arraycopy(MESSAGE_DOMAIN, 0, framed, 0, MESSAGE_DOMAIN.length);
        System.arraycopy(message, 0, framed, MESSAGE_DOMAIN.length, message.length);
        return framed;
    }

    /**
     * Bounded cache of Pufferfish2 results. The cache key wraps the raw 64-byte preimage
     * (target ‖ nonce), both halves of which are fully attacker-controlled on every PoW
     * verification path (addBlock, registerOrphan, header sync, fork-choice branch validation all
     * pass useCache=true). An unbounded map therefore grew
     * one permanent entry per distinct block/header the node ever verified — a free remote
     * memory-exhaustion vector (feed /submit a stream of blocks with fresh nonces). Cap it like
     * OrphanPool/MemPool so it can never itself be a growth vector; the miner (useCache=false)
     * never populates it, so caching only ever helped repeat verification of the same input.
     */
    private static final int PUFFERFISH_CACHE_MAX = 4096;

    /**
     * Cache key: the input hash AND the cost parameters. Two cost pairs over the same
     * preimage give different outputs, and both can be live simultaneously around a
     * scheduled PoW-cost upgrade, so keying on the input alone would serve the wrong
     * costs' result across the boundary.
     */
    private record PufferfishCacheKey(SHA256Hash inputHash, PowCosts costs) {}

    // ConcurrentHashMap rather than a synchronized LinkedHashMap for two reasons (audit):
    //  - computeIfAbsent is atomic PER KEY, so N racing verifiers of the same block hash it
    //    once, while misses on DIFFERENT inputs still hash in parallel. A synchronized-map
    //    computeIfAbsent would hold the global map lock through the whole memory-hard compute,
    //    serialising every verifier (and every cache hit) behind a single miss.
    //  - the size stays bounded by the approximate eviction below: CHM exposes no access order,
    //    so victims are iteration-order arbitrary instead of true-LRU. The DoS bound is the
    //    security property; eviction precision only affects the hit rate.
    private static final ConcurrentHashMap<PufferfishCacheKey, SHA256Hash> pufferfishCache =
        new ConcurrentHashMap<>();

    /** Pufferfish2 PoW hash under the genesis costs ({@link PowCosts#DEFAULT}). */
    public static SHA256Hash PUFFERFISH(byte[] input, boolean useCache) {
        return PUFFERFISH(input, useCache, PowCosts.DEFAULT);
    }

    /** Pufferfish2 PoW hash under the given consensus cost parameters. */
    public static SHA256Hash PUFFERFISH(byte[] input, boolean useCache, PowCosts costs) {
        if (!useCache) {
            // Miner path: never touches the cache — do not even allocate the key.
            return SHA256(PufferfishAlgorithm.compute(input, costs));
        }
        PufferfishCacheKey key = new PufferfishCacheKey(SHA256Hash.of(input), costs);
        SHA256Hash result = pufferfishCache.computeIfAbsent(
            key, k -> SHA256(PufferfishAlgorithm.compute(input, costs)));
        evictPufferfishCacheIfOversized();
        return result;
    }

    private static void evictPufferfishCacheIfOversized() {
        int excess = pufferfishCache.size() - PUFFERFISH_CACHE_MAX;
        if (excess <= 0) {
            return;
        }
        // Approximate eviction (see the field comment). Racing evictors are benign: the
        // iterator is weakly consistent and remove() on an already-removed key is a no-op, so
        // the map can only dip slightly below the cap, never above it for long.
        var it = pufferfishCache.keySet().iterator();
        while (excess > 0 && it.hasNext()) {
            it.next();
            it.remove();
            excess--;
        }
    }

    public static SHA256Hash SHA256(byte[] hash) {
        return SHA256(hash, false, false);
    }

    /**
     * Per-thread SHA-256 digest: {@code MessageDigest.getInstance} walks the provider list and
     * allocates on every call, which dominated the plain-SHA256 hot paths (merkle leaves/nodes,
     * tx ids) on blocks with thousands of hashes (audit perf). MessageDigest is not thread-safe,
     * hence one instance per thread; {@code digest(byte[])} resets it implicitly.
     */
    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Unable to find SHA-256 algorithm", e);
        }
    });

    public static SHA256Hash SHA256(byte[] data, boolean usePufferFish, boolean useCache) {
        if (usePufferFish) {
            return PUFFERFISH(data, useCache);
        }

        // Standard SHA-256 Hashing
        return SHA256Hash.of(SHA256_DIGEST.get().digest(data));
    }

    public static boolean verifyHash(SHA256Hash target, SHA256Hash nonce, int challengeSize, boolean usePufferFish, boolean useCache) {
        return verifyHash(target, nonce, challengeSize, usePufferFish, useCache, PowCosts.DEFAULT);
    }

    /** Verifies the PoW over {@code (target ‖ nonce)} under the given cost parameters. */
    public static boolean verifyHash(SHA256Hash target, SHA256Hash nonce, int challengeSize, boolean usePufferFish, boolean useCache, PowCosts costs) {
        SHA256Hash fullHash = concatHashes(target, nonce, usePufferFish, useCache, costs);
        return checkLeadingZeroBits(fullHash, challengeSize);
    }

    public static SHA256Hash concatHashes(SHA256Hash a, SHA256Hash b, boolean usePufferFish, boolean useCache) {
        return concatHashes(a, b, usePufferFish, useCache, PowCosts.DEFAULT);
    }

    public static SHA256Hash concatHashes(SHA256Hash a, SHA256Hash b, boolean usePufferFish, boolean useCache, PowCosts costs) {
        // Proof-of-work hash over (target ‖ nonce). The consensus-critical part is that
        // usePufferFish is HONORED: on a PUFFERFISH2 network the header must be verified
        // (and mined) with the memory-hard Pufferfish2 function, not plain SHA-256 — that
        // is the whole ASIC-resistance property. See PowAlgorithm / NetworkParameters.
        byte[] data = new byte[64];
        // raw(): both halves are copied into `data` on the next two lines and never retained —
        // this runs twice per PoW verification (audit: hot-path defensive copies).
        System.arraycopy(a.raw(), 0, data, 0, 32);
        System.arraycopy(b.raw(), 0, data, 32, 32);
        return usePufferFish ? PUFFERFISH(data, useCache, costs) : SHA256(data);
    }

    public static boolean checkLeadingZeroBits(SHA256Hash hash, int challengeSize) {
        // A non-positive challenge means "no work required" — every hash would pass. That is
        // never a legitimate PoW target (minDifficulty is always >= 1), and accepting it let a
        // zero-difficulty block satisfy verifyNonce with no work at all. Refuse it outright so
        // no reward or chain weight can ever be minted without matching work.
        if (challengeSize <= 0) {
            return false;
        }
        // The hash is 32 bytes (256 bits); a challenge past that can never be satisfied and,
        // read literally, indexes past the array. Adversarial blocks can carry any difficulty
        // int, and registerOrphan/uncleEligible call verifyNonce before bounding the high side,
        // so fail closed instead of throwing an AIOOBE the caller must catch (audit).
        if (challengeSize > 256) {
            return false;
        }
        byte[] a = hash.raw(); // read-only scan within this call (hot PoW path — see raw())
        int bytes = challengeSize / 8;
        for (int i = 0; i < bytes; i++) {
            if (a[i] != 0) return false;
        }
        int remainingBits = challengeSize % 8;
        if (remainingBits > 0) {
            // Create a bitmask to check only the required remaining bits
            int bitmask = (1 << remainingBits) - 1;
            return (a[bytes] & (bitmask << (8 - remainingBits))) == 0;
        } else {
            return true;
        }
    }

    public static BigInteger addWork(BigInteger work, int exponent) {
        // BigInteger.pow would throw a bare ArithmeticException("Negative exponent"); fail fast
        // with a proper argument error instead — a negative chain-work delta is always an
        // upstream decoding bug and must surface as such (audit).
        if (exponent < 0) {
            throw new IllegalArgumentException("exponent must be non-negative: " + exponent);
        }
        BigInteger base = BigInteger.valueOf(2);
        return work.add(base.pow(exponent));
    }

}
