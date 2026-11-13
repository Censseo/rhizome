package rhizome.net;

import org.json.JSONObject;

/**
 * What a candidate peer's {@code /info} says about the chain it is on. Every field is nullable:
 * absent means UNKNOWN (an older node that predates the field), never a mismatch — pre-launch
 * every node runs this build, so absence is tolerated rather than relied on.
 */
public record PeerIdentity(Integer chainId, String genesisHash, String rulesetVersion) {

    /** Reads the identity fields from a {@code /info} body (depth-bounded); wrong-typed fields read as absent. */
    public static PeerIdentity fromInfoBody(String body) {
        JSONObject info = PeerJson.parseObject(body);
        return new PeerIdentity(
            info.opt("chainId") instanceof Number n ? Integer.valueOf(n.intValue()) : null,
            info.opt("genesisHash") instanceof String s ? s : null,
            info.opt("rulesetVersion") instanceof String s ? s : null);
    }
}
