package app.sable.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class LearningScoreboardTest {
    private class MapStore : CounterStore {
        val ints = HashMap<String, Int>()
        val texts = HashMap<String, String>()
        override fun count(key: String) = ints[key] ?: 0
        override fun add(key: String, n: Int) { ints[key] = count(key) + n }
        override fun text(key: String) = texts[key]
        override fun setText(key: String, value: String) { texts[key] = value }
        override fun keys(): Set<String> = ints.keys + texts.keys
    }

    private fun ms(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    private var now = ms(2026, 10, 28)
    private var offset = 0L
    private val store = MapStore()
    private val board = LearningScoreboard(store, { now }, { offset })

    @Test fun nothingRecorded_saysSo() {
        val lines = board.report()
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("nothing recorded yet"))
    }

    @Test fun weeksRunMondayToSunday_inThePhonesTimeZone() {
        now = ms(2026, 10, 25, 23, 59)  // Sunday
        board.played("speaker", "Phone speaker", known = false)
        now = ms(2026, 10, 26, 0, 0)    // Monday
        board.played("speaker", "Phone speaker", known = false)
        val weekly = board.report().filter { it.contains("week of") }
        assertEquals(listOf("SCOREBOARD week of Oct 26 · Phone speaker: 1 plays could teach, 0 corrected (0%); new songs: 0 of 1 corrected (0%)",
            "SCOREBOARD week of Oct 19 · Phone speaker: 1 plays could teach, 0 corrected (0%); new songs: 0 of 1 corrected (0%)"), weekly)
        // Monday 03:00 UTC is still Sunday evening in Los Angeles (UTC-7): the previous week there.
        now = ms(2026, 11, 2, 3, 0)
        offset = -7 * 3_600_000L
        board.played("speaker", "Phone speaker", known = false)
        assertTrue(board.report().any { it.startsWith("SCOREBOARD week of Oct 26") && it.contains("2 plays") })
    }

    @Test fun learnedSongsAreComparedWithNewOnes_andEveryCorrectionIsAccountedFor() {
        val id = "bt:JBL Charge 5"
        val label = "Bluetooth · JBL Charge 5"
        repeat(8) { board.played(id, label, known = false) }
        repeat(4) { board.played(id, label, known = true) }
        board.moved(id, label, known = false, up = false); board.outcome(id, label, LearningScoreboard.TAUGHT)
        board.moved(id, label, known = false, up = false); board.outcome(id, label, "ign_section")
        board.moved(id, label, known = false, up = true); board.outcome(id, label, "ign_capped")
        board.moved(id, label, known = true, up = false); board.outcome(id, label, "ign_paused")
        board.outcome(id, label, "ign_limit")  // MATH-1.36: a second tap on one play, past the 1 dB limit
        board.undone(id, label)
        board.off(id, label, "resumed"); board.off(id, label, "midsong"); board.off(id, label, "provisional"); board.off(id, label, "provisional")
        board.outside(id, label)
        val line = board.report().first { it.startsWith("SCOREBOARD all weeks") }
        assertEquals(
            "SCOREBOARD all weeks · Bluetooth · JBL Charge 5: 12 plays could teach, 4 corrected (33%); corrections: 3 down, 1 up; " +
                "1 counted as lessons, ignored: paused 1, section 1, capped 1, limit 1; songs already taught: 1 of 4 corrected (25%); " +
                "new songs: 3 of 8 corrected (38%); 4 plays could not teach (put back where left 1, mid-song 1, provisional gain 2); " +
                "1 touches put back; 1 touches outside the window", line)
    }

    @Test fun placesAreKeptApart_andAnUnknownOutcomeIsCountedAsOther() {
        board.played("speaker", "Phone speaker", known = false)
        board.played("bt:Car|Stereo", "Bluetooth · Car|Stereo", known = false)  // a '|' in a device name must not break the keys
        board.moved("bt:Car|Stereo", "Bluetooth · Car|Stereo", known = false, up = true)
        board.outcome("bt:Car|Stereo", "Bluetooth · Car|Stereo", "something new")
        val lines = board.report()
        assertTrue(lines.any { it.startsWith("SCOREBOARD all weeks · Phone speaker: 1 plays could teach, 0 corrected") })
        assertTrue(lines.any { it.startsWith("SCOREBOARD all weeks · Bluetooth · Car|Stereo: 1 plays could teach, 1 corrected (100%)") && it.contains("ignored: other 1") })
    }

    @Test fun theLabelFollowsTheDeviceName() {
        board.played("bt:Buds", "Bluetooth · Buds", known = false)
        board.played("bt:Buds", "Bluetooth · Buds (renamed)", known = false)
        assertTrue(board.report().any { it.startsWith("SCOREBOARD all weeks · Bluetooth · Buds (renamed): 2 plays") })
    }

    @Test fun onlyTheLastWeeksAreListed_butTheTotalsKeepEverything() {
        for (day in 0 until 70 step 7) { now = ms(2026, 8, 3) + day * 86_400_000L; board.played("speaker", "Phone speaker", known = false) }
        val lines = board.report(weeks = 3)
        assertEquals(3, lines.count { it.contains("week of") })
        assertTrue(lines.any { it.startsWith("SCOREBOARD all weeks") && it.contains("10 plays") })
    }

    @Test fun theCountersLiveInTheirOwnFile_soForgetDoesNotWipeThem() {
        assertNotEquals("sable_learning", LearningScoreboard.PREFS)
    }
}
