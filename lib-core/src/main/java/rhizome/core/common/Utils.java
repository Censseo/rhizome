package rhizome.core.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import rhizome.crypto.Hex;

public class Utils {

    private Utils() {}

    public static byte[] longToBytes(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(value);
        return buffer.array();
    }

    public static long bytesToLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getLong();
    }

    public static byte[] intToBytes(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(value);
        return buffer.array();
    }

    public static String bytesToHex(byte[] bytes) {
        return Hex.bytesToHex(bytes);
    }

    public static byte[] hexStringToByteArray(String s) {
        return Hex.hexStringToByteArray(s);
    }
}
