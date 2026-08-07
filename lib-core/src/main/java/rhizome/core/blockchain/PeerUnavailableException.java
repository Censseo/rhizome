package rhizome.core.blockchain;

/**
 * A transport-level failure talking to a {@link PeerSource} — the request did not reach
 * the peer, or its response was not a well-formed protocol payload's worth of bytes
 * (connection refused, timeout, an HTTP status other than 200). Distinct from a protocol
 * violation ({@code PEER_INVALID}): the peer gave us no data to judge, so it must never
 * earn ban score or an eviction. The synchronizers re-throw it out of the header/body
 * phases so the sync round logs it at DEBUG and moves on; retrying the peer on a later
 * round is the whole remedy (testnet campaign S5: a node mid-reorg serving a transiently
 * truncated chain must read as "unavailable, retry", not as "invalid, ban").
 *
 * <p>Lives in lib-core so the synchronizers can distinguish it from other runtime
 * failures without depending on a transport implementation; lib-net's
 * {@code HttpPeerSource.PeerUnavailableException} extends this class.
 */
public class PeerUnavailableException extends RuntimeException {

    public PeerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
