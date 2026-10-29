package app.sable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import app.sable.ui.theme.SableColors
import org.json.JSONObject
import kotlin.math.abs

private val MoodIvory = androidx.compose.ui.graphics.Color(0xFFE8E1D3)  // the accent
private val MoodWarm = androidx.compose.ui.graphics.Color(0xFFCDB88F)   // brass, softened: stays under ~30 % saturation
private val MoodCool = androidx.compose.ui.graphics.Color(0xFF9DAEC2)   // a cool grey-blue

/** The engine's view of a song: short-term loudness every 0.5 s and the anchor (the body Sable balances to). */
data class SongShape(val lufs: List<Double?>, val anchor: List<Pair<Double, Double>>, val durationSec: Double, val tilt: List<Double?> = emptyList()) {
    companion object {
        fun of(json: JSONObject?): SongShape? {
            json ?: return null
            val s = json.optJSONArray("shape") ?: return null
            val l = List(s.length()) { i -> if (s.isNull(i)) null else s.optDouble(i) }
            val a = json.optJSONArray("anchor")
            val anchor = if (a == null) emptyList() else List(a.length()) { i -> a.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }
            val fs = json.optDouble("sampleRateHz", 0.0)
            val dur = if (fs > 0) json.optDouble("frames", 0.0) / fs else l.size * 0.5
            val tl = json.optJSONArray("shapeTilt")  // MATH §8.5 (analysis 1.9 and later): the song's brightness every 0.5 s
            val tilt = if (tl == null) emptyList() else List(tl.length()) { i -> if (tl.isNull(i)) null else tl.optDouble(i) }
            return SongShape(l, anchor, dur, tilt)
        }
    }
}

/**
 * Beautiful, not technical: a soft loudness silhouette, the anchor glowing in the accent, the played part brighter.
 * [progress] is read at draw time, so the playhead moves at the display rate without recomposition.
 */
@Composable
fun LoudnessShape(shape: SongShape, progress: () -> Float, modifier: Modifier = Modifier, mood: Boolean = false, onSeek: ((Float) -> Unit)? = null) {
    val pts = remember(shape) {
        val vals = shape.lufs.map { it ?: Double.NaN }
        val sorted = vals.filter { !it.isNaN() }.sorted()
        val top = sorted.lastOrNull() ?: -10.0
        // Scale to the song's own range (10th percentile minus a margin, at most 24 LU deep) so dense masters still show shape.
        val p10 = sorted.getOrNull(sorted.size / 10) ?: (top - 24.0)
        val floor = maxOf(top - 24.0, minOf(p10 - 4.0, top - 8.0))
        // Normalised heights 0..1, lightly smoothed (3-point) so the silhouette reads as a shape, not a measurement.
        val h = vals.map { v -> if (v.isNaN()) 0f else ((v - floor) / (top - floor)).coerceIn(0.0, 1.0).toFloat() }
        List(h.size) { i -> (h[maxOf(0, i - 1)] + 2 * h[i] + h[minOf(h.size - 1, i + 1)]) / 4f }
    }
    // Mood colours (display only): the silhouette's colour follows the song's own brightness (spectral tilt, MATH §8.5) and its
    // energy (the heights above). Ivory is the middle; bright leans soft brass, dark and bassy leans cool grey-blue. Built once per
    // song; drawing it costs nothing more than the plain fill.
    val moodStops = remember(shape, pts, mood) {
        if (!mood || pts.size < 2) null else {
            val n = minOf(48, pts.size)
            Array(n) { s ->
                val i0 = s * pts.size / n
                val i1 = maxOf(i0 + 1, (s + 1) * pts.size / n).coerceAtMost(pts.size)
                val e = (i0 until i1).map { pts[it] }.average().toFloat()
                val ts = (i0 until i1).mapNotNull { shape.tilt.getOrNull(it)?.takeIf { v -> v.isFinite() } }
                val x = if (ts.isEmpty()) 0f else (((ts.average() + 1.5) / 2.0).coerceIn(-1.0, 1.0)).toFloat()  // -1 dark .. +1 bright
                val amount = abs(x) * (0.35f + 0.65f * e)  // quiet parts drift less
                val tint = androidx.compose.ui.graphics.lerp(MoodIvory, if (x >= 0) MoodWarm else MoodCool, amount.coerceIn(0f, 0.85f))
                s / (n - 1f) to tint.copy(alpha = 0.26f + 0.30f * e)  // brighter at the song's peak, dimmer in quiet parts
            }
        }
    }
    val seekMod = if (onSeek == null) Modifier else Modifier
        .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) } }
    Canvas(modifier.then(seekMod)) {
        if (pts.size < 2) return@Canvas
        val w = size.width
        val hgt = size.height
        val dur = shape.durationSec.coerceAtLeast(0.5)
        val path = Path()
        val stepX = w / (pts.size - 1)
        fun y(i: Int) = hgt - (0.06f + 0.94f * pts[i]) * hgt
        path.moveTo(0f, hgt)
        path.lineTo(0f, y(0))
        for (i in 1 until pts.size) {
            val x0 = (i - 1) * stepX
            val x1 = i * stepX
            path.quadraticTo(x0, y(i - 1), (x0 + x1) / 2, (y(i - 1) + y(i)) / 2)
        }
        path.lineTo(w, y(pts.size - 1))
        path.lineTo(w, hgt)
        path.close()
        // Anchor bands: a soft glow behind the body of the song.
        for ((a, b) in shape.anchor) {
            val x0 = (a / dur * w).toFloat()
            val x1 = (b / dur * w).toFloat()
            drawRect(SableColors.accent.copy(alpha = 0.06f), Offset(x0, 0f), Size(x1 - x0, hgt))
        }
        if (moodStops != null) drawPath(path, Brush.horizontalGradient(colorStops = moodStops, startX = 0f, endX = w))
        else drawPath(path, SableColors.textTertiary.copy(alpha = 0.35f))
        for ((a, b) in shape.anchor) {
            clipRect(left = (a / dur * w).toFloat(), right = (b / dur * w).toFloat()) {
                drawPath(path, Brush.verticalGradient(listOf(SableColors.accent.copy(alpha = 0.55f), SableColors.accent.copy(alpha = 0.12f))))
            }
        }
        val px = progress().coerceIn(0f, 1f) * w
        clipRect(right = px) { drawPath(path, SableColors.textPrimary.copy(alpha = 0.22f)) }
        drawLine(SableColors.accent, Offset(px, 0f), Offset(px, hgt), strokeWidth = 2f, cap = StrokeCap.Round)
        drawPath(path, SableColors.line, style = Stroke(1f))
    }
}
