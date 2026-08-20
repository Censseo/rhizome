package rhizome.testsupport;

/**
 * Reads Java source the way the tests in this module that scan it for structural properties need
 * to — with comments removed but string and char literals copied verbatim.
 *
 * <p>Extracted out of {@code RoutePolicyCompletenessTest}, whose quote-aware stripper is the one
 * other adversarial-style source scan in this module ({@code TokenComparisonAttackTest}) needs
 * too. A regex-based stripper (the simpler one {@code AdversarialProtocolTest} uses in lib-core,
 * where the scanned files are test sources without path-literal-bearing strings) is not safe here:
 * {@code NodeApi.java} contains string and path literals that a naive comment-delimiter regex
 * could misparse. Six copies of a hand-rolled scanner drifting apart is exactly the failure mode
 * {@code AdversarialChain}'s javadoc warns about — this exists so there is one.
 */
public final class JavaSource {

    private JavaSource() {
    }

    /**
     * The source with {@code //}, {@code /* *}{@code /} and javadoc comments removed — string and
     * char literals (including one containing a quote via a backslash escape) are copied verbatim,
     * so a structural pattern cannot be satisfied by a mention in prose or a commented-out line,
     * and a path- or method-looking literal inside a string cannot be misread as a comment
     * delimiter.
     */
    public static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = source.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
