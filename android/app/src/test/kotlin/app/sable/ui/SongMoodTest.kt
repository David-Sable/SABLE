package app.sable.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMoodTest {
    private fun runs(w: List<String>): List<Int> {
        val r = ArrayList<Int>()
        w.forEachIndexed { i, x -> if (i > 0 && w[i - 1] == x) r[r.size - 1] += 1 else r += 1 }
        return r
    }

    @Test fun aWordLastsAtLeastSixSeconds() {
        val flicker = List(200) { if ((it / 3) % 2 == 0) "soft" else "full" }
        val held = SongMood.held(flicker)
        assertEquals(200, held.size)
        assertTrue(runs(held).all { it >= 12 })
    }

    @Test fun longRunsAreLeftAlone_andAShortOpeningJoinsTheNext() {
        val w = List(4) { "hushed" } + List(30) { "full" } + List(30) { "soft" }
        assertEquals(List(34) { "full" } + List(30) { "soft" }, SongMood.held(w))
    }

    @Test fun aSongShorterThanTheHoldKeepsOneWord() {
        assertEquals(1, runs(SongMood.held(listOf("soft", "soft", "full", "full", "soft"))).size)
    }

    @Test fun aQuietVerseAndALoudChorusSayDifferentThings() {
        val lufs = List(240) { if (it < 120) -24.0 else -10.0 }
        val tilt = List(240) { if (it < 120) -3.0 else 0.5 }
        val m = SongMood.of(SongShape(lufs.map { it as Double? }, emptyList(), 120.0, tilt.map { it as Double? }))
        assertNotNull(m)
        assertTrue(m!!.caption(40), m.caption(40).startsWith("soft") || m.caption(40).startsWith("hushed"))
        assertTrue(m.caption(40), m.caption(40).endsWith("dark") || m.caption(40).endsWith("deep"))
        assertTrue(m.caption(200), m.caption(200).startsWith("full") || m.caption(200).startsWith("steady"))
        assertTrue(m.caption(200), m.caption(200).endsWith("bright") || m.caption(200).endsWith("warm"))
    }

    @Test fun aSlowBuildSaysRising() {
        val lufs = List(300) { if (it < 100) -24.0 else if (it < 180) -24.0 + (it - 100) * 0.2 else -8.0 }
        val tilt = List(300) { -1.0 }
        val m = SongMood.of(SongShape(lufs.map { it as Double? }, emptyList(), 150.0, tilt.map { it as Double? }))!!
        assertTrue(m.caption(140), m.caption(140).startsWith("rising"))
    }
}
