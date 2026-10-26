package app.sable.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Listening settings that change the plan (MATH §16.1, §16.3, §17.1). Shared in-process by the UI and the service. */
object PlaybackPrefs {
    enum class Mode(val targetLufs: Double, val label: String) { STANDARD(-14.0, "Standard"), QUIET(-19.0, "Quiet") }
    enum class AlbumMode(val key: String, val label: String) { AUTO("auto", "Auto"), TRACK("track", "Always track"), ALBUM("album", "Always album") }

    data class Settings(val mode: Mode = Mode.STANDARD, val albumMode: AlbumMode = AlbumMode.AUTO, val intimacyLu: Double = 1.0)

    private val state = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = state.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences("sable", Context.MODE_PRIVATE)
        state.value = Settings(
            mode = runCatching { Mode.valueOf(p.getString("mode", null)!!) }.getOrDefault(Mode.STANDARD),
            albumMode = runCatching { AlbumMode.valueOf(p.getString("albumMode", null)!!) }.getOrDefault(AlbumMode.AUTO),
            intimacyLu = p.getFloat("intimacyLu", 1.0f).toDouble().coerceIn(0.0, 2.0),
        )
        loaded = true
    }

    fun update(context: Context, change: (Settings) -> Settings) {
        load(context)
        val s = change(state.value)
        state.value = s
        context.getSharedPreferences("sable", Context.MODE_PRIVATE).edit()
            .putString("mode", s.mode.name).putString("albumMode", s.albumMode.name).putFloat("intimacyLu", s.intimacyLu.toFloat()).apply()
    }
}

/** What the playing item's gain is and why (the UI's readout until the Why sheet in M6). */
data class NowPlayingGain(
    val mediaId: String = "",
    val gainDb: Double = 0.0,
    val source: String = "",
    val manualStart: Boolean = true,
    val albumRun: Boolean = false,
    val rules: List<Pair<String, Double>> = emptyList(),
    val ceilingDbtp: Double = -1.0,
    val route: String = "",
)

object NowPlaying {
    val gain = MutableStateFlow(NowPlayingGain())
}
