@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package app.sable.ui

import androidx.media3.common.Player
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sable.data.LibraryRow
import app.sable.data.SableDatabase
import app.sable.playback.NowPlaying
import app.sable.playback.QueueId
import app.sable.ui.theme.Motion
import app.sable.ui.theme.Radius
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space
import app.sable.ui.theme.grain
import app.sable.ui.theme.sheetSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CoroutineStart
import kotlin.math.abs
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest

/**
 * Redraw clock for the playhead. It ticks only while playing, and only as often as the playhead moves by one pixel
 * across [widthPx] (a 3-minute song over 1000 px: every ~180 ms), so a moving playhead stays smooth without the
 * cost of redrawing every 90 Hz frame (battery, MATH-independent UI tuning; M7).
 */
@Composable
fun frameTick(active: Boolean, durationMs: Long = 0L, widthPx: Int = 1000): State<Long> {
    val onScreen = LocalOnScreen.current
    return produceState(0L, active, durationMs, widthPx) {
        val step = if (durationMs > 0 && widthPx > 0) (durationMs / widthPx).coerceIn(16L, 500L) else 250L
        if (active) whileOnScreen(onScreen) {
            while (true) {
                delay(step)
                value = SystemClock.uptimeMillis()
            }
        }
    }
}

/**
 * Whether the screen is in view. Now Playing stays built while closed (SableApp) and provides false then, so its clocks stop and
 * a hidden sheet never wakes the display. Elsewhere it is always true.
 */
val LocalOnScreen = androidx.compose.runtime.staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

/** Runs [block] while [onScreen] is true, cancelling it when the screen goes out of view and starting it again when it returns. */
suspend fun whileOnScreen(onScreen: State<Boolean>, block: suspend () -> Unit) =
    snapshotFlow { onScreen.value }.collectLatest { if (it) block() }

@Composable
fun analysisJson(trackId: Long?): JSONObject? {
    val context = LocalContext.current
    val v by produceState<JSONObject?>(null, trackId) {
        value = trackId?.let { id ->
            withContext(Dispatchers.IO) {
                SableDatabase.get(context).library().analysisForTrack(id, SableDatabase.MATH_VERSION)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
            }
        }
    }
    return v
}

/** The black either side of Now Playing's cover. David's settled look (2026-10-30, from his own screenshot, then "maybe a little bigger"):
 *  a framed cover on black under the plain bar. Larger, and running it to the screen's edges, was tried and felt like too much. */
private val COVER_MARGIN = Space.gutter

/** The moving parts shared by Now Playing's cover and the mini player: see [rememberSongDeck]. */
class SongDeck(val dragX: Animatable<Float, *>, val step: Float, val next: LibraryRow?, val previous: LibraryRow?, val modifier: Modifier)

/**
 * Swipe sideways to change songs: left for the next one, right for the previous one. The offset belongs to the song ([SongDeck.dragX]
 * is new for each), and the new offset is the old one plus one step along the queue, so a landed swipe arrives at rest and Next,
 * Previous or a song ending glide the new song in with the old one going out beside it. Every arrival is Sable's signature landing:
 * a soft spring, a touch past and back, carrying the speed. Nothing past the queue's ends: it resists, and pulling the first song
 * back starts it again. Whatever interrupts it, the offset always returns to rest: 0.42 could leave the mini player stuck to the left.
 */
@Composable
fun rememberSongDeck(state: AppState, rowOf: (Long) -> LibraryRow?, gap: Float): SongDeck {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var width by remember { mutableIntStateOf(1) }
    val step = width + gap
    val last = remember { arrayOfNulls<Animatable<Float, *>>(1) }
    val arrive = remember { floatArrayOf(0f) }
    val landing = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
    val landingSwipe = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f)
    val onScreen = LocalOnScreen.current
    val dragX = remember(state.currentQueueId, state.queueIndex) {
        val from = last[0]?.value ?: 0f
        arrive[0] = last[0]?.velocity ?: 0f
        Animatable(if (onScreen.value && last[0] != null) (from + state.songStep * step).coerceIn(-step, step) else 0f)
    }
    SideEffect { last[0] = dragX }
    var held by remember(dragX) { mutableStateOf(false) }
    var leaving by remember(dragX) { mutableIntStateOf(0) }  // the side a released song is on its way out to (+1 next, -1 previous)
    var asked by remember(dragX) { mutableStateOf(false) }
    suspend fun rest(v: Float) {
        try { dragX.animateTo(0f, landing, initialVelocity = v) } catch (e: kotlinx.coroutines.CancellationException) { currentCoroutineContext().ensureActive() }  // a finger took over
    }
    LaunchedEffect(dragX) {
        if (dragX.value != 0f) rest(arrive[0])
        snapshotFlow { held || leaving != 0 || dragX.isRunning }.collect { busy -> if (!busy && dragX.value != 0f) rest(0f) }
    }
    fun neighbour(i: Int) = if (i == state.queueIndex) null else state.queueIds.getOrNull(i)?.let(QueueId::trackId)?.let(rowOf)
    val next = neighbour(state.nextIndex)
    val previous = neighbour(state.previousIndex)
    fun change(dir: Int) { asked = true; state.withController { c -> if (dir > 0) c.seekToNextMediaItem() else c.seekToPreviousMediaItem() } }
    val modifier = Modifier
        .onSizeChanged { width = maxOf(1, it.width) }
        .draggable(
            orientation = Orientation.Horizontal,
            onDragStarted = { held = true; if (leaving != 0 && !asked) change(leaving) },  // a second swipe before the first landed
            state = rememberDraggableState { d ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val x = dragX.value
                    val free = if (x + d < 0f) next != null else previous != null
                    dragX.snapTo(if (free) (x + d).coerceIn(-step, step) else (x + d * 0.3f).coerceIn(-width * 0.2f, width * 0.2f))
                }
            },
            onDragStopped = { velocity ->
                val x = dragX.value
                val dir = when {
                    abs(velocity) > 1500f -> if (velocity < 0f) 1 else -1
                    x < -width * (if (next != null) 0.25f else 0.1f) -> 1
                    x > width * (if (previous != null) 0.25f else 0.1f) -> -1
                    else -> 0
                }
                if (dir != 0 && (if (dir > 0) next else previous) != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)  // the song changes: a soft tick
                    leaving = dir
                    held = false
                    // The song changes once it has landed: changing it in flight stalled the cover for 3-4 frames (filmed on the
                    // phone), and at rest that cost cannot be seen.
                    dragX.animateTo(-dir * step, landingSwipe, initialVelocity = velocity)
                    if (!asked) change(dir)
                    delay(1000)  // normally the song has changed and this offset is gone; if the player refused, come back
                    leaving = 0
                } else {
                    if (dir < 0 && previous == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        state.withController { it.seekTo(0L) }
                    }
                    held = false
                }
            },
        )
    return SongDeck(dragX, step, next, previous, modifier)
}

/**
 * Now Playing's cover and the two beside it. The next cover waits just off the right edge and the previous one off the left: a
 * swipe brings the neighbour in with the finger, smaller and in shadow, while the playing cover steps back into shadow, and the
 * gap between them stays one even width. Let go past a quarter (or with a flick) and the neighbour lands exactly where the cover
 * sits, then the song changes; let go sooner and both return. A short window (compact) keeps the neighbours inside the cover's own
 * square, off the controls.
 */
@Composable
fun CoverDeck(state: AppState, row: LibraryRow, rowOf: (Long) -> LibraryRow?, pressScale: Float, compact: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val deck = rememberSongDeck(state, rowOf, with(density) { COVER_MARGIN.toPx() })
    val dragX = deck.dragX
    val step = deck.step
    val next = deck.next
    val previous = deck.previous
    // Every song change, however it came, ends with one soft dip and rebound of the new cover: it starts from full size, so nothing jumps.
    val pulse = remember { Animatable(1f) }
    val seen = remember { arrayOfNulls<Any>(1) }
    val onScreen = LocalOnScreen.current
    LaunchedEffect(dragX) {
        val changed = seen[0] != null
        seen[0] = dragX
        if (changed && onScreen.value) pulse.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 320f), initialVelocity = -0.9f)
    }
    val corner = with(density) { Radius.art.toPx() }
    fun Modifier.shade(dark: () -> Float) = drawWithContent {
        drawContent()
        drawRoundRect(Color.Black, alpha = dark(), cornerRadius = CornerRadius(corner))
    }
    Box(modifier.then(if (compact) Modifier.clipToBounds() else Modifier).then(deck.modifier)) {
        for ((side, other) in listOf(-1 to previous, 1 to next)) if (other != null) key(other.albumId) {
            AlbumArt(
                other.albumId, other.uri, other.album, 320.dp,
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val p = (abs(dragX.value) / step).coerceIn(0f, 1f)
                        alpha = if (dragX.value * side < 0f) 1f else 0f  // only the side the finger is opening
                        translationX = side * step + dragX.value
                        val s = 0.86f + 0.14f * p
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(if (side > 0) 0f else 1f, 0.5f)  // the near edge stays with the finger
                    }
                    .scale(pressScale)
                    .shade { 0.55f * (1f - (abs(dragX.value) / step).coerceIn(0f, 1f)) },
                px = 1024,
            )
        }
        key(row.albumId) {  // a new cover is there in its first frame, not one frame after
            AlbumArt(
                row.albumId, row.uri, row.album, 320.dp,
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val s = (1f - 0.08f * (abs(dragX.value) / step).coerceIn(0f, 1f)) * pulse.value
                        translationX = dragX.value
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(if (dragX.value < 0f) 1f else 0f, 0.5f)  // the gap between the covers stays even
                    }
                    .scale(pressScale)
                    .coverSpot(state, mini = false)  // measured after the pause shrink: the flight lands on the cover as shown
                    .shade { 0.5f * (abs(dragX.value) / step).coerceIn(0f, 1f) },
                px = 1024,
            )
        }
    }
}

/**
 * Where a cover is on screen, for the cover's flight between the mini player and Now Playing (drawn by SableApp from these two
 * rectangles and the sheet's progress, so it starts exactly on one cover and ends exactly on the other). While it flies, this
 * cover is hidden. Replaces Compose's shared element, which on the first opening only learned where the cover lands at the last
 * moment and made it jump (David, 2026-10-29).
 */
fun Modifier.coverSpot(state: AppState, mini: Boolean): Modifier = this
    .onGloballyPositioned { c ->
        val r = c.boundsInRoot()  // as drawn: includes the pause shrink and a sideways swipe (a paused cover is 94%; measuring it at full size made it land big and snap smaller)
        if (mini) state.miniCover = r
        else state.npCover = r
    }
    .graphicsLayer { alpha = if (state.coverFlying()) 0f else 1f }

@Composable
fun MiniPlayer(
    state: AppState, row: LibraryRow?, modifier: Modifier = Modifier,
    shared: androidx.compose.animation.SharedTransitionScope? = null, visibility: androidx.compose.animation.AnimatedVisibilityScope? = null,
    rowOf: (Long) -> LibraryRow? = { null },
) {
    val tick by frameTick(state.playing, state.durationMs, 1080)
    val deck = rememberSongDeck(state, rowOf, with(LocalDensity.current) { Space.xl.toPx() })
    // Tap to open, or swipe up: Now Playing rises with the finger and is let go onto the same spring as a tap.
    val lift = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { d -> state.dragSheet(-d, leaving = false) },
        onDragStopped = { velocity -> state.releaseSheet(-velocity) },
    )
    Column(modifier.fillMaxWidth().background(SableColors.surface1).grain().navigationBarsPadding().then(lift).clickable { state.go(Route.NowPlaying) }) {
        Canvas(Modifier.fillMaxWidth().height(2.dp).graphicsLayer()) {  // own layer: the 150 ms progress redraw stays here
            tick
            drawRect(SableColors.line)
            drawRect(SableColors.accent, size = size.copy(width = size.width * state.progress()))
        }
        Row(Modifier.padding(end = Space.l, top = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            // Cover and title swipe sideways to change songs, with the next or previous one beside them, as on Now Playing; a tap
            // anywhere still opens Now Playing.
            // The big cover Now Playing will want, decoded and uploaded while the song plays (not in the first frames of the open).
            val artContext = LocalContext.current
            LaunchedEffect(row?.albumId) { row?.let { ArtCache.load(artContext, it.albumId, it.uri, 1024) } }
            Box(Modifier.weight(1f).clipToBounds().then(deck.modifier)) {
                for ((side, other) in listOf(-1 to deck.previous, 1 to deck.next)) if (other != null) key(other.mediaId) {
                    MiniSong(other, Modifier.graphicsLayer {
                        alpha = if (deck.dragX.value * side < 0f) 1f else 0f
                        translationX = side * deck.step + deck.dragX.value
                    })
                }
                key(row?.mediaId) { MiniSong(row, Modifier.graphicsLayer { translationX = deck.dragX.value }, Modifier.coverSpot(state, mini = true)) }
            }
            IconButton(if (state.playing) Glyph.PAUSE else Glyph.PLAY, { state.withController { c -> if (c.isPlaying) c.pause() else c.play() } }, color = SableColors.accentAlt)
        }
    }
}

/** One song in the mini player: its cover, title and credit. */
@Composable
private fun MiniSong(row: LibraryRow?, modifier: Modifier, cover: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = Space.l), verticalAlignment = Alignment.CenterVertically) {
        if (row != null) AlbumArt(row.albumId, row.uri, row.album, 44.dp, Modifier.size(44.dp).then(cover), px = 128)
        Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
            Text(row?.let(::songTitle) ?: "", style = SableType.body, color = SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(creditText(row?.let(::credit).orEmpty()), style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun NowPlayingScreen(
    state: AppState, row: LibraryRow?,
    shared: androidx.compose.animation.SharedTransitionScope? = null, visibility: androidx.compose.animation.AnimatedVisibilityScope? = null,
    rowOf: (Long) -> LibraryRow? = { null },
) {
    val gain by NowPlaying.gain.collectAsState()
    val settings by app.sable.playback.PlaybackPrefs.settings.collectAsState()
    var nudgeNote by remember { mutableStateOf<String?>(null) }
    val favorites = rememberFavorites()
    LaunchedEffect(nudgeNote) { if (nudgeNote != null) { delay(2500); nudgeNote = null } }
    val sleep by app.sable.playback.SleepTimer.state.collectAsState()
    val context = LocalContext.current
    val view by produceState(SongViews.peek(state.currentTrackId), state.currentTrackId) { value = SongViews.load(context, state.currentTrackId) }
    val shape = view?.shape
    val mood = view?.mood
    val tick by frameTick(state.playing, state.durationMs, 1000)
    // The anchor (the part Sable balanced to) brightens while the song plays through it.
    val inAnchor by produceState(false, shape, state.playing) {
        while (true) {
            val t = state.position() / 1000.0
            value = state.playing && shape != null && shape.anchor.any { (a, b) -> t >= a && t < b }
            delay(250)
        }
    }
    val anchorLit by animateFloatAsState(if (inAnchor) 1f else 0f, androidx.compose.animation.core.tween(900), label = "anchorLit")
    val onScreen = LocalOnScreen.current
    LaunchedEffect(state.playing) { whileOnScreen(onScreen) { while (true) { state.syncPosition(); delay(1000) } } }
    // Pause: the cover settles back a little on a soft spring and comes forward again on play. Part of Sable's feel (David, 2026-10-30).
    val pressScale by animateFloatAsState(if (state.playing) 1f else 0.94f, spring(dampingRatio = 0.68f, stiffness = 240f), label = "art")
    // Gestures move only in the draw phase (graphicsLayer translation: no relayout per frame), follow the finger the
    // same frame (undispatched), and settle on a spring that carries the finger's speed, so nothing jumps or stalls.
    val scope = rememberCoroutineScope()
    fun follow(a: Animatable<Float, *>, to: Float) { scope.launch(start = CoroutineStart.UNDISPATCHED) { a.snapTo(to) } }
    val settle = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    // Swipe down to close: the finger holds the sheet's one progress value (AppState.sheet) and lets go onto its spring.
    val swipe = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { d -> state.dragSheet(-d, leaving = true) },
        onDragStopped = { velocity -> state.releaseSheet(-velocity) },
    )
    // Decode the neighbours' covers ahead of time, so a swipe never shows a blank square while the new one loads.
    LaunchedEffect(state.nextIndex, state.previousIndex, state.queueIds) {
        for (i in listOf(state.nextIndex, state.previousIndex)) {
            val r = state.queueIds.getOrNull(i)?.let(QueueId::trackId)?.let(rowOf) ?: continue
            ArtCache.load(context, r.albumId, r.uri, 1024)
            SongViews.load(context, r.mediaId)  // and its shape, so the silhouette reshapes straight away
        }
    }
    if (row == null) {
        Column(Modifier.fillMaxSize().grain()) {
            TopBar("NOW PLAYING", { state.pop() })
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                SableMark(88.dp)
                Spacer(Modifier.height(Space.xl))
                Text("Nothing playing yet", style = SableType.title, color = SableColors.textTertiary)
            }
        }
        return
    }
    // Where the sheet is (0 away .. 1 open), from its real place on screen, drag and slide included: the screen behind, the corners
    // and the arrival of the words and controls all follow it, so they stay in step with the finger as well as with the animation.
    val screenH = with(LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // The sheet is kept while closed (SableApp). A swipe's offset is cleared when it opens again (it starts below the screen, so
    // nothing jumps): clearing it as soon as the sheet's top left the screen pulled the sheet back into view mid-slide, where it
    // showed as a faded copy sliding out a second time (David, 2026-10-29).
    LaunchedEffect(Unit) {
        snapshotFlow { onScreen.value }.collectLatest { open -> if (!open) state.sleepSheet = false }
    }
    val corner = with(LocalDensity.current) { 28.dp.toPx() }
    // The words and controls fade with the sheet's progress (0 below `from`, 1 when open, on a smooth curve) and never move on the
    // sheet: going down they are gone before the cover leaves the sheet, so nothing passes over or under it (David, 2026-10-29:
    // "2099 goes where it shouldn't"); coming up they arrive after the cover.
    fun arrive(from: Float): Float = ((state.sheetP - from) / (1f - from)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    Column(
        Modifier.fillMaxSize().then(swipe)
            .onPlaced { if (!state.npPrewarm) state.sheetP = (1f - it.positionInRoot().y / screenH).coerceIn(0f, 1f) }
            .sheetSurface { if (state.animCorners) corner * (1f - state.sheetP) else 0f },
    ) {
        Box(Modifier.graphicsLayer { alpha = arrive(0.5f) }) {
        TopBar("NOW PLAYING", { state.pop() }) {
            IconButton(Glyph.QUEUE, { state.go(Route.Queue) })
        }
        }
        sleepStatus(sleep)?.let { Text(it, style = SableType.caption, color = SableColors.accent, modifier = Modifier.padding(horizontal = Space.xl)) }
        // A short window (split screen, a small pane) puts the cover beside the controls instead of above them.
        BoxWithConstraints(Modifier.weight(1f)) {
            val compact = maxHeight < 440.dp
            val gap = if (compact) Space.s else Space.xl
            val shapeH = if (compact) 40.dp else 56.dp
            val words: @Composable ColumnScope.() -> Unit = {
            // The words change with the cover, moving the same way: the old ones leave quickly, the new ones settle in behind them.
            AnimatedContent(
                row, contentKey = { it.mediaId }, label = "words",
                transitionSpec = {
                    val d = state.songStep
                    (fadeIn(tween(220, delayMillis = 30)) + slideInHorizontally(Motion.page(IntOffset.VisibilityThreshold)) { d * it / 10 }) togetherWith
                        (fadeOut(tween(110)) + slideOutHorizontally(Motion.page(IntOffset.VisibilityThreshold)) { -d * it / 16 }) using SizeTransform(clip = false) { _, _ -> snap() }
                },
            ) { row ->
            Column {
            // Tap the title for its album, the artist line for the artist; the heart keeps the song in Favourites.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row?.let(::songTitle) ?: "", style = SableType.title, color = SableColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(enabled = row != null) { row?.let { state.go(Route.Album(albumKey(it))) } })
                if (row != null) {
                    val fav = row.mediaId in favorites
                    val pop = remember { Animatable(1f) }  // a kept song gets a small lift
                    Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
                        IconButton(if (fav) Glyph.HEART_FILLED else Glyph.HEART, {
                            Favorites.set(context, row.mediaId, !fav)
                            if (!fav) scope.launch { pop.snapTo(0.72f); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f)) }
                        }, size = 22.dp, color = if (fav) SableColors.accent else SableColors.textTertiary)
                    }
                }
            }
            CreditLine(row?.let(::songSubtitle).orEmpty(), SableType.body, SableColors.textSecondary,
                Modifier.padding(top = 2.dp).clickable(enabled = row != null) { row?.let { state.go(Route.Artist(artistOf(it))) } }, maxLines = 2)
            }
            }
            }
            val playback: @Composable ColumnScope.() -> Unit = {
            if (shape != null) {
                LoudnessShape(shape, { tick; state.progress() }, anchorLit = { anchorLit }, modifier = Modifier.fillMaxWidth().height(shapeH).graphicsLayer(), mood = settings.mood, view = view) { state.seekFraction(it) }  // own layer
            } else {
                Canvas(Modifier.fillMaxWidth().height(shapeH).graphicsLayer()) {
                    tick
                    val y = size.height - 1f
                    drawLine(SableColors.line, Offset(0f, y), Offset(size.width, y), 2f)
                    drawLine(SableColors.accent, Offset(0f, y), Offset(size.width * state.progress(), y), 2f)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = Space.xs)) {
                Clock(state)
                Spacer(Modifier.weight(1f))
                if (settings.mood && mood != null) MoodCaption(state, mood)
                Spacer(Modifier.weight(1f))
                Text(mmss(state.durationMs), style = SableType.caption, color = SableColors.textTertiary)
            }
            Row(Modifier.fillMaxWidth().padding(top = if (compact) Space.s else Space.l), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                // Repeat on the left, sleep on the right: both lit in the accent while on.
                IconButton(if (state.repeatMode == Player.REPEAT_MODE_ONE) Glyph.REPEAT_ONE else Glyph.REPEAT, { state.cycleRepeat() }, size = 22.dp,
                    color = if (state.repeatMode == Player.REPEAT_MODE_OFF) SableColors.textTertiary else SableColors.accent)
                IconButton(Glyph.PREV, { state.withController { it.seekToPrevious() } }, size = 26.dp)
                Box(
                    Modifier.size(if (compact) 48.dp else 72.dp).softClick(pressedScale = 0.93f) { state.withController { c -> if (c.isPlaying) c.pause() else c.play() } }
                        .background(SableColors.accentAlt, CircleShape),  // brass: Sable's signature, the same as the widget (David, 2026-10-30)
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(state.playing, label = "playPause", transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(Motion.page(), initialScale = 0.7f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(120), targetScale = 0.7f))
                    }) { p -> Icon(if (p) Glyph.PAUSE else Glyph.PLAY, size = 30.dp, color = SableColors.onAccent) }
                }
                IconButton(Glyph.NEXT, { state.withController { it.seekToNext() } }, size = 26.dp)
                IconButton(Glyph.MOON, { state.sleepSheet = true }, size = 20.dp, color = if (sleep != null) SableColors.accent else SableColors.textTertiary)
            }
            }
            if (compact) {
                Row(Modifier.fillMaxSize().padding(horizontal = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                    CoverDeck(state, row, rowOf, pressScale, compact = true, Modifier.weight(0.3f).aspectRatio(1f))
                    Column(
                        Modifier.weight(0.7f).padding(start = Space.m).graphicsLayer { alpha = arrive(0.55f) },
                        verticalArrangement = Arrangement.Center,
                    ) { words(); Spacer(Modifier.height(gap)); playback() }
                }
            } else {
                // The cover stays at the top and the controls at the bottom whatever the song; the words sit centred in what is left,
                // which always holds the longest case (two title lines, two credit lines; the cover at full width comes first, David, 2026-10-30). Before, a long credit pushed everything
                // and squeezed the play button into an oval. Only a phone too short for that gets a smaller cover.
                val longestWords = with(LocalDensity.current) { (SableType.title.lineHeight * 2).toDp() + (SableType.body.lineHeight * 2).toDp() } + 2.dp
                val controls = shapeH + 21.dp + Space.l + 72.dp  // the shape, the clock line, the play row
                val side = minOf(maxWidth - COVER_MARGIN * 2, maxHeight - Space.s - controls - longestWords)
                Column(Modifier.fillMaxSize().padding(top = Space.s)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CoverDeck(state, row, rowOf, pressScale, compact = false, Modifier.size(side))
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.xl).graphicsLayer { alpha = arrive(0.55f) }, verticalArrangement = Arrangement.Center, content = words)  // arrives after the cover
                    Column(Modifier.padding(horizontal = Space.xl).graphicsLayer { alpha = arrive(0.55f) }, content = playback)
                }
            }
        }
        // The engine at work, in words: the applied gain and why.
        // The gain and its reason on one line; with the Quieter / Louder buttons on, those sit on their own row under it.
        Column(
            Modifier.fillMaxWidth().graphicsLayer { alpha = arrive(0.6f) }
                .navigationBarsPadding().padding(horizontal = Space.xl, vertical = Space.l),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Hold the number to hear the file as it is (no gain, no contour); let go and Sable's balance is back.
                var holding by remember { mutableStateOf(false) }
                val haptics = LocalHapticFeedback.current
                DisposableEffect(Unit) { onDispose { app.sable.playback.PlaybackService.holdOriginal(false) } }
                val uiPrefs = remember { context.getSharedPreferences("sable_ui", android.content.Context.MODE_PRIVATE) }
                var heldOnce by remember { mutableStateOf(uiPrefs.getBoolean("heldOriginal", false)) }
                val eff = settings.effective()
                val thoughts = remember(gain, heldOnce, row?.character, shape, eff.mode) {
                    WhyText.thoughts(WhyText.Facts(
                        worldLabel(gain), gain.gainDb, gain.rules, gain.source, gain.learnedDb, gain.contourOn, row?.character,
                        eff.mode.label, eff.mode.targetLufs, shape?.bodyLufs, gain.albumRun, hint = !heldOnce,  // the hint goes once tried
                    ))
                }
                // One line every 8 s of the song, turning slowly through all of them (paused, it stays). The anchor is shown
                // by its glow alone (a words note for it stuck on screen when paused there, and was clutter).
                val thought by produceState(thoughts.first(), thoughts) {
                    while (true) {
                        value = thoughts[((state.position() / 8_000L) % thoughts.size).toInt()]
                        delay(500)
                    }
                }
                Row(
                    Modifier.weight(1f).pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            holding = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            app.sable.playback.PlaybackService.holdOriginal(true)
                            try { tryAwaitRelease() } finally {
                                holding = false
                                app.sable.playback.PlaybackService.holdOriginal(false)
                                if (!heldOnce) { heldOnce = true; uiPrefs.edit().putBoolean("heldOriginal", true).apply() }
                            }
                        })
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val shownGain by animateFloatAsState(gain.gainDb.toFloat(), Motion.panel(0.01f), label = "gain")  // the figure counts over to the new song's
                    Text(if (holding) "Original" else "%+.1f dB".format(shownGain), style = SableType.headline, color = if (holding) SableColors.textPrimary else SableColors.accent)
                    val line = when {
                        holding -> originalNote(gain.gainDb)
                        nudgeNote != null -> nudgeNote!!
                        else -> thought
                    }
                    androidx.compose.animation.Crossfade(line, animationSpec = androidx.compose.animation.core.tween(280), label = "thought",
                        modifier = Modifier.weight(1f).padding(horizontal = Space.m)) { s ->
                        Text(s, style = SableType.caption, color = if (holding || nudgeNote != null) SableColors.accent else SableColors.textTertiary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!settings.carTap) Pill("Why", selected = false) { state.whyTrack = state.currentTrackId }
            }
            if (settings.carTap) Row(Modifier.fillMaxWidth().padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                // MATH-1.32 §19.4: a deliberate correction for this song at this place; it acts only when tapped.
                Pill("Quieter", selected = false) { app.sable.playback.PlaybackService.nudge(false) { nudgeNote = it } }
                Pill("Louder", selected = false) { app.sable.playback.PlaybackService.nudge(true) { nudgeNote = it } }
                Spacer(Modifier.weight(1f))
                Pill("Why", selected = false) { state.whyTrack = state.currentTrackId }
            }
        }
    }
}

/** The mood of the moment in two plain words ("full · bright"), in its own colour; changes at most every half second (MATH §8.5). */
@Composable
private fun MoodCaption(state: AppState, mood: SongMood) {
    val onScreen = LocalOnScreen.current
    val i by produceState(0, state.playing, state.currentQueueId, mood) {
        whileOnScreen(onScreen) {
            while (true) {
                value = (state.position() / 500L).toInt()
                delay(500 - state.position() % 500 + 5)
            }
        }
    }
    Text(mood.caption(i), style = SableType.caption, color = if (SableColors.light) SableColors.textSecondary else mood.tint(i).copy(alpha = 0.9f))  // the ivory tint is unreadable on paper
}

@Composable
private fun Clock(state: AppState) {
    val onScreen = LocalOnScreen.current
    val t by produceState(0L, state.playing, state.currentQueueId) {
        whileOnScreen(onScreen) { while (true) { value = state.position(); delay(1000 - state.position() % 1000 + 5) } }
    }
    Text(mmss(t), style = SableType.caption, color = SableColors.textTertiary)
}

/** What the listener hears while holding the number (MATH §23.6): the file as it is, or its sound held to 6 dB above Sable. */
internal fun originalNote(gainDb: Double): String = when {
    abs(gainDb) < 0.05 -> "the file as it is, same level"
    gainDb > 0 -> "the file as it is, %.1f dB quieter".format(gainDb)
    -gainDb <= app.sable.playback.GainTable.ORIGINAL_MAX_JUMP_DB -> "the file as it is, %.1f dB louder".format(-gainDb)
    else -> "%.1f dB louder as a file · held to +%.0f dB".format(-gainDb, app.sable.playback.GainTable.ORIGINAL_MAX_JUMP_DB)
}

fun worldLabel(g: app.sable.playback.NowPlayingGain): String {
    val world = when {
        g.source == "provisional" -> "listening…"
        g.source == "jit" -> "quick estimate"
        g.albumRun && g.rules.any { it.first == "FLOW" } -> "flowing album tracks"
        g.albumRun -> "album world"
        else -> "song world"
    }
    return "$world · ${g.route}"
}

@Composable
fun QueueScreen(state: AppState, rows: List<LibraryRow>, bottomPad: Dp) {
    val byId = remember(rows) { rows.associateBy { it.mediaId } }
    val plan by NowPlaying.plan.collectAsState()
    val advanced = app.sable.playback.PlaybackPrefs.settings.collectAsState().value.advanced
    val list = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, state.queueIndex - 1))
    Column(Modifier.fillMaxSize()) {
        TopBar("QUEUE", { state.pop() }) {
            if (state.queueIndex + 1 < state.queueIds.size) Pill("Clear", selected = false, Modifier.padding(end = Space.s)) { state.clearUpNext() }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = Space.xl + endPad(bottomPad))) {
            items(state.queueIds.size, key = { state.queueIds[it] }) { i ->
                val id = state.queueIds[i]
                val r = QueueId.trackId(id)?.let { byId[it] }
                val current = i == state.queueIndex
                Row(
                    Modifier.fillMaxWidth().animateItem().clickable { state.withController { it.seekTo(i, 0L) } }
                        .background(if (current) SableColors.surface2 else SableColors.base)
                        .padding(horizontal = Space.gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (r != null) AlbumArt(r.albumId, r.uri, r.album, 40.dp, Modifier.size(40.dp), px = 128)
                    Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                        Text(r?.let(::songTitle) ?: "", style = SableType.body, color = if (current) SableColors.accent else SableColors.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(creditText(r?.let(::credit).orEmpty()), style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val g = plan[id]
                    if (advanced) {
                        if (g != null) Text("%+.1f".format(g), style = SableType.mono, color = SableColors.textTertiary)
                        if (QueueId.albumIntent(id)) Text(" album", style = SableType.caption, color = SableColors.textTertiary)
                    } else if (r != null) Text(mmss(r.durationMs), style = SableType.caption, color = SableColors.textTertiary)
                    if (i > state.queueIndex) IconButton(Glyph.CLOSE, { state.removeAt(i) }, size = 14.dp, color = SableColors.textTertiary)
                    else Spacer(Modifier.width(48.dp))
                }
            }
        }
    }
}

/** The mini player's height, including the gesture bar under it (it pads itself by the navigation-bar inset). */
@Composable
fun bottomBarHeight(): Dp = 76.dp + androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Room at the end of a page: what the mini player covers, or the gesture bar when it is hidden. */
@Composable
fun endPad(bottomPad: Dp): Dp =
    if (bottomPad > 0.dp) bottomPad else androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
