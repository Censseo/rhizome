package rhizome;

import org.junit.jupiter.api.Test;

import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.crypto.SHA256Hash;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.SHA256;
import static rhizome.core.common.Crypto.addWork;
import static rhizome.core.common.Crypto.concatHashes;
import static rhizome.core.common.Crypto.generateKeyPair;
import static rhizome.core.common.Crypto.signWithPrivateKey;
import static rhizome.core.common.Crypto.verifyHash;
import static rhizome.core.common.Utils.bytesToHex;
import static rhizome.core.common.Utils.hexStringToByteArray;
import static rhizome.core.common.Crypto.checkSignature;

import rhizome.core.blockchain.Miner;
import rhizome.core.common.PowAlgorithm;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

class CryptoTests {

    @Test
    void testKeyStringConversion() {
        var keys = generateKeyPair();
        var publicKey = PublicKey.of(keys.getPublic());
        var publicKeyData = publicKey.toBytes();

        var publicKeyString = publicKey.toHexString();
        var convertedPublicKey = PublicKey.of(publicKeyString);
        var convertedPublicKeyData = convertedPublicKey.toBytes();

        assertArrayEquals(publicKeyData, convertedPublicKeyData);
    }

    @Test
    void testSignatureStringConversion() {
        var keys = generateKeyPair();
        var privateKey = new PrivateKey((Ed25519PrivateKeyParameters) keys.getPrivate());
        var message = "FOOBAR";
        var signature = signWithPrivateKey(message.getBytes(StandardCharsets.UTF_8), privateKey);

        var signatureString = bytesToHex(signature);
        var convertedSignature = hexStringToByteArray(signatureString);

        assertArrayEquals(signature, convertedSignature);
    }

    @Test
    void testSignatureVerifications() {
        var keys = generateKeyPair();
        var privateKey = new PrivateKey((Ed25519PrivateKeyParameters) keys.getPrivate());
        var publicKey = PublicKey.of(keys.getPublic());

        var message = "FOOBAR";
        var signature = signWithPrivateKey(message.getBytes(StandardCharsets.UTF_8), privateKey);
        var status = checkSignature(message.getBytes(StandardCharsets.UTF_8), signature, publicKey);
        assertTrue(status);

        // check with wrong public key
        var wrongKeys = generateKeyPair();
        var wrongPrivateKey = new PrivateKey((Ed25519PrivateKeyParameters) wrongKeys.getPrivate());
        var wrongSignature = signWithPrivateKey(message.getBytes(StandardCharsets.UTF_8), wrongPrivateKey);
        status = checkSignature(message.getBytes(StandardCharsets.UTF_8), wrongSignature, publicKey);
        assertFalse(status);
    }

    @Test
    void total_work() {
        var work = BigInteger.ZERO;
        work = addWork(work, 16);
        work = addWork(work, 16);
        work = addWork(work, 16);
        var base = BigInteger.valueOf(2);
        var mult = BigInteger.valueOf(3);
        var expected = mult.multiply(base.pow(16));
        assertEquals(expected, work);
        assertEquals(new BigInteger("196608"), work);
        work = addWork(work, 32);
        work = addWork(work, 28);
        work = addWork(work, 74);
        work = addWork(work, 174);
        var b = expected;
        b = b.add(base.pow(32));
        b = b.add(base.pow(28));
        b = b.add(base.pow(74));
        b = b.add(base.pow(174));
        
        assertEquals(b, work);
        assertEquals("23945242826029513411849172299242470459974281928572928", work.toString());
    }

    // @Test
    // void mine_hash() {
    //     var hash = SHA256("Hello World".getBytes());
    //     var answer = mineHash(hash, 6);
    //     var newHash = concatHashes(hash, answer);
    //     byte[] data = newHash.data();
        
    //     // check first 6 bits are 0
    //     assertTrue((data[0] & 0b11111100) == 0);
    // }

    /**
     * Regression guard for the PoW wiring bug: {@code concatHashes} must actually run
     * Pufferfish2 when asked, not silently fall back to plain SHA-256. We assert both
     * that the two algorithms produce different digests for the same input (so the flag
     * is honored) and that a nonce mined under Pufferfish2 verifies under Pufferfish2.
     */
    @Test
    void proofOfWorkHonorsPufferfishFlag() {
        var target = SHA256("rhizome-pow-vector".getBytes(StandardCharsets.UTF_8));
        var nonce = SHA256Hash.empty();

        SHA256Hash sha = concatHashes(target, nonce, false, false);
        SHA256Hash puffer = concatHashes(target, nonce, true, false);
        // If the flag were ignored (the old bug), these would be identical.
        assertNotEquals(sha.toHexString(), puffer.toHexString());

        // A nonce mined under Pufferfish2 (difficulty 4 = ~16 hashes, fast) must verify
        // under Pufferfish2 and be rejected as a plain-SHA-256 solution would not carry.
        int difficulty = 4;
        SHA256Hash pufferNonce = Miner.mineNonce(target, difficulty, PowAlgorithm.PUFFERFISH2);
        assertTrue(verifyHash(target, pufferNonce, difficulty, true, false));
    }

    @Test
    void sha256ToString() {
        var message = "FOOBAR";
        var hash = SHA256(message.getBytes(StandardCharsets.UTF_8));
        var hashString = hash.toHexString();
        var convertedHash = SHA256Hash.of(hashString);

        assertTrue(hash.hash().isContentEqual(convertedHash.hash()));
    }
}
