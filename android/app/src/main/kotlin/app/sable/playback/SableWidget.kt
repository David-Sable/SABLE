package app.sable.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.SizeF
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import app.sable.R
import app.sable.ui.MainActivity

/**
 * The home-screen widget: cover, title, artist, previous / play-pause / next. Its buttons send media-button presses to
 * Media3's receiver, so they work like a headset's: they reach the playing session, or wake Sable and resume the last
 * queue when it is not running. PlaybackService pushes what is playing with [show].
 */
class SableWidget : AppWidgetProvider() {
    /** Previous / next: straight to the playing session (Media3's button receiver only accepts play when it may start the service). */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NEXT -> PlaybackService.skip(next = true)
            ACTION_PREV -> PlaybackService.skip(next = false)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Placed (or the launcher asked): show the last state, or the idle card until something plays.
        manager.updateAppWidget(ids, views(context, last))
    }

    data class State(val title: String, val artist: String, val playing: Boolean, val art: Bitmap?)

    companion object {
        @Volatile private var last: State? = null
        private const val ACTION_NEXT = "app.sable.widget.NEXT"
        private const val ACTION_PREV = "app.sable.widget.PREV"

        /** Sable stopped: keep the song on the card, but show play (not pause). */
        fun stopped(context: Context) = show(context, last?.copy(playing = false))

        /** Updates every placed widget. Cheap when none is placed. */
        fun show(context: Context, state: State?) {
            last = state
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SableWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, state))
        }

        /** One row tall (4x1) gets the compact layout, two rows the roomy one (title on two lines, controls underneath). */
        private fun views(context: Context, s: State?) = RemoteViews(mapOf(
            SizeF(250f, 56f) to layout(context, s, R.layout.widget_player),
            SizeF(250f, 110f) to layout(context, s, R.layout.widget_player_large),
        ))

        private fun layout(context: Context, s: State?, layoutId: Int) = RemoteViews(context.packageName, layoutId).apply {
            setTextViewText(R.id.widget_title, s?.title ?: context.getString(R.string.widget_idle_title))
            setTextViewText(R.id.widget_artist, s?.artist ?: context.getString(R.string.widget_idle_artist))
            // No cover (idle, or a song without art): the lion on the quiet tile, tinted by the theme (the cover view stays empty).
            if (s?.art != null) setImageViewBitmap(R.id.widget_art, s.art) else setImageViewBitmap(R.id.widget_art, null)
            setViewVisibility(R.id.widget_lion, if (s?.art != null) View.GONE else View.VISIBLE)
            setImageViewResource(R.id.widget_play, if (s?.playing == true) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            setOnClickPendingIntent(R.id.widget_play, button(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1))
            setOnClickPendingIntent(R.id.widget_next, own(context, ACTION_NEXT, 2))
            setOnClickPendingIntent(R.id.widget_prev, own(context, ACTION_PREV, 3))
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widget_art_box, openPi)
            setOnClickPendingIntent(R.id.widget_text, openPi)
        }

        private fun own(context: Context, action: String, request: Int): PendingIntent =
            PendingIntent.getBroadcast(context, request, Intent(action).setClass(context, SableWidget::class.java), PendingIntent.FLAG_IMMUTABLE)

        private fun button(context: Context, keyCode: Int, request: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, request, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
