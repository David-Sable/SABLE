package app.sable.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
 * SABLE design tokens (dark only). Every colour, type style, spacing step and motion curve in the UI comes from here.
 * True black base for AMOLED, near-black matte surfaces layered on top, one restrained accent (warm ivory; brushed
 * brass is kept as the alternate token, PLAN decision 2026-10-25).
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
    val accentAlt = Color(0xFFB5975A)   // brushed brass (alternate)
    val onAccent = Color(0xFF0A0A09)
    val accentSoft = accent.copy(alpha = 0.14f)
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
}

@Composable
fun SableTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SableColors.accent, onPrimary = SableColors.onAccent,
            background = SableColors.base, onBackground = SableColors.textPrimary,
            surface = SableColors.surface1, onSurface = SableColors.textPrimary,
            surfaceVariant = SableColors.surface2, onSurfaceVariant = SableColors.textSecondary,
            surfaceContainerLow = SableColors.surface1, surfaceContainer = SableColors.surface2, surfaceContainerHigh = SableColors.surface3,
            outline = SableColors.line, secondary = SableColors.accentAlt,
        ),
        content = content,
    )
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
    val bmp = android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
    bmp.asImageBitmap()
}

fun Modifier.grain(alpha: Float = 0.035f): Modifier = drawBehind {
    drawRect(ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated)), alpha = alpha)
}
