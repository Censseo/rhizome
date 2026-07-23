package rhizome.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigInteger;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.Signer;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;


public class Crypto {

    private Crypto() {}

    /** One shared, thread-safe CSPRNG for key generation; avoids a reseed on every {@code new SecureRandom()}. */
    private static final SecureRandom KEYGEN_RNG = new SecureRandom();

    public static byte[] signWithPrivateKey(String content, PrivateKey privateKey) {
        return signWithPrivateKey(content.getBytes(), privateKey);
    }

    public static byte[] signWithPrivateKey(byte[] message, PrivateKey privateKey) {
        try {
            Signer signer = new Ed25519Signer();
            signer.init(true, privateKey.key());
            signer.update(message, 0, message.length);
            return signer.generateSignature();
        } catch (Exception e) {
            // Never return an empty/garbage signature on failure: that used to let a
            // signing fault surface only much later as a rejected transaction, masking
            // key-corruption bugs and risking broadcast of an unsigned tx (audit M11).
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    public static boolean checkSignature(String content, byte[] signature, PublicKey publicKey) {
        return checkSignature(content.getBytes(), signature, publicKey);
    }
    
    public static boolean checkSignature(byte[] bytes, byte[] signature, PublicKey publicKey) {
        // An absent/empty public key (e.g. an all-zero signing key decoded to PublicKey.empty())
        // has no Ed25519 parameters: signer.init(false, null) would NPE inside BouncyCastle. A
        // missing key is definitionally an invalid signature, so return false rather than throw —
        // the security primitive must fail closed on attacker-controlled input (audit M4).
        if (publicKey == null || publicKey.get() == null || signature == null) {
            return false;
        }
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(false, publicKey.get());
        signer.update(bytes, 0, bytes.length);
        return signer.verifySignature(signature);
    }

    public static AsymmetricCipherKeyPair generateKeyPair() {
        Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
        keyGen.init(new Ed25519KeyGenerationParameters(KEYGEN_RNG));
        return keyGen.generateKeyPair();
    }

    /**
     * Bounded LRU of Pufferfish2 results. The cache key is SHA-256(target ‖ nonce) where both are
     * fully attacker-controlled on every PoW verification path (addBlock, registerOrphan, header
     * sync, fork-choice branch validation all pass useCache=true). An unbounded map therefore grew
     * one permanent entry per distinct block/header the node ever verified — a free remote
     * memory-exhaustion vector (feed /submit a stream of blocks with fresh nonces). Cap it like
     * OrphanPool/MemPool so it can never itself be a growth vector; the miner (useCache=false)
     * never populates it, so caching only ever helped repeat verification of the same input.
     */
    private static final int PUFFERFISH_CACHE_MAX = 4096;
    private static final Map<SHA256Hash, SHA256Hash> pufferfishCache =
        Collections.synchronizedMap(new LinkedHashMap<>(512, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SHA256Hash, SHA256Hash> eldest) {
                return size() > PUFFERFISH_CACHE_MAX;
            }
        });

    public static SHA256Hash PUFFERFISH(byte[] input, boolean useCache) {
        SHA256Hash inputHash = SHA256Hash.of(input);

        if (useCache) {
            SHA256Hash cachedHash = pufferfishCache.get(inputHash);
            if (cachedHash != null) {
                return cachedHash;
            }
        }

        byte[] hash = new byte[PufferfishConstants.PF_HASHSPACE];
        hash = PufferfishAlgorithm.compute(input);

        SHA256Hash finalHash = SHA256(hash); // Assuming SHA256 is standard SHA-256 hash

        if (useCache) {
            pufferfishCache.put(inputHash, finalHash);
        }

        return finalHash;
    }

    public static SHA256Hash SHA256(byte[] hash) {
        return SHA256(hash, false, false);
    }

    public static SHA256Hash SHA256(byte[] data, boolean usePufferFish, boolean useCache) {
        if (usePufferFish) {
            return PUFFERFISH(data, useCache);
        }
        
        // Standard SHA-256 Hashing
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return SHA256Hash.of(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Unable to find SHA-256 algorithm", e);
        }
    }

    public static boolean verifyHash(SHA256Hash target, SHA256Hash nonce, int challengeSize, boolean usePufferFish, boolean useCache) {
        SHA256Hash fullHash = concatHashes(target, nonce, usePufferFish, useCache);
        return checkLeadingZeroBits(fullHash, challengeSize);
    }

    public static SHA256Hash concatHashes(SHA256Hash a, SHA256Hash b, boolean usePufferFish, boolean useCache) {
        // Proof-of-work hash over (target ‖ nonce). The consensus-critical part is that
        // usePufferFish is HONORED: on a PUFFERFISH2 network the header must be verified
        // (and mined) with the memory-hard Pufferfish2 function, not plain SHA-256 — that
        // is the whole ASIC-resistance property. See PowAlgorithm / NetworkParameters.
        byte[] data = new byte[64];
        System.arraycopy(a.hash(), 0, data, 0, 32);
        System.arraycopy(b.hash(), 0, data, 32, 32);
        return usePufferFish ? PUFFERFISH(data, useCache) : SHA256(data);
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
        byte[] a = hash.hash();
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
        BigInteger base = BigInteger.valueOf(2);
        return work.add(base.pow(exponent));
    }

}
