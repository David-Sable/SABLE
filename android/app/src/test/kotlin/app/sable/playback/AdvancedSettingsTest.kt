package app.sable.playback

import app.sable.playback.PlaybackPrefs.AlbumMode
import app.sable.playback.PlaybackPrefs.Mode
import app.sable.playback.PlaybackPrefs.Route
import app.sable.playback.PlaybackPrefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Advanced off means Sable's own choices (only Normal/Quiet stays); the fine-tuning is kept and comes back when Advanced is on. */
class AdvancedSettingsTest {
    private val tuned = Settings(
        mode = Mode.QUIET, albumMode = AlbumMode.TRACK, intimacyLu = 2.0, contour = false,
        routeCal = mapOf("bluetooth" to 2.5), routeCeiling = mapOf("bluetooth" to -0.5, "speaker" to -3.0),
    )

    @Test fun offUsesTheDefaultsButKeepsTheMode() {
        val e = tuned.copy(advanced = false).effective()
        assertEquals(Mode.QUIET, e.mode)
        assertEquals(AlbumMode.AUTO, e.albumMode)
        assertEquals(1.0, e.intimacyLu, 0.0)
        assertEquals(true, e.contour)
        assertEquals(-2.0, e.ceiling(Route.BLUETOOTH), 0.0)
        assertEquals(-1.0, e.ceiling(Route.SPEAKER), 0.0)
        assertEquals(0.0, e.cal(Route.BLUETOOTH), 0.0)
    }

    @Test fun onUsesTheStoredFineTuning() {
        val e = tuned.copy(advanced = true).effective()
        assertEquals(AlbumMode.TRACK, e.albumMode)
        assertEquals(2.0, e.intimacyLu, 0.0)
        assertEquals(false, e.contour)
        assertEquals(-0.5, e.ceiling(Route.BLUETOOTH), 0.0)
        assertEquals(-3.0, e.ceiling(Route.SPEAKER), 0.0)
        assertEquals(2.5, e.cal(Route.BLUETOOTH), 0.0)
    }

    @Test fun offThenOnRestoresEverythingUntouched() {
        val off = tuned.copy(advanced = false)
        assertEquals(tuned.copy(advanced = true), off.copy(advanced = true))  // the stored values were never cleared
    }
}
