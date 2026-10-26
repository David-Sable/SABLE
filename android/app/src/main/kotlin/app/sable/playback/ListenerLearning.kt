package app.sable.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.sable.data.TrackEntity
import app.sable.engine.SableEngine
import org.json.JSONObject
import kotlin.math.abs

/**
 * Listener learning (MATH §19): volume-key presses in the first 20 s of a song are feedback. Small, bounded (±2 dB),
 * decaying (30-day half-life) offsets per track and per artist. The update math is in the engine (learnUpdate);
 * this class stores the offsets and decides when a press counts.
 */
class ListenerLearning(private val context: Context, private val main: Handler) {
    private val prefs = context.getSharedPreferences("sable_learning", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)

    data class Offsets(val trackDb: Double, val trackDays: Double, val artistDb: Double, val artistDays: Double, val artistTracks: Int)

    // ---------------- Storage ----------------
    private fun read(key: String): Pair<Double, Double>? = prefs.getString(key, null)?.split(',')?.let { p ->
        val v = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        val d = p.getOrNull(1)?.toDoubleOrNull() ?: return null
        v to d
    }

    private fun artistKey(t: TrackEntity) = t.artist.trim().lowercase().ifBlank { "<unknown>" }
    private fun trackKey(t: TrackEntity) = t.contentHash ?: "id${t.mediaId}"

    fun offsets(t: TrackEntity): Offsets {
        val now = nowDays()
        val tr = read("t:${trackKey(t)}")
        val ar = read("a:${artistKey(t)}")
        val n = prefs.getStringSet("ac:${artistKey(t)}", emptySet())!!.size
        return Offsets(tr?.first ?: 0.0, tr?.second ?: now, ar?.first ?: 0.0, ar?.second ?: now, n)
    }

    /** The planner's "learn" input (it decays and combines them, §19.2). */
    fun learnJson(t: TrackEntity): JSONObject {
        val o = offsets(t)
        return JSONObject().put("trackDb", o.trackDb).put("trackDays", o.trackDays).put("artistDb", o.artistDb)
            .put("artistDays", o.artistDays).put("artistTracks", o.artistTracks)
    }

    fun resetTrack(t: TrackEntity) = prefs.edit().remove("t:${trackKey(t)}").apply()
    fun resetArtist(t: TrackEntity) = prefs.edit().remove("a:${artistKey(t)}").remove("ac:${artistKey(t)}").apply()
    fun resetAll() = prefs.edit().clear().apply()

    // ---------------- Feedback window (§19.1) ----------------
    private var track: TrackEntity? = null
    private var startAt = 0L
    private var startDb = 0.0
    private var lastChangeAt = 0L
    private var lastIndex = -1
    private var routeChangedAt = -1_000_000L
    private val close = Runnable { closeWindow() }
    // One UI does not always deliver the volume broadcast to apps: also sample the volume while a window is open.
    private val poll = object : Runnable {
        override fun run() {
            if (track == null) return
            onVolumeMaybeChanged()
            main.postDelayed(this, POLL_MS)
        }
    }
    var onLearned: (TrackEntity) -> Unit = {}

    fun start() {
        lastIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Volume changes: the system broadcast where delivered, and the settings observer as a fallback.
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onVolumeMaybeChanged()
        }
        context.registerReceiver(r, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_EXPORTED)
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onVolumeMaybeChanged()
        })
    }

    fun onRouteChanged() { routeChangedAt = SystemClock.elapsedRealtime() }

    /** A song began (tap, skip or natural transition). Closes any open window; A/B rounds never teach (§19.1). */
    fun onItemStart(t: TrackEntity?, abActive: Boolean) {
        closeWindow()
        if (t == null || abActive) return
        track = t
        startAt = SystemClock.elapsedRealtime()
        startDb = volumeDb()
        lastChangeAt = 0L
        lastIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        main.postDelayed(close, WINDOW_MS)
        main.postDelayed(poll, POLL_MS)
    }

    private fun onVolumeMaybeChanged() {
        val idx = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (idx == lastIndex) return
        lastIndex = idx
        val now = SystemClock.elapsedRealtime()
        if (track == null || now - startAt > WINDOW_MS) return
        Log.i(TAG, "volume index $idx at ${(now - startAt) / 1000.0} s")
        lastChangeAt = now
        main.removeCallbacks(close)  // a press can still be undone within 3 s: wait for that before closing
        main.postDelayed(close, maxOf(startAt + WINDOW_MS, now + UNDO_MS) - now)
    }

    private fun closeWindow() {
        main.removeCallbacks(close)
        main.removeCallbacks(poll)
        val t = track ?: return
        track = null
        if (lastChangeAt == 0L) return
        val delta = volumeDb() - startDb  // presses in one window accumulate; an undone press nets to ~0
        Log.i(TAG, "LEARN window ${t.displayName}: %.2f -> %.2f dB".format(startDb, startDb + delta))
        if (abs(delta) < MIN_EVENT_DB) return
        if (routeChangedAt >= startAt - ROUTE_QUIET_MS) { Log.i(TAG, "LEARN ignored (output changed) ${t.displayName}"); return }
        commit(t, delta)
    }

    private fun commit(t: TrackEntity, delta: Double) {
        val o = offsets(t)
        val now = nowDays()
        val input = JSONObject().put("deltaDb", delta).put("trackDb", o.trackDb).put("trackDays", o.trackDays)
            .put("artistDb", o.artistDb).put("artistDays", o.artistDays).put("nowDays", now)
        if (prefs.contains("prevDelta")) input.put("prevDeltaDb", prefs.getFloat("prevDelta", 0f).toDouble())
        val out = JSONObject(SableEngine.learnUpdate(input.toString()))
        if (out.has("error")) return
        val ac = prefs.getStringSet("ac:${artistKey(t)}", emptySet())!!.toMutableSet().apply { add(trackKey(t)) }
        prefs.edit()
            .putString("t:${trackKey(t)}", "${out.getDouble("trackDb")},${out.getDouble("trackDays")}")
            .putString("a:${artistKey(t)}", "${out.getDouble("artistDb")},${out.getDouble("artistDays")}")
            .putStringSet("ac:${artistKey(t)}", ac)
            .putFloat("prevDelta", delta.toFloat())
            .apply()
        Log.i(TAG, "LEARN ${t.displayName}: volume %+.1f dB, weight ${out.getDouble("omega")} -> track %+.2f dB, artist %+.2f dB"
            .format(delta, out.getDouble("trackDb"), out.getDouble("artistDb")))
        onLearned(t)
    }

    /** The media volume in dB (§19.1 Δv): the platform's own figure, or the AOSP default curve where it is flat. */
    private fun volumeDb(): Double {
        val idx = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val media = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
        val type = audio.getAudioDevicesForAttributes(media).firstOrNull()?.type ?: android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        fun platform(i: Int) = runCatching { audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, i, type).toDouble() }.getOrDefault(Double.NaN)
        val usable = platform(max).let { top -> platform(max / 2).let { mid -> top.isFinite() && mid.isFinite() && top - mid > 1.0 } }
        return if (usable) platform(idx).let { if (it.isFinite()) it else -96.0 } else defaultCurveDb(idx, max)
    }

    companion object {
        const val TAG = "SablePlay"
        const val WINDOW_MS = 20_000L     // §19.1 t_w
        const val UNDO_MS = 3_000L        // a press undone within 3 s cancels
        const val ROUTE_QUIET_MS = 10_000L
        const val POLL_MS = 250L
        const val MIN_EVENT_DB = 0.5      // below one volume step's worth: not a deliberate correction
        fun nowDays() = System.currentTimeMillis() / 86_400_000.0

        /**
         * AOSP's default media volume curve (frameworks/av audiopolicy default_volume_tables.xml, DEFAULT_MEDIA_VOLUME_CURVE): index as % of the range ->
         * dB, piecewise linear through (1, -58), (20, -40), (60, -17), (100, 0); 0 = muted. Used where the platform's
         * getStreamVolumeDb is flat (One UI applies its own volume elsewhere).
         */
        fun defaultCurveDb(index: Int, max: Int): Double {
            if (index <= 0 || max <= 0) return -96.0
            val pc = 100.0 * index / max
            val pts = doubleArrayOf(1.0, -58.0, 20.0, -40.0, 60.0, -17.0, 100.0, 0.0)
            if (pc <= pts[0]) return pts[1]
            var k = 0
            while (k + 3 < pts.size && pc > pts[k + 2]) k += 2
            val (x0, y0, x1, y1) = listOf(pts[k], pts[k + 1], pts[k + 2], pts[k + 3])
            return y0 + (y1 - y0) * (pc - x0) / (x1 - x0)
        }
    }
}
