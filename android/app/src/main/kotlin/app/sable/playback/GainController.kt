package app.sable.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.sable.data.SableDatabase
import app.sable.data.TrackEntity
import app.sable.engine.SableEngine
import app.sable.library.JitAnalyzer
import app.sable.library.TrackAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Queue item ids: "<trackId>:<A|S>[:<token>]". A = queued by Play album, S = song world (MATH §17.1). */
object QueueId {
    private val tokens = AtomicLong()
    fun request(trackId: Long, albumIntent: Boolean) = "$trackId:${if (albumIntent) "A" else "S"}"
    /** A unique id for one queue entry, so the same song twice in a queue can carry two different gains. */
    fun unique(requestId: String): String? {
        val p = requestId.split(':')
        val id = p.getOrNull(0)?.toLongOrNull() ?: return null
        return "$id:${if (p.getOrNull(1) == "A") "A" else "S"}:${tokens.incrementAndGet()}"
    }
    fun trackId(id: String) = id.substringBefore(':').toLongOrNull()
    fun albumIntent(id: String) = id.split(':').getOrNull(1) == "A"
}

/**
 * Keeps the [GainTable] in step with the queue: plans every item near the playing one with the engine's planner
 * (MATH §17.1, §18, §21, §22), runs JIT and full analysis for songs not analysed yet, and tracks the output ceiling.
 */
@UnstableApi
class GainController(
    private val context: Context,
    private val player: ExoPlayer,
    private val gains: GainTable,
    private val scope: CoroutineScope,
) : Player.Listener {
    private val dao = SableDatabase.get(context).library()
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val trackCache = ConcurrentHashMap<Long, TrackEntity>()
    private val analysisCache = ConcurrentHashMap<String, String>()  // contentHash -> analysis JSON (without UI series)
    private val jitCache = ConcurrentHashMap<Long, String>()
    private val jitRunning = ConcurrentHashMap.newKeySet<Long>()
    private val fullQueued = ConcurrentHashMap.newKeySet<Long>()
    private val fullAnalysis = Channel<TrackEntity>(Channel.UNLIMITED)
    private val manualByItem = HashMap<String, Boolean>()  // main thread: how each started item actually began
    @Volatile private var lastPlan: Map<String, JSONObject> = emptyMap()
    private var ceilingDbtp = -1.0
    private var route = "speaker"
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun start() {
        player.addListener(this)
        updateRoute()
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onRouteMaybeChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onRouteMaybeChanged()
        }, main)
        scope.launch { PlaybackPrefs.settings.drop(1).collect { replan(user = true) } }
        scope.launch { AbSession.condition.drop(1).collect { replan(user = true) } }
        scope.launch(Dispatchers.Default) {
            for (t in fullAnalysis) {  // one at a time, boosted (M4: ~6 s per 4 min)
                runCatching { TrackAnalyzer(context).ensureAnalyzed(t) }.onFailure { Log.w(TAG, "analysis failed: ${t.displayName}", it) }
                trackCache.remove(t.mediaId)
                withContext(Dispatchers.Main) { replan(user = false) }
            }
        }
    }

    // ---------------- Player events ----------------
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = replan(user = false)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = replan(user = false)

    /** From the audio thread: the item that just started and the gain it started with. */
    fun onItemGain(key: Any?, db: Double, manual: Boolean) {
        main.post {
            val id = key as? String ?: return@post
            val changed = manualByItem.put(id, manual) != manual
            publish(id, db, manual)
            if (changed) replan(user = false)  // the next item's seam depends on how this one began
        }
    }

    // ---------------- Planning ----------------
    /** Before a new queue reaches the player: wait up to 1.5 s for JIT of the first song (§22.2), then plan it. */
    suspend fun prepareStart(ids: List<String>, startIndex: Int, excerptMs: Map<String, Long> = emptyMap()) {
        val id = ids.getOrNull(startIndex)?.let(QueueId::trackId)
        val t = id?.let { track(it) }
        if (t != null && analysis(t) == null && !jitCache.containsKey(t.mediaId)) {
            val job = scope.async(Dispatchers.Default) { runJit(t) }
            withTimeoutOrNull(JIT_WAIT_MS) { job.await() }
        }
        mutex.withLock { plan(ids, startIndex, ids.indices.map { it == startIndex }, user = false, excerptMs) }
    }

    fun replan(user: Boolean) {
        scope.launch(Dispatchers.Main) {
            mutex.withLock {
                val n = player.mediaItemCount
                if (n == 0) return@withLock
                val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
                val from = maxOf(0, cur - 1)
                val to = minOf(n, cur + WINDOW_AHEAD)
                val ids = (from until to).map { player.getMediaItemAt(it).mediaId }
                val excerpts = (from until to).mapNotNull { i ->
                    val item = player.getMediaItemAt(i)
                    excerptMsOf(item)?.let { item.mediaId to it }
                }.toMap()
                // How each item began: as recorded by the sink for started items; the playing one is manual until known.
                val manual = ids.mapIndexed { i, id -> manualByItem[id] ?: (i == cur - from) }
                plan(ids, cur - from, manual, user, excerpts)
            }
        }
    }

    private suspend fun plan(ids: List<String>, cur: Int, manual: List<Boolean>, user: Boolean, excerptMs: Map<String, Long>) {
        val settings = PlaybackPrefs.settings.value
        val ab = AbSession.condition.value  // §30: a blind A/B round is running
        val ceiling = ceilingDbtp
        val result = withContext(Dispatchers.IO) {
            val items = JSONArray()
            val albums = JSONObject()
            val missing = ArrayList<TrackEntity>()   // JIT + full analysis
            val albumMissing = ArrayList<TrackEntity>()  // full analysis only
            for ((i, id) in ids.withIndex()) {
                val t = QueueId.trackId(id)?.let { track(it) }
                val o = JSONObject()
                if (t != null) {
                    val a = analysis(t)
                    if (a != null) o.put("analysis", JSONObject(a)) else {
                        jitCache[t.mediaId]?.let { o.put("jit", JSONObject(it)) }
                        if (i == cur || i == cur + 1) missing.add(t)
                    }
                    val key = albumKey(t)
                    if (key != null) {
                        o.put("album", key)
                        val wantsAlbum = settings.albumMode == PlaybackPrefs.AlbumMode.ALBUM ||
                            (settings.albumMode == PlaybackPrefs.AlbumMode.AUTO && QueueId.albumIntent(id))
                        if (wantsAlbum && !albums.has(key)) albums.put(key, albumJson(t, albumMissing))
                    }
                }
                o.put("albumIntent", QueueId.albumIntent(id))
                o.put("manual", ab != null || manual[i])
                excerptMs[id]?.let { o.put("excerptSec", it / 1000.0) }
                items.put(o)
            }
            val input = JSONObject()
                .put("targetLufs", settings.mode.targetLufs).put("intimacyLu", settings.intimacyLu)
                .put("ceilingDbtp", ceiling).put("limiter", false).put("outputCalDb", 0.0)
                .put("albumMode", settings.albumMode.key).put("albums", albums).put("items", items)
            if (ab != null) input.put("abCondition", ab)
            val out = JSONObject(SableEngine.planQueue(input.toString()))
            missing.forEach { scheduleAnalysis(it, jit = true) }
            albumMissing.forEach { scheduleAnalysis(it, jit = false) }
            out.optJSONArray("items")
        } ?: return
        val plan = HashMap<String, JSONObject>()
        for (i in ids.indices) {
            val p = result.optJSONObject(i) ?: continue
            plan[ids[i]] = p
            gains.put(ids[i], p.optDouble("gainDb", settings.mode.targetLufs + 8), p.optDouble("manualGainDb", settings.mode.targetLufs + 8), user,
                excerptMs[ids[i]] ?: 0L)
        }
        gains.fallbackDb = settings.mode.targetLufs + 8.0  // §22.1
        gains.retain(ids.toSet())
        lastPlan = plan
        NowPlaying.plan.value = plan.mapValues { (_, p) -> p.optDouble("gainDb") }
    }

    /** §17.1: album gain needs every track of the album analysed; otherwise they are queued at high priority. */
    private suspend fun albumJson(t: TrackEntity, missing: MutableList<TrackEntity>): JSONObject {
        val members = dao.albumTracks(t.albumId, t.albumArtist)
        val tracks = JSONArray()
        var complete = members.isNotEmpty()
        for (m in members) {
            val a = analysis(m, keepAlbumData = true)
            if (a == null) { complete = false; if (missing.none { it.mediaId == m.mediaId }) missing.add(m) } else tracks.put(JSONObject(a))
        }
        return JSONObject().put("complete", complete).put("tracks", if (complete) tracks else JSONArray())
    }

    private fun albumKey(t: TrackEntity) = if (t.album.isBlank()) null else "${t.albumId}|${t.albumArtist}"

    private suspend fun track(id: Long): TrackEntity? = trackCache[id] ?: dao.track(id)?.also { trackCache[id] = it }

    /** The cached analysis JSON without the UI series (shape, sections); anchor blocks only when pooling an album. */
    private suspend fun analysis(t: TrackEntity, keepAlbumData: Boolean = false): String? {
        val h = t.contentHash ?: return null
        val full = analysisCache[h] ?: dao.analysis(h, SableDatabase.MATH_VERSION)?.json?.let { js ->
            JSONObject(js).apply { remove("shape"); remove("sections") }.toString().also { analysisCache[h] = it }
        } ?: return null
        return if (keepAlbumData) full else JSONObject(full).apply { remove("anchorBlockPower") }.toString()
    }

    private fun scheduleAnalysis(t: TrackEntity, jit: Boolean) {
        if (jit && !jitCache.containsKey(t.mediaId) && jitRunning.add(t.mediaId)) {
            scope.launch(Dispatchers.Default) {
                runJit(t)
                withContext(Dispatchers.Main) { replan(user = false) }
            }
        }
        if (fullQueued.add(t.mediaId)) fullAnalysis.trySend(t)
    }

    private fun runJit(t: TrackEntity) {
        val r = runCatching { JitAnalyzer(context).analyze(Uri.parse(t.uri), t.durationMs, ceilingDbtp) }.getOrNull()
        if (r != null) {
            jitCache[t.mediaId] = r.json
            Log.i(TAG, "JIT ${t.displayName}: ${r.millis} ms ${r.json}")
        }
        jitRunning.remove(t.mediaId)
    }

    // ---------------- Output (MATH §20.1) ----------------
    private fun onRouteMaybeChanged() {
        val before = ceilingDbtp
        updateRoute()
        if (before != ceilingDbtp) replan(user = true)
    }

    private fun updateRoute() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val type = audioManager.getAudioDevicesForAttributes(attrs).firstOrNull()?.type
        val (name, c) = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth" to -2.0  // codec not exposed to apps: "unknown" row
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE" to -1.5
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB" to -1.0
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired" to -1.0
            else -> "Phone speaker" to -1.0
        }
        route = name
        ceilingDbtp = c
    }

    private fun publish(id: String, db: Double, manual: Boolean) {
        val p = lastPlan[id]
        val rules = ArrayList<Pair<String, Double>>()
        p?.optJSONArray("rules")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { rules.add(it.optString("id") to it.optDouble("deltaDb")) } }
        NowPlaying.gain.value = NowPlayingGain(id, db, p?.optString("source") ?: "provisional", manual, p?.optBoolean("albumRun") ?: false, rules, ceilingDbtp, route)
        Log.i(TAG, "ITEM $id gain %.2f dB manual=$manual ${p?.optString("source")} ${rules.joinToString { "${it.first} %+.2f".format(it.second) }}".format(db))
    }

    companion object {
        /** An A/B excerpt's length (§30.2), from its clipping; null for a whole song. */
        fun excerptMsOf(item: MediaItem): Long? {
            val c = item.clippingConfiguration
            if (c.endPositionMs == androidx.media3.common.C.TIME_END_OF_SOURCE) return null
            return (c.endPositionMs - c.startPositionMs).takeIf { it > 0 }
        }

        const val TAG = "SablePlay"
        const val JIT_WAIT_MS = 1500L   // §22.2 W_jit
        const val WINDOW_AHEAD = 30
    }
}
