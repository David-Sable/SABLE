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

/** The engine's view of a song: short-term loudness every 0.5 s and the anchor (the body Sable balances to). */
data class SongShape(val lufs: List<Double?>, val anchor: List<Pair<Double, Double>>, val durationSec: Double) {
    companion object {
        fun of(json: JSONObject?): SongShape? {
            json ?: return null
            val s = json.optJSONArray("shape") ?: return null
            val l = List(s.length()) { i -> if (s.isNull(i)) null else s.optDouble(i) }
            val a = json.optJSONArray("anchor")
            val anchor = if (a == null) emptyList() else List(a.length()) { i -> a.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }
            val fs = json.optDouble("sampleRateHz", 0.0)
            val dur = if (fs > 0) json.optDouble("frames", 0.0) / fs else l.size * 0.5
            return SongShape(l, anchor, dur)
        }
    }
}

/**
 * Beautiful, not technical: a soft loudness silhouette, the anchor glowing in the accent, the played part brighter.
 * [progress] is read at draw time, so the playhead moves at the display rate without recomposition.
 */
@Composable
fun LoudnessShape(shape: SongShape, progress: () -> Float, modifier: Modifier = Modifier, onSeek: ((Float) -> Unit)? = null) {
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
        drawPath(path, SableColors.textTertiary.copy(alpha = 0.35f))
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
