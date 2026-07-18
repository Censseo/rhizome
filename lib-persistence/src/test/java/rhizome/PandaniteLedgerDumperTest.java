package rhizome;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.GenesisLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.persistence.leveldb.LevelDBLedger;
import rhizome.persistence.tools.PandaniteLedgerDumper;
import rhizome.persistence.tools.PandaniteLedgerDumper.DumpResult;

class PandaniteLedgerDumperTest {

    @TempDir
    Path tempDir;

    private Path pandaniteLedgerDir;

    private static byte[] address(int seed) {
        byte[] a = new byte[PublicAddress.SIZE];
        a[0] = 0; // version byte, as Pandanite
        for (int i = 1; i < a.length; i++) {
            a[i] = (byte) (seed * 31 + i);
        }
        return a;
    }

    private static byte[] amountLittleEndian(long amount) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (amount >>> (8 * i));
        }
        return b;
    }

    @BeforeEach
    void setUp() throws IOException {
        pandaniteLedgerDir = tempDir.resolve("pandanite-ledger");
        Options options = new Options();
        options.createIfMissing(true);
        DB db = factory.open(pandaniteLedgerDir.toFile(), options);
        try {
            db.put(address(1), amountLittleEndian(500_000L));
            db.put(address(2), amountLittleEndian(1L));
            db.put(address(3), amountLittleEndian(999_999_999L));
            // A malformed entry that must be skipped (wrong value size).
            db.put(address(4), new byte[]{1, 2, 3});
            // A malformed entry with a non-address key length.
            db.put(new byte[]{9, 9, 9}, amountLittleEndian(42L));
        } finally {
            db.close();
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        FileUtils.deleteDirectory(pandaniteLedgerDir.toFile());
    }

    @Test
    void dumpsWalletBalancesAndSkipsMalformed() throws IOException {
        DumpResult result = PandaniteLedgerDumper.dump(pandaniteLedgerDir, Set.of(), "pandanite", 536000, 1);

        assertEquals(3, result.snapshot().size());
        assertEquals(2, result.malformedEntries());
        assertEquals(0, result.excludedWallets());
        assertEquals(500_000L, result.snapshot().balances().get(PublicAddress.of(address(1))).amount());
        assertEquals(999_999_999L, result.snapshot().balances().get(PublicAddress.of(address(3))).amount());
    }

    @Test
    void excludesInflatedWallets() throws IOException {
        String excludedHex = PublicAddress.of(address(2)).toHexString().toUpperCase();
        DumpResult result = PandaniteLedgerDumper.dump(
            pandaniteLedgerDir, Set.of(excludedHex), "pandanite", 536000, 1);

        assertEquals(2, result.snapshot().size());
        assertEquals(1, result.excludedWallets());
        assertFalse(result.snapshot().balances().containsKey(PublicAddress.of(address(2))));
    }

    @Test
    void dumpedSnapshotSeedsGenesisLedger() throws IOException {
        DumpResult result = PandaniteLedgerDumper.dump(pandaniteLedgerDir, Set.of(), "pandanite", 536000, 1);
        LedgerSnapshot snapshot = result.snapshot();

        LevelDBLedger genesisLedger = new LevelDBLedger(tempDir.resolve("genesis-ledger").toString());
        try {
            int seeded = GenesisLedger.seed(genesisLedger, snapshot);

            assertEquals(3, seeded);
            assertTrue(genesisLedger.hasWallet(PublicAddress.of(address(1))));
            assertEquals(500_000L,
                genesisLedger.getWalletValue(PublicAddress.of(address(1))).amount());
            assertEquals(999_999_999L,
                genesisLedger.getWalletValue(PublicAddress.of(address(3))).amount());
        } finally {
            genesisLedger.closeDB();
            genesisLedger.deleteDB();
        }
    }

    @Test
    void decodesLittleEndianAmount() {
        assertEquals(500_000L,
            PandaniteLedgerDumper.decodeAmountLittleEndian(amountLittleEndian(500_000L)));
    }
}
