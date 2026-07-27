package rhizome.net;

import java.io.IOException;

/**
 * The LOCAL body-read worker pool was saturated, so the exchange was rejected BEFORE any
 * network I/O against the peer took place. This says nothing about the peer's health or
 * honesty: it must never be counted as a peer failure — no eviction, no ban score, no
 * penalty. It is a local, retry-able condition (backpressure), surfaced distinctly from a
 * genuine transport failure precisely so callers can treat it that way.
 */
public final class BodyReadSaturatedException extends IOException {

    public BodyReadSaturatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
