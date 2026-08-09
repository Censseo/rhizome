package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import rhizome.core.serialization.JsonSink;

/**
 * {@code JsonSink.value(String)} escaping must match {@code org.json.JSONObject.quote(String)}
 * byte-for-byte: {@code org.json} stays the parse-side dependency, but the node's response
 * bodies switch to {@link JsonSink} for writing, so any divergence here is a wire-format
 * regression, not a cosmetic one. {@code JSONObject.quote(String)} is {@code public static},
 * so it is used directly as the test oracle rather than re-derived from the design doc's summary
 * of its rules — see {@code org.json.JSONObject#quote(String, java.io.Writer)} for the source of
 * truth this mirrors:
 *
 * <ul>
 *   <li>empty string -&gt; the literal two-byte output {@code ""}
 *   <li>{@code \} and {@code "} are each backslash-escaped
 *   <li>{@code /} is backslash-escaped only when the immediately preceding character was
 *       {@code <}
 *   <li>{@code \b \t \n \f \r} use their named short escapes
 *   <li>any other char {@code < 0x20}, or in {@code [0x0080, 0x00A0)}, or in
 *       {@code [0x2000, 0x2100)}, is a {@code \\uXXXX} escape with lowercase hex digits
 *   <li>{@code 0x7F} (DEL) is emitted raw, unescaped
 *   <li>everything else is UTF-8 encoded normally, except an unpaired UTF-16 surrogate becomes
 *       the single byte {@code 0x3F} ({@code '?'}) — matching {@code String.getBytes(UTF_8)},
 *       which does NOT produce a U+FFFD replacement sequence for a lone surrogate
 * </ul>
 */
class JsonSinkEscapingTest {

    private static final char[] ALPHABET = {'<', '/', '"', 'a'};

    /** The one assertion this whole test file exists to run, over every corpus string: the
     *  bytes {@link JsonSink#value(String)} writes for {@code s} must equal
     *  {@code JSONObject.quote(s)} encoded as UTF-8. */
    private static void assertMatchesOracle(String s) {
        byte[] expected = JSONObject.quote(s).getBytes(StandardCharsets.UTF_8);
        JsonSink sink = JsonSink.create(64);
        sink.value(s);
        byte[] actual = sink.toByteArray();
        assertArrayEquals(expected, actual, "mismatch for " + describe(s));
    }

    private static String describe(String s) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Integer.toHexString(s.charAt(i)));
        }
        return sb.append(']').toString();
    }

    @Test
    void emptyAndLoneQuoteAndBackslash() {
        assertMatchesOracle("");
        assertMatchesOracle("\"");
        assertMatchesOracle("\\");
    }

    @Test
    void scriptTagSlashCombinationsAndFourCharAlphabetProduct() {
        assertMatchesOracle("</script>");
        assertMatchesOracle("<");
        assertMatchesOracle("/");
        assertMatchesOracle("a/b");
        for (String s : alphabetCombosUpToLength(4)) {
            assertMatchesOracle(s);
        }
    }

    /** All 4^1 + 4^2 + 4^3 + 4^4 = 340 combinations of {@code {<, /, ", a}}, so the fast/slow
     *  path boundary around {@code <} is exercised at every position. */
    private static List<String> alphabetCombosUpToLength(int maxLen) {
        List<String> result = new ArrayList<>();
        for (int len = 1; len <= maxLen; len++) {
            int total = 1;
            for (int i = 0; i < len; i++) {
                total *= ALPHABET.length;
            }
            for (int n = 0; n < total; n++) {
                char[] chars = new char[len];
                int x = n;
                for (int i = 0; i < len; i++) {
                    chars[i] = ALPHABET[x % ALPHABET.length];
                    x /= ALPHABET.length;
                }
                result.add(new String(chars));
            }
        }
        return result;
    }

    @Test
    void everyLatin1CharWrapped() {
        for (int c = 0x0000; c <= 0x00FF; c++) {
            assertMatchesOracle("a" + (char) c + "b");
        }
    }

    @Test
    void everyGeneralPunctuationRangeCharWrapped() {
        for (int c = 0x2000; c <= 0x2100; c++) {
            assertMatchesOracle("a" + (char) c + "b");
        }
    }

    @Test
    void boundaryCharsWrapped() {
        int[] boundaries = {0x9F, 0xA0, 0x1FFF, 0x2000, 0x20FF, 0x2100, 0x2101};
        for (int c : boundaries) {
            assertMatchesOracle("a" + (char) c + "b");
        }
    }

    @Test
    void multiByteSequences() {
        assertMatchesOracle("é");                          // e-with-acute, 2-byte UTF-8
        assertMatchesOracle("€");                           // euro sign, 3-byte UTF-8
        assertMatchesOracle(new String(Character.toChars(0x1D11E))); // musical symbol G clef, 4-byte UTF-8
    }

    @Test
    void loneSurrogatesEmbeddedMidString() {
        assertMatchesOracle("a" + (char) 0xD83D + "b"); // lone high surrogate
        assertMatchesOracle("a" + (char) 0xDC00 + "b"); // lone low surrogate
    }

    @Test
    void fuzzAgainstOracleWithFixedSeed() {
        Random random = new Random(42); // fixed seed: reproducible, not true random
        for (int iter = 0; iter < 10_000; iter++) {
            int len = random.nextInt(21); // 0..20 chars
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                char c = switch (random.nextInt(5)) {
                    case 0 -> (char) (0x20 + random.nextInt(0x7F - 0x20)); // ASCII printable
                    case 1 -> (char) random.nextInt(0x20);                 // control chars
                    case 2 -> (char) (0x80 + random.nextInt(0x20));        // 0x80-0x9F
                    case 3 -> (char) (0x2000 + random.nextInt(0x100));     // 0x2000-0x20FF
                    default -> (char) (0xD800 + random.nextInt(0x800));   // surrogate range
                };
                sb.append(c);
            }
            assertMatchesOracle(sb.toString());
        }
    }
}
