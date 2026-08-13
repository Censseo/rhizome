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
 * The embedded dashboard SPA: static files bundled in the node jar under
 * {@code /dashboard/}, loaded once into memory at startup and served from there
 * (they are small, and this avoids blocking the HTTP event loop on classpath IO
 * per request).
 *
 * <p>The base file list is explicit rather than discovered — classpath directory
 * listing is unreliable inside jars, and an explicit list means a missing
 * resource fails loudly at startup instead of 404ing in production.
 *
 * <p>Contract templates are the one exception: their {@code .rs}/{@code .wasm} pair is not
 * listed here but read from {@code templates/manifest.json} (staged from lib-vm — its single
 * source — by the {@code stageContractTemplates} build task), the same way {@link DocsAssets}
 * reads its file list from the generated docs manifest rather than repeating it. A manifest
 * naming a file the jar does not carry fails loudly here instead of 404ing in production.
 */
final class DashboardAssets {

    /** Resource prefix every dashboard file is staged under. */
    private static final String ROOT = "/dashboard/";

    /** The fixed SPA shell, relative to the {@code dashboard/} resource root — never derived. */
    private static final String[] BASE_FILES = {
        "index.html",
        "app.css",
        "app.js",
        "crypto.js",
        "tx.js",
        "md.js",
    };

    /** One served file: its bytes and the Content-Type they are served with. */
    record Asset(byte[] bytes, String contentType) {}

    private final Map<String, Asset> byPath;

    private DashboardAssets(Map<String, Asset> byPath) {
        this.byPath = byPath;
    }

    /**
     * Loads every bundled file from the classpath — the SPA shell, then the contract templates
     * named by {@code templates/manifest.json} — and throws if any is missing.
     */
    static DashboardAssets load() {
        Map<String, Asset> assets = new HashMap<>();
        for (String file : BASE_FILES) {
            assets.put(file, new Asset(read(file), contentType(file)));
        }

        String manifestPath = "templates/manifest.json";
        byte[] manifestBytes = read(manifestPath);
        assets.put(manifestPath, new Asset(manifestBytes, contentType(manifestPath)));
        JSONArray templates = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8))
            .getJSONArray("templates");
        if (templates.isEmpty()) {
            throw new IllegalStateException("dashboard templates manifest lists no templates");
        }
        for (int i = 0; i < templates.length(); i++) {
            JSONObject t = templates.getJSONObject(i);
            for (String key : new String[] {"wasm", "source"}) {
                String file = "templates/" + t.getString(key);
                assets.put(file, new Asset(read(file), contentType(file)));
            }
        }
        return new DashboardAssets(assets);
    }

    /** The asset at {@code path} (e.g. {@code "app.js"}), or {@code null} if not bundled. */
    Asset get(String path) {
        return byPath.get(path);
    }

    Asset index() {
        return byPath.get("index.html");
    }

    /** Reads {@code path} (relative to {@link #ROOT}, e.g. {@code "app.js"}) from the classpath. */
    private static byte[] read(String path) {
        String resource = ROOT + path;
        try (InputStream in = DashboardAssets.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing dashboard resource: " + resource);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load dashboard resource: " + resource, e);
        }
    }

    private static String contentType(String file) {
        if (file.endsWith(".html")) return "text/html; charset=utf-8";
        if (file.endsWith(".css")) return "text/css; charset=utf-8";
        if (file.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (file.endsWith(".json")) return "application/json; charset=utf-8";
        if (file.endsWith(".wasm")) return "application/wasm";
        return "text/plain; charset=utf-8";
    }
}
