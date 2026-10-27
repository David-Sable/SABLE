package app.sable.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.sable.data.SableDatabase
import androidx.compose.ui.graphics.asAndroidBitmap
import app.sable.ui.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import android.os.SystemClock

/**
 * Playback (Milestone 5): ExoPlayer whose audio goes through [SableAudioSink] (float, per-item gain), a MediaSession for
 * the system controls and lock screen, and Media3's foreground media notification.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope: CoroutineScope = MainScope()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var controller: GainController
    private val gains = GainTable()

    override fun onCreate() {
        super.onCreate()
        PlaybackPrefs.load(this)
        PlaybackDiag.init(this)
        PlaybackDiag.log("SERVICE start")
        current = this
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                SableAudioSink(
                    DefaultAudioSink.Builder(context).setEnableFloatOutput(true).build(),
                    gains,
                    keyOf = ::mediaIdOf,
                    onItemGain = { key, db, manual -> controller.onItemGain(key, db, manual) },
                )
        }.setMediaCodecSelector(androidCodecsFirst)
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addAnalyticsListener(PlaybackDiag.listener)
        controller = GainController(this, player, gains, scope).also { it.start() }
        // Sleep timer: when due, pause at the end of the playing song (never mid-song), then clear itself.
        scope.launch {
            SleepTimer.state.collectLatest { st ->
                player.pauseAtEndOfMediaItems = st != null && st.deadlineMs == null
                if (st?.deadlineMs != null) {
                    delay(maxOf(0L, st.deadlineMs - SystemClock.elapsedRealtime()))
                    PlaybackDiag.log("SLEEP due: stopping after this song")
                    SleepTimer.due()
                }
            }
        }
        // Repeat is remembered between sessions.
        val playerPrefs = getSharedPreferences("sable_player", MODE_PRIVATE)
        player.repeatMode = playerPrefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        player.addListener(object : Player.Listener {
            // Pick up where you left off: the queue, the song and the spot in it are kept as playback moves.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { saveResume(); updateWidget() }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = saveResume()
            override fun onIsPlayingChanged(isPlaying: Boolean) { saveResume(); updateWidget() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = saveResume()

            override fun onRepeatModeChanged(repeatMode: Int) {
                PlaybackDiag.log("REPEAT ${listOf("off", "one", "all").getOrElse(repeatMode) { "$repeatMode" }}")
                playerPrefs.edit().putInt("repeat", repeatMode).apply()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && SleepTimer.state.value != null) {
                    PlaybackDiag.log("SLEEP stopped at the end of the song")
                    SleepTimer.cancel()
                }
            }
        })
        // While playing, the spot in the song is also saved every 10 s (a crash or a swipe-away loses at most that).
        scope.launch { while (true) { delay(10_000); if (player.isPlaying) saveResume() } }
        restoreResume()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setCallback(callback).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var widgetJob: kotlinx.coroutines.Job? = null

    /** Pushes what is playing to the home-screen widget (the cover is loaded off the main thread). */
    private fun updateWidget() {
        val item = player.currentMediaItem
        val playing = player.isPlaying
        widgetJob?.cancel()
        widgetJob = scope.launch {
            if (item == null) { SableWidget.show(this@PlaybackService, null); return@launch }
            val m = item.mediaMetadata
            val t = QueueId.trackId(item.mediaId)?.let { SableDatabase.get(this@PlaybackService).library().track(it) }
            val art = t?.let { runCatching { app.sable.ui.ArtCache.load(this@PlaybackService, it.albumId, it.uri, 192) }.getOrNull() }
            SableWidget.show(this@PlaybackService, SableWidget.State(
                m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), playing,
                art?.asAndroidBitmap(),
            ))
        }
    }

    @Volatile private var resumptionAsked = false
    @Volatile private var queueRequested = false  // a controller asked for a queue: the automatic restore must not replace it
    private val resumePrefs by lazy { getSharedPreferences("sable_resume", MODE_PRIVATE) }

    /** Saves the queue as request ids ("<trackId>:<A|S>": the album or song world each entry was started in). */
    private fun saveResume() {
        val n = player.mediaItemCount
        if (n == 0) return
        val ids = (0 until n).joinToString(",") { i ->
            val id = player.getMediaItemAt(i).mediaId
            QueueId.request(QueueId.trackId(id) ?: return@joinToString "", QueueId.albumIntent(id))
        }
        resumePrefs.edit().putString("ids", ids).putInt("index", player.currentMediaItemIndex)
            // A queue that played to its end starts its last song again rather than sitting at the very end.
            .putLong("positionMs", if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0L)).apply()
    }

    /**
     * Puts the last queue back, paused at the same song and spot, when the service starts with nothing loaded. Never
     * starts playback by itself; songs no longer in the library are skipped.
     */
    private fun restoreResume() {
        scope.launch {
            // Plans only if nobody asked for anything meanwhile (a plan of the old queue would drop the new one's gains).
            val r = resumeItems { !resumptionAsked && !queueRequested && player.mediaItemCount == 0 } ?: return@launch
            // The app or a play button (which resumes the same queue itself) already started something.
            if (resumptionAsked || queueRequested || player.mediaItemCount > 0) return@launch
            player.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs)
            player.prepare()
            PlaybackDiag.log("RESUME ${r.mediaItems.size} songs at ${r.startIndex + 1}, ${r.startPositionMs / 1000} s (paused)")
        }
    }

    /** The saved queue, resolved and planned, or null if there is none (or none of its songs is still in the library). */
    private suspend fun resumeItems(stillWanted: () -> Boolean = { true }): MediaSession.MediaItemsWithStartPosition? {
        val ids = resumePrefs.getString("ids", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (ids.isEmpty()) return null
        val items = resolve(ids.map { MediaItem.Builder().setMediaId(it).build() })
        if (items.isEmpty()) return null
        val start = resumePrefs.getInt("index", 0).coerceIn(0, items.size - 1)
        // Songs removed from the library shift the index: then start the song from its beginning.
        val pos = if (items.size == ids.size) resumePrefs.getLong("positionMs", 0L) else 0L
        if (!stillWanted()) return null
        controller.prepareStart(items.map { it.mediaId }, start)  // JIT wait + plan before the first note
        controller.markResumed(items[start].mediaId)  // a resumed song is not feedback (§19.1)
        return MediaSession.MediaItemsWithStartPosition(items, start, pos)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        PlaybackDiag.log("TASK removed (swiped away), playing=${player.playWhenReady}")
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        PlaybackDiag.log("SERVICE stop")
        current = null
        SableWidget.stopped(this)
        saveResume()
        SleepTimer.cancel()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    /** Controllers send request ids ("<trackId>:<A|S>"); the service resolves them to playable items with unique ids. */
    private val callback = object : MediaSession.Callback {
        // Who asked for a skip, pause or seek: this app, the car, a headset button, the notification...
        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            if (playerCommand in loggedCommands) PlaybackDiag.log("COMMAND ${commandName(playerCommand)} from ${controller.packageName}")
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future { resolve(mediaItems) }

        // A play button pressed while Sable was not running (car, headphones, the system media card): the last queue.
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            resumptionAsked = true
            PlaybackDiag.log("RESUMPTION asked by ${controller.packageName} (for playback: $isForPlayback)")
            resumeItems() ?: throw UnsupportedOperationException("nothing to resume")
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            queueRequested = true
            val items = resolve(mediaItems)
            PlaybackDiag.log("QUEUE ${items.size} songs from ${controller.packageName}, start ${startIndex + 1}")
            val start = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, maxOf(0, items.size - 1))
            this@PlaybackService.controller.prepareStart(items.map { it.mediaId }, start)  // JIT wait + plan before playing
            MediaSession.MediaItemsWithStartPosition(items, start, if (startIndex == C.INDEX_UNSET) 0 else startPositionMs)
        }
    }

    private suspend fun resolve(requests: List<MediaItem>): MutableList<MediaItem> {
        val dao = SableDatabase.get(this).library()
        val out = ArrayList<MediaItem>(requests.size)
        for (r in requests) {
            val id = QueueId.unique(r.mediaId) ?: continue
            val t = dao.track(QueueId.trackId(id)!!) ?: continue
            out += MediaItem.Builder()
                .setMediaId(id)
                .setUri(t.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(t.title.ifBlank { t.displayName }).setArtist(t.artist).setAlbumTitle(t.album)
                        .setAlbumArtist(t.albumArtist).setTrackNumber(t.track).setDiscNumber(t.disc).build()
                )
                .build()
        }
        return out
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch {
            try { f.set(block()) } catch (e: Throwable) { f.setException(e) }
        }
        return f
    }

    companion object {
        @Volatile private var current: PlaybackService? = null

        /** Debug: the change-of-place path without a real device (GainController.simulatePlaceChange). */
        fun debugReroute() { val s = current ?: return; s.main.post { s.controller.simulatePlaceChange() } }

        /** The widget's previous / next: acts on the running player; does nothing when Sable is not playing anything. */
        fun skip(next: Boolean) {
            val s = current ?: return
            s.main.post {
                PlaybackDiag.log("COMMAND ${if (next) "next" else "previous"} from widget")
                if (next) s.player.seekToNext() else s.player.seekToPrevious()
            }
        }

        /** The gain table key of the item a sink configuration belongs to: its queue entry's unique media id. */
        fun mediaIdOf(timeline: Timeline, periodUid: Any?): Any? {
            if (periodUid == null || timeline.isEmpty) return null
            val idx = timeline.getIndexOfPeriod(periodUid)
            if (idx == C.INDEX_UNSET) return null
            val w = timeline.getPeriod(idx, Timeline.Period()).windowIndex
            return timeline.getWindow(w, Timeline.Window()).mediaItem.mediaId
        }

        /** Android's own Codec2 decoders first: the A17's vendor FLAC decoder mislabels 16-bit output as float (M4). */
        val androidCodecsFirst = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.name.startsWith("c2.android.")) 0 else 1 }
        }
    }
}

private val loggedCommands = setOf(
    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
)

private fun commandName(c: Int) = when (c) {
    Player.COMMAND_PLAY_PAUSE -> "play/pause"
    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "next"
    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "previous"
    Player.COMMAND_STOP -> "stop"
    else -> "seek"
}
