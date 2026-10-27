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
enum class Glyph { PLAY, PAUSE, NEXT, PREV, QUEUE, INFO, BACK, DOWN, SETTINGS }

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
        Glyph.SETTINGS -> for ((y, x) in listOf(0.28f to 0.65f, 0.5f to 0.35f, 0.72f to 0.58f)) {
            drawLine(c, Offset(w * 0.16f, h * y), Offset(w * 0.84f, h * y), sw * 0.8f, StrokeCap.Round)
            drawCircle(SableColors.base, w * 0.09f, Offset(w * x, h * y))
            drawCircle(c, w * 0.09f, Offset(w * x, h * y), style = Stroke(sw * 0.8f))
        }
    }
}
