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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sable.ui.theme.SableColors

/** Hand-drawn line icons: consistent weight, no emoji or font fallbacks (Samsung renders some symbols as emoji). */
enum class Glyph { PLAY, PAUSE, NEXT, PREV, QUEUE, INFO, BACK, DOWN, SETTINGS, MOON, SEARCH, CLOSE, REPEAT, REPEAT_ONE }

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
            drawRoundRect(c, Offset(w * 0.14f, h * 0.26f), Size(w * 0.72f, h * 0.48f), CornerRadius(w * 0.14f), style = stroke)
            val a = w * 0.09f
            drawPath(Path().apply { moveTo(w * 0.62f - a, h * 0.26f - a); lineTo(w * 0.62f, h * 0.26f); lineTo(w * 0.62f - a, h * 0.26f + a) }, c, style = stroke)
            drawPath(Path().apply { moveTo(w * 0.38f + a, h * 0.74f - a); lineTo(w * 0.38f, h * 0.74f); lineTo(w * 0.38f + a, h * 0.74f + a) }, c, style = stroke)
            if (g == Glyph.REPEAT_ONE) {  // a plain "1": straight stem with a short flag, clear of the loop and the arrowheads
                drawLine(c, Offset(w * 0.5f, h * 0.40f), Offset(w * 0.5f, h * 0.60f), sw, StrokeCap.Round)
                drawLine(c, Offset(w * 0.5f, h * 0.40f), Offset(w * 0.44f, h * 0.45f), sw, StrokeCap.Round)
            }
        }
        Glyph.SETTINGS ->for ((y, x) in listOf(0.28f to 0.65f, 0.5f to 0.35f, 0.72f to 0.58f)) {
            drawLine(c, Offset(w * 0.16f, h * y), Offset(w * 0.84f, h * y), sw * 0.8f, StrokeCap.Round)
            drawCircle(SableColors.base, w * 0.09f, Offset(w * x, h * y))
            drawCircle(c, w * 0.09f, Offset(w * x, h * y), style = Stroke(sw * 0.8f))
        }
    }
}
