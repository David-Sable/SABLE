package app.sable.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
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
        for (id in ids) manager.updateAppWidget(id, views(context, last, manager, id))
    }

    /** Resized: lay it out again for the new size (the cover follows the card's height). */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, views(context, last, manager, id))
    }

    data class State(val title: String, val artist: String, val album: String, val playing: Boolean, val art: Bitmap?)

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
            for (id in ids) manager.updateAppWidget(id, views(context, state, manager, id))
        }

        /** Every placed widget again, after its settings changed (WidgetSettingsActivity). */
        fun refresh(context: Context) = show(context, last)

        /** The last song shown, for the settings screen's live preview. */
        fun lastState(): State? = last

        /**
         * One layout per size the launcher may show (portrait and landscape), chosen and sized for that size:
         * under 100 dp tall the one-row card; narrow (2x2) the full-bleed cover; otherwise the cover beside the text
         * with the controls centred under the text.
         */
        private fun views(context: Context, s: State?, manager: AppWidgetManager, id: Int): RemoteViews {
            val sizes = manager.getAppWidgetOptions(id)
                .getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                ?.takeIf { it.isNotEmpty() } ?: listOf(SizeF(250f, 56f), SizeF(250f, 110f), SizeF(250f, 180f), SizeF(150f, 150f))
            val look = WidgetLook.get(context, id)
            return RemoteViews(sizes.associateWith { layout(context, s, it, look) })
        }

        private fun layout(context: Context, s: State?, size: SizeF, look: WidgetLook.Look): RemoteViews {
            val w = size.width
            val h = size.height
            // One editorial grid from two rows up (the cover as tall as the card, text and controls beside it); one row; or the
            // full-bleed cover when the card is narrow (2x2).
            val layoutId = when {
                h < 100f -> R.layout.widget_player
                w < 260f -> R.layout.widget_player_square
                else -> R.layout.widget_player_large
            }
            return layout(context, s, layoutId).apply {
                if (layoutId == R.layout.widget_player_large) {
                    // The cover as big as the card allows (its height less padding), never more than ~45 % of its width; the
                    // column beside it is exactly as tall, so the text hangs from the cover's top and the controls sit on its bottom.
                    val cover = minOf(h - 28f, w * 0.45f).coerceAtLeast(72f)
                    setViewLayoutWidth(R.id.widget_art_box, cover, TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutHeight(R.id.widget_art_box, cover, TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutHeight(R.id.widget_column, cover, TypedValue.COMPLEX_UNIT_DIP)
                    setViewVisibility(R.id.widget_album, if (look.album && !s?.album.isNullOrBlank()) View.VISIBLE else View.GONE)
                } else setViewVisibility(R.id.widget_album, View.GONE)
                WidgetLook.apply(this, look, s?.art, square = layoutId == R.layout.widget_player_square)
                if (layoutId == R.layout.widget_player_square) {
                    // On a cover the text sits on the dark shade; without one, the shade goes and the text takes Sable's own colours.
                    val onArt = s?.art != null
                    setViewVisibility(R.id.widget_shade, if (onArt) View.VISIBLE else View.GONE)
                    setColorStateList(R.id.widget_title, "setTextColor", if (onArt) R.color.w_on_art else R.color.w_text)
                    setColorStateList(R.id.widget_artist, "setTextColor", if (onArt) R.color.w_on_art2 else R.color.w_text2)
                }
            }
        }

        private fun layout(context: Context, s: State?, layoutId: Int) = RemoteViews(context.packageName, layoutId).apply {
            setTextViewText(R.id.widget_title, s?.title ?: context.getString(R.string.widget_idle_title))
            setTextViewText(R.id.widget_artist, s?.artist ?: context.getString(R.string.widget_idle_artist))
            setTextViewText(R.id.widget_album, s?.album.orEmpty())
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
