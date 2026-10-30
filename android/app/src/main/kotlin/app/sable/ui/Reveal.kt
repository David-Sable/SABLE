package app.sable.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import app.sable.ui.theme.Motion

/**
 * A fade that costs nothing extra: the alpha is applied to each drawing operation instead of rendering the content into
 * an offscreen buffer first (Compose's default for alpha < 1). Measured on the A17 at 90 Hz, the offscreen copy of a
 * whole screen or a large cover was what pushed frames past their 11 ms budget.
 */
fun Modifier.lightAlpha(alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/**
 * Fades a section in (and out), with the light fade above. With [expand] it also opens its height, which re-measures
 * everything below it on every frame: only worth it when there is content below to push down. A section at the end of
 * its page fades in place.
 */
@Composable
fun Reveal(visible: Boolean, expand: Boolean = true, riseWithSheet: Boolean = false, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        // Inside a SableSheet the sheet itself rises to the new height (draw position only), so the section only fades in.
        enter = if (expand && !riseWithSheet) expandVertically(Motion.reveal()) else EnterTransition.None,
        exit = if (expand) shrinkVertically(Motion.medium()) else fadeOut(Motion.medium(), targetAlpha = 1f),  // kept on screen while the light fade runs
    ) {
        val a by transition.animateFloat(
            transitionSpec = { if (targetState == EnterExitState.Visible) (if (expand) Motion.reveal() else Motion.slow()) else Motion.medium() }, label = "reveal",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        Box(Modifier.lightAlpha { a }) { content() }
    }
}
