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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = saveResume()
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = saveResume()
            override fun onIsPlayingChanged(isPlaying: Boolean) = saveResume()
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
        val ids = resumePrefs.getString("ids", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (ids.isEmpty()) return
        val index = resumePrefs.getInt("index", 0)
        val pos = resumePrefs.getLong("positionMs", 0L)
        scope.launch {
            val requests = ids.map { MediaItem.Builder().setMediaId(it).build() }
            val items = resolve(requests)
            if (items.isEmpty() || player.mediaItemCount > 0) return@launch  // the app already started something new
            val start = index.coerceIn(0, items.size - 1)
            controller.prepareStart(items.map { it.mediaId }, start)
            if (player.mediaItemCount > 0) return@launch
            player.setMediaItems(items, start, if (items.size == ids.size) pos else 0L)
            player.prepare()
            PlaybackDiag.log("RESUME ${items.size} songs at ${start + 1}, ${pos / 1000} s (paused)")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        PlaybackDiag.log("TASK removed (swiped away), playing=${player.playWhenReady}")
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        PlaybackDiag.log("SERVICE stop")
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

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val items = resolve(mediaItems)
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
