package rhizome.crypto;

import java.util.HexFormat;

public final class Hex {

    private static final HexFormat hexFormat = HexFormat.of().withUpperCase();

    private Hex() {}

    public static String bytesToHex(byte[] bytes) {
        return hexFormat.formatHex(bytes);
    }

    public static byte[] hexStringToByteArray(String s) {
        return hexFormat.parseHex(s);
    }
}
