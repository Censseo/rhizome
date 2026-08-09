package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

class JsonSinkTest {

    private static String asString(JsonSink sink) {
        return new String(sink.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void emptyObject() {
        JsonSink sink = JsonSink.create(16);
        sink.beginObject().endObject();
        assertEquals("{}", asString(sink));
    }

    @Test
    void objectWithOneField() {
        JsonSink sink = JsonSink.create(16);
        sink.beginObject();
        sink.field(Key.of("a"), 1L);
        sink.endObject();
        assertEquals("{\"a\":1}", asString(sink));
    }

    @Test
    void objectWithTwoFieldsHasExactlyOneComma() {
        JsonSink sink = JsonSink.create(32);
        sink.beginObject();
        sink.field(Key.of("a"), 1L);
        sink.field(Key.of("b"), 2L);
        sink.endObject();
        assertEquals("{\"a\":1,\"b\":2}", asString(sink));
    }

    @Test
    void nestedObjectsTwoLevelsDeep() {
        JsonSink sink = JsonSink.create(64);
        sink.beginObject();
        sink.name(Key.of("outer"));
        sink.beginObject();
        sink.name(Key.of("inner"));
        sink.beginObject();
        sink.field(Key.of("leaf"), 42L);
        sink.endObject();
        sink.endObject();
        sink.field(Key.of("sibling"), true);
        sink.endObject();
        assertEquals("{\"outer\":{\"inner\":{\"leaf\":42}},\"sibling\":true}", asString(sink));
    }

    @Test
    void arrayOfObjectsPlacesCommaBetweenElementsOnly() {
        JsonSink sink = JsonSink.create(64);
        sink.beginArray();
        sink.beginObject();
        sink.field(Key.of("a"), 1L);
        sink.endObject();
        sink.beginObject();
        sink.field(Key.of("b"), 2L);
        sink.endObject();
        sink.endArray();
        assertEquals("[{\"a\":1},{\"b\":2}]", asString(sink));
    }

    @Test
    void arrayOfBareScalarsIsCommaSeparated() {
        JsonSink sink = JsonSink.create(32);
        sink.beginArray();
        sink.value(1L);
        sink.value(2L);
        sink.value(3L);
        sink.endArray();
        assertEquals("[1,2,3]", asString(sink));
    }

    @Test
    void emptyArrayHasNoComma() {
        JsonSink sink = JsonSink.create(16);
        sink.beginArray().endArray();
        assertEquals("[]", asString(sink));
    }

    @Test
    void hexUpperAndHexLowerActuallyDifferOnSameInput() {
        byte[] bytes = {(byte) 0xAB, (byte) 0xCD, 0x0F};
        JsonSink upper = JsonSink.create(16);
        upper.hexUpper(bytes);
        JsonSink lower = JsonSink.create(16);
        lower.hexLower(bytes);

        assertEquals("\"ABCD0F\"", asString(upper));
        assertEquals("\"abcd0f\"", asString(lower));
        assertFalse(asString(upper).equals(asString(lower)));
    }

    @Test
    void hexKeyFusedConvenienceMatchesManualNameThenValue() {
        byte[] bytes = {0x01, (byte) 0xFF};
        JsonSink combined = JsonSink.create(32);
        combined.beginObject();
        combined.hexUpper(Key.of("id"), bytes);
        combined.endObject();

        JsonSink manual = JsonSink.create(32);
        manual.beginObject();
        manual.name(Key.of("id"));
        manual.hexUpper(bytes);
        manual.endObject();

        assertEquals(asString(manual), asString(combined));
        assertEquals("{\"id\":\"01FF\"}", asString(combined));
    }

    @Test
    void valueNullProducesBareNullLiteral() {
        JsonSink sink = JsonSink.create(8);
        sink.valueNull();
        assertEquals("null", asString(sink));
    }

    @Test
    void fieldNullProducesBareNullLiteralAsFieldValue() {
        JsonSink sink = JsonSink.create(16);
        sink.beginObject();
        sink.fieldNull(Key.of("x"));
        sink.endObject();
        assertEquals("{\"x\":null}", asString(sink));
    }

    @Test
    void negativeLongValues() {
        long[] values = {-1L, -42L, Long.MIN_VALUE, Long.MAX_VALUE, 0L, 1L};
        for (long v : values) {
            JsonSink sink = JsonSink.create(32);
            sink.value(v);
            assertEquals(Long.toString(v), asString(sink), "mismatch for " + v);
        }
    }

    @Test
    void valueLongAsStringQuotesTheDigits() {
        JsonSink sink = JsonSink.create(32);
        sink.value(Long.MIN_VALUE);
        String bare = asString(sink);

        JsonSink asStringSink = JsonSink.create(32);
        asStringSink.valueLongAsString(Long.MIN_VALUE);
        assertEquals("\"" + bare + "\"", asString(asStringSink));
    }

    @Test
    void unbalancedEndObjectOnFreshSinkThrows() {
        JsonSink sink = JsonSink.create(8);
        assertThrows(IllegalStateException.class, sink::endObject);
    }

    @Test
    void unbalancedEndArrayOnFreshSinkThrows() {
        JsonSink sink = JsonSink.create(8);
        assertThrows(IllegalStateException.class, sink::endArray);
    }

    @Test
    void extraEndAfterBalancedObjectThrows() {
        JsonSink sink = JsonSink.create(16);
        sink.beginObject().endObject();
        assertThrows(IllegalStateException.class, sink::endObject);
    }

    @Test
    void arrayAndLengthAccessorsExposeTheSameBytesAsToByteArray() {
        JsonSink sink = JsonSink.create(4); // small hint forces at least one grow
        sink.beginObject();
        sink.field(Key.of("a"), "hello world this forces growth past the initial hint");
        sink.endObject();

        byte[] copy = sink.toByteArray();
        byte[] backing = sink.array();
        int len = sink.length();
        assertTrue(backing.length >= len);
        assertArrayEquals(copy, Arrays.copyOf(backing, len));
    }

    @Test
    void booleanValues() {
        JsonSink t = JsonSink.create(8);
        t.value(true);
        assertEquals("true", asString(t));

        JsonSink f = JsonSink.create(8);
        f.value(false);
        assertEquals("false", asString(f));
    }

    @Test
    void intValueDelegatesToLongEncoding() {
        JsonSink sink = JsonSink.create(16);
        sink.value(Integer.MIN_VALUE);
        assertEquals(Integer.toString(Integer.MIN_VALUE), asString(sink));
    }

    @Test
    void keyRejectsNonAsciiAndEmptyNames() {
        assertThrows(IllegalArgumentException.class, () -> Key.of(""));
        assertThrows(IllegalArgumentException.class, () -> Key.of("naéme"));
    }
}
