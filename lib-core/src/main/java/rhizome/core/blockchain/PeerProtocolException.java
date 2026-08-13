package rhizome.core.blockchain;

/**
 * The port's malformed-data signal: the transport worked, but the peer served protocol data no
 * honest node would emit — an unparseable height/work/block, an out-of-range snapshot chunk
 * count, a header range that decodes to nothing. Distinct from
 * {@link PeerUnavailableException} (a transport outage, retried and never penalised): a
 * synchronizer maps this to a ban-score-earning verdict instead of shrugging it off
 * (audit F9).
 *
 * <p>The transport adapter throws it (the HTTP one through its own subclass, so call sites in
 * the net layer keep their names); the synchronizers and the sync round catch the type from
 * THIS package, so the ban signal is part of the port's vocabulary, not the adapter's.
 * Unchecked so it propagates through {@link PeerSource}, whose methods declare no checked
 * exceptions.
 */
public class PeerProtocolException extends RuntimeException {

    public PeerProtocolException(String message) {
        super(message);
    }

    public PeerProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
