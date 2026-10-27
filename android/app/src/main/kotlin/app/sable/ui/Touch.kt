package app.sable.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The feel of a tap on Sable's own controls: no ripple, the control gives a little under the finger and springs back, and
 * a soft haptic tick confirms the press. Put it first in the modifier chain so the background and border move with it;
 * the touch area itself does not shrink.
 */
fun Modifier.softClick(pressedScale: Float = 0.94f, haptic: Boolean = true, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press")
    val feedback = LocalHapticFeedback.current
    this
        .clickable(interactionSource = source, indication = null, enabled = enabled) {
            if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
        .graphicsLayer { scaleX = scale; scaleY = scale }
}
