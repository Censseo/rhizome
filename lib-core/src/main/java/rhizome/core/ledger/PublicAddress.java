package rhizome.core.ledger;

import static rhizome.crypto.Hex.bytesToHex;
import static rhizome.crypto.Hex.hexStringToByteArray;

import java.util.Arrays;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SimpleHashType;

public record PublicAddress(byte[] address) implements SimpleHashType {
    public PublicAddress {
        checkSize(address);
    }

    public static PublicAddress empty() {
        return new PublicAddress(SimpleHashType.empty(SIZE));
    }

    public static PublicAddress random() {
        return new PublicAddress(SimpleHashType.random(SIZE));
    }

    public static PublicAddress of(PublicKey publicKey){
        if (!publicKey.key().isPresent()) {
            return PublicAddress.empty();
        }

        byte[] publicKeyBytes = publicKey.toBytes();

        SHA256Digest sha256 = new SHA256Digest();
        byte[] hash1 = new byte[32];
        sha256.update(publicKeyBytes, 0, publicKeyBytes.length);
        sha256.doFinal(hash1, 0);

        RIPEMD160Digest ripemd160 = new RIPEMD160Digest();
        byte[] hash2 = new byte[20];
        ripemd160.update(hash1, 0, hash1.length);
        ripemd160.doFinal(hash2, 0);

        byte[] hash3 = new byte[32];
        byte[] hash4 = new byte[32];
        sha256.reset();
        sha256.update(hash2, 0, hash2.length);
        sha256.doFinal(hash3, 0);
        sha256.reset();
        sha256.update(hash3, 0, hash3.length);
        sha256.doFinal(hash4, 0);

        byte[] out = new byte[SIZE];
        out[0] = 0;
        System.arraycopy(hash2, 0, out, 1, 20);
        System.arraycopy(hash4, 0, out, 21, 4);

        return new PublicAddress(out);
    }

    public static PublicAddress of(byte[] address) {
        return new PublicAddress(address);
    }

    public static PublicAddress of(String hexString) {
        if (hexString.length() != 50) {
            throw new IllegalArgumentException("Invalid wallet address string");
        }
        return PublicAddress.of(hexStringToByteArray(hexString));
    }

    /**
     * Verifies the 4-byte trailing checksum of a key-derived (wallet) address, recomputing
     * {@code SHA256(SHA256(ripemd160))[0:4]} exactly as {@link #of(PublicKey)} does.
     *
     * <p>Not enforced on parse: contract, box and token addresses are hash-derived and carry
     * no checksum, so rejecting unchecked addresses in {@code of()} would break them. This is a
     * capability a UI can use to warn on a mistyped <em>wallet</em> recipient (audit M10),
     * where funds sent to a typo'd-but-well-formed address would otherwise be unspendable.
     */
    public boolean isValidChecksum() {
        byte[] a = address;
        if (a.length != SIZE) {
            return false;
        }
        SHA256Digest sha = new SHA256Digest();
        byte[] h3 = new byte[32];
        sha.update(a, 1, 20); // the 20-byte RIPEMD160 body, matching of(PublicKey)
        sha.doFinal(h3, 0);
        byte[] h4 = new byte[32];
        sha.reset();
        sha.update(h3, 0, 32);
        sha.doFinal(h4, 0);
        for (int i = 0; i < 4; i++) {
            if (a[21 + i] != h4[i]) {
                return false;
            }
        }
        return true;
    }

    public String toHexString() {
        return bytesToHex(address);
    }

    public byte[] toBytes() {
        return address;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PublicAddress)) {
            return false;
        }
        return Arrays.equals(address, ((PublicAddress) other).address());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(address);
    }

    public static final int SIZE = 25;
    @Override
    public int getSize() {
        return 25;
    }
}
