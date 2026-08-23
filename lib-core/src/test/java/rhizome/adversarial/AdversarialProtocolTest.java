package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The gate that makes {@code docs/adversarial/spec.md} a protocol rather than a document.
 *
 * <p>A security catalogue rots in one specific way: the prose keeps claiming a defence after the
 * test that proved it was renamed, moved, disabled or deleted. Nothing fails, and the claim quietly
 * becomes folklore. So the catalogue is parsed here and every proof it names is resolved against
 * the working tree — the file must exist, the method must be declared in it, and it must not be
 * disabled. Renaming an attack test without updating the catalogue is a build failure, which is the
 * only mechanism that keeps the two in agreement over years.
 *
 * <p>The link is checked in both directions, because each direction fails differently. Catalogue to
 * tree catches a defence claimed after its proof vanished. Tree to catalogue
 * ({@link #everyScenarioLabelInAnAttackSuiteMatchesTheCatalogue()}) catches the opposite drift: a
 * suite whose in-file {@code FAMILY-NN} labels no longer name the scenarios the catalogue says
 * those tests prove. That drift is silent by construction — the labels are comments — and it
 * happened on this protocol's own first commit, in four suites out of six, which is why it is
 * machine-checked rather than reviewed.
 *
 * <p>What this test deliberately does <em>not</em> do is check that a proof actually proves its
 * scenario. No parser can. It checks the link, the shape and the completeness; the reviewer checks
 * the argument.
 */
class AdversarialProtocolTest {

    /**
     * A scenario id: a family prefix then a two-digit number. The family may contain digits after
     * its first character — {@code E2E} does — which a letters-only prefix silently excluded,
     * making that family's rows invisible to every check in this class rather than failing one.
     * The prefix may run up to 7 characters — {@code GENESIS} does — one longer than every prior
     * family; widened from a {@code {1,5}} bound that happened to fit every family through
     * {@code SUPPLY}/{@code WALLET} but was never a stated limit, so a same-shape addition would
     * have silently repeated the {@code E2E} lesson instead of failing loud.
     */
    private static final String ID = "[A-Z][A-Z0-9]{1,6}-\\d{2}";

    /** {@code | ID | Scenario | Class | Verdict | Proof |} */
    private static final Pattern SCENARIO_ROW =
        Pattern.compile("^\\|\\s*(" + ID + ")\\s*\\|(.+)\\|\\s*$", Pattern.MULTILINE);

    /** A proof reference: {@code `module/src/.../SomeTest.java#someMethod`}. */
    private static final Pattern PROOF_REFERENCE =
        Pattern.compile("`([a-z0-9\\-]+/src/[^`#]+\\.java)#(\\w+)`");

    /** A family prefix as the Families section declares it: a backticked code, then its gloss. */
    private static final Pattern FAMILY_DECLARATION = Pattern.compile("`([A-Z][A-Z0-9]{1,6})`\\s+\\w");

    /** A javadoc block immediately preceding a {@code @Test} method — never spanning another. */
    private static final Pattern DOCUMENTED_TEST = Pattern.compile(
        "/\\*\\*((?:(?!\\*/).)*)\\*/\\s*@Test\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*void\\s+(\\w+)\\s*\\(",
        Pattern.DOTALL);

    /** Any {@code @Test} method, documented or not. */
    private static final Pattern TEST_METHOD =
        Pattern.compile("@Test\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*void\\s+(\\w+)\\s*\\(");

    /** The scenario label convention: the javadoc opens with the id, then a dash. */
    private static final Pattern SCENARIO_LABEL =
        Pattern.compile("^(" + ID + ")\\s*[\\u2014-]");

    private static final Set<String> VERDICTS = Set.of("DEFENDED", "BOUNDED", "RESIDUAL");
    private static final Set<String> ATTACKER_CLASSES = Set.of("A0", "A1", "A2", "A3", "A4", "A5", "A6");

    /**
     * What the Known gaps section says when it is legitimately empty. Required so an empty table
     * cannot mean two different things — "the format broke" and "there is nothing left to close" —
     * with the same silent shape; see {@link #everyKnownGapIsExplained()}.
     */
    private static final String NO_GAPS_SENTINEL =
        "_None — every catalogued scenario carries a proof._";

    private record Scenario(String id, String family, String attacker, String verdict, String proof) {}

    // ---- reading the protocol ----

    /** The repository root, found by walking up to the build's own marker. */
    private static Path repoRoot() {
        Path directory = Path.of("").toAbsolutePath();
        for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no settings.gradle above " + directory);
    }

    private static String protocol() throws IOException {
        Path spec = repoRoot().resolve("docs/adversarial/spec.md");
        assertTrue(Files.exists(spec), "the protocol document is missing: " + spec);
        return Files.readString(spec, StandardCharsets.UTF_8);
    }

    /** The document between {@code heading} and the next section, failing loudly if it is gone. */
    private static String section(String document, String heading, String nextHeading) {
        int start = document.indexOf(heading);
        assertTrue(start >= 0, "the protocol has no '" + heading + "' section");
        int end = document.indexOf(nextHeading, start + heading.length());
        assertTrue(end > start, "'" + heading + "' is not followed by '" + nextHeading + "'");
        return document.substring(start, end);
    }

    /** Every catalogued scenario, in document order. Gap rows carry fewer columns and are skipped. */
    private static List<Scenario> catalogue(String document) {
        List<Scenario> scenarios = new ArrayList<>();
        Matcher matcher = SCENARIO_ROW.matcher(document);
        while (matcher.find()) {
            String[] columns = matcher.group(2).split("\\|", -1);
            if (columns.length < 4) {
                continue; // the Known gaps table: id | scenario | why
            }
            scenarios.add(new Scenario(matcher.group(1), matcher.group(1).split("-")[0],
                columns[1].trim(), columns[2].trim(), columns[3].trim()));
        }
        return scenarios;
    }

    /** Scenario id to the {@code path#method} proofs it names. */
    private static Map<String, Set<String>> proofsById(String document) {
        Map<String, Set<String>> byId = new LinkedHashMap<>();
        for (Scenario scenario : catalogue(document)) {
            Set<String> proofs = new LinkedHashSet<>();
            Matcher references = PROOF_REFERENCE.matcher(scenario.proof());
            while (references.find()) {
                proofs.add(references.group(1) + "#" + references.group(2));
            }
            byId.put(scenario.id(), proofs);
        }
        return byId;
    }

    // ---- reading the tests ----

    /**
     * Java source with comments removed, so a proof reference cannot be satisfied by a mention of
     * the method in prose or by a commented-out declaration — both of which are exactly the silent
     * coverage loss the catalogue claims to prevent.
     */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    /** The {@code @Test} methods a suite has switched off, plus every method when the class is. */
    private static boolean isDisabled(String strippedSource, String method) {
        int classDeclaration = strippedSource.indexOf("class ");
        int firstDisabled = strippedSource.indexOf("@Disabled");
        if (firstDisabled >= 0 && classDeclaration >= 0 && firstDisabled < classDeclaration) {
            return true; // the whole suite is off
        }
        Matcher declaration = Pattern.compile("void\\s+" + Pattern.quote(method) + "\\s*\\(")
            .matcher(strippedSource);
        while (declaration.find()) {
            String preceding = strippedSource.substring(
                Math.max(0, declaration.start() - 400), declaration.start());
            if (preceding.contains("@Disabled")) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The suites written for this protocol, across every module — the component attack suites in
     * {@code lib-core} and the end-to-end ones in {@code app-node} alike.
     *
     * <p>Scoping this to one module was wrong and silently so: a scenario suite added under
     * another module's {@code rhizome.adversarial} package was neither label-checked nor required
     * to appear in the catalogue, which is exactly the drift the reverse direction exists to catch.
     * {@code AdversarialProtocolTest} itself is excluded — its tests check the catalogue, they do
     * not run scenarios.
     *
     * <p>Scoping this to the {@code rhizome.adversarial} package alone was <em>also</em> wrong: the
     * {@code adversarialTest} Gradle task (root {@code build.gradle}) matches a suite by class name
     * too — {@code *AttackTest} or {@code *AdversarialTest} — regardless of package. A suite named
     * that way but placed elsewhere would run under {@code ./gradlew adversarial} while escaping
     * every check in this class entirely, which is the same silent drift the whole point of this
     * method is to prevent, just via the other Gradle filter. {@link #matchesGradleAdversarialFilter}
     * mirrors all three {@code includeTestsMatching} patterns for that reason.
     */
    private static List<Path> attackSuites() throws IOException {
        Path root = repoRoot();
        List<Path> suites = new ArrayList<>();
        try (var modules = Files.list(root)) {
            for (Path module : modules.sorted().toList()) {
                // Both registered test source directories (root build.gradle's subprojects block
                // adds src/java/test alongside the conventional src/test/java) — a suite is
                // compiled into the test classes, and therefore reachable by the Gradle filter,
                // from either one.
                for (String testSrc : List.of("src/test/java", "src/java/test")) {
                    Path testRoot = module.resolve(testSrc);
                    if (!Files.isDirectory(testRoot)) {
                        continue;
                    }
                    try (var files = Files.walk(testRoot)) {
                        files.filter(p -> p.getFileName().toString().endsWith(".java"))
                            .filter(p -> !p.getFileName().toString().equals("AdversarialProtocolTest.java"))
                            .filter(AdversarialProtocolTest::matchesGradleAdversarialFilter)
                            .sorted()
                            .forEach(suites::add);
                    }
                }
            }
        }
        return suites;
    }

    /**
     * Mirrors the three {@code includeTestsMatching} patterns the {@code adversarialTest} Gradle
     * task filters on: the {@code rhizome.adversarial} package, or a class name ending
     * {@code AttackTest} or {@code AdversarialTest} in any package.
     */
    private static boolean matchesGradleAdversarialFilter(Path file) {
        String fileName = file.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());
        if (className.endsWith("AttackTest") || className.endsWith("AdversarialTest")) {
            return true;
        }
        return fileName.endsWith("Test.java")
            && file.toString().replace('\\', '/').contains("/rhizome/adversarial/");
    }

    private static String repoRelative(Path file) {
        return repoRoot().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // ---- the checks ----

    @Test
    void everyScenarioIdIsWellFormedUniqueAndDenselyNumberedWithinItsFamily() throws IOException {
        List<Scenario> catalogue = catalogue(protocol());
        assertFalse(catalogue.isEmpty(), "no scenarios parsed — the table format changed");

        Map<String, List<Integer>> numbersByFamily = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Scenario scenario : catalogue) {
            assertTrue(seen.add(scenario.id()), "duplicate scenario id " + scenario.id());
            numbersByFamily.computeIfAbsent(scenario.family(), f -> new ArrayList<>())
                .add(Integer.parseInt(scenario.id().substring(scenario.family().length() + 1)));
        }

        // Dense numbering from 01: a hole means a scenario was deleted rather than superseded, and
        // a deleted scenario is exactly the kind of silent coverage loss this protocol exists to
        // catch. Retiring one means renumbering, which shows up in review.
        numbersByFamily.forEach((family, numbers) -> {
            List<Integer> sorted = numbers.stream().sorted().toList();
            for (int i = 0; i < sorted.size(); i++) {
                assertEquals(i + 1, sorted.get(i),
                    "family " + family + " is not densely numbered from 01: " + sorted);
            }
        });
    }

    @Test
    void everyScenarioNamesADeclaredFamilyAKnownAttackerClassAndAKnownVerdict() throws IOException {
        String document = protocol();
        // Read the declarations from the Families section alone, so a backticked verdict or
        // attacker class elsewhere in the document cannot accidentally legitimise a typo'd prefix.
        String families = section(document, "### Families", "## Running the protocol");

        Set<String> declared = new LinkedHashSet<>();
        Matcher declarations = FAMILY_DECLARATION.matcher(families);
        while (declarations.find()) {
            declared.add(declarations.group(1));
        }
        assertFalse(declared.isEmpty(), "no families declared — the Families section changed");

        for (Scenario scenario : catalogue(document)) {
            assertTrue(declared.contains(scenario.family()),
                scenario.id() + " uses family '" + scenario.family()
                    + "' which the Families section does not declare");
            assertTrue(ATTACKER_CLASSES.contains(scenario.attacker()),
                scenario.id() + " names attacker class '" + scenario.attacker() + "'");
            assertTrue(VERDICTS.contains(scenario.verdict()),
                scenario.id() + " carries verdict '" + scenario.verdict() + "'");
        }
    }

    /**
     * Catalogue to tree: a defended or bounded scenario must point at test methods that are
     * declared, and live. This is what turns a rename, a deletion or an {@code @Disabled} into a
     * build failure instead of a silent lie.
     */
    @Test
    void everyDefendedScenarioResolvesToTestMethodsThatExistAndRun() throws IOException {
        Path root = repoRoot();
        Map<Path, String> stripped = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        int checked = 0;

        for (Scenario scenario : catalogue(protocol())) {
            if (scenario.verdict().equals("RESIDUAL")) {
                assertFalse(scenario.proof().isBlank(),
                    scenario.id() + " is RESIDUAL and must still state the bound that limits it");
                continue;
            }
            Matcher proofs = PROOF_REFERENCE.matcher(scenario.proof());
            int found = 0;
            while (proofs.find()) {
                found++;
                checked++;
                Path file = root.resolve(proofs.group(1));
                String method = proofs.group(2);
                if (!Files.exists(file)) {
                    unresolved.add(scenario.id() + " -> missing file " + proofs.group(1));
                    continue;
                }
                String source = stripped.computeIfAbsent(file, f -> withoutComments(read(f)));
                if (!Pattern.compile("void\\s+" + Pattern.quote(method) + "\\s*\\(")
                        .matcher(source).find()) {
                    unresolved.add(scenario.id() + " -> " + proofs.group(1)
                        + " declares no test method " + method);
                } else if (isDisabled(source, method)) {
                    unresolved.add(scenario.id() + " -> " + proofs.group(1) + "#" + method
                        + " is @Disabled, so the defence is claimed but never executed");
                }
            }
            assertTrue(found > 0,
                scenario.id() + " is " + scenario.verdict() + " but names no proof; a scenario "
                    + "without an executing test belongs in Known gaps");
        }

        assertTrue(unresolved.isEmpty(),
            "the protocol names proofs that no longer exist or no longer run — a defence was "
                + "claimed after its test was renamed, deleted or disabled:\n  "
                + String.join("\n  ", unresolved));
        // A canary against the reference regex silently matching nothing after a formatting change:
        // every per-scenario assertion above would still pass on an empty match set.
        assertTrue(checked > 100, "only " + checked + " proof references parsed; the format changed");
    }

    /**
     * Tree to catalogue: every {@code @Test} in an attack suite opens its javadoc with the scenario
     * it runs, and the catalogue agrees that this test is that scenario's proof.
     *
     * <p>Without this, the labels are decorative. They drifted in four of the six suites on the
     * first commit — a test documented as {@code SIG-05} was catalogued as {@code SIG-04}, and the
     * real {@code SIG-05} lived in another file — which is invisible to a reader who trusts either
     * side alone, and defeats the traceability the whole protocol is for.
     */
    @Test
    void everyScenarioLabelInAnAttackSuiteMatchesTheCatalogue() throws IOException {
        Map<String, Set<String>> proofs = proofsById(protocol());
        List<String> problems = new ArrayList<>();
        int labelled = 0;

        for (Path suite : attackSuites()) {
            String source = read(suite);
            String reference = repoRelative(suite);

            Set<String> documented = new TreeSet<>();
            Matcher tests = DOCUMENTED_TEST.matcher(source);
            while (tests.find()) {
                String method = tests.group(2);
                documented.add(method);
                // The javadoc body with its leading asterisks removed, so the label is the very
                // first thing it says.
                String body = tests.group(1).replaceAll("(?m)^\\s*\\*\\s?", "").strip();
                Matcher label = SCENARIO_LABEL.matcher(body);
                if (!label.find()) {
                    problems.add(reference + "#" + method
                        + " has no scenario label; its javadoc must open with \"FAMILY-NN — \"");
                    continue;
                }
                labelled++;
                String id = label.group(1);
                Set<String> cited = proofs.get(id);
                if (cited == null) {
                    problems.add(reference + "#" + method + " is labelled " + id
                        + ", which the catalogue does not define");
                } else if (!cited.contains(reference + "#" + method)) {
                    String actual = proofs.entrySet().stream()
                        .filter(e -> e.getValue().contains(reference + "#" + method))
                        .map(Map.Entry::getKey).findFirst().orElse("no scenario");
                    problems.add(reference + "#" + method + " is labelled " + id
                        + " but the catalogue lists it under " + actual);
                }
            }

            Matcher all = TEST_METHOD.matcher(source);
            while (all.find()) {
                if (!documented.contains(all.group(1))) {
                    problems.add(reference + "#" + all.group(1)
                        + " is a @Test with no javadoc naming the scenario it runs");
                }
            }
        }

        assertTrue(problems.isEmpty(), "attack suites disagree with the catalogue:\n  "
            + String.join("\n  ", problems));
        assertTrue(labelled > 0, "no labelled tests parsed — the suites or the convention changed");
    }

    /**
     * The suites written for this protocol must stay wired into it. A new attack suite that no
     * scenario cites is coverage the catalogue does not know about — which means the catalogue
     * understates the node's defences, and the next reader trusts the wrong number.
     */
    @Test
    void everyAttackSuiteInThisPackageIsCitedByAtLeastOneScenario() throws IOException {
        String document = protocol();
        List<Path> uncited = attackSuites().stream()
            .filter(p -> !document.contains(p.getFileName().toString()))
            .toList();
        assertTrue(uncited.isEmpty(), "attack suites absent from the protocol catalogue: " + uncited);
    }

    /**
     * Every gap is a deliberate, explained entry — not an empty row someone meant to fill in.
     *
     * <p>An empty section is legitimate exactly once: when every catalogued scenario finally
     * carries a proof, closing the last gap. That state must say so with
     * {@link #NO_GAPS_SENTINEL} rather than by simply having no rows — otherwise this test cannot
     * tell "the last gap closed" from "the table format broke", and the second one is exactly the
     * silent failure the rest of this class exists to catch. Both a real gap row and the sentinel
     * present together is equally wrong: the sentinel claims something the table contradicts.
     */
    @Test
    void everyKnownGapIsExplained() throws IOException {
        String gaps = section(protocol(), "## Known gaps", "## Change log");

        Matcher rows = SCENARIO_ROW.matcher(gaps);
        int count = 0;
        while (rows.find()) {
            // The id is captured separately, so a `| id | scenario | why |` row leaves two columns.
            String[] columns = rows.group(2).split("\\|", -1);
            assertEquals(2, columns.length,
                rows.group(1) + " in Known gaps must be | id | scenario | why it is open |");
            assertTrue(columns[1].trim().length() > 40,
                rows.group(1) + " is listed as a gap without saying what closing it needs");
            count++;
        }
        boolean sentinel = gaps.contains(NO_GAPS_SENTINEL);
        assertTrue(count > 0 || sentinel,
            "the Known gaps table parsed as empty — either the table format changed, or every "
                + "scenario now has a proof and the section must say so with \"" + NO_GAPS_SENTINEL + "\"");
        assertFalse(count > 0 && sentinel,
            "the Known gaps table has both real gap rows and the \"" + NO_GAPS_SENTINEL
                + "\" sentinel — a section cannot be both open and empty");
    }
}
