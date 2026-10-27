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
import kotlin.math.roundToInt

/**
 * Listener learning (MATH §19): volume-key presses in the first 20 s of a song are feedback. Small, bounded (±2 dB),
 * decaying (30-day half-life) offsets per track and per artist, kept per place (§19.3: the output device). The update
 * math is in the engine (learnUpdate); this class stores the offsets and decides when a press counts.
 */
class ListenerLearning(private val context: Context, private val main: Handler) {
    private val prefs = context.getSharedPreferences("sable_learning", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)

    data class Offsets(
        val trackDb: Double, val trackDays: Double, val artistDb: Double, val artistDays: Double, val artistTracks: Int,
        val trackTaught: Boolean = false, val artistTrackDbs: List<Double> = emptyList(),
    )

    /** §19.3: where the media stream plays now. */
    data class Place(val id: String, val label: String)

    fun currentPlace(): Place {
        val d = audio.getAudioDevicesForAttributes(mediaAttrs).firstOrNull()
        return placeOf(d?.type ?: android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, d?.productName?.toString())
    }

    // ---------------- Storage ----------------
    // Keys: "<place>|t:<track>", "<place>|a:<artist>", "<place>|ac:<artist>", "<place>|prevDelta"; the same keys without
    // a place prefix hold what was learned before places existed (MATH-1.20), the starting point of every place.
    private fun read(key: String): Pair<Double, Double>? = prefs.getString(key, null)?.split(',')?.let { p ->
        val v = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        val d = p.getOrNull(1)?.toDoubleOrNull() ?: return null
        v to d
    }

    // A song with no real artist ("<unknown>", blank, "Various Artists") shares nothing with the others filed under that name:
    // it has no artist state at all (MATH-1.27).
    private fun artistKey(t: TrackEntity): String? = t.artist.trim().lowercase().takeIf { it.isNotEmpty() && it !in NO_ARTIST }
    private fun trackKey(t: TrackEntity) = t.contentHash ?: idKey(t)
    // A song taught before its content was hashed (a new file, played before analysis) was stored under its MediaStore
    // id: it is still found under that key, and moved to the hash key at its next event.
    private fun idKey(t: TrackEntity) = "id${t.mediaId}"

    private fun readAt(p: Place, key: String) = read("${p.id}|$key") ?: read(key)
    private fun setAt(p: Place, key: String): Set<String>? = prefs.getStringSet("${p.id}|$key", null) ?: prefs.getStringSet(key, null)

    fun offsets(t: TrackEntity, p: Place = currentPlace()): Offsets {
        val now = nowDays()
        val tr = readAt(p, "t:${trackKey(t)}") ?: readAt(p, "t:${idKey(t)}")
        val ak = artistKey(t)
        val ar = ak?.let { readAt(p, "a:$it") }
        val taught = ak?.let { setAt(p, "ac:$it") }.orEmpty()
        // §19.2 (MATH-1.23): the artist's taught songs' own offsets, so the engine can check they agree.
        val songs = taught.mapNotNull { k -> readAt(p, "t:$k")?.first }
        return Offsets(tr?.first ?: 0.0, tr?.second ?: now, ar?.first ?: 0.0, ar?.second ?: now, taught.size, tr != null, songs)
    }

    /** The planner's "learn" input (it decays and combines them, §19.2), for the place playing now. */
    fun learnJson(t: TrackEntity): JSONObject {
        val o = offsets(t)
        return JSONObject().put("trackDb", o.trackDb).put("trackDays", o.trackDays).put("artistDb", o.artistDb)
            .put("artistDays", o.artistDays).put("artistTracks", o.artistTracks)
            .put("trackTaught", o.trackTaught).put("artistTrackDbs", org.json.JSONArray(o.artistTrackDbs))
    }

    /** §19.3: forgets the song (or artist) for the current place and the shared earlier state. */
    fun resetTrack(t: TrackEntity, p: Place = currentPlace()) =
        prefs.edit().remove("${p.id}|t:${trackKey(t)}").remove("t:${trackKey(t)}").remove("${p.id}|t:${idKey(t)}").remove("t:${idKey(t)}").apply()
    fun resetArtist(t: TrackEntity, p: Place = currentPlace()) {
        val a = artistKey(t) ?: return
        prefs.edit().remove("${p.id}|a:$a").remove("${p.id}|ac:$a").remove("a:$a").remove("ac:$a").apply()
    }
    fun resetAll() = prefs.edit().clear().apply()

    /** Everything learned, one line per entry, for the "learned" debug command. [names]: track key -> file name. */
    fun describe(names: Map<String, String>): List<String> {
        val now = nowDays()
        return prefs.all.toSortedMap().map { (k, v) ->
            val place = if ('|' in k) k.substringBefore('|') else "(shared, before places)"
            val key = k.substringAfter('|')
            val what = when {
                key.startsWith("t:") -> "song ${names[key.removePrefix("t:")] ?: key}"
                key.startsWith("ac:") -> "artist ${key.removePrefix("ac:")} songs taught"
                key.startsWith("a:") -> "artist ${key.removePrefix("a:")}"
                else -> key
            }
            val value = when (v) {
                is String -> read(k)?.let { (db, days) -> "%+.2f dB, %.1f days ago".format(db, now - days) } ?: v
                is Set<*> -> "${v.size}"
                else -> "$v"
            }
            "$place · $what: $value"
        }
    }

    // ---------------- Feedback window (§19.1) ----------------
    private var track: TrackEntity? = null
    private var place = Place("speaker", "Phone speaker")  // where the open window's song is playing
    private var startAt = 0L
    private var startDb = 0.0
    private var lastChangeAt = 0L
    private var lastIndex = -1
    private var routeChangedAt = -1_000_000L
    private var startIndex = 0
    private var firstPressPosMs = -1L  // playback position at the window's first press (§19.1, MATH-1.27)
    private var pressedWhilePaused = false
    private var sawMute = false
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

    /** The song's loudness every 0.5 s (short-term, 3 s windows) and its anchor, from the cached analysis (MATH-1.27). */
    class OpeningShape(val lufs: DoubleArray, val anchorLufs: Double)
    var shapeOf: (TrackEntity) -> OpeningShape? = { null }
    /** Whether playback is running and where it is, in ms. */
    var playback: () -> Pair<Boolean, Long> = { true to 0L }

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
    fun onItemStart(t: TrackEntity?) {
        closeWindow()
        if (t == null) return
        track = t
        place = currentPlace()
        startAt = SystemClock.elapsedRealtime()
        startDb = volumeDb()
        lastChangeAt = 0L
        lastIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        startIndex = lastIndex
        firstPressPosMs = -1L
        pressedWhilePaused = false
        sawMute = false
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
        val (playing, posMs) = playback()
        if (lastChangeAt == 0L) firstPressPosMs = posMs
        if (!playing) pressedWhilePaused = true  // paused: the press is about the room, not the song (MATH-1.27)
        if (idx == 0) sawMute = true
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
        note("LEARN window ${t.displayName} at ${place.label}: volume %.2f -> %.2f dB".format(startDb, startDb + delta))
        if (abs(delta) < MIN_EVENT_DB) return
        if (routeChangedAt >= startAt - ROUTE_QUIET_MS) { note("LEARN ignored (output changed) ${t.displayName}"); return }
        if (pressedWhilePaused) { note("LEARN ignored (paused) ${t.displayName}"); return }
        commit(t, delta, place)
    }

    private fun commit(t: TrackEntity, delta: Double, p: Place) {
        val o = offsets(t, p)
        val now = nowDays()
        val input = JSONObject().put("deltaDb", delta).put("trackDb", o.trackDb).put("trackDays", o.trackDays)
            .put("artistDb", o.artistDb).put("artistDays", o.artistDays).put("nowDays", now)
        val prev = "${p.id}|prevDelta"
        if (prefs.contains(prev)) input.put("prevDeltaDb", prefs.getFloat(prev, 0f).toDouble())
        prefs.getString("${p.id}|prevDays", null)?.toDoubleOrNull()?.let { input.put("prevDays", it) }  // §19.1 T_env
        prefs.getString("${p.id}|prevEndDb", null)?.toDoubleOrNull()?.let { input.put("prevEndDb", it) }  // §19.1 put back?
        input.put("startDb", startDb)
        // MATH-1.27: a mute is not a verdict, and the section the press was made in is measured against the song's anchor.
        if (startIndex == 0 || sawMute || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) input.put("muted", true)
        var heard: Double? = null
        shapeOf(t)?.let { sh ->
            val k = ((firstPressPosMs / 1000.0 - 3.0) / 0.5).roundToInt().coerceIn(0, sh.lufs.size - 1)  // the 3 s before the press
            heard = sh.lufs.getOrNull(k)?.takeIf { it.isFinite() && firstPressPosMs >= 0 }
            heard?.let { input.put("heardLufs", it).put("anchorLufs", sh.anchorLufs) }
        }
        val out = JSONObject(SableEngine.learnUpdate(input.toString()))
        if (out.has("error")) return
        if (out.has("ignored")) {
            note("LEARN ignored (${out.getString("ignored")}) ${t.displayName}: volume %+.1f dB".format(delta) +
                (heard?.let { ", heard %.1f LUFS, song %.1f".format(it, input.optDouble("anchorLufs")) } ?: ""))
            return
        }
        val ak = artistKey(t)
        val ac = ak?.let { (setAt(p, "ac:$it") ?: emptySet()).toMutableSet().apply { if (t.contentHash != null) remove(idKey(t)); add(trackKey(t)) } }
        prefs.edit()
            .apply { if (t.contentHash != null) remove("${p.id}|t:${idKey(t)}") }
            .putString("${p.id}|t:${trackKey(t)}", "${out.getDouble("trackDb")},${out.getDouble("trackDays")}")
            .apply { if (ak != null) putString("${p.id}|a:$ak", "${out.getDouble("artistDb")},${out.getDouble("artistDays")}").putStringSet("${p.id}|ac:$ak", ac) }
            .putFloat(prev, delta.toFloat())
            .putString("${p.id}|prevDays", now.toString())
            .putString("${p.id}|prevEndDb", (startDb + delta).toString())
            .apply()
        note("LEARN ${t.displayName} at ${p.label}: volume %+.1f dB, weight ${out.getDouble("omega")}, counted %+.1f dB -> track %+.2f dB, artist %+.2f dB"
            .format(delta, out.getDouble("effectiveDb"), out.getDouble("trackDb"), out.getDouble("artistDb")))
        onLearned(t)
    }

    /** The media volume in dB (§19.1 Δv): the platform's own figure, or the AOSP default curve where it is flat. */
    private fun volumeDb(): Double {
        val idx = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val type = audio.getAudioDevicesForAttributes(mediaAttrs).firstOrNull()?.type ?: android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        fun platform(i: Int) = runCatching { audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, i, type).toDouble() }.getOrDefault(Double.NaN)
        val usable = platform(max).let { top -> platform(max / 2).let { mid -> top.isFinite() && mid.isFinite() && top - mid > 1.0 } }
        return if (usable) platform(idx).let { if (it.isFinite()) it else -96.0 } else defaultCurveDb(idx, max)
    }

    /** Learning events go to the playback log too: logcat rolls over within hours. */
    private fun note(msg: String) { Log.i(TAG, msg); PlaybackDiag.log(msg) }

    private val mediaAttrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()

    companion object {
        const val TAG = "SablePlay"
        private val NO_ARTIST = setOf("<unknown>", "unknown", "unknown artist", "various artists")

        /** §19.3: a place from the output's kind and, for Bluetooth, BLE and USB, the device's name. */
        fun placeOf(type: Int, productName: String?): Place {
            val name = productName?.trim()?.takeIf { it.isNotEmpty() }
            fun named(kind: String, label: String) = if (name == null) Place(kind, label) else Place("$kind:$name", "$label · $name")
            return when (type) {
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> named("bt", "Bluetooth")
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
                android.media.AudioDeviceInfo.TYPE_BLE_BROADCAST -> named("ble", "Bluetooth LE")
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
                android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> named("usb", "USB-C")
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Place("wired", "Wired headphones")
                else -> Place("speaker", "Phone speaker")
            }
        }
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
