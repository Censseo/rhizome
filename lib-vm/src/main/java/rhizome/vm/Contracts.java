package rhizome.vm;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import rhizome.core.ledger.PublicAddress;

/** Contract-address derivation. */
public final class Contracts {

    private Contracts() {}

    /**
     * Deterministic contract address = first 25 bytes of SHA-256(deployer || nonce),
     * so a deployer's contract addresses are predictable and collision-resistant, and
     * two deploys from the same account (different nonce) never collide.
     */
    public static PublicAddress deriveAddress(PublicAddress deployer, long nonce) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(deployer.toBytes());
            sha.update(ByteBuffer.allocate(Long.BYTES).putLong(nonce).array());
            byte[] digest = sha.digest();
            return PublicAddress.of(Arrays.copyOf(digest, PublicAddress.SIZE));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
