package rhizome.core.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONObject;

/**
 * Reads a {@link LedgerSnapshot} from a JSON file (as produced by the Pandanite
 * ledger dumper), or yields an empty snapshot for a premine-free fresh chain.
 */
public final class SnapshotLoader {

    private SnapshotLoader() {}

    public static LedgerSnapshot fromFile(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return LedgerSnapshot.fromJson(new JSONObject(content));
    }

    /** An empty snapshot for the given network (fresh chain, no initial balances). */
    public static LedgerSnapshot empty(int chainId) {
        return new LedgerSnapshot("empty", 0, chainId);
    }
}
