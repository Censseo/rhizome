package rhizome.persistence.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.json.JSONArray;
import org.json.JSONObject;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;

import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionAmount;

/**
 * Reads a synced Pandanite node's LevelDB ledger directory and produces a
 * {@link LedgerSnapshot} to seed the clean Rhizome chain's genesis state.
 *
 * <p>The Pandanite ledger stores {@code address (25 bytes) -> amount
 * (uint64 little-endian, 8 bytes)}. Entries whose key/value sizes don't match
 * are skipped. Addresses listed in an exclusion file (Pandanite's
 * {@code invalid.json}, the wallets inflated by the historical balance bug) are
 * dropped so the fresh chain starts from a sanitised state.
 *
 * <p>CLI:
 * <pre>
 *   PandaniteLedgerDumper &lt;ledgerDir&gt; &lt;out.json&gt; [invalid.json] [sourceHeight] [chainId]
 * </pre>
 */
public final class PandaniteLedgerDumper {

    private PandaniteLedgerDumper() {}

    /** Result of a dump: the snapshot plus counters for what was skipped. */
    public record DumpResult(LedgerSnapshot snapshot, long malformedEntries, long excludedWallets) {}

    public static long decodeAmountLittleEndian(byte[] value) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (value[i] & 0xffL) << (8 * i);
        }
        return v;
    }

    /** Loads the set of wallet addresses (uppercase hex) to exclude from a Pandanite invalid.json. */
    public static Set<String> loadExclusions(Path invalidJson) throws IOException {
        Set<String> excluded = new HashSet<>();
        String content = Files.readString(invalidJson, StandardCharsets.UTF_8);
        JSONArray entries = new JSONArray(content);
        for (int i = 0; i < entries.length(); i++) {
            excluded.add(entries.getJSONObject(i).getString("wallet").toUpperCase());
        }
        return excluded;
    }

    /**
     * Opens the Pandanite ledger directory and builds a snapshot, excluding the
     * given wallet addresses (uppercase hex).
     */
    public static DumpResult dump(Path ledgerDir, Set<String> excludedWallets,
                                  String source, long sourceHeight, int chainId) throws IOException {
        LedgerSnapshot snapshot = new LedgerSnapshot(source, sourceHeight, chainId);
        long malformed = 0;
        long excluded = 0;

        Options options = new Options();
        options.createIfMissing(false);
        DB db = factory.open(ledgerDir.toFile(), options);
        try (DBIterator it = db.iterator()) {
            for (it.seekToFirst(); it.hasNext(); it.next()) {
                Map.Entry<byte[], byte[]> entry = it.peekNext();
                byte[] key = entry.getKey();
                byte[] value = entry.getValue();
                if (key.length != PublicAddress.SIZE || value.length != 8) {
                    malformed++;
                    continue;
                }
                PublicAddress address = PublicAddress.of(key);
                if (excludedWallets.contains(address.toHexString().toUpperCase())) {
                    excluded++;
                    continue;
                }
                snapshot.put(address, new TransactionAmount(decodeAmountLittleEndian(value)));
            }
        } finally {
            db.close();
        }
        return new DumpResult(snapshot, malformed, excluded);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println(
                "usage: PandaniteLedgerDumper <ledgerDir> <out.json> [invalid.json] [sourceHeight] [chainId]");
            System.exit(2);
        }
        Path ledgerDir = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Set<String> exclusions = args.length >= 3 && !args[2].isEmpty()
            ? loadExclusions(Path.of(args[2]))
            : Set.of();
        long sourceHeight = args.length >= 4 ? Long.parseLong(args[3]) : 0L;
        int chainId = args.length >= 5 ? Integer.parseInt(args[4]) : 1;

        DumpResult result = dump(ledgerDir, exclusions, "pandanite", sourceHeight, chainId);
        Files.writeString(out, result.snapshot().toJson().toString(2), StandardCharsets.UTF_8);

        System.out.printf(
            "Wrote %d wallets to %s (excluded %d inflated, skipped %d malformed, total supply %s)%n",
            result.snapshot().size(), out, result.excludedWallets(), result.malformedEntries(),
            Long.toUnsignedString(result.snapshot().totalSupply()));
    }
}
