package app.sable.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val planCache = ConcurrentHashMap<String, String>()      // contentHash -> the same without anchor blocks (planning)
    private val albumCache = ConcurrentHashMap<String, Pair<String, JSONObject>>()  // album key -> (members signature, complete album JSON)
    private val jitCache = ConcurrentHashMap<Long, String>()
    private val jitRunning = ConcurrentHashMap.newKeySet<Long>()
    private val fullQueued = ConcurrentHashMap.newKeySet<Long>()
    private val fullAnalysis = Channel<TrackEntity>(Channel.UNLIMITED)
    private val manualByItem = HashMap<String, Boolean>()  // main thread: how each started item actually began
    @Volatile private var lastPlan: Map<String, JSONObject> = emptyMap()
    private var ceilingDbtp = -1.0
    private var route = PlaybackPrefs.Route.SPEAKER
    private val audioManager = context.getSystemService(AudioManager::class.java)
    val learning = ListenerLearning(context, main)
    private val frozenLearned = HashMap<String, Double>()  // §28: learning applies from the next play, never mid-track
    private val refreeze = HashSet<String>()  // main thread: started items to re-freeze from the next plan (new place, §19.3)
    private var placeId: String? = null       // §19.3: the device playing now (two Bluetooth devices are two places)
    private var placeGen = 0                  // main thread: bumped on every change of place
    private var contourKey: Pair<Boolean, Double>? = null
    private var contourVersion = 0L
    private var contourHeadroom = 0.0

    /** The service is stopping: the volume listeners go (see [ListenerLearning.stop]). */
    fun stop() = learning.stop()

    fun start() {
        player.addListener(this)
        updateRoute()
        learning.start()
        learning.onLearned = { replan(user = false) }
        learning.playback = { player.isPlaying to player.currentPosition }
        learning.shapeOf = { t -> t.contentHash?.let { shapes[it] } }
        learning.headroomOf = { t -> headroomOf(t) }
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onDevicesChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onDevicesChanged()
        }, main)
        scope.launch { PlaybackPrefs.settings.drop(1).collect { replan(user = true) } }
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
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        onRouteMaybeChanged()
        // MATH-1.31: a queue entry that was already the playing one and plays again (repeat one, the wrap of repeat all, Previous,
        // skipping back) is a new play: it takes today's lessons and opens its own learning window. A first play is handled where
        // the sink reports it (onItemGain), whichever of the two events comes first.
        mediaItem?.mediaId?.let { id ->
            if (id in playedBefore && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) replay(id)
            playedBefore += id
        }
        replan(user = false)
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) onRouteMaybeChanged() }
    /** MATH-1.31: a seek back to the start of the song that is playing (Previous after the first seconds, the scrubber at 0:00) starts it again. */
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK || oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
        if (newPosition.positionMs > REPLAY_AT_MS || oldPosition.positionMs <= LEARN_START_MS) return
        newPosition.mediaItem?.mediaId?.let { id -> if (id in playedBefore) replay(id) }
    }
    override fun onRepeatModeChanged(repeatMode: Int) = replan(user = false)

    private val resumedIds = HashSet<String>()  // main thread: queue entries put back where they were left
    private val playedBefore = HashSet<String>()  // main thread: queue entries that have been the playing item

    /** MATH-1.31: the entry plays again from its start: drop the offset frozen for its first play (the next plan takes the current one and freezes it), and open a window. */
    private fun replay(id: String) {
        frozenLearned.remove(id)
        refreeze += id
        resumedIds -= id
        QueueId.trackId(id)?.let { trackCache[it] }?.let { PlaybackDiag.log("REPLAY ${it.displayName}") }
        openWindow(id)
    }

    /** §19.1: opens the learning window for a play that starts now, unless one of the rules says this play teaches nothing. */
    private fun openWindow(id: String) {
        val t = QueueId.trackId(id)?.let { trackCache[it] }
        // A gapless next song reaches the sink before it is the player's current item: it starts at 0.
        val pos = if (player.currentMediaItem?.mediaId == id) player.currentPosition else 0L
        val source = lastPlan[id]?.optString("source")
        val off = when {
            id in resumedIds -> "picked up where you left off"
            pos > LEARN_START_MS -> "started at ${pos / 1000} s"
            source != "full" -> "playing with the ${source ?: "provisional"} gain"
            else -> null
        }
        learning.onItemStart(t.takeIf { off == null })
        if (off != null && t != null) PlaybackDiag.log("LEARN off for ${t.displayName}: $off")
    }

    /** MATH-1.31: how much louder the playing song could still go before its peak cap holds it (dB), from the plan that started it. */
    private fun headroomOf(t: TrackEntity): Double? {
        val id = player.currentMediaItem?.mediaId ?: return null
        if (QueueId.trackId(id) != t.mediaId) return null
        val p = lastPlan[id] ?: return null
        val gain = (if (manualByItem[id] != false) p.optDouble("manualGainDb") else p.optDouble("gainDb"))
        val cap = p.optDouble("capDb")
        return if (gain.isFinite() && cap.isFinite()) (cap - gain).coerceAtLeast(0.0) else null
    }

    /** The queue entry Sable put back where you left off (restore or a play button): it never teaches (§19.1). */
    fun markResumed(id: String) { main.post { resumedIds += id } }

    /** From the audio thread: the item that just started and the gain it started with. */
    fun onItemGain(key: Any?, db: Double, manual: Boolean) {
        main.post {
            val id = key as? String ?: return@post
            val firstStart = !manualByItem.containsKey(id)
            val changed = manualByItem.put(id, manual) != manual
            if (firstStart) {
                lastPlan[id]?.let { frozenLearned[id] = it.optDouble("learnedDb", 0.0) }
                // §19.1 (MATH-1.25): a song teaches only when it starts from its beginning, with its full-analysis gain.
                openWindow(id)
            }
            publish(id, db, manual)
            if (changed) replan(user = false)  // the next item's seam depends on how this one began
        }
    }

    // ---------------- Planning ----------------
    /** Before a new queue reaches the player: wait up to 1.5 s for JIT of the first song (§22.2), then plan it. */
    suspend fun prepareStart(ids: List<String>, startIndex: Int) {
        val id = ids.getOrNull(startIndex)?.let(QueueId::trackId)
        val t = id?.let { track(it) }
        if (t != null && analysis(t) == null && !jitCache.containsKey(t.mediaId)) {
            val job = scope.async(Dispatchers.Default) { runJit(t) }
            withTimeoutOrNull(JIT_WAIT_MS) { job.await() }
        }
        // Only the window the player keeps planned (the rest is planned as playback moves): a "play all" of hundreds of
        // songs must not delay the first note.
        val from = maxOf(0, startIndex - 1)
        val to = minOf(ids.size, startIndex + WINDOW_AHEAD)
        val window = ids.subList(from, to)
        mutex.withLock { plan(window, startIndex - from, window.indices.map { it == startIndex - from }, user = false) }
    }

    private var replanQueued = false   // main thread: a replan is waiting to run; further requests join it
    private var replanUser = false

    /** Plans the window around the playing item. Requests that arrive before it runs are merged into one plan. */
    fun replan(user: Boolean) {
        replanUser = replanUser || user
        if (replanQueued) return
        replanQueued = true
        scope.launch(Dispatchers.Main) {
            mutex.withLock {
                replanQueued = false
                val user = replanUser.also { replanUser = false }
                val n = player.mediaItemCount
                if (n == 0) return@withLock
                val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
                // The song before the playing one, then up to WINDOW_AHEAD songs. With repeat all the queue is a circle:
                // the last song comes before the first and the first after the last, so seams across the wrap are planned
                // and the playing song keeps its predecessor (dropping it would change its gain mid-song).
                val window = if (player.repeatMode == Player.REPEAT_MODE_ALL && n > 1) {
                    (0 until minOf(n, WINDOW_AHEAD + 1)).map { (cur - 1 + n + it) % n }
                } else (maxOf(0, cur - 1) until minOf(n, cur + WINDOW_AHEAD)).toList()
                val at = window.indexOf(cur)
                val ids = window.map { player.getMediaItemAt(it).mediaId }
                // How each item began: as recorded by the sink for started items; the playing one is manual until known.
                // A song after the wrap will begin naturally, however it began last time round.
                val manual = ids.mapIndexed { i, id -> if (i > at && window[i] < cur) false else manualByItem[id] ?: (i == at) }
                plan(ids, at, manual, user)
            }
        }
    }

    private suspend fun plan(ids: List<String>, cur: Int, manual: List<Boolean>, user: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        val settings = PlaybackPrefs.settings.value.effective()
        val r = route
        val ceiling = settings.ceiling(r)
        ceilingDbtp = ceiling
        val cal = settings.cal(r)
        updateContour(settings.contour && settings.mode == PlaybackPrefs.Mode.QUIET, cal)
        val headroom = if (gains.contour != null) contourHeadroom else 0.0
        val frozen = HashMap(frozenLearned)
        val gen = placeGen  // a plan begun before a change of place must not re-freeze the old place's lessons
        val result = withContext(Dispatchers.IO) {
            val items = JSONArray()
            val albums = JSONObject()
            val missing = ArrayList<TrackEntity>()   // JIT + full analysis
            val albumMissing = ArrayList<TrackEntity>()  // full analysis only
            val tracks = ids.map { id -> QueueId.trackId(id)?.let { track(it) } }
            val keys = tracks.map { t -> t?.let { albumKey(it) } }
            for ((i, id) in ids.withIndex()) {
                val t = tracks[i]
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
                        // §17.3: a neighbour from the same album may flow into this song; the planner decides.
                        val neighbour = keys.getOrNull(i - 1) == key || keys.getOrNull(i + 1) == key
                        val wantsAlbum = settings.albumMode == PlaybackPrefs.AlbumMode.ALBUM ||
                            (settings.albumMode == PlaybackPrefs.AlbumMode.AUTO && (QueueId.albumIntent(id) || neighbour))
                        if (wantsAlbum && !albums.has(key)) albums.put(key, albumJson(key, t, albumMissing))
                    }
                    o.put("disc", t.disc).put("trackNo", t.track)
                }
                o.put("albumIntent", QueueId.albumIntent(id))
                o.put("manual", manual[i])
                if (t != null) frozen[id]?.let { o.put("learnedDb", it) } ?: o.put("learn", learning.learnJson(t))
                items.put(o)
            }
            val input = JSONObject()
                .put("targetLufs", settings.mode.targetLufs).put("intimacyLu", settings.intimacyLu)
                .put("ceilingDbtp", ceiling).put("limiter", false).put("outputCalDb", cal)
                .put("contourHeadroomDb", headroom).put("nowDays", ListenerLearning.nowDays())
                .put("albumMode", settings.albumMode.key).put("albums", albums).put("items", items)
            val out = JSONObject(SableEngine.planQueue(input.toString()))
            missing.forEach { scheduleAnalysis(it, jit = true) }
            albumMissing.forEach { scheduleAnalysis(it, jit = false) }
            out.optJSONArray("items")
        } ?: return
        val plan = HashMap<String, JSONObject>()
        for (i in ids.indices) {
            val p = result.optJSONObject(i) ?: continue
            plan[ids[i]] = p
            gains.put(ids[i], p.optDouble("gainDb", settings.mode.targetLufs + 8), p.optDouble("manualGainDb", settings.mode.targetLufs + 8), user)
        }
        gains.fallbackDb = settings.mode.targetLufs + 8.0  // §22.1
        gains.retain(ids.toSet())
        lastPlan = plan
        NowPlaying.plan.value = plan.mapValues { (_, p) -> p.optDouble("gainDb") }
        if (refreeze.isNotEmpty() && gen == placeGen) {
            for (id in refreeze) plan[id]?.let { frozenLearned[id] = it.optDouble("learnedDb", 0.0) }
            refreeze.clear()
        }
        // A user-side replan (output, settings) may leave the playing song's gain unchanged, so the sink reports
        // nothing: refresh Now Playing (gain, place, rules) from the plan itself.
        if (user) player.currentMediaItem?.mediaId?.let { id ->
            val manual = manualByItem[id] ?: return@let
            val p = plan[id] ?: return@let
            publish(id, if (manual) p.optDouble("manualGainDb") else p.optDouble("gainDb"), manual)
        }
        Log.d(TAG, "PLAN ${ids.size} items in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /**
     * §17.1: album gain needs every track of the album analysed; otherwise they are queued at high priority.
     * A complete album's JSON is reused while its members (ids and content) are unchanged.
     */
    private suspend fun albumJson(key: String, t: TrackEntity, missing: MutableList<TrackEntity>): JSONObject {
        val members = dao.albumTracks(t.albumId, t.albumArtist)
        val sig = members.joinToString(",") { "${it.mediaId}:${it.contentHash}" }
        albumCache[key]?.takeIf { it.first == sig }?.let { return it.second }
        val tracks = JSONArray()
        var complete = members.isNotEmpty()
        for (m in members) {
            val a = analysis(m, keepAlbumData = true)
            if (a == null) { complete = false; if (missing.none { it.mediaId == m.mediaId }) missing.add(m) } else tracks.put(JSONObject(a))
        }
        val json = JSONObject().put("complete", complete).put("tracks", if (complete) tracks else JSONArray())
        if (complete) albumCache[key] = sig to json
        return json
    }

    private val shapes = ConcurrentHashMap<String, ListenerLearning.OpeningShape>()  // by content hash, for the learning window (§19.1)

    private fun openingShape(o: JSONObject): ListenerLearning.OpeningShape? {
        val s = o.optJSONArray("shape") ?: return null
        val anchor = o.optDouble("anchorLufs", Double.NaN).takeIf { it.isFinite() } ?: return null
        return ListenerLearning.OpeningShape(DoubleArray(s.length()) { i -> if (s.isNull(i)) Double.NaN else s.optDouble(i) }, anchor)
    }

    private fun albumKey(t: TrackEntity) = if (t.album.isBlank()) null else "${t.albumId}|${t.albumArtist}"

    private suspend fun track(id: Long): TrackEntity? = trackCache[id] ?: dao.track(id)?.also { trackCache[id] = it }

    /** The cached analysis JSON without the UI series (shape, sections); anchor blocks only when pooling an album. */
    private suspend fun analysis(t: TrackEntity, keepAlbumData: Boolean = false): String? {
        val h = t.contentHash ?: return null
        val full = analysisCache[h] ?: dao.analysis(h, SableDatabase.MATH_VERSION)?.json?.let { js ->
            JSONObject(js).also { o -> openingShape(o)?.let { shapes[h] = it } }.apply { remove("shape"); remove("sections") }.toString().also { analysisCache[h] = it }
        } ?: return null
        if (keepAlbumData) return full
        return planCache.getOrPut(h) { JSONObject(full).apply { remove("anchorBlockPower") }.toString() }
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

    /** §24: the Quiet contour for this calibration; the sink reads it from the gain table. */
    private fun updateContour(on: Boolean, cal: Double) {
        val key = on to cal
        if (key == contourKey) return
        contourKey = key
        if (!on) { gains.contour = null; return }
        val design = JSONObject(SableEngine.contourDesign(cal, 48000.0))
        contourHeadroom = design.optDouble("headroomDb", 0.0)
        val byRate = ConcurrentHashMap<Int, Pair<DoubleArray, DoubleArray>>()
        gains.contour = GainTable.Contour(++contourVersion) { fs ->
            byRate.getOrPut(fs) {
                val j = JSONObject(SableEngine.contourDesign(cal, fs.toDouble()))
                fun arr(k: String) = j.getJSONArray(k).let { a -> DoubleArray(5) { a.getDouble(it) } }
                arr("low") to arr("high")
            }
        }
        Log.i(TAG, "CONTOUR on: low ${design.optDouble("lowHz")} Hz %+.1f dB, high ${design.optDouble("highHz")} Hz %+.1f dB, headroom %.1f dB"
            .format(design.optDouble("lowGainDb"), design.optDouble("highGainDb"), contourHeadroom))
    }

    // ---------------- Output (MATH §20) ----------------
    /**
     * A device came or went. Android announces a Bluetooth device before it moves the music to it, so the output is
     * checked now and again as the routing settles; [onRouteMaybeChanged] acts only on a real change.
     */
    private fun onDevicesChanged() {
        onRouteMaybeChanged()
        for (delay in ROUTE_RECHECK_MS) main.postDelayed({ onRouteMaybeChanged() }, delay)
    }

    /** Debug (`--es cmd reroute`): runs the change-of-place path as if a new device had taken over. */
    fun simulatePlaceChange() { placeId = null; onRouteMaybeChanged() }

    fun onRouteMaybeChanged() {
        val before = route
        val beforePlace = placeId
        updateRoute()
        // Registering the callback reports the devices already present: not a change. Two Bluetooth devices (the JBL,
        // the car) are the same route kind but different places, so the place is compared too.
        if (before != route || beforePlace != placeId) {
            PlaybackDiag.log("ROUTE $before -> $route (${learning.currentPlace().label})")
            learning.onRouteChanged()
            // §19.3 (MATH-1.26): the playing song takes the new place's lessons now, not at its next play; the next
            // plan re-freezes them (§28 still holds: no lesson from this play is in them).
            refreeze += frozenLearned.keys
            frozenLearned.clear()
            placeGen++
            replan(user = true)
        }
    }

    private fun updateRoute() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val type = audioManager.getAudioDevicesForAttributes(attrs).firstOrNull()?.type
        route = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> PlaybackPrefs.Route.BLUETOOTH  // codec not exposed to apps: "unknown" row
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> PlaybackPrefs.Route.BLE
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> PlaybackPrefs.Route.USB
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> PlaybackPrefs.Route.WIRED
            else -> PlaybackPrefs.Route.SPEAKER
        }
        OutputState.route.value = route
        placeId = learning.currentPlace().id
        ceilingDbtp = PlaybackPrefs.settings.value.effective().ceiling(route)
    }

    private fun publish(id: String, db: Double, manual: Boolean) {
        val p = lastPlan[id]
        val rules = ArrayList<Pair<String, Double>>()
        p?.optJSONArray("rules")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { rules.add(it.optString("id") to it.optDouble("deltaDb")) } }
        if (manual) rules.removeAll { it.first == "SEAM" }  // §18: the seam belongs to the natural variant; a user start plays unseamed
        val s = PlaybackPrefs.settings.value.effective()
        NowPlaying.gain.value = NowPlayingGain(
            id, db, p?.optString("source") ?: "provisional", manual, p?.optBoolean("albumRun") ?: false, rules, ceilingDbtp, route.label,
            learnedDb = p?.optDouble("learnedDb", 0.0) ?: 0.0, outputCalDb = s.cal(route), contourOn = gains.contour != null,
        )
        Log.i(TAG, "ITEM $id gain %.2f dB manual=$manual ${p?.optString("source")} at ${route.label} learned %+.2f ${rules.joinToString { "${it.first} %+.2f".format(it.second) }}".format(db, p?.optDouble("learnedDb", 0.0) ?: 0.0))
    }

    companion object {
        const val TAG = "SablePlay"
        const val JIT_WAIT_MS = 1500L   // §22.2 W_jit
        const val WINDOW_AHEAD = 30
        val ROUTE_RECHECK_MS = longArrayOf(400L, 1_500L, 3_000L)
        const val REPLAY_AT_MS = 1_000L  // a seek that lands this close to the start begins the song again (MATH-1.31)
        const val LEARN_START_MS = 3_000L  // §19.1: a song started later than this into it opens no learning window
    }
}
