package rhizome.node;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import io.activej.bytebuf.ByteBuf;
import io.activej.csp.queue.ChannelBuffer;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.eventloop.Eventloop;

import rhizome.core.blockchain.ContractProcessor.ContractLog;

/**
 * Push streaming of contract event logs over Server-Sent Events: the live
 * counterpart of the {@code /logs} height-cursor scan. Each subscriber gets a
 * bounded per-connection buffer; every applied block sends a heartbeat comment
 * (which doubles as a keepalive at the 5-second cadence) and one {@code data:}
 * event per contract log, with the block height as the SSE event id — so a
 * dropped client resumes exactly where it left off via {@code /logs?fromHeight}.
 *
 * <p>A subscriber that cannot keep up (its buffer saturates) is disconnected
 * rather than allowed to grow unbounded memory — the standard SSE contract:
 * reconnect and catch up from the cursor.
 *
 * <p>All subscriber state is confined to the HTTP event loop; {@link #publish}
 * may be called from any thread and marshals itself onto it.
 */
final class SseLogHub {

    /** Queued chunks per subscriber before it is considered too slow and dropped. */
    private static final int SUBSCRIBER_BUFFER = 256;

    private final Eventloop eventloop;
    private final int maxSubscribers;
    private final List<ChannelBuffer<ByteBuf>> subscribers = new ArrayList<>();

    SseLogHub(Eventloop eventloop, int maxSubscribers) {
        this.eventloop = eventloop;
        this.maxSubscribers = maxSubscribers;
    }

    /**
     * Registers a new SSE subscriber. Must be called on the event loop (servlet
     * handlers are). Returns {@code null} when the subscriber cap is reached.
     */
    ChannelSupplier<ByteBuf> subscribe() {
        if (subscribers.size() >= maxSubscribers) {
            return null;
        }
        ChannelBuffer<ByteBuf> buffer = new ChannelBuffer<>(SUBSCRIBER_BUFFER);
        subscribers.add(buffer);
        // An immediate comment so the client sees headers and first bytes at once.
        buffer.put(chunk(": connected\nretry: 2000\n\n"));
        return buffer.getSupplier();
    }

    /** Number of live subscribers (event-loop confined; for tests and metrics). */
    int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Publishes a block's logs to every subscriber. Callable from any thread;
     * the work runs on the event loop. Blocks without logs still produce a
     * heartbeat comment, so idle streams stay alive.
     */
    void publish(long height, List<ContractLog> logs) {
        eventloop.execute(() -> deliver(height, logs));
    }

    private void deliver(long height, List<ContractLog> logs) {
        if (subscribers.isEmpty()) {
            return;
        }
        String payload = format(height, logs);
        Iterator<ChannelBuffer<ByteBuf>> it = subscribers.iterator();
        while (it.hasNext()) {
            ChannelBuffer<ByteBuf> buffer = it.next();
            if (buffer.getException() != null) {
                it.remove(); // connection already closed by the client
                continue;
            }
            if (buffer.isSaturated()) {
                // Too slow to drain: disconnect rather than queue unboundedly. The
                // client reconnects and catches up via the /logs cursor.
                buffer.closeEx(new java.io.IOException("SSE subscriber too slow"));
                it.remove();
                continue;
            }
            buffer.put(chunk(payload));
        }
    }

    /** One heartbeat comment plus one SSE event per log, ids carrying the height. */
    private static String format(long height, List<ContractLog> logs) {
        StringBuilder sb = new StringBuilder(64 + logs.size() * 160);
        sb.append(": h ").append(height).append("\n\n");
        for (ContractLog log : logs) {
            sb.append("id: ").append(height).append('\n')
              .append("data: {\"height\":").append(height)
              .append(",\"contract\":\"").append(log.contract().toHexString())
              .append("\",\"topic\":\"").append(hex(log.topic()))
              .append("\",\"data\":\"").append(hex(log.data()))
              .append("\"}\n\n");
        }
        return sb.toString();
    }

    private static ByteBuf chunk(String s) {
        return ByteBuf.wrapForReading(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
