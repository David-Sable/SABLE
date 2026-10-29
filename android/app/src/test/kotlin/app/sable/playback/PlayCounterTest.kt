package app.sable.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Listening history: 30 s (or half a short song) makes a play; once per time it plays, and a replay is a new time. */
class PlayCounterTest {
    @Test fun countsOnceAfterThirtySeconds() {
        val c = PlayCounter()
        assertFalse(c.tick("1:S:1", true, 29_000, 200_000))
        assertTrue(c.tick("1:S:1", true, 30_000, 200_000))
        assertFalse(c.tick("1:S:1", true, 90_000, 200_000))
    }

    @Test fun shortSongCountsAtHalf() {
        val c = PlayCounter()
        assertFalse(c.tick("2:S:1", true, 9_000, 20_000))
        assertTrue(c.tick("2:S:1", true, 10_000, 20_000))
    }

    @Test fun pausedOrUnknownLengthDoesNotCount() {
        val c = PlayCounter()
        assertFalse(c.tick("3:S:1", false, 60_000, 200_000))
        assertFalse(c.tick("3:S:1", true, 60_000, 0))
    }

    /** The 2026-10-29 bug: repeat one kept the same queue entry, so every play after the first was lost. */
    @Test fun replayOfTheSameEntryCountsAgain() {
        val c = PlayCounter()
        assertTrue(c.tick("4:S:7", true, 31_000, 200_000))
        c.restarted("4:S:7")
        assertFalse(c.tick("4:S:7", true, 5_000, 200_000))  // starts over: not yet 30 s into this play
        assertTrue(c.tick("4:S:7", true, 31_000, 200_000))
    }

    @Test fun restartOfAnotherEntryChangesNothing() {
        val c = PlayCounter()
        assertTrue(c.tick("5:S:1", true, 31_000, 200_000))
        c.restarted("6:S:2")
        assertFalse(c.tick("5:S:1", true, 40_000, 200_000))
    }

    @Test fun nextEntryCountsOnItsOwn() {
        val c = PlayCounter()
        assertTrue(c.tick("7:S:1", true, 31_000, 200_000))
        assertTrue(c.tick("8:S:2", true, 31_000, 200_000))
    }
}
