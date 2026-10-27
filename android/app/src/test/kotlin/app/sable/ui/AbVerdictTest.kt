package app.sable.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbVerdictTest {
    @Test fun chanceByLuck_isTheBinomialTail() {
        assertEquals(73.0 / 729.0, AbVerdict.chanceByLuck(4, 6), 1e-12)  // (15·4 + 6·2 + 1) / 3^6
        assertEquals(1.0, AbVerdict.chanceByLuck(0, 26), 1e-12)
        assertEquals(Math.pow(1.0 / 3.0, 10.0), AbVerdict.chanceByLuck(10, 10), 1e-15)
        assertEquals(0.0582734, AbVerdict.chanceByLuck(13, 26), 1e-6)
    }

    @Test fun sentence_saysLuckOrReal() {
        assertTrue(AbVerdict.sentence(4, 6).startsWith("After 10 rounds"))
        assertEquals("Picking at random, Sable would win this often about 1 time in 17, so it could still be luck. Keep going.", AbVerdict.sentence(13, 26))
        assertTrue(AbVerdict.sentence(14, 27).endsWith("You can hear the engine."))
        assertEquals("So far that is what random picks would give. Keep going.", AbVerdict.sentence(8, 26))
    }

    @Test fun twoVersions_useChanceOneHalf() {
        assertEquals(0.5, AbVerdict.chanceByLuck(1, 1, m = 2), 1e-12)
        assertEquals(11.0 / 16.0, AbVerdict.chanceByLuck(2, 4, m = 2), 1e-12)  // (6 + 4 + 1) / 2^4
        assertEquals(AbVerdict.chanceByLuck(4, 6), AbVerdict.chanceByLuck(4, 6, m = 3), 0.0)
    }
}
