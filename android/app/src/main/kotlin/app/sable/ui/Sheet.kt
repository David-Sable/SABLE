package app.sable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.sable.ui.theme.Motion
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.Space
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Slides the sheet away, for an action inside it. Calling onDismiss directly removed the sheet in one frame, and the page flashed bright; a sheet opened from it (song menu to Add to playlist) now rises as this one leaves. */
val LocalSheetClose = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }
/** Slides the panel down by the given px and stays there until its content next gets shorter, which then happens in that same frame:
 *  a section closes as one slide into place. Taking the height away first dropped the panel off the screen and it climbed back (David, 2026-10-30). */
val LocalSheetSink = androidx.compose.runtime.staticCompositionLocalOf<suspend (Float) -> Unit> { {} }

/**
 * Sable's bottom sheet (Why, the song menu, the sleep timer, Add to playlist), drawn in the app's own window. Material's
 * ModalBottomSheet opens a window of its own every time: measured on the A17 that froze one frame for 70-75 ms when it opened and
 * 17-26 ms when it closed. This one looks and behaves the same (scrim, rounded panel, handle; slides up; tap outside, Back or a
 * pull down closes it; a scrolled list hands a pull past its top to the sheet) and costs an ordinary frame.
 * Place it last in a full-screen Box so it covers everything. [onDismiss] runs once the sheet has slid away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SableSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }  // 0 below the screen .. 1 up
    val pull = remember { Animatable(0f) }   // px pulled down by the finger
    val grow = remember { Animatable(0f) }   // px still to rise after the content grew
    var height by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }
    var sunk by remember { mutableStateOf(false) }
    // Now Playing's family (Motion.panel): one no-overshoot spring in and out; a pull that lets go keeps the finger's speed.
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.panel(0.0005f)) }
    fun close(velocity: Float = 0f) {
        if (closing) return
        closing = true
        scope.launch { shown.animateTo(0f, Motion.panel(0.0005f), initialVelocity = -velocity / maxOf(1, height)); onDismiss() }
    }
    fun settle(velocity: Float) {
        if (pull.value > height * 0.25f || velocity > 1800f) close(velocity) else scope.launch { pull.animateTo(0f, Motion.panel(0.5f), initialVelocity = velocity) }
    }
    BackHandler { close() }
    // A pull down that a scrolled list did not use (it is at its top) moves the sheet; scrolling back up takes the pull back first.
    val handOff = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || pull.value <= 0f) return Offset.Zero
                val used = maxOf(available.y, -pull.value)
                scope.launch { pull.snapTo(pull.value + used) }
                return Offset(0f, used)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset(0f, minOf(available.y, 0f))  // never lifts above its place
                scope.launch { pull.snapTo(pull.value + available.y) }
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, minOf(available.y, 0f))
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = shown.value * (1f - (pull.value / maxOf(1, height)).coerceIn(0f, 1f)) }
                .background(SableColors.base.copy(alpha = 0.6f))
                .pointerInput(Unit) { detectTapGestures { close() } },
        )
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = Space.xl)) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    // Growing (the Why sheet's details opening) takes the new height at once and slides the panel up into it: only its
                    // place changes per frame. Opening the height frame by frame re-measured the whole sheet, 10-14 ms a frame on the A17
                    // against 11 ms at 90 Hz, and skipped frames made it jump (David, 2026-10-29). Shrinking keeps its own animation.
                    .layout { m, c ->
                        val p = m.measure(c)
                        val old = height
                        if (old > 0 && p.height > old && !closing && shown.value >= 1f) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            grow.snapTo(grow.value + (p.height - old)); grow.animateTo(0f, Motion.panel(0.5f))
                        }
                        if (sunk && p.height < old) { sunk = false; scope.launch(start = CoroutineStart.UNDISPATCHED) { grow.snapTo(0f) } }
                        height = p.height
                        layout(p.width, p.height) { p.place(0, ((1f - shown.value) * p.height + pull.value + grow.value).roundToInt()) }
                    }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> scope.launch { pull.snapTo((pull.value + d).coerceAtLeast(0f)) } },
                        onDragStopped = { v -> settle(v) },
                    )
                    .nestedScroll(handOff)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(SableColors.surface1)
                    .pointerInput(Unit) { detectTapGestures { } },  // a tap on the panel is not a tap outside 
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = Space.m).size(36.dp, 4.dp).background(SableColors.line, RoundedCornerShape(2.dp)))
                androidx.compose.runtime.CompositionLocalProvider(LocalSheetClose provides { close() }, LocalSheetSink provides { px -> sunk = true; grow.animateTo(px, Motion.panel(0.5f)) }) { Column(Modifier.fillMaxWidth(), content = content) }
                // The window already shrinks above the keyboard; a little room so a text field does not sit right on it.
                if (WindowInsets.isImeVisible) Spacer(Modifier.size(1.dp, 28.dp))
            }
        }
    }
}
