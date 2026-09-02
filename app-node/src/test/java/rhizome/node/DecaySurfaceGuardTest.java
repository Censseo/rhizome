package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * 008-decaying-supply-target T018 (env half) and T019 (persisted half): the surfaces this module
 * owns must stay free of the decay constants. "Governance over fees, not money" (FR-009) and "no
 * new persisted state" (FR-019) are otherwise enforced only by convention — these scans fail the
 * build if either surface ever gains them.
 *
 * <p>Deliberately classfile-level and reflective, not behavioural: an env var that configures a
 * consensus constant is a design fault BEFORE any behaviour could prove it harmful.
 */
class DecaySurfaceGuardTest {

    /** The decay/monetary tokens no {@code RHIZOME_*} binding may ever name. */
    private static final Pattern DECAY_TOKEN =
        Pattern.compile("DECAY|SUPPLY_TARGET|TARGET_FLOOR|START_HEIGHT");

    @Test
    void noRhizomeEnvironmentBindingNamesADecayConstant() throws IOException {
        // Read the parsed env surface out of NodeConfig's own classfile: every RHIZOME_*
        // literal the config parser can ever consult is in its constant pool. Scanning there
        // fails the build the moment a RHIZOME_DECAY_* (or RHIZOME_SUPPLY_TARGET*, ...) binding
        // is added — no running node required.
        String classfile = readOwnClassfile(NodeConfig.class);
        Matcher matcher = Pattern.compile("RHIZOME_[A-Z0-9_]+").matcher(classfile);
        Set<String> bindings = new TreeSet<>();
        while (matcher.find()) {
            bindings.add(matcher.group());
        }

        // The scan itself must be alive: the config parser's documented bindings are present.
        assertTrue(bindings.contains("RHIZOME_NETWORK") && bindings.contains("RHIZOME_MINER")
                && bindings.contains("RHIZOME_BLOCK_INTERVAL_MS"),
            "the classfile scan found none of the documented bindings -- it is not reading "
                + "what it claims to; found: " + bindings);

        for (String binding : bindings) {
            assertTrue(!DECAY_TOKEN.matcher(binding).find(),
                "environment binding '" + binding + "' names a decay/monetary constant: the "
                    + "supply-target schedule is a per-profile consensus constant (FR-009) and "
                    + "must never become operator-configurable");
        }
    }

    @Test
    void theNodeStoreAddsNoDecayOrSupplyColumnFamily() throws IllegalAccessException {
        // T019's persisted half: the feature's full-database column families are pinned here.
        // A decay that persisted anything — a schedule checkpoint, an obligation accumulator —
        // would need a family here and would fail this scan.
        Set<String> families = new TreeSet<>();
        for (Field field : rhizome.persistence.rocksdb.RocksDbNodeStore.class
                .getDeclaredFields()) {
            if (!field.getName().startsWith("CF_") || field.getType() != byte[].class
                    || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            families.add(new String((byte[]) field.get(null), StandardCharsets.ISO_8859_1));
        }

        assertEquals(
            Set.of("blocks", "headers", "txindex", "meta", "ledger", "ledger_journal",
                "nonces", "uncles"),
            families,
            "the node database's column families changed: a persistence addition is exactly "
                + "what the decaying target promised not to make (FR-019 -- the target reads "
                + "only height, so there is nothing to store)");
        for (String family : families) {
            assertTrue(!DECAY_TOKEN.matcher(family.toUpperCase(java.util.Locale.ROOT)).find(),
                "column family '" + family + "' names a decay/monetary constant");
        }
    }

    private static String readOwnClassfile(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("could not read " + resource
                    + " from the runtime classpath -- the env-surface scan cannot run");
            }
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
