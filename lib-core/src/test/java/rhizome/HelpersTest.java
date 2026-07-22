package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import rhizome.core.common.Helpers;

/** Guards {@link Helpers#PDN} against silently building negative/overflowed amounts (audit V6g). */
class HelpersTest {

    @Test
    void pdnConvertsValidAmounts() {
        assertEquals(505_000L, Helpers.PDN(50.5).amount());
        assertEquals(0L, Helpers.PDN(0.0).amount());
    }

    @Test
    void pdnRejectsNegativeNaNAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> Helpers.PDN(-5.0));
        assertThrows(IllegalArgumentException.class, () -> Helpers.PDN(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Helpers.PDN(Double.MAX_VALUE));
    }
}
