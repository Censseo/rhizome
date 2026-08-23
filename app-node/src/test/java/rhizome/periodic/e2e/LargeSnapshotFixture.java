package rhizome.periodic.e2e;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams a synthetic {@code N}-wallet genesis snapshot straight to a file, one JSON entry at a
 * time, without ever holding more than the current entry in memory -- the same shape a real
 * multi-million-address allocation artifact would take, without needing gigabytes of test-JVM
 * heap to build it (see {@code Build I5} in the design this closes).
 *
 * <p>Addresses are a zero-padded hex counter (index {@code i+1}, 50 hex chars = 25 bytes) rather
 * than derived key pairs: {@code LedgerSnapshot.fromJson}/{@code PublicAddress.of(String)} parse
 * any well-formed 50-hex-char string regardless of whether it is checksum-valid or key-derived
 * (checksum validity is an opt-in UI capability, not a parse-time rule -- see
 * {@code PublicAddress#isValidChecksum}), and generating millions of real Ed25519 key pairs would
 * make fixture generation itself the bottleneck this class exists to avoid.
 */
final class LargeSnapshotFixture {

    private LargeSnapshotFixture() {
    }

    /**
     * Writes {@code count} entries of {@code amountPerWallet} each to {@code file}, under
     * {@code chainId}. Returns {@code count * amountPerWallet} (the snapshot's total supply) for
     * the caller's own bookkeeping.
     */
    static long generate(Path file, long count, long amountPerWallet, int chainId) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("{\"version\":1,\"source\":\"large-fixture\",\"sourceHeight\":0,\"chainId\":");
            writer.write(Integer.toString(chainId));
            writer.write(",\"balances\":{");
            for (long i = 0; i < count; i++) {
                if (i > 0) {
                    writer.write(",");
                }
                writer.write('"');
                writer.write(addressHex(i + 1));
                writer.write("\":\"");
                writer.write(Long.toString(amountPerWallet));
                writer.write('"');
            }
            writer.write("}}");
        }
        return Math.multiplyExact(count, amountPerWallet);
    }

    /** A unique 50-hex-char (25-byte) address string derived from a plain counter. */
    private static String addressHex(long index) {
        String hex = Long.toHexString(index);
        return "0".repeat(50 - hex.length()) + hex;
    }
}
