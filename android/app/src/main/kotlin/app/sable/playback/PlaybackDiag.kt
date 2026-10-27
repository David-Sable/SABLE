package app.sable.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small playback log kept on the phone (files/playback-log.txt, the newest ~256 KB), so a skip or dropout heard away
 * from the PC leaves evidence: audio underruns, errors, interruptions (focus, unplugged), skips and who sent them, route
 * changes and service restarts. Read it with `adb shell am start -n app.sable/.ui.MainActivity --es cmd diag`
 * (printed under the logcat tag "SableDiag"). Writes happen off the audio thread.
 */
object PlaybackDiag {
    private const val TAG = "SableDiag"
    private const val MAX_BYTES = 256 * 1024L
    private val io = Executors.newSingleThreadExecutor()
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "playback-log.txt")
    }

    fun log(msg: String) {
        val line = "${clock.format(Date())} $msg"
        Log.i(TAG, line)
        val f = file ?: return
        io.execute {
            runCatching {
                if (f.length() > MAX_BYTES) {  // keep the newer half
                    val text = f.readText()
                    f.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
                f.appendText(line + "\n")
            }
        }
    }

    /** Prints the whole log to logcat, line by line. */
    fun dump() = io.execute {
        val f = file ?: return@execute
        if (!f.exists()) Log.i(TAG, "(empty)") else f.forEachLine { Log.i(TAG, it) }
    }

    private fun idOf(item: MediaItem?) = item?.mediaId ?: "-"

    val listener = object : AnalyticsListener {
        override fun onAudioUnderrun(t: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
            log("UNDERRUN buffer ${bufferSizeMs} ms, ${elapsedSinceLastFeedMs} ms since last feed, at ${t.currentPlaybackPositionMs} ms")

        override fun onPlayerError(t: AnalyticsListener.EventTime, error: PlaybackException) =
            log("ERROR ${error.errorCodeName}: ${error.message} (${error.cause?.javaClass?.simpleName}: ${error.cause?.message})")

        override fun onAudioSinkError(t: AnalyticsListener.EventTime, audioSinkError: Exception) =
            log("SINK_ERROR ${audioSinkError.javaClass.simpleName}: ${audioSinkError.message}")

        override fun onPlayWhenReadyChanged(t: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) =
            log("PLAY_WHEN_READY $playWhenReady reason ${playReason(reason)}")

        override fun onPlaybackSuppressionReasonChanged(t: AnalyticsListener.EventTime, reason: Int) =
            log("SUPPRESSED ${if (reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) "no" else "reason $reason (1 = transient focus loss)"}")

        override fun onPlaybackStateChanged(t: AnalyticsListener.EventTime, state: Int) {
            if (state == Player.STATE_BUFFERING || state == Player.STATE_ENDED) log("STATE ${if (state == Player.STATE_BUFFERING) "buffering" else "ended"} at ${t.currentPlaybackPositionMs} ms")
        }

        override fun onPositionDiscontinuity(t: AnalyticsListener.EventTime, old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return  // logged as a transition
            log("JUMP ${idOf(old.mediaItem)} @${old.positionMs} -> ${idOf(new.mediaItem)} @${new.positionMs} reason $reason (1 seek, 3 skip, 4 remove, 5 internal)")
        }

        override fun onMediaItemTransition(t: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) =
            log("ITEM ${idOf(mediaItem)} reason $reason (0 repeat, 1 auto, 2 seek/skip, 3 playlist changed)")
    }

    private fun playReason(r: Int) = when (r) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user/controller"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus lost"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "output unplugged/disconnected"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of item"
        else -> "$r"
    }
}
