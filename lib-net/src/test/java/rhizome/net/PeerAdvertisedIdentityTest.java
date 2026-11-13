package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PeerAdvertisedIdentityTest {

    @Test
    void readsTheIdentityFieldsFromInfo() {
        PeerIdentity id = PeerIdentity.fromInfoBody(
            "{\"chainId\":4,\"genesisHash\":\"AB\",\"rulesetVersion\":\"CD\",\"height\":7}");
        assertEquals(new PeerIdentity(4, "AB", "CD"), id);
    }

    @Test
    void absentOrWrongTypedFieldsReadAsUnknownNotAsMismatch() {
        assertEquals(new PeerIdentity(null, null, null),
            PeerIdentity.fromInfoBody("{\"height\":7}"));
        PeerIdentity wrong = PeerIdentity.fromInfoBody("{\"chainId\":\"4\",\"genesisHash\":5}");
        assertNull(wrong.chainId());
        assertNull(wrong.genesisHash());
    }
}
