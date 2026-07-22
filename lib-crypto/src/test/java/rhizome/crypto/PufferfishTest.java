package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.MessageDigest;

import org.junit.jupiter.api.Test;


/**
 * Validates the pure-Java Pufferfish2 port bit-for-bit against golden vectors
 * generated from the reference C implementation bundled with Pandanite
 * (cost_t=0, cost_m=8, all-zero salt). The `pow` value is
 * {@code SHA-256(pf_newhash(input))}, which is exactly what
 * {@link Crypto#PUFFERFISH} produces.
 */
class PufferfishTest {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static String pow(String inputHex) {
        // Crypto.PUFFERFISH(input) == SHA-256 over the pf_newhash "$PF2$..." buffer.
        SHA256Hash h = Crypto.PUFFERFISH(hex(inputHex), false);
        return h.toHexString().toLowerCase();
    }

    @Test
    void matchesGoldenVectors() {
        assertEquals("c6dd80b673863a328234252cefbb1b91d48de8abd78f49524f59fe61124392a1", pow(""));
        assertEquals("ceaf3e5c39720367565585e0f58054d158205e018d8bb60fd0d34d11b71d869e", pow("00"));
        assertEquals("1d73da01e358cc187a1b03f6e5a83cca142beb8c285dbc2d52fb67a5c8140a20", pow("616263"));
        assertEquals("190de85a8e1e4b537733da40d924c6cfc79e8b1c1b037f50c60e9dd2a95b47b0",
            pow("0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("2281528570a9332f7aae6df93dc4e166d2e61293ed2a4bcc89bfce9288e22352",
            pow("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
        assertEquals("133ee356222c2ed1e358796b2256b7cf4c28e5826cf87d12b496da476854cc9a",
            pow("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertEquals("3c6433ee5387bc748ed90d1a8ce24d24038afdd77a67c2924b508f50c10e9f4e",
            pow("deadbeefcafebabe0011223344556677"));
    }

    @Test
    void isDeterministic() {
        String a = pow("616263");
        String b = pow("616263");
        assertEquals(a, b);
    }

    @Test
    void sanityPlainSha256Unchanged() throws Exception {
        // Guard: the non-Pufferfish path is still a plain SHA-256.
        byte[] data = hex("616263");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        SHA256Hash expected = SHA256Hash.of(md.digest(data));
        assertEquals(expected.toHexString(), Crypto.SHA256(data).toHexString());
    }
}
