package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The documentation the node serves about itself. These lock the contract between the
 * {@code stageDocs} build task, {@link DocsAssets} and the dashboard's docs page: every page the
 * manifest advertises must actually be in the jar, its title must be the document's own H1 (the
 * dashboard's nav shows the manifest title while the reader sees the heading), and lookups must
 * stay confined to the manifest.
 */
class DocsAssetsTest {

    private static final DocsAssets DOCS = DocsAssets.load();

    private static String text(DashboardAssets.Asset asset) {
        return new String(asset.bytes(), StandardCharsets.UTF_8);
    }

    @Test
    void manifestIsServedAndDescribesEveryPage() {
        DashboardAssets.Asset manifest = DOCS.get("manifest.json");
        assertNotNull(manifest);
        assertEquals("application/json; charset=utf-8", manifest.contentType());

        JSONArray pages = new JSONObject(text(manifest)).getJSONArray("pages");
        assertTrue(pages.length() >= 16, "expected the whole docs tree, got " + pages.length());
        assertEquals(pages.length(), DOCS.pages().length());

        Set<String> slugs = new HashSet<>();
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            String slug = page.getString("slug");
            assertTrue(slugs.add(slug), "duplicate slug: " + slug);
            assertFalse(page.getString("title").isBlank());
            assertFalse(page.getString("group").isBlank());
            assertTrue(page.getString("source").endsWith(".md"));
            assertEquals(slug + ".md", page.getString("file"));
        }
        // The whitepaper and the spec index are what the dashboard links to first.
        assertTrue(slugs.contains("whitepaper"));
        assertTrue(slugs.contains("index"));
        assertTrue(slugs.contains("consensus"));
    }

    @Test
    void everyAdvertisedPageIsBundledAndTitledByItsOwnHeading() {
        JSONArray pages = DOCS.pages();
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            DashboardAssets.Asset asset = DOCS.get(page.getString("file"));
            assertNotNull(asset, "manifest names an absent document: " + page.getString("file"));
            assertEquals("text/markdown; charset=utf-8", asset.contentType());

            String markdown = text(asset);
            assertTrue(markdown.length() > 200, page.getString("slug") + " looks truncated");
            String heading = markdown.lines().filter(l -> l.startsWith("# ")).findFirst().orElse("");
            assertEquals("# " + page.getString("title"), heading,
                page.getString("slug") + ": manifest title must be the document's first heading");
        }
    }

    @Test
    void lookupsOutsideTheManifestMiss() {
        // Serving is an exact map hit, not a filesystem resolve, so a traversal attempt is
        // simply an unknown key rather than a path that escapes the documentation root.
        assertNull(DOCS.get("../dashboard/app.js"));
        assertNull(DOCS.get("/etc/passwd"));
        assertNull(DOCS.get("nope.md"));
        assertNull(DOCS.get(""));
    }
}
