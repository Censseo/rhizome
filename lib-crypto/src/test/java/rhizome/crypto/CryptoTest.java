package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Domain-separated message signing (audit F9) and pinned UTF-8 string signing (audit F7). */
class CryptoTest {

    @Test
    void signMessageRoundTripsThroughVerifyMessage() {
        var pair = Crypto.generateKeyPair();
        PrivateKey privateKey = PrivateKey.of(pair.getPrivate());
        PublicKey publicKey = PublicKey.of(pair.getPublic());

        byte[] signature = Crypto.signMessage(privateKey, "hello rhizome".getBytes(StandardCharsets.UTF_8));
        assertTrue(Crypto.verifyMessage(publicKey, "hello rhizome".getBytes(StandardCharsets.UTF_8), signature));
        assertFalse(Crypto.verifyMessage(publicKey, "goodbye".getBytes(StandardCharsets.UTF_8), signature));
    }

    @Test
    void messageSignatureDoesNotVerifyAgainstTheRawMessage() {
        // The domain prefix keeps the two signing domains apart: a signMessage output must not
        // double as a raw (transaction-domain) signature over the same bytes.
        var pair = Crypto.generateKeyPair();
        PrivateKey privateKey = PrivateKey.of(pair.getPrivate());
        PublicKey publicKey = PublicKey.of(pair.getPublic());

        byte[] message = "transfer everything".getBytes(StandardCharsets.UTF_8);
        byte[] signature = Crypto.signMessage(privateKey, message);
        assertFalse(Crypto.checkSignature(message, signature, publicKey));
    }

    @Test
    void stringSigningIsUtf8RegardlessOfPlatformCharset() {
        var pair = Crypto.generateKeyPair();
        PrivateKey privateKey = PrivateKey.of(pair.getPrivate());

        byte[] fromString = Crypto.signWithPrivateKey("héllo — ☃", privateKey);
        byte[] fromUtf8Bytes = Crypto.signWithPrivateKey("héllo — ☃".getBytes(StandardCharsets.UTF_8), privateKey);
        assertArrayEquals(fromUtf8Bytes, fromString);
    }
}
