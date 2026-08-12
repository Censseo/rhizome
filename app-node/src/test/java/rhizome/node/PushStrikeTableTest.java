package rhizome.node;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import rhizome.core.mempool.ExecutionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The push-fault window, on a clock the test controls.
 *
 * <p>The window is a minute long and lived inside {@code NodeService} on
 * {@code System.currentTimeMillis}, so the two properties that define it — a shed EXPIRES, and a
 * fresh window starts from zero rather than from the old count — could only be asserted by
 * sleeping through a minute, and therefore never were. Everything that was covered
 * ({@code NodeApiTest}) pushed real signed transactions through the servlet to move the counter,
 * which is why the bounded-table case needed 8192 of them.
 */
class PushStrikeTableTest {

    /** A clock the test advances by hand. */
    private static final class Ticker {
        final AtomicLong now = new AtomicLong(1_000_000L);
        long get() { return now.get(); }
        void advance(long ms) { now.addAndGet(ms); }
    }

    @Test
    void theShedAppliesOnlyPastTheLimitAndExpiresWithTheWindow() {
        Ticker clock = new Ticker();
        PushStrikeTable table = new PushStrikeTable(clock::get);

        for (int i = 0; i < PushStrikeTable.STRIKE_LIMIT; i++) {
            assertFalse(table.isShed("abuser"), "under the limit, strike " + i);
            table.note("abuser");
        }
        assertTrue(table.isShed("abuser"), "at the limit the client is shed");

        // Mid-window the shed holds, even without new faults.
        clock.advance(PushStrikeTable.WINDOW_MS - 1);
        assertTrue(table.isShed("abuser"));

        // Past the window it lifts, and the next fault counts as the first of a fresh window —
        // not as number 21, which would make one stale burst a permanent shed.
        clock.advance(1);
        assertFalse(table.isShed("abuser"), "the window rolled: the shed must lift");
        assertEquals(0, table.count("abuser"));
        table.note("abuser");
        assertEquals(1, table.count("abuser"), "a rolled window restarts from zero");
        assertFalse(table.isShed("abuser"));
    }

    @Test
    void aFullTableEvictsTheStalestWindowRatherThanFailingOpen() {
        // Overflow used to go to a bucket the shed never read, so 8192 live keys exempted every
        // new IP (audit I-2). The newcomer must be tracked, and the victim must be the stalest
        // window — which is another offender, since honest clients accrue no strikes at all.
        Ticker clock = new Ticker();
        PushStrikeTable table = new PushStrikeTable(clock::get);

        table.note("oldest");                       // window starts here
        clock.advance(1_000);
        for (int i = 1; i < PushStrikeTable.MAX_KEYS; i++) {
            table.note("filler-" + i);              // all newer, none expired
        }
        assertEquals(PushStrikeTable.MAX_KEYS, table.size());

        table.note("latecomer");
        assertEquals(PushStrikeTable.MAX_KEYS, table.size(), "the bound holds");
        assertEquals(1, table.count("latecomer"), "the client offending now is always tracked");
        assertEquals(0, table.count("oldest"), "the stalest window is the one evicted");
        assertEquals(1, table.count("filler-1"), "a newer window survives");

        for (int i = 1; i < PushStrikeTable.STRIKE_LIMIT; i++) {
            table.note("latecomer");
        }
        assertTrue(table.isShed("latecomer"), "filling the table must not exempt the next abuser");
    }

    @Test
    void expiredWindowsAreReclaimedBeforeAnythingIsEvicted() {
        // The cheap path first: when the table is full but every entry is stale, the sweep alone
        // makes room, so no live offender is dropped to admit a new one.
        Ticker clock = new Ticker();
        PushStrikeTable table = new PushStrikeTable(clock::get);
        for (int i = 0; i < PushStrikeTable.MAX_KEYS; i++) {
            table.note("stale-" + i);
        }
        clock.advance(PushStrikeTable.WINDOW_MS);
        table.note("fresh");
        assertEquals(1, table.size(), "a full table of expired windows collapses to the new entry");
        assertEquals(1, table.count("fresh"));
    }

    @Test
    void onlyProvableJunkCounts() {
        // The whitelist is the collateral-damage guard: honest gossip produces non-success
        // statuses constantly (a rebroadcast we already have, an uncle our pool has not seen, a
        // nonce consumed meanwhile), and striking those would throttle honest peers.
        assertTrue(PushStrikeTable.isFault(ExecutionStatus.INVALID_SIGNATURE));
        assertTrue(PushStrikeTable.isFault(ExecutionStatus.INVALID_CHAIN_ID));
        assertTrue(PushStrikeTable.isFault(ExecutionStatus.INVALID_NONCE), "failed PoW");

        assertFalse(PushStrikeTable.isFault(ExecutionStatus.SUCCESS));
        assertFalse(PushStrikeTable.isFault(null));
        assertFalse(PushStrikeTable.isFault(ExecutionStatus.ALREADY_IN_QUEUE), "a duplicate is honest");
        assertFalse(PushStrikeTable.isFault(ExecutionStatus.INVALID_UNCLES), "an empty orphan pool");
        assertFalse(PushStrikeTable.isFault(ExecutionStatus.BALANCE_TOO_LOW), "a balance view race");
        assertFalse(PushStrikeTable.isFault(ExecutionStatus.SUBMIT_THROTTLED), "our own shed");
    }
}
