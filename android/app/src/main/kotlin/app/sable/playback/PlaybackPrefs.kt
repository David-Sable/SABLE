package app.sable.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Listening settings that change the plan (MATH §16.1, §16.3, §17.1). Shared in-process by the UI and the service. */
object PlaybackPrefs {
    enum class Mode(val targetLufs: Double, val label: String) { STANDARD(-14.0, "Normal"), QUIET(-19.0, "Quiet") }
    enum class AlbumMode(val key: String, val label: String) { AUTO("auto", "Auto"), TRACK("track", "Always track"), ALBUM("album", "Always album") }

    /** Output routes (MATH §20): default ceilings; the user may override each in [-3, -0.5] and calibrate in [-3, +3]. */
    enum class Route(val key: String, val label: String, val defaultCeilingDbtp: Double) {
        SPEAKER("speaker", "Phone speaker", -1.0), WIRED("wired", "Wired", -1.0), USB("usb", "USB-C audio", -1.0),
        BLUETOOTH("bluetooth", "Bluetooth", -2.0), BLE("ble", "Bluetooth LE", -1.5)
    }

    data class Settings(
        val mode: Mode = Mode.STANDARD,
        val albumMode: AlbumMode = AlbumMode.AUTO,
        val intimacyLu: Double = 1.0,
        val contour: Boolean = true,                            // §24, Quiet mode only; on by default (owner decision)
        val routeCal: Map<String, Double> = emptyMap(),         // §20.2 c_out per route
        val routeCeiling: Map<String, Double> = emptyMap(),     // §20.1 overrides
        val advanced: Boolean = false,                          // Easy mode unless the listener opts in
    ) {
        /**
         * What playback actually uses. Easy mode (the default) keeps Sable's own defaults for everything except
         * Normal/Quiet; the advanced values stay stored, so switching Advanced back on restores them untouched.
         */
        fun effective(): Settings = if (advanced) this else copy(
            albumMode = AlbumMode.AUTO, intimacyLu = 1.0, contour = true, routeCal = emptyMap(), routeCeiling = emptyMap(),
        )

        fun ceiling(r: Route) = (routeCeiling[r.key] ?: r.defaultCeilingDbtp).coerceIn(-3.0, -0.5)
        fun cal(r: Route) = (routeCal[r.key] ?: 0.0).coerceIn(-3.0, 3.0)
    }

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
            contour = p.getBoolean("contourOn", true),
            advanced = p.getBoolean("advanced", false),
            routeCal = Route.entries.mapNotNull { r -> if (p.contains("cal_${r.key}")) r.key to p.getFloat("cal_${r.key}", 0f).toDouble() else null }.toMap(),
            routeCeiling = Route.entries.mapNotNull { r -> if (p.contains("ceil_${r.key}")) r.key to p.getFloat("ceil_${r.key}", 0f).toDouble() else null }.toMap(),
        )
        loaded = true
    }

    fun update(context: Context, change: (Settings) -> Settings) {
        load(context)
        val s = change(state.value)
        state.value = s
        val e = context.getSharedPreferences("sable", Context.MODE_PRIVATE).edit()
            .putString("mode", s.mode.name).putString("albumMode", s.albumMode.name).putFloat("intimacyLu", s.intimacyLu.toFloat())
            .putBoolean("contourOn", s.contour).putBoolean("advanced", s.advanced)
        for (r in Route.entries) {
            s.routeCal[r.key]?.let { e.putFloat("cal_${r.key}", it.toFloat()) } ?: e.remove("cal_${r.key}")
            s.routeCeiling[r.key]?.let { e.putFloat("ceil_${r.key}", it.toFloat()) } ?: e.remove("ceil_${r.key}")
        }
        e.apply()
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
    val learnedDb: Double = 0.0,
    val outputCalDb: Double = 0.0,
    val contourOn: Boolean = false,
)

/** The current output route, for the settings screen. */
object OutputState {
    val route = MutableStateFlow(PlaybackPrefs.Route.SPEAKER)
}

object NowPlaying {
    val gain = MutableStateFlow(NowPlayingGain())
    /** Planned gain (dB, natural transition) of each queue item near the playing one, for the queue screen. */
    val plan = MutableStateFlow<Map<String, Double>>(emptyMap())
}

/** A running blind A/B round (MATH §30): the condition currently heard, or null when A/B is off. */
object AbSession {
    enum class Condition(val key: String, val label: String) { SABLE("sable", "Sable"), PLAIN("plain", "Plain -14 LUFS"), OFF("off", "No normalization") }
    val condition = MutableStateFlow<String?>(null)
    /** §30.6: the round is song changes (pairs: a song's ending, then the next song's opening) rather than clips. */
    @Volatile var transitions = false
}
