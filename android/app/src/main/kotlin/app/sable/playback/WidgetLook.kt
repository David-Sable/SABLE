package app.sable.playback

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.RemoteViews
import app.sable.R

/**
 * How each home-screen widget looks, chosen on its settings screen (long-press the widget, Settings): the card's opacity from
 * clear (the wallpaper) to solid, Sable black or the cover's own glow, the play button in ivory or brass, and the album line.
 * Kept per widget; a new widget starts from the last look chosen. Display only.
 */
object WidgetLook {
    data class Look(val opacity: Int = 100, val glow: Boolean = false, val album: Boolean = true, val brass: Boolean = false)

    private const val PREFS = "sable_widget"
    private const val BLACK = 0x0A0A09  // SableColors.base for the card (w_card)
    private const val BRASS = 0xFFB5975A.toInt()
    private const val IVORY = 0xFFE8E1D3.toInt()

    fun get(context: Context, id: Int): Look {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = if (p.contains("$id.opacity")) "$id" else "last"
        return Look(p.getInt("$k.opacity", 100), p.getBoolean("$k.glow", false), p.getBoolean("$k.album", true), p.getBoolean("$k.brass", false))
    }

    fun set(context: Context, id: Int, look: Look) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (k in listOf("$id", "last")) {
            e.putInt("$k.opacity", look.opacity).putBoolean("$k.glow", look.glow).putBoolean("$k.album", look.album).putBoolean("$k.brass", look.brass)
        }
        e.apply()
    }

    /** The card behind the widget: Sable black, or the cover's glow, at the chosen opacity; the play button's colour. */
    fun apply(rv: RemoteViews, look: Look, art: Bitmap?, square: Boolean) {
        val alpha = (look.opacity.coerceIn(0, 100) * 255 + 50) / 100
        val glow = if (look.glow && art != null && !square) glowOf(art) else null
        if (glow != null) {
            rv.setImageViewBitmap(R.id.widget_ambient, glow)
            rv.setInt(R.id.widget_ambient, "setImageAlpha", alpha)
            rv.setViewVisibility(R.id.widget_ambient, View.VISIBLE)
            rv.setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(0))  // the glow is the card
        } else {
            rv.setViewVisibility(R.id.widget_ambient, View.GONE)
            rv.setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf((alpha shl 24) or BLACK))
        }
        rv.setColorStateList(R.id.widget_play, "setBackgroundTintList", ColorStateList.valueOf(if (look.brass) BRASS else IVORY))
    }

    @Volatile private var glowFor: Bitmap? = null
    @Volatile private var glowCache: Bitmap? = null

    /**
     * The cover's glow: the art shrunk to a few pixels (only its broad colours survive) and opened up again with smoothing, so
     * it reads as a soft blur of the cover, then darkened so the ivory text and buttons stay clear on it. Made once per cover.
     */
    fun glowOf(art: Bitmap): Bitmap {
        if (glowFor === art) glowCache?.let { return it }
        val src = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art  // a GPU bitmap cannot be drawn in software
        val tiny = Bitmap.createScaledBitmap(src, 6, 6, true)
        val soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(tiny, 24, 24, true), 96, 96, true)
        val out = soft.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(0x99000000.toInt())  // 60 % black over it
        glowFor = art; glowCache = out
        return out
    }
}
