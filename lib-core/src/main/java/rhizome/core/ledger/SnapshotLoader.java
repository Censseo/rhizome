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

    /** Hard cap on the snapshot file size so a misconfigured path cannot OOM the boot. */
    private static final long MAX_SNAPSHOT_FILE_BYTES = 512L * 1024 * 1024;

    /** Deepest bracket nesting accepted: org.json parses recursively, so a deeply nested
     *  snapshot overflows the boot thread's stack (same guard as the API JSON parser). */
    private static final int MAX_JSON_DEPTH = 64;

    public static LedgerSnapshot fromFile(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_SNAPSHOT_FILE_BYTES) {
            throw new IOException("snapshot file too large: " + size + " bytes (cap "
                + MAX_SNAPSHOT_FILE_BYTES + ")");
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        // Same depth guard as the API JSON parser, string-aware so brackets inside a string
        // value (addresses, metadata) don't count as nesting. One O(n) pass at boot, same order
        // as the parse itself.
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                if (++depth > MAX_JSON_DEPTH) {
                    throw new IOException("snapshot JSON nesting too deep (max " + MAX_JSON_DEPTH + ")");
                }
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
            }
        }
        return LedgerSnapshot.fromJson(new JSONObject(content));
    }

    /** An empty snapshot for the given network (fresh chain, no initial balances). */
    public static LedgerSnapshot empty(int chainId) {
        return new LedgerSnapshot("empty", 0, chainId);
    }
}
