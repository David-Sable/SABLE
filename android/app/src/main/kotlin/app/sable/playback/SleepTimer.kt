package app.sable.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The sleep timer. It never cuts or fades a song: when it is due, playback stops at the end of the song that is playing
 * (Media3 pause-at-end), so Sable's one-gain-per-song promise holds to the last note. Set from the UI, carried out by
 * [PlaybackService] (same process).
 */
object SleepTimer {
    /**
     * [deadlineMs] (elapsedRealtime) is when the timer becomes due; null once it is due (or for "end of this song"),
     * after which playback stops at the end of the current song. [minutes] is what was chosen, for the UI.
     */
    data class State(val deadlineMs: Long?, val minutes: Int)

    val state = MutableStateFlow<State?>(null)

    val choices = listOf(15, 30, 45, 60, 90)

    fun endOfSong() { state.value = State(null, 0) }
    fun after(minutes: Int) { state.value = State(SystemClock.elapsedRealtime() + minutes * 60_000L, minutes) }
    fun cancel() { state.value = null }

    /** Called when the deadline passes: from now on, stop at the end of the current song. */
    fun due() { state.value = state.value?.copy(deadlineMs = null) }
}
