package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionAmount;

class LedgerSnapshotTest {

    private static final String ADDR_A = "0011223344556677889900112233445566778899AA";
    private static final String ADDR_B = "00AABBCCDDEEFF00112233445566778899AABBCCDD";

    private static PublicAddress addr(String shortHex) {
        // pad/truncate to a valid 50-hex (25-byte) address
        StringBuilder sb = new StringBuilder(shortHex);
        while (sb.length() < 50) sb.append('0');
        return PublicAddress.of(sb.substring(0, 50));
    }

    @Test
    void jsonRoundTrip() {
        LedgerSnapshot snapshot = new LedgerSnapshot("pandanite", 536000, 1);
        snapshot.put(addr(ADDR_A), new TransactionAmount(500_000L));
        snapshot.put(addr(ADDR_B), new TransactionAmount(1L));

        JSONObject json = snapshot.toJson();
        LedgerSnapshot restored = LedgerSnapshot.fromJson(json);

        assertEquals(2, restored.size());
        assertEquals("pandanite", restored.source());
        assertEquals(536000, restored.sourceHeight());
        assertEquals(1, restored.chainId());
        assertEquals(500_000L, restored.balances().get(addr(ADDR_A)).amount());
        assertEquals(1L, restored.balances().get(addr(ADDR_B)).amount());
    }

    @Test
    void handlesUnsignedAmountsAboveLongMax() {
        // A uint64 value beyond Long.MAX_VALUE (as could exist from the C++ overflow bug)
        long unsignedValue = 0xFFFFFFFFFFFFFFFFL; // -1 signed, 18446744073709551615 unsigned
        LedgerSnapshot snapshot = new LedgerSnapshot("pandanite", 0, 1);
        snapshot.put(addr(ADDR_A), new TransactionAmount(unsignedValue));

        JSONObject json = snapshot.toJson();
        assertTrue(json.getJSONObject("balances").getString(addr(ADDR_A).toHexString())
            .equals("18446744073709551615"));

        LedgerSnapshot restored = LedgerSnapshot.fromJson(json);
        assertEquals(unsignedValue, restored.balances().get(addr(ADDR_A)).amount());
    }

    @Test
    void totalSupplySums() {
        LedgerSnapshot snapshot = new LedgerSnapshot("pandanite", 0, 1);
        snapshot.put(addr(ADDR_A), new TransactionAmount(500_000L));
        snapshot.put(addr(ADDR_B), new TransactionAmount(250_000L));
        assertEquals(750_000L, snapshot.totalSupply());
    }
}
