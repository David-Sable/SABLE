package app.sable.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.sable.data.LibraryRow
import app.sable.data.SableDatabase
import app.sable.playback.NowPlaying
import app.sable.playback.PlaybackPrefs
import app.sable.playback.PlaybackService
import app.sable.playback.QueueId
import com.google.common.util.concurrent.ListenableFuture
import app.sable.library.JitAnalyzer
import app.sable.library.LibraryScanner
import app.sable.library.TrackAnalyzer
import app.sable.work.ScanWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * M5 preview: library, Play album / song world, Standard / Quiet, now playing with the applied gain. The designed UI
 * arrives in M6. adb automation (results logged under tags "SableBench" and "SablePlay"):
 *   adb shell am start -n app.sable/.ui.MainActivity --es cmd sync | scan | stats | cancel
 *   adb shell am start -n app.sable/.ui.MainActivity --es cmd bench --es match "<title substring>"
 *   adb shell am start -n app.sable/.ui.MainActivity --es cmd playalbum --es match "<album>" [--ei start N]
 *   adb shell am start -n app.sable/.ui.MainActivity --es cmd playsong --es match "<title>"
 *   adb shell am start -n app.sable/.ui.MainActivity --es cmd next | prev | pause | play | seek --el ms N
 *   adb shell am start -n app.sable/.ui.MainActivity --es cmd mode --es value quiet|standard
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var now by mutableStateOf(NowPlayingUi())

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) askPermissions.launch(needed.toTypedArray())
        PlaybackPrefs.load(this)
        handleCommand(intent)
        val rowsFlow = SableDatabase.get(applicationContext).library().observeRows(SableDatabase.MATH_VERSION)
        setContent {
            val rows by rowsFlow.collectAsState(initial = emptyList())
            val settings by PlaybackPrefs.settings.collectAsState()
            val gain by NowPlaying.gain.collectAsState()
            PlayerScreen(
                rows, settings, now, gain,
                onPlayAlbum = { a -> play(a.tracks.map { QueueId.request(it.mediaId, albumIntent = true) }, 0) },
                onPlaySongWorld = { list, i -> play(list.drop(i).map { QueueId.request(it.mediaId, albumIntent = false) }, 0) },
                onMode = { m -> PlaybackPrefs.update(this) { it.copy(mode = m) } },
                onAlbumMode = { PlaybackPrefs.update(this) { it.copy(albumMode = PlaybackPrefs.AlbumMode.entries[(it.albumMode.ordinal + 1) % 3]) } },
                onPrev = { withController { it.seekToPrevious() } },
                onPlayPause = { withController { c -> if (c.isPlaying) c.pause() else c.play() } },
                onNext = { withController { it.seekToNext() } },
                onRescan = {
                    lifecycleScope.launch(Dispatchers.Default) {
                        LibraryScanner(applicationContext).syncMediaStore()
                        ScanWorker.enqueue(applicationContext, requireCharging = false)
                    }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        controllerFuture = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh(player)
            })
            refresh(c)
            pending.forEach { it(c) }
            pending.clear()
        }, mainExecutor)
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    private fun refresh(p: Player) {
        val m = p.mediaMetadata
        now = NowPlayingUi(m.title?.toString() ?: "", m.artist?.toString() ?: "", p.isPlaying, p.currentMediaItem != null)
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: pending.add(action)
    }

    private fun play(requestIds: List<String>, start: Int) = withController { c ->
        c.setMediaItems(requestIds.map { MediaItem.Builder().setMediaId(it).build() }, start, 0L)
        c.prepare()
        c.play()
    }

    /** The library in album order (for the adb play commands). */
    private suspend fun libraryInAlbumOrder() = SableDatabase.get(applicationContext).library().allTracks()
        .sortedWith(compareBy({ it.albumArtist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.track }))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommand(intent)
    }

    private fun handleCommand(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd") ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            when (cmd) {
                "sync" -> Log.i(TAG, "sync: ${LibraryScanner(applicationContext).syncMediaStore()} tracks")
                "scan" -> ScanWorker.enqueue(applicationContext, requireCharging = false)
                "stats" -> stats()
                "ensure" -> {  // cache test: hash + reuse-or-analyse matching tracks, logging which happened
                    LibraryScanner(applicationContext).syncMediaStore()
                    val m = intent.getStringExtra("match") ?: ""
                    val mime = intent.getStringExtra("mime")
                    for (t in SableDatabase.get(applicationContext).library().allTracks().filter { it.displayName.contains(m, true) && (mime == null || it.mime == mime) }) {
                        val t0 = System.nanoTime()
                        val r = TrackAnalyzer(applicationContext).ensureAnalyzed(t, force = intent.getBooleanExtra("force", false))
                        Log.i(TAG, "ENSURE ${t.displayName} id ${t.mediaId} reused=${r.reused} ${(System.nanoTime() - t0) / 1_000_000} ms hash ${r.analysis.contentHash.take(12)}")
                    }
                    Log.i(TAG, "ENSURE done")
                }
                "cancel" -> androidx.work.WorkManager.getInstance(applicationContext).cancelUniqueWork(ScanWorker.NAME).also { Log.i(TAG, "scan cancelled") }
                "bench" -> bench(intent.getStringExtra("match") ?: "", intent.getBooleanExtra("mc", false))
                "playalbum" -> {
                    val m = intent.getStringExtra("match") ?: ""
                    val album = libraryInAlbumOrder().filter { it.album.contains(m, true) }.groupBy { it.albumId }.values.firstOrNull() ?: return@launch
                    runOnUiThread { play(album.map { QueueId.request(it.mediaId, albumIntent = true) }, intent.getIntExtra("start", 0)) }
                }
                "playsong" -> {
                    val m = intent.getStringExtra("match") ?: ""
                    val all = libraryInAlbumOrder()
                    val t = all.firstOrNull { it.title.contains(m, true) || it.displayName.contains(m, true) } ?: return@launch
                    val album = all.filter { it.albumId == t.albumId && it.albumArtist == t.albumArtist }
                    val from = album.indexOfFirst { it.mediaId == t.mediaId }
                    runOnUiThread { play(album.drop(from).map { QueueId.request(it.mediaId, albumIntent = false) }, 0) }
                }
                "next" -> runOnUiThread { withController { it.seekToNext() } }
                "prev" -> runOnUiThread { withController { it.seekToPrevious() } }
                "pause" -> runOnUiThread { withController { it.pause() } }
                "play" -> runOnUiThread { withController { it.play() } }
                "seek" -> runOnUiThread { withController { it.seekTo(intent.getLongExtra("ms", 0L)) } }
                "mode" -> PlaybackPrefs.update(applicationContext) {
                    it.copy(mode = if (intent.getStringExtra("value") == "quiet") PlaybackPrefs.Mode.QUIET else PlaybackPrefs.Mode.STANDARD)
                }
            }
        }
    }

    /** Full analysis + JIT on matching tracks, logging decode/engine/JIT timings. */
    private suspend fun bench(match: String, forceMediaCodec: Boolean = false) {
        LibraryScanner(applicationContext).syncMediaStore()
        val dao = SableDatabase.get(applicationContext).library()
        val tracks = dao.allTracks().filter { it.title.contains(match, ignoreCase = true) || it.displayName.contains(match, ignoreCase = true) }
        for (t in tracks) {
            val a = TrackAnalyzer(applicationContext).analyze(Uri.parse(t.uri), "bench", forceMediaCodec = forceMediaCodec)
            val j = JitAnalyzer(applicationContext).analyze(Uri.parse(t.uri), t.durationMs)
            Log.i(TAG, "BENCH ${t.displayName} | ${t.mime} | dur ${t.durationMs / 1000.0}s | decode ${a.decodeMs} ms | engine ${a.engineMs} ms | " +
                "clipped ${a.decodeClipped} | I ${a.integratedLufs} | gainStd ${a.gainStandardDb} | ${a.character} | JIT ${j.millis} ms ${j.json}")
            Log.i(TAG, "JSON ${t.displayName} ${a.json.take(600)}")
        }
        Log.i(TAG, "BENCH done (${tracks.size} tracks)")
    }

    /** Whole-library summary from the cache (M4 report). */
    private suspend fun stats() {
        LibraryScanner(applicationContext).syncMediaStore()
        val dao = SableDatabase.get(applicationContext).library()
        val tracks = dao.allTracks()
        var analysed = 0; var audioMs = 0L; var workMs = 0L; var worstPer4 = 0.0; var worstName = ""; var clipped = 0
        val chars = HashMap<String, Int>()
        val hashes = HashSet<String>()
        for (t in tracks) {
            val h = t.contentHash ?: continue
            val a = dao.analysis(h, SableDatabase.MATH_VERSION) ?: continue
            analysed++
            if (!hashes.add(h)) continue
            audioMs += t.durationMs
            val ms = a.decodeMs + a.engineMs
            workMs += ms
            val per4 = ms * 240_000.0 / maxOf(1L, t.durationMs)
            if (per4 > worstPer4) { worstPer4 = per4; worstName = t.displayName }
            if (a.decodeClipped) clipped++
            chars[a.character] = (chars[a.character] ?: 0) + 1
        }
        Log.i(TAG, "STATS tracks ${tracks.size} analysed $analysed unique ${hashes.size} | audio ${audioMs / 60000} min | " +
            "analysis ${workMs / 1000} s total, ${"%.2f".format(workMs * 240_000.0 / maxOf(1L, audioMs) / 1000)} s per 4 min avg, worst ${"%.2f".format(worstPer4 / 1000)} s ($worstName) | " +
            "decodeClipped $clipped | characters $chars")
    }

    companion object {
        const val TAG = "SableBench"
    }
}
