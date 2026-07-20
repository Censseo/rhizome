package rhizome.node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The embedded dashboard SPA: static files bundled in the node jar under
 * {@code /dashboard/}, loaded once into memory at startup and served from there
 * (they are small, and this avoids blocking the HTTP event loop on classpath IO
 * per request).
 *
 * <p>The file list is explicit rather than discovered — classpath directory
 * listing is unreliable inside jars, and an explicit list means a missing
 * resource fails loudly at startup instead of 404ing in production.
 */
final class DashboardAssets {

    /** Every file the SPA is made of, relative to the {@code dashboard/} resource root. */
    private static final String[] FILES = {
        "index.html",
        "app.css",
        "app.js",
        "crypto.js",
        "tx.js",
        "templates/manifest.json",
        "templates/counter.wasm",
        "templates/token.wasm",
        "templates/amm.wasm",
        "templates/pair.wasm",
        "templates/router.wasm",
        "templates/launchpad.wasm",
        "templates/emitter.wasm",
        "templates/agent_wallet.wasm",
        "templates/counter.rs",
        "templates/token.rs",
        "templates/amm.rs",
        "templates/pair.rs",
        "templates/router.rs",
        "templates/launchpad.rs",
        "templates/emitter.rs",
        "templates/agent_wallet.rs",
    };

    /** One served file: its bytes and the Content-Type they are served with. */
    record Asset(byte[] bytes, String contentType) {}

    private final Map<String, Asset> byPath;

    private DashboardAssets(Map<String, Asset> byPath) {
        this.byPath = byPath;
    }

    /** Loads every bundled file from the classpath; throws if any is missing. */
    static DashboardAssets load() {
        Map<String, Asset> assets = new HashMap<>();
        for (String file : FILES) {
            assets.put(file, new Asset(read("/dashboard/" + file), contentType(file)));
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

    private static byte[] read(String resource) {
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
