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
        controller = GainController(this, player, gains, scope).also { it.start() }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setCallback(callback).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    /** Controllers send request ids ("<trackId>:<A|S>"); the service resolves them to playable items with unique ids. */
    private val callback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future { resolve(mediaItems) }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val items = resolve(mediaItems)
            val start = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, maxOf(0, items.size - 1))
            val excerpts = items.mapNotNull { i -> GainController.excerptMsOf(i)?.let { i.mediaId to it } }.toMap()
            this@PlaybackService.controller.prepareStart(items.map { it.mediaId }, start, excerpts)  // JIT wait + plan before playing
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
                .setClippingConfiguration(r.clippingConfiguration)  // A/B excerpts (§30.2)
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
