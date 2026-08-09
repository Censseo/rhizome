package rhizome.core.serialization;

import java.util.Arrays;

/**
 * Hand-written, allocation-light JSON writer: a growable {@code byte[]} buffer that response
 * serialization writes directly into, instead of building an {@code org.json} tree, rendering it
 * to a {@code String}, then re-encoding that {@code String} to UTF-8. Like
 * {@link BinarySerializable}, this is written by hand rather than delegated to a
 * reflective/codegen encoder: no reflection, no runtime bytecode generation — a GraalVM
 * native-image prerequisite — and it is the fast path measured for the node's hot JSON
 * endpoints.
 *
 * <p><b>Comma bookkeeping.</b> A single {@code needComma} flag (no stack of open scopes) is
 * enough at any nesting depth: {@link #beginObject()}/{@link #beginArray()} check it (a comma is
 * owed if this container is itself a later sibling value) and then clear it for the container's
 * own first child; every completed value — a scalar, or a nested {@link #endObject()}/
 * {@link #endArray()} closing sibling — sets it back to {@code true} so the next value or
 * {@link #name(Key)} knows to emit a separator first. {@link #name(Key)} performs the same
 * comma check before writing the key, then leaves {@code needComma} clear so the value that
 * follows does not also insert one.
 *
 * <p>The parallel {@code depth} counter is <em>not</em> a scope stack — it does not remember
 * whether an open scope was an object or an array, so a mismatched {@code beginObject()} /
 * {@link #endArray()} pair is not caught. It exists purely as a dev-time bug guard: an
 * {@link #endObject()}/{@link #endArray()} with no matching open scope throws
 * {@link IllegalStateException} rather than silently emitting an unbalanced bracket.
 *
 * <p><b>Conditional fields</b> are plain {@code if (condition) sink.field(K_X, value)} at call
 * sites — this class deliberately has no {@code optionalField(...)} helper, because hiding the
 * condition would hide exactly the fact (present vs. absent) that some optional header fields
 * fold into the block hash on.
 *
 * <p><b>Hex has no default case.</b> The node emits both upper- and lowercase hex, sometimes in
 * the same JSON object, so {@link #hexUpper(byte[])} and {@link #hexLower(byte[])} are separate,
 * explicitly named methods rather than one method with an implied default — a default would be a
 * silent wrong-case regression waiting to happen at some call site.
 */
public final class JsonSink {

    private static final byte[] HEX_UPPER = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };
    private static final byte[] HEX_LOWER = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private static final byte[] TRUE_BYTES = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE_BYTES = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] NULL_BYTES = {'n', 'u', 'l', 'l'};

    /** A pre-encoded {@code "name":} — quotes and colon included — built once per call site. */
    public record Key(byte[] encoded) {

        /**
         * Builds the {@code "name":} encoding for an object field name. Field names are
         * compile-time constants in call-site code (never attacker-controlled), so the only
         * check needed is that they are plain ASCII identifiers — this is a dev-time guard
         * against a typo'd key, not an escaping routine.
         */
        public static Key of(String name) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("key name must not be empty");
            }
            for (int i = 0; i < name.length(); i++) {
                if (name.charAt(i) > 0x7F) {
                    throw new IllegalArgumentException("key name must be ASCII: " + name);
                }
            }
            byte[] encoded = new byte[name.length() + 3];
            encoded[0] = '"';
            for (int i = 0; i < name.length(); i++) {
                encoded[i + 1] = (byte) name.charAt(i);
            }
            encoded[name.length() + 1] = '"';
            encoded[name.length() + 2] = ':';
            return new Key(encoded);
        }
    }

    private byte[] buf;
    private int length;
    private boolean needComma;
    private int depth;

    private JsonSink(int sizeHint) {
        this.buf = new byte[Math.max(sizeHint, 16)];
    }

    /** Allocates a new sink sized from {@code sizeHint}. One sink per response — see the class
     *  javadoc on {@link #reset(int)} for why buffers are never shared across responses. */
    public static JsonSink create(int sizeHint) {
        return new JsonSink(sizeHint);
    }

    /**
     * Reinitializes this sink for reuse (tests and benchmarks only). Production call sites
     * allocate one sink per response with {@link #create(int)} instead: ActiveJ writes the
     * response body to the socket after the handler returns, so a buffer recycled across
     * responses could be overwritten while a previous body is still queued for send.
     */
    public JsonSink reset(int sizeHint) {
        this.buf = new byte[Math.max(sizeHint, 16)];
        this.length = 0;
        this.needComma = false;
        this.depth = 0;
        return this;
    }

    // -- structure ----------------------------------------------------------------------------

    public JsonSink beginObject() {
        beforeValue();
        writeByte((byte) '{');
        depth++;
        return this;
    }

    public JsonSink endObject() {
        closeScope((byte) '}');
        return this;
    }

    public JsonSink beginArray() {
        beforeValue();
        writeByte((byte) '[');
        depth++;
        return this;
    }

    public JsonSink endArray() {
        closeScope((byte) ']');
        return this;
    }

    private void closeScope(byte closingBracket) {
        if (depth == 0) {
            throw new IllegalStateException("unbalanced JsonSink: end without matching begin");
        }
        depth--;
        writeByte(closingBracket);
        needComma = true;
    }

    public JsonSink name(Key key) {
        beforeValue();
        byte[] encoded = key.encoded();
        ensureCapacity(encoded.length);
        System.arraycopy(encoded, 0, buf, length, encoded.length);
        length += encoded.length;
        return this;
    }

    /** Emits the separator comma iff one is owed, then clears the flag for this value's own
     *  first child (if it turns out to be a container). */
    private void beforeValue() {
        if (needComma) {
            writeByte((byte) ',');
        }
        needComma = false;
    }

    // -- scalars --------------------------------------------------------------------------------

    public JsonSink value(long v) {
        beforeValue();
        writeLongDigits(v);
        needComma = true;
        return this;
    }

    public JsonSink value(int v) {
        return value((long) v);
    }

    public JsonSink value(boolean v) {
        beforeValue();
        writeBytes(v ? TRUE_BYTES : FALSE_BYTES);
        needComma = true;
        return this;
    }

    public JsonSink valueNull() {
        beforeValue();
        writeBytes(NULL_BYTES);
        needComma = true;
        return this;
    }

    /** Writes {@code v}'s decimal digits as a quoted JSON string — the shape {@code totalWork}
     *  and block/transaction {@code timestamp} need, since both can exceed 2^53 and the
     *  dashboard reads them with JS {@code BigInt(...)}. */
    public JsonSink valueLongAsString(long v) {
        beforeValue();
        writeByte((byte) '"');
        writeLongDigits(v);
        writeByte((byte) '"');
        needComma = true;
        return this;
    }

    /** Full escaping, byte-for-byte matching {@code org.json.JSONObject.quote(String)} — see
     *  {@code JsonSinkEscapingTest} for the oracle comparison this mirrors. */
    public JsonSink value(String s) {
        beforeValue();
        writeQuotedString(s);
        needComma = true;
        return this;
    }

    // -- hex ------------------------------------------------------------------------------------

    public JsonSink hexUpper(byte[] bytes) {
        beforeValue();
        writeHex(bytes, HEX_UPPER);
        needComma = true;
        return this;
    }

    public JsonSink hexLower(byte[] bytes) {
        beforeValue();
        writeHex(bytes, HEX_LOWER);
        needComma = true;
        return this;
    }

    // -- Key-fused field convenience -------------------------------------------------------------

    public JsonSink field(Key key, long v) {
        name(key);
        return value(v);
    }

    public JsonSink field(Key key, int v) {
        name(key);
        return value(v);
    }

    public JsonSink field(Key key, boolean v) {
        name(key);
        return value(v);
    }

    public JsonSink field(Key key, String v) {
        name(key);
        return value(v);
    }

    public JsonSink fieldNull(Key key) {
        name(key);
        return valueNull();
    }

    public JsonSink fieldLongAsString(Key key, long v) {
        name(key);
        return valueLongAsString(v);
    }

    public JsonSink hexUpper(Key key, byte[] bytes) {
        name(key);
        return hexUpper(bytes);
    }

    public JsonSink hexLower(Key key, byte[] bytes) {
        name(key);
        return hexLower(bytes);
    }

    // -- output -----------------------------------------------------------------------------------

    /** The backing buffer, valid for {@code [0, length())} — no copy. */
    public byte[] array() {
        return buf;
    }

    public int length() {
        return length;
    }

    /** Exact-size copy of the written bytes. Test convenience only — production call sites use
     *  {@link #array()}/{@link #length()} to avoid the copy. */
    public byte[] toByteArray() {
        return Arrays.copyOf(buf, length);
    }

    // -- string escaping ----------------------------------------------------------------------

    private void writeQuotedString(String s) {
        writeByte((byte) '"');
        int len = s.length();
        char prev = 0;
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                case '"':
                    ensureCapacity(2);
                    buf[length++] = '\\';
                    buf[length++] = (byte) c;
                    i++;
                    break;
                case '/':
                    if (prev == '<') {
                        ensureCapacity(2);
                        buf[length++] = '\\';
                        buf[length++] = '/';
                    } else {
                        writeByte((byte) '/');
                    }
                    i++;
                    break;
                case '\b':
                    writeEscape((byte) 'b');
                    i++;
                    break;
                case '\t':
                    writeEscape((byte) 't');
                    i++;
                    break;
                case '\n':
                    writeEscape((byte) 'n');
                    i++;
                    break;
                case '\f':
                    writeEscape((byte) 'f');
                    i++;
                    break;
                case '\r':
                    writeEscape((byte) 'r');
                    i++;
                    break;
                default:
                    if (c < 0x0020 || (c >= 0x0080 && c < 0x00A0) || (c >= 0x2000 && c < 0x2100)) {
                        writeUnicodeEscape(c);
                        i++;
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                            writeUtf8CodePoint(Character.toCodePoint(c, s.charAt(i + 1)));
                            prev = s.charAt(i + 1);
                            i += 2;
                            continue;
                        }
                        // Unpaired surrogate: matches String.getBytes(UTF_8)'s single-byte
                        // '?' substitution, NOT a U+FFFD replacement sequence.
                        writeByte((byte) 0x3F);
                        i++;
                    } else if (Character.isLowSurrogate(c)) {
                        writeByte((byte) 0x3F);
                        i++;
                    } else {
                        writeUtf8CodePoint(c);
                        i++;
                    }
            }
            prev = c;
        }
        writeByte((byte) '"');
    }

    private void writeEscape(byte letter) {
        ensureCapacity(2);
        buf[length++] = '\\';
        buf[length++] = letter;
    }

    private void writeUnicodeEscape(char c) {
        ensureCapacity(6);
        buf[length++] = '\\';
        buf[length++] = 'u';
        buf[length++] = HEX_LOWER[(c >>> 12) & 0xF];
        buf[length++] = HEX_LOWER[(c >>> 8) & 0xF];
        buf[length++] = HEX_LOWER[(c >>> 4) & 0xF];
        buf[length++] = HEX_LOWER[c & 0xF];
    }

    /** Standard UTF-8 encoding of one code point — {@code cp} is either a non-surrogate BMP
     *  {@code char} (1-3 bytes) or a combined surrogate-pair code point up to 0x10FFFF
     *  (4 bytes). */
    private void writeUtf8CodePoint(int cp) {
        if (cp <= 0x7F) {
            writeByte((byte) cp);
        } else if (cp <= 0x7FF) {
            ensureCapacity(2);
            buf[length++] = (byte) (0xC0 | (cp >>> 6));
            buf[length++] = (byte) (0x80 | (cp & 0x3F));
        } else if (cp <= 0xFFFF) {
            ensureCapacity(3);
            buf[length++] = (byte) (0xE0 | (cp >>> 12));
            buf[length++] = (byte) (0x80 | ((cp >>> 6) & 0x3F));
            buf[length++] = (byte) (0x80 | (cp & 0x3F));
        } else {
            ensureCapacity(4);
            buf[length++] = (byte) (0xF0 | (cp >>> 18));
            buf[length++] = (byte) (0x80 | ((cp >>> 12) & 0x3F));
            buf[length++] = (byte) (0x80 | ((cp >>> 6) & 0x3F));
            buf[length++] = (byte) (0x80 | (cp & 0x3F));
        }
    }

    // -- numbers, hex, growth ---------------------------------------------------------------------

    private void writeLongDigits(long v) {
        boolean neg = v < 0;
        // For v == Long.MIN_VALUE, "-v" overflows back to the same bit pattern, which is
        // exactly Long.MIN_VALUE's magnitude (2^63) reinterpreted as unsigned - so the
        // unsigned div/rem below extracts the correct digits without a separate special case.
        long mag = neg ? -v : v;
        byte[] digits = new byte[20]; // max digits in an unsigned 64-bit value
        int idx = digits.length;
        do {
            digits[--idx] = (byte) ('0' + (int) Long.remainderUnsigned(mag, 10));
            mag = Long.divideUnsigned(mag, 10);
        } while (mag != 0);
        int numDigits = digits.length - idx;
        ensureCapacity(numDigits + (neg ? 1 : 0));
        if (neg) {
            buf[length++] = '-';
        }
        System.arraycopy(digits, idx, buf, length, numDigits);
        length += numDigits;
    }

    private void writeHex(byte[] bytes, byte[] table) {
        ensureCapacity(bytes.length * 2 + 2);
        buf[length++] = '"';
        for (byte b : bytes) {
            buf[length++] = table[(b >>> 4) & 0xF];
            buf[length++] = table[b & 0xF];
        }
        buf[length++] = '"';
    }

    private void writeByte(byte b) {
        ensureCapacity(1);
        buf[length++] = b;
    }

    private void writeBytes(byte[] bytes) {
        ensureCapacity(bytes.length);
        System.arraycopy(bytes, 0, buf, length, bytes.length);
        length += bytes.length;
    }

    private void ensureCapacity(int additional) {
        long required = (long) length + additional;
        if (required > buf.length) {
            long newCap = Math.max(buf.length, 1);
            while (newCap < required) {
                newCap *= 2;
            }
            buf = Arrays.copyOf(buf, (int) newCap);
        }
    }
}
