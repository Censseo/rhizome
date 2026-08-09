package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import rhizome.core.serialization.JsonSink;

/**
 * {@link ApiResponses#json(JsonSink)} must be indistinguishable, on the wire, from
 * {@link ApiResponses#json(org.json.JSONObject)} for anything a client inspects: the
 * {@code Content-Type} header (application/json; charset=utf-8), the
 * {@code X-Content-Type-Options: nosniff} header, and — since the whole point of the overload is
 * a zero-copy handoff of the sink's backing array — the exact body bytes written to the sink.
 */
class ApiResponsesJsonSinkTest {

    @Test
    void sinkOverloadMatchesLegacyHeaders() {
        JSONObject legacyBody = new JSONObject().put("status", "OK").put("height", 42);
        HttpResponse legacy = ApiResponses.json(legacyBody);

        JsonSink sink = JsonSink.create(64);
        sink.beginObject();
        sink.field(JsonSink.Key.of("status"), "OK");
        sink.field(JsonSink.Key.of("height"), 42);
        sink.endObject();
        HttpResponse viaSink = ApiResponses.json(sink);

        assertEquals(legacy.getHeader(HttpHeaders.CONTENT_TYPE), viaSink.getHeader(HttpHeaders.CONTENT_TYPE));
        assertEquals(legacy.getHeader(ApiResponses.H_XCTO), viaSink.getHeader(ApiResponses.H_XCTO));
        assertEquals("nosniff", viaSink.getHeader(ApiResponses.H_XCTO));
        assertNotNull(viaSink.getHeader(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void sinkOverloadBodyMatchesBytesWrittenToTheSink() {
        JsonSink sink = JsonSink.create(64);
        sink.beginObject();
        sink.field(JsonSink.Key.of("status"), "OK");
        sink.field(JsonSink.Key.of("height"), 42);
        sink.endObject();
        byte[] expected = sink.toByteArray();

        HttpResponse viaSink = ApiResponses.json(sink);

        assertArrayEquals(expected, viaSink.getBody().getArray());
    }

    @Test
    void sinkOverloadBodyIsBoundedToSinkLengthNotBackingArrayCapacity() {
        // Oversize the hint so the backing array has unused trailing capacity beyond length() —
        // the response body must stop at length(), not leak the unused tail of the array.
        JsonSink sink = JsonSink.create(4096);
        sink.beginObject();
        sink.field(JsonSink.Key.of("status"), "OK");
        sink.endObject();

        HttpResponse viaSink = ApiResponses.json(sink);

        assertEquals(sink.length(), viaSink.getBody().readRemaining());
        assertArrayEquals(sink.toByteArray(), viaSink.getBody().getArray());
    }
}
