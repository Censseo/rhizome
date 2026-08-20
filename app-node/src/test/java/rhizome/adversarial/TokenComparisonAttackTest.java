package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import rhizome.testsupport.JavaSource;

/**
 * A timing side channel on the API bearer-token comparison (API family — see
 * docs/adversarial/spec.md).
 *
 * <p>{@code NodeApi.bearerMatches} already compares the presented bearer against the configured
 * token with {@code MessageDigest.isEqual} rather than {@code String.equals} — a naive
 * short-circuiting comparison would leak the correct prefix length through response timing to an
 * attacker who can send many requests over a shared, possibly-proxied loopback interface. That
 * defence was true the day it was written and had no proof: nothing failed the build if a future
 * edit swapped it back to {@code .equals(}, which reads as an equivalent, entirely plausible
 * refactor to someone who does not know why the line is shaped the way it is.
 *
 * <p>This is the structural half of the API-13 proof: it checks the vulnerable shape cannot land
 * unnoticed, and that {@code bearerMatches} stays the single place the token is ever compared. The
 * behavioural half — a real socket refusing every strict prefix of the token, so a comparison this
 * test could pass by accident (a truncating or length-only compare) is caught independently of the
 * source text — is {@code E2EApiAbuseTest#everyStrictPrefixOfTheBearerIsRefusedAndOnlyTheFullTokenPasses}.
 * A wall-clock timing measurement is deliberately asserted in neither: see {@code docs/adversarial/spec.md}
 * on why that would be a flaky-test generator rather than a security proof at this scale.
 */
class TokenComparisonAttackTest {

    private static Path nodeApiSource() {
        Path source = Path.of("src/main/java/rhizome/node/NodeApi.java");
        assertTrue(Files.exists(source), "NodeApi source must be readable from the module directory");
        return source;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The exact body of {@code bearerMatches}, found by name then matched brace-for-brace so a
     * nested {@code if} block cannot truncate the extraction early — a regex bounded by the next
     * {@code \}} at the wrong nesting level would silently scan only half the method.
     */
    private static String bearerMatchesBody(String strippedSource) {
        Matcher declaration = Pattern.compile("boolean\\s+bearerMatches\\s*\\([^)]*\\)\\s*\\{")
            .matcher(strippedSource);
        assertTrue(declaration.find(), "bearerMatches no longer exists, or no longer has this shape — "
            + "the API-13 proof has nothing to check");
        int bodyStart = declaration.end();
        int depth = 1;
        int i = bodyStart;
        while (i < strippedSource.length() && depth > 0) {
            char c = strippedSource.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            i++;
        }
        assertTrue(depth == 0, "bearerMatches' braces never balance — the extraction is unreliable");
        return strippedSource.substring(bodyStart, i - 1);
    }

    /**
     * API-13 — structural tripwire. Rewriting the constant-time comparison as {@code .equals(} or
     * {@code ==} reads as an equivalent, entirely plausible refactor with no functional test
     * failure — the request still returns 401 for a wrong bearer and 200 for the right one either
     * way. Only a check on the source shape itself catches the regression, which is exactly the
     * situation this class exists to close: a defence that was true in prose and unproven in code.
     */
    @Test
    void bearerComparisonStaysConstantTimeAndIsTheOnlyPlaceTheTokenIsCompared() {
        String stripped = JavaSource.withoutComments(read(nodeApiSource()));

        String body = bearerMatchesBody(stripped);
        assertTrue(body.contains("MessageDigest.isEqual"),
            "bearerMatches must compare the presented bearer with MessageDigest.isEqual, not a "
                + "short-circuiting comparison that leaks the correct prefix length through timing");
        assertFalse(body.contains(".equals("),
            "bearerMatches must not fall back to String.equals anywhere in its body");
        // A blanket "==" ban would also flag the legitimate `header == null` guard above it — only
        // a direct comparison of the token parameter itself is the vulnerable shape.
        assertFalse(Pattern.compile("\\btoken\\s*==|==\\s*token\\b").matcher(body).find(),
            "bearerMatches must not compare the token parameter's content with ==");

        // The parameter is named `token` inside bearerMatches (never `apiToken`), so scanning the
        // whole file for `apiToken` cannot see into the method body it just checked above — this
        // check is entirely about every OTHER site. The only legitimate uses of `apiToken` outside
        // that method are: threading it through as a parameter/argument, the null check that
        // decides whether the route is even gated, and the single call into bearerMatches itself.
        String withoutNullChecks = stripped
            .replaceAll("apiToken\\s*(==|!=)\\s*null", "")
            .replaceAll("null\\s*(==|!=)\\s*apiToken", "");
        Matcher suspicious = Pattern.compile(
                "apiToken\\s*(==|!=)|(==|!=)\\s*apiToken|apiToken\\s*\\.equals\\(|\\.equals\\(\\s*apiToken\\s*\\)")
            .matcher(withoutNullChecks);
        assertFalse(suspicious.find(),
            "apiToken is compared somewhere other than inside bearerMatches: \""
                + (suspicious.reset().find() ? suspicious.group() : "") + "\" — a second, "
                + "un-constant-time comparison site would defeat this proof entirely");

        // Excludes the declaration itself ("boolean bearerMatches(") so only invocations are counted.
        long callSites = Pattern.compile("(?<!boolean )bearerMatches\\(").matcher(stripped).results().count();
        assertEquals(1, callSites,
            "bearerMatches must be the single choke point for token comparison — a second call "
                + "site is a second place the discipline above has to be maintained by hand");
    }
}
