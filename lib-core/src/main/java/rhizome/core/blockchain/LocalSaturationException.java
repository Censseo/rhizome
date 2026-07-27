package rhizome.core.blockchain;

/**
 * A LOCAL resource bound — not the peer — prevented a {@link PeerSource} exchange from
 * being attempted (e.g. the transport's bounded body-read worker pool was saturated, so
 * the request was rejected before any network I/O). Distinct from both a transport
 * failure and a protocol violation: it carries no information about the peer, so it must
 * never earn the peer ban score, an eviction, or a {@code PEER_INVALID} verdict. The
 * synchronizers surface it as {@code NO_CHANGE} (retried on a later round); any other
 * caller should treat it as retry-able backpressure.
 */
public final class LocalSaturationException extends RuntimeException {

    public LocalSaturationException(String message, Throwable cause) {
        super(message, cause);
    }
}
