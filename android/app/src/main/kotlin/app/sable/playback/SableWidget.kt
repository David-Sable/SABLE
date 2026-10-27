package app.sable.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
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
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Placed (or the launcher asked): show the last state, or the idle card until something plays.
        manager.updateAppWidget(ids, views(context, last))
    }

    data class State(val title: String, val artist: String, val playing: Boolean, val art: Bitmap?)

    companion object {
        @Volatile private var last: State? = null

        /** Updates every placed widget. Cheap when none is placed. */
        fun show(context: Context, state: State?) {
            last = state
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SableWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, state))
        }

        private fun views(context: Context, s: State?) = RemoteViews(context.packageName, R.layout.widget_player).apply {
            setTextViewText(R.id.widget_title, s?.title ?: context.getString(R.string.widget_idle_title))
            setTextViewText(R.id.widget_artist, s?.artist ?: context.getString(R.string.widget_idle_artist))
            if (s?.art != null) setImageViewBitmap(R.id.widget_art, s.art) else setImageViewResource(R.id.widget_art, R.drawable.lion_mark)
            setImageViewResource(R.id.widget_play, if (s?.playing == true) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            setOnClickPendingIntent(R.id.widget_play, button(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1))
            setOnClickPendingIntent(R.id.widget_next, button(context, KeyEvent.KEYCODE_MEDIA_NEXT, 2))
            setOnClickPendingIntent(R.id.widget_prev, button(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 3))
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widget_art, openPi)
            setOnClickPendingIntent(R.id.widget_text, openPi)
        }

        private fun button(context: Context, keyCode: Int, request: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, request, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
