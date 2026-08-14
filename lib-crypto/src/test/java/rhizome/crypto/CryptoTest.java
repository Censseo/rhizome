package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static rhizome.crypto.Crypto.generateKeyPairTyped;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Domain-separated message signing (audit F9) and pinned UTF-8 string signing (audit F7). */
class CryptoTest {

    @Test
    void signMessageRoundTripsThroughVerifyMessage() {
        var pair = generateKeyPairTyped();
        PrivateKey privateKey = pair.privateKey();
        PublicKey publicKey = pair.publicKey();

        byte[] signature = Crypto.signMessage(privateKey, "hello rhizome".getBytes(StandardCharsets.UTF_8));
        assertTrue(Crypto.verifyMessage(publicKey, "hello rhizome".getBytes(StandardCharsets.UTF_8), signature));
        assertFalse(Crypto.verifyMessage(publicKey, "goodbye".getBytes(StandardCharsets.UTF_8), signature));
    }

    @Test
    void messageSignatureDoesNotVerifyAgainstTheRawMessage() {
        // The domain prefix keeps the two signing domains apart: a signMessage output must not
        // double as a raw (transaction-domain) signature over the same bytes.
        var pair = generateKeyPairTyped();
        PrivateKey privateKey = pair.privateKey();
        PublicKey publicKey = pair.publicKey();

        byte[] message = "transfer everything".getBytes(StandardCharsets.UTF_8);
        byte[] signature = Crypto.signMessage(privateKey, message);
        assertFalse(Crypto.checkSignature(message, signature, publicKey));
    }

    @Test
    void stringSigningIsUtf8RegardlessOfPlatformCharset() {
        var pair = generateKeyPairTyped();
        PrivateKey privateKey = pair.privateKey();

        byte[] fromString = Crypto.signWithPrivateKey("héllo — ☃", privateKey);
        byte[] fromUtf8Bytes = Crypto.signWithPrivateKey("héllo — ☃".getBytes(StandardCharsets.UTF_8), privateKey);
        assertArrayEquals(fromUtf8Bytes, fromString);
    }

    @Test
    void nonAsciiStringSignsAndVerifiesThroughTheStringOverloads() {
        // sign(String) and checkSignature(String) must agree on the SAME pinned charset (UTF-8):
        // with the platform default on the verify side, a non-ASCII message signed under UTF-8
        // would fail to verify on any host whose default charset differs (e.g. windows-1252).
        var pair = generateKeyPairTyped();
        PrivateKey privateKey = pair.privateKey();
        PublicKey publicKey = pair.publicKey();

        String message = "héllo — ☃ 中文";
        byte[] signature = Crypto.signWithPrivateKey(message, privateKey);
        assertTrue(Crypto.checkSignature(message, signature, publicKey));
        assertTrue(Crypto.checkSignature(message.getBytes(StandardCharsets.UTF_8), signature, publicKey));
        assertFalse(Crypto.checkSignature(message + "!", signature, publicKey));
    }
}
