package rhizome.core.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import rhizome.crypto.Hex;

public class Utils {

    private Utils() {}

    // Big-endian encode with plain shifts instead of allocating a ByteBuffer per call: these run on
    // the hashing hot path (hashContents/hash call them 6+ times per transaction), so the per-call
    // ByteBuffer + backing array churn was pure allocation pressure. Output is byte-for-byte identical.
    public static byte[] longToBytes(long value) {
        return new byte[] {
            (byte) (value >>> 56), (byte) (value >>> 48), (byte) (value >>> 40), (byte) (value >>> 32),
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    public static long bytesToLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getLong();
    }

    public static byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    public static String bytesToHex(byte[] bytes) {
        return Hex.bytesToHex(bytes);
    }

    public static byte[] hexStringToByteArray(String s) {
        return Hex.hexStringToByteArray(s);
    }
}
