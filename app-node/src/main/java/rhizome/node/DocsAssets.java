package rhizome.node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The documentation the node serves about itself: the repository markdown staged into the jar
 * under {@code /docs/} at build time (the {@code stageDocs} task in app-node/build.gradle),
 * loaded once into memory at startup and served from there like the dashboard assets. Nothing
 * is read from the working tree at runtime, so a bare jar and the native binary serve the same
 * documentation they were built from.
 *
 * <p>Unlike {@link DashboardAssets} the file list is not repeated here: it is read from the
 * generated {@code manifest.json}, which the build already validated covers every document in
 * the tree. A manifest naming a file the jar does not carry — or carrying no pages at all —
 * fails loudly at startup instead of 404ing in production.
 */
final class DocsAssets {

    /** Resource prefix the build stages documentation under. */
    private static final String ROOT = "/docs/";

    private final Map<String, DashboardAssets.Asset> byPath;
    private final JSONArray pages;

    private DocsAssets(Map<String, DashboardAssets.Asset> byPath, JSONArray pages) {
        this.byPath = byPath;
        this.pages = pages;
    }

    /** Reads the manifest and every page it names; throws if any is missing or the set is empty. */
    static DocsAssets load() {
        byte[] manifestBytes = read(ROOT + "manifest.json");
        JSONArray pages = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8))
            .getJSONArray("pages");
        if (pages.isEmpty()) {
            throw new IllegalStateException("docs manifest lists no pages");
        }
        Map<String, DashboardAssets.Asset> assets = new HashMap<>();
        assets.put("manifest.json", new DashboardAssets.Asset(manifestBytes, "application/json; charset=utf-8"));
        for (int i = 0; i < pages.length(); i++) {
            String file = pages.getJSONObject(i).getString("file");
            assets.put(file, new DashboardAssets.Asset(read(ROOT + file), "text/markdown; charset=utf-8"));
        }
        return new DocsAssets(assets, pages);
    }

    /**
     * The document at {@code path} (e.g. {@code "consensus.md"}), or {@code null} if the manifest
     * does not name it. Lookup is an exact map hit rather than a filesystem resolve, so a
     * traversal attempt like {@code ../dashboard/app.js} simply misses.
     */
    DashboardAssets.Asset get(String path) {
        return byPath.get(path);
    }

    /** Page descriptors from the manifest, in nav order. */
    JSONArray pages() {
        return pages;
    }

    private static byte[] read(String resource) {
        try (InputStream in = DocsAssets.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing documentation resource: " + resource
                    + " (run the stageDocs build task)");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load documentation resource: " + resource, e);
        }
    }
}
