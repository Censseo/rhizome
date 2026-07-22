package rhizome.core.transaction;

import java.util.Arrays;

import rhizome.crypto.SimpleHashType;

import static rhizome.crypto.Hex.bytesToHex;
import static rhizome.crypto.Hex.hexStringToByteArray;

public record TransactionSignature(byte[] signature) implements SimpleHashType {

    public static TransactionSignature empty() {
        return new TransactionSignature(SimpleHashType.empty(SIZE));
    }

    public static TransactionSignature random() {
        return new TransactionSignature(SimpleHashType.random(SIZE));
    }

    public static TransactionSignature of(byte[] bytes) {
        return new TransactionSignature(bytes);
    }

    public static TransactionSignature of(String hexString) {
        return TransactionSignature.of(hexStringToByteArray(hexString));
    }

    public String toHexString() {
        return bytesToHex(signature);
    }

    public byte[] toBytes() {
        return signature;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TransactionSignature)) {
            return false;
        }
        return Arrays.equals(signature, ((TransactionSignature) other).signature());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(signature);
    }

    public static final int SIZE = 64;
    @Override
    public int getSize() {
        return SIZE;
    }
}
