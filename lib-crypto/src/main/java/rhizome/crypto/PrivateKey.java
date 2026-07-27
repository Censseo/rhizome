package rhizome.crypto;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;


import static rhizome.crypto.Hex.bytesToHex;
import static rhizome.crypto.Hex.hexStringToByteArray;

public record PrivateKey(Ed25519PrivateKeyParameters key) implements SimpleHashType {

    public static PrivateKey of(byte[] bytes) {
        if (bytes == null || bytes.length != SIZE) {
            throw new IllegalArgumentException(
                "Invalid private key length: expected " + SIZE + " bytes, got "
                    + (bytes == null ? "null" : bytes.length));
        }
        return new PrivateKey(new Ed25519PrivateKeyParameters(bytes, 0));
    }

    public static PrivateKey of(AsymmetricKeyParameter keyParameter) {
        if (keyParameter == null) {
            return null;
        }
        if (keyParameter instanceof Ed25519PrivateKeyParameters) {
            return new PrivateKey((Ed25519PrivateKeyParameters) keyParameter);
        }
        return null;
    }

    // Residual exposure: the hex String (and the String returned by toHexString) stays on the
    // heap until GC — seed-handling callers should prefer of(byte[]) and wipe their own copy.
    // No destroy() is offered: Ed25519PrivateKeyParameters keeps a private internal copy that
    // BouncyCastle 1.78 exposes no wipe for, so only the mutable buffers created here are zeroed.
    public static PrivateKey of(String hexString) {
        if ("".equals(hexString)) {
            return null;
        }
        if (hexString.length() != 64) {
            throw new IllegalArgumentException("Invalid private key string length. Expected 64 characters for a 32-byte key.");
        }
        byte[] seed = hexStringToByteArray(hexString);
        try {
            return new PrivateKey(new Ed25519PrivateKeyParameters(seed, 0));
        } finally {
            java.util.Arrays.fill(seed, (byte) 0);
        }
    }

    public String toHexString() {
        if (key == null) {
            return "";
        }
        byte[] encoded = key.getEncoded();
        try {
            return encoded == null ? "" : bytesToHex(encoded);
        } finally {
            if (encoded != null) {
                java.util.Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    public byte[] toBytes() {
        return key.getEncoded();
    }

    public static final int SIZE = 32;
    @Override
    public int getSize() {
        return SIZE;
    }

    // Never leak key material into logs/stack traces: the record default would defer to
    // Ed25519PrivateKeyParameters.toString(), whose output is a library implementation detail
    // that must never become a secret-disclosure channel. Redact unconditionally (audit).
    @Override
    public String toString() {
        return "PrivateKey[REDACTED]";
    }
}
