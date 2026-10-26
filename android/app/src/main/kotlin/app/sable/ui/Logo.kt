package app.sable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sable.ui.theme.SableColors

/**
 * The SABLE mark: an S sitting on a level line, inside a fine ring. Same geometry as the launcher icon
 * (res/drawable/ic_launcher_foreground.xml), drawn in the icon's 66-unit safe zone (21..87) scaled to [size].
 */
@Composable
fun SableMark(size: Dp = 28.dp, modifier: Modifier = Modifier, ring: Boolean = true) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 66f
        fun p(x: Float, y: Float) = Offset((x - 21f) * k, (y - 21f) * k)
        if (ring) drawCircle(SableColors.accent.copy(alpha = 0.35f), 25f * k, p(54f, 54f), style = Stroke(1.2f * k))
        val s = Path().apply {
            val top = p(54f, 45f)
            val bottom = p(54f, 63f)
            val r = 9f * k
            arcTo(Rect(top.x - r, top.y - r, top.x + r, top.y + r), -30f, -240f, true)
            arcTo(Rect(bottom.x - r, bottom.y - r, bottom.x + r, bottom.y + r), -90f, 240f, false)
        }
        drawPath(s, SableColors.accent, style = Stroke(3.2f * k, cap = StrokeCap.Round))
        val a = SableColors.accent
        drawRect(
            Brush.horizontalGradient(
                0f to a.copy(alpha = 0f), 0.3f to a.copy(alpha = 0.9f), 0.7f to a.copy(alpha = 0.9f), 1f to a.copy(alpha = 0f),
                startX = p(30f, 0f).x, endX = p(78f, 0f).x,
            ),
            topLeft = p(30f, 53.1f), size = Size(48f * k, 1.8f * k),
        )
    }
}
