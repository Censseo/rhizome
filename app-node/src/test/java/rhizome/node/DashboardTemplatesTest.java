package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The dashboard bundles its own copy of every contract template — {@code .rs} source and
 * {@code .wasm} binary — under {@code dashboard/templates/}. That copy is hand-maintained
 * separately from the audited originals in {@code lib-vm}, and it has drifted before: the
 * bundled {@code token.rs} was caught missing the deployer-front-run guard (audit T1) and the
 * checked-arithmetic fix on transfers, while the {@code token.wasm} served right next to it was
 * already the post-audit binary. A user reading "Source" in the dashboard and compiling it
 * themselves would have shipped a vulnerable contract.
 *
 * <p>This pins byte-identity between what the dashboard serves and what {@code lib-vm} audits
 * and tests against, so any future drift — in either the source or the binary — fails the build
 * instead of shipping quietly. If this test ever fails, treat it as "the served copy is stale",
 * not "the audited copy is wrong": {@code lib-vm/contracts/*.rs} and
 * {@code lib-vm/src/test/resources/*.wasm} are the source of truth.
 */
class DashboardTemplatesTest {

    // The manifest's non-null template ids (see templates/manifest.json), in file-name terms.
    // logtree is deliberately excluded: it is a lib-vm test fixture (LogOrderingTest), never a
    // dashboard template.
    private static final List<String> TEMPLATE_IDS = List.of(
        "counter", "token", "agent_wallet", "amm", "pair", "launchpad", "router", "emitter");

    // build/resources/main is what stageContractTemplates + processResources actually produce —
    // the merged output that ends up in the jar — not src/main/resources/dashboard/templates/,
    // which after this change holds only the checked-in manifest.json.
    private static Path served(String fileName) {
        return Path.of("build/resources/main/dashboard/templates/" + fileName);
    }

    private static Path audited(String fileName) {
        return Path.of("../lib-vm/src/test/resources/" + fileName);
    }

    private static Path source(String fileName) {
        return Path.of("../lib-vm/contracts/" + fileName);
    }

    @Test
    void everyServedWasmMatchesTheAuditedFixtureByteForByte() throws Exception {
        for (String id : TEMPLATE_IDS) {
            String wasm = id + ".wasm";
            Path servedPath = served(wasm);
            Path auditedPath = audited(wasm);
            assertTrue(Files.exists(servedPath), servedPath + " must exist");
            assertTrue(Files.exists(auditedPath), auditedPath + " must exist");
            assertArrayEquals(Files.readAllBytes(auditedPath), Files.readAllBytes(servedPath),
                wasm + ": the dashboard-served binary must be exactly the audited one");
        }
    }

    @Test
    void everyServedSourceMatchesTheAuditedOriginalByteForByte() throws Exception {
        for (String id : TEMPLATE_IDS) {
            String rs = id + ".rs";
            Path servedPath = served(rs);
            Path sourcePath = source(rs);
            assertTrue(Files.exists(servedPath), servedPath + " must exist");
            assertTrue(Files.exists(sourcePath), sourcePath + " must exist");
            assertArrayEquals(Files.readAllBytes(sourcePath), Files.readAllBytes(servedPath),
                rs + ": the dashboard-served source must match lib-vm/contracts exactly — a "
                    + "mismatch here means a user reading \"Source\" sees code that does not "
                    + "describe the binary next to it");
        }
    }

    @Test
    void theServedTokenBinaryCarriesTheDeployerFrontRunGuard() throws Exception {
        // audit T1: init() checks get_deployer() before minting. A stripped/stale token.wasm
        // that dropped this guard would still import storage_read/storage_write/set_output but
        // NOT get_deployer — grep the raw binary for the import name rather than re-deploying it
        // through the VM, since lib-vm's own tests (TokenContractTest) already exercise the
        // guard's runtime behavior against this exact resource.
        byte[] wasm = Files.readAllBytes(served("token.wasm"));
        assertTrue(containsAscii(wasm, "get_deployer"),
            "served token.wasm must import get_deployer (audit T1 guard) — "
                + "see rhizome.vm.TokenContractTest#initCannotBeFrontRunByANonDeployer");
    }

    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
