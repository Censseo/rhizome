package rhizome.core.crypto;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import rhizome.core.common.SimpleHashType;

import static rhizome.core.common.Utils.bytesToHex;
import static rhizome.core.common.Utils.hexStringToByteArray;

public record PublicKey(Ed25519PublicKeyParameters key) implements SimpleHashType {

    public static PublicKey of(byte[] bytes) {
        return new PublicKey(new Ed25519PublicKeyParameters(bytes, 0));
    }

    public static PublicKey of(String hexString) {
        if ("".equals(hexString)) {
            return null;
        }
        if (hexString.length() != 64) {
            throw new IllegalArgumentException("Invalid public key string length. Expected 64 characters for a 32-byte key.");
        }
        return new PublicKey(new Ed25519PublicKeyParameters(hexStringToByteArray(hexString), 0));    
    }

    public String toHexString() {
        if (key == null) {
            return "";
        }
        return bytesToHex(key.getEncoded() == null ? new byte[0] : key.getEncoded());
    }

    public byte[] toBytes() {
        return key.getEncoded();
    }

    public static final int SIZE = 32;
    @Override
    public int getSize() {
        return SIZE;
    }
}
