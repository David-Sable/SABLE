package app.sable.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/**
 * SABLE design tokens (dark only, whatever the phone is set to; David, 2026-10-29). Every colour, type style, spacing step and motion curve in the UI comes from here.
 * True black base for AMOLED, near-black matte surfaces layered on top, warm ivory as the accent and brushed brass on the
 * play buttons.
 */
object SableColors {
    val base = Color(0xFF000000)
    val surface1 = Color(0xFF0A0A09)
    val surface2 = Color(0xFF121110)
    val surface3 = Color(0xFF1B1A17)
    val line = Color(0xFF24221E)
    val textPrimary = Color(0xFFEDE8DF)
    val textSecondary = Color(0xFFA39D92)
    val textTertiary = Color(0xFF6B665E)
    val accent = Color(0xFFE8E1D3)      // warm ivory
    val accentAlt = Color(0xFFB5975A)   // brushed brass, Sable's signature on the play buttons
    val onAccent = Color(0xFF0A0A09)
    val accentSoft get() = accent.copy(alpha = 0.14f)
}

object SableType {
    private val serif = FontFamily.Serif
    private val sans = FontFamily.SansSerif
    val wordmark = TextStyle(fontFamily = sans, fontWeight = FontWeight.Light, fontSize = 22.sp, letterSpacing = 7.sp)
    val display = TextStyle(fontFamily = serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.2).sp)
    val title = TextStyle(fontFamily = serif, fontWeight = FontWeight.Normal, fontSize = 23.sp, lineHeight = 29.sp)
    val headline = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val label = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.3.sp)
    val caption = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
    val overline = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.6.sp)
    val number = TextStyle(fontFamily = serif, fontWeight = FontWeight.Light, fontSize = 44.sp, letterSpacing = (-0.5).sp)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp)
}

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    val gutter = 20.dp
}

object Radius {
    val art = 6.dp
    val card = 14.dp
}

/** Slow, confident motion: one easing curve, two durations. */
object Motion {
    val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val SLOW = 480
    const val MEDIUM = 300
    fun <T> slow() = tween<T>(SLOW, easing = easing)
    fun <T> medium() = tween<T>(MEDIUM, easing = easing)
    /** Now Playing's sheet and the cover's flight: durations in ms (x the live tuning factor), one easing so they move as one. */
    const val SHEET_IN = 520
    const val SHEET_OUT = 340
    /** Arriving: moves at once (15% of the way in the first 5% of the time) and lands with a shorter tail than the shared easing,
     *  whose slow start hid the sheet behind the fading mini player for the first frames (David, 2026-10-29). */
    val enterEasing = CubicBezierEasing(0.1f, 0.3f, 0.3f, 1f)
    fun <T> sheetIn(m: Float) = tween<T>((SHEET_IN * m).toInt().coerceAtLeast(80), easing = enterEasing)
    /** Leaving starts at speed (a flick is already moving) and keeps it into the landing: measured at 60 fps, a long settle spent
     *  320 ms creeping the last few millimetres into the mini player's corner, which read as delayed (David, 2026-10-29). */
    val exitEasing = CubicBezierEasing(0.1f, 0.4f, 0.6f, 1f)
    fun <T> sheetOut(m: Float) = tween<T>((SHEET_OUT * m).toInt().coerceAtLeast(80), easing = exitEasing)
    /** Now Playing's open and close: critically damped (no overshoot, so no bounce), about 350 ms from rest, and it takes the finger's
     *  speed when a swipe lets go. The tiny threshold keeps the last pixels from snapping into place. */
    val sheetSpring = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 280f, visibilityThreshold = 0.0005f)
    /** One family (David, 2026-10-29: the whole app should move like Now Playing): every larger motion is a critically damped
     *  spring, no overshoot, and the smaller the thing the quicker it settles. Now Playing 280 (~350 ms); a sheet over it (Why, the
     *  song menu, playlists, sleep) and a section opening 380 (~300 ms); a page coming in or going back 520 (~250 ms). */
    const val PANEL = 380f
    const val PAGE = 520f
    fun <T> panel(threshold: T? = null) = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = PANEL, visibilityThreshold = threshold)
    fun <T> page(threshold: T? = null) = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = PAGE, visibilityThreshold = threshold)
    /** A tall section opening (the Why sheet's details): the sheet's pace with an even curve. The shared easing put most of a
     *  long list's height into the first tenth of a second, so it popped open faster than everything else (David, 2026-10-29). */
    val revealEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    fun <T> reveal() = tween<T>(SHEET_IN, easing = revealEasing)
}

@Composable
fun SableTheme(content: @Composable () -> Unit) {
    val c = SableColors
    val scheme = darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent,
        background = c.base, onBackground = c.textPrimary,
        surface = c.surface1, onSurface = c.textPrimary,
        surfaceVariant = c.surface2, onSurfaceVariant = c.textSecondary,
        surfaceContainerLow = c.surface1, surfaceContainer = c.surface2, surfaceContainerHigh = c.surface3,
        outline = c.line, secondary = c.accentAlt,
    )
    MaterialTheme(colorScheme = scheme) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides app.sable.ui.QuietPress, content = content)
    }
}

/** A fine, static film grain over matte surfaces (one 96 px tile, drawn at very low alpha). */
private val grainTile: ImageBitmap by lazy {
    val n = 96
    val px = IntArray(n * n)
    val r = Random(1770)
    for (i in px.indices) {
        val v = r.nextInt(256)
        px[i] = (v shl 24) or 0x00FFFFFF  // white, alpha = noise
    }
    android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The same grain already blended into the base colour: one opaque tile, so a full-screen surface costs one pass, not two. */
private val grainOnBase: ImageBitmap by lazy {
    val n = 96
    val px = IntArray(n * n)
    val r = Random(1770)
    for (i in px.indices) {
        val a = r.nextInt(256) / 255f * 0.035f          // as drawn by grain(): white at this alpha over black
        val v = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
        px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Now Playing's surface: the base colour with the grain, in one pass, with its top corners rounded by [corner] (px, read at draw
 * time so it can follow the sheet). Only this background is rounded, not a clip over the whole sheet (a clip that large is slow
 * to draw); nothing but the background reaches the corners.
 */
fun Modifier.sheetSurface(corner: () -> Float): Modifier = drawWithCache {
    val brush = ShaderBrush(ImageShader(grainOnBase, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind {
        val r = corner()
        if (r > 0.5f) drawRoundRect(brush, size = androidx.compose.ui.geometry.Size(size.width, size.height + r), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        else drawRect(brush)
    }
}

fun Modifier.grain(alpha: Float = 0.035f): Modifier = drawWithCache {
    val brush = ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated))  // built once per size, not per frame
    onDrawBehind { drawRect(brush, alpha = alpha) }
}
