package app.sable.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
 * What a press looks like on rows and anything else that is simply clickable: no ripple spreading from the finger, the row lifts
 * a shade straight away and eases back. Given to the whole app in SableTheme, in place of Material's ripple.
 */
object QuietPress : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun hashCode() = -1
    override fun equals(other: Any?) = other === this

    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private val shade = Animatable(0f)
        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> launch { shade.animateTo(1f, tween(70)) }
                        is PressInteraction.Release, is PressInteraction.Cancel -> launch { shade.animateTo(0f, tween(280)) }
                    }
                }
            }
        }
        override fun ContentDrawScope.draw() {
            drawContent()
            if (shade.value > 0f) drawRect(Color.White, alpha = 0.06f * shade.value)
        }
    }
}

/**
 * The feel of a tap on Sable's own controls: no ripple, the control gives a little under the finger and springs back, and
 * a soft haptic tick confirms the press. Put it first in the modifier chain so the background and border move with it;
 * the touch area itself does not shrink.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.softClick(pressedScale: Float = 0.94f, haptic: Boolean = true, enabled: Boolean = true, onLong: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press")
    val feedback = LocalHapticFeedback.current
    this
        .combinedClickable(
            interactionSource = source, indication = null, enabled = enabled,
            onLongClick = onLong?.let { f -> { feedback.performHapticFeedback(HapticFeedbackType.LongPress); f() } },
        ) {
            if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
        .graphicsLayer { scaleX = scale; scaleY = scale }
}
