package app.sable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sable.ui.theme.SableColors

/** Hand-drawn line icons: consistent weight, no emoji or font fallbacks (Samsung renders some symbols as emoji). */
enum class Glyph { PLAY, PAUSE, NEXT, PREV, QUEUE, INFO, BACK, DOWN, SETTINGS, MOON, SEARCH, CLOSE, REPEAT, REPEAT_ONE, HEART, HEART_FILLED }

@Composable
fun Icon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = SableColors.textPrimary) {
    Canvas(modifier.size(size)) { draw(glyph, color) }
}

private fun DrawScope.draw(g: Glyph, c: Color) {
    val w = size.width
    val h = size.height
    val sw = w * 0.075f
    val stroke = Stroke(width = sw, cap = StrokeCap.Round)
    fun tri(x0: Float, x1: Float) = Path().apply {
        moveTo(x0, h * 0.18f); lineTo(x1, h * 0.5f); lineTo(x0, h * 0.82f); close()
    }
    when (g) {
        Glyph.PLAY -> drawPath(tri(w * 0.28f, w * 0.80f), c)
        Glyph.PAUSE -> {
            val bw = w * 0.16f
            drawRoundRect(c, Offset(w * 0.28f, h * 0.2f), Size(bw, h * 0.6f), CornerRadius(bw / 3))
            drawRoundRect(c, Offset(w * 0.56f, h * 0.2f), Size(bw, h * 0.6f), CornerRadius(bw / 3))
        }
        Glyph.NEXT -> {
            drawPath(tri(w * 0.18f, w * 0.68f), c)
            drawRoundRect(c, Offset(w * 0.70f, h * 0.2f), Size(w * 0.1f, h * 0.6f), CornerRadius(w * 0.03f))
        }
        Glyph.PREV -> {
            drawPath(Path().apply { moveTo(w * 0.82f, h * 0.18f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.82f, h * 0.82f); close() }, c)
            drawRoundRect(c, Offset(w * 0.20f, h * 0.2f), Size(w * 0.1f, h * 0.6f), CornerRadius(w * 0.03f))
        }
        Glyph.QUEUE -> for ((i, y) in listOf(0.3f, 0.5f, 0.7f).withIndex()) {
            drawLine(c, Offset(w * 0.18f, h * y), Offset(w * (if (i == 2) 0.58f else 0.82f), h * y), sw, StrokeCap.Round)
        }
        Glyph.INFO -> {
            drawCircle(c, w * 0.38f, style = stroke)
            drawLine(c, Offset(w * 0.5f, h * 0.46f), Offset(w * 0.5f, h * 0.70f), sw, StrokeCap.Round)
            drawCircle(c, sw * 0.75f, Offset(w * 0.5f, h * 0.32f))
        }
        Glyph.BACK -> drawPath(Path().apply { moveTo(w * 0.62f, h * 0.2f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.62f, h * 0.8f) }, c, style = stroke)
        Glyph.DOWN -> drawPath(Path().apply { moveTo(w * 0.2f, h * 0.38f); lineTo(w * 0.5f, h * 0.66f); lineTo(w * 0.8f, h * 0.38f) }, c, style = stroke)
        Glyph.MOON -> {  // a crescent: the outer disc minus an offset disc, outlined
            val outer = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.5f, h * 0.5f), w * 0.33f)) }
            val inner = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.68f, h * 0.36f), w * 0.27f)) }
            drawPath(Path().apply { op(outer, inner, androidx.compose.ui.graphics.PathOperation.Difference) }, c, style = stroke)
        }
        Glyph.SEARCH -> {
            drawCircle(c, w * 0.24f, Offset(w * 0.44f, h * 0.44f), style = stroke)
            drawLine(c, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.82f, h * 0.82f), sw, StrokeCap.Round)
        }
        Glyph.CLOSE -> {
            drawLine(c, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), sw, StrokeCap.Round)
        }
        Glyph.REPEAT, Glyph.REPEAT_ONE -> {  // a loop with an arrow at the end of each run (top right, bottom left); REPEAT_ONE adds a 1 in the middle
            // two open runs, each ending in an arrowhead at a corner (top right, bottom left)
            val l = w * 0.07f; val r = w * 0.93f; val t = h * 0.20f; val b = h * 0.80f; val k = w * 0.15f
            val join = StrokeJoin.Round
            val s = Stroke(width = sw, cap = StrokeCap.Round, join = join)
            drawPath(Path().apply {
                moveTo(l, b - k); lineTo(l, t + k); quadraticTo(l, t, l + k, t); lineTo(r - k, t)
            }, c, style = s)
            drawPath(Path().apply {
                moveTo(r, t + k); lineTo(r, b - k); quadraticTo(r, b, r - k, b); lineTo(l + k, b)
            }, c, style = s)
            val a = w * 0.11f
            drawPath(Path().apply { moveTo(r - k - a, t - a); lineTo(r - k + a * 0.4f, t); lineTo(r - k - a, t + a) }, c, style = s)
            drawPath(Path().apply { moveTo(l + k + a, b - a); lineTo(l + k - a * 0.4f, b); lineTo(l + k + a, b + a) }, c, style = s)
            if (g == Glyph.REPEAT_ONE) {  // a plain "1" in the middle of the loop: tall stem with a short flag
                drawLine(c, Offset(w * 0.52f, h * 0.35f), Offset(w * 0.52f, h * 0.65f), sw, StrokeCap.Round)
                drawLine(c, Offset(w * 0.52f, h * 0.35f), Offset(w * 0.43f, h * 0.42f), sw, StrokeCap.Round)
            }
        }
        Glyph.HEART, Glyph.HEART_FILLED -> {
            val heart = Path().apply {
                moveTo(w * 0.5f, h * 0.82f)
                cubicTo(w * 0.14f, h * 0.60f, w * 0.08f, h * 0.36f, w * 0.20f, h * 0.26f)
                cubicTo(w * 0.32f, h * 0.16f, w * 0.46f, h * 0.22f, w * 0.5f, h * 0.34f)
                cubicTo(w * 0.54f, h * 0.22f, w * 0.68f, h * 0.16f, w * 0.80f, h * 0.26f)
                cubicTo(w * 0.92f, h * 0.36f, w * 0.86f, h * 0.60f, w * 0.5f, h * 0.82f)
                close()
            }
            if (g == Glyph.HEART_FILLED) drawPath(heart, c) else drawPath(heart, c, style = Stroke(width = sw, join = StrokeJoin.Round))
        }
        Glyph.SETTINGS ->for ((y, x) in listOf(0.28f to 0.65f, 0.5f to 0.35f, 0.72f to 0.58f)) {
            drawLine(c, Offset(w * 0.16f, h * y), Offset(w * 0.84f, h * y), sw * 0.8f, StrokeCap.Round)
            drawCircle(SableColors.base, w * 0.09f, Offset(w * x, h * y))
            drawCircle(c, w * 0.09f, Offset(w * x, h * y), style = Stroke(sw * 0.8f))
        }
    }
}
