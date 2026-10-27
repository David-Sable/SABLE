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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space
import app.sable.ui.theme.grain
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
import kotlin.math.roundToInt

/**
 * Redraw clock for the playhead. It ticks only while playing, and only as often as the playhead moves by one pixel
 * across [widthPx] (a 3-minute song over 1000 px: every ~180 ms), so a moving playhead stays smooth without the
 * cost of redrawing every 90 Hz frame (battery, MATH-independent UI tuning; M7).
 */
@Composable
fun frameTick(active: Boolean, durationMs: Long = 0L, widthPx: Int = 1000): State<Long> = produceState(0L, active, durationMs, widthPx) {
    val step = if (durationMs > 0 && widthPx > 0) (durationMs / widthPx).coerceIn(16L, 500L) else 250L
    while (active) {
        delay(step)
        value = SystemClock.uptimeMillis()
    }
}

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

/**
 * Swipe sideways to change songs: left for the next one, right for the previous one (or the start of the first). The
 * content follows the finger in the draw phase, leaves on the side it was pushed, and the new song comes in from the
 * other side on a spring that carries the finger's speed. Used by the Now Playing cover and the mini player.
 */
@Composable
fun songSwipe(state: AppState): Modifier {
    val scope = rememberCoroutineScope()
    val settle = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    val dragX = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    return Modifier
        .onSizeChanged { width = maxOf(1, it.width) }
        .graphicsLayer {
            translationX = dragX.value
            alpha = 1f - (abs(dragX.value) / width).coerceIn(0f, 0.6f)
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        .draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { d -> scope.launch(start = CoroutineStart.UNDISPATCHED) { dragX.snapTo(dragX.value + d) } },
            onDragStopped = { velocity ->
                val w = width.toFloat()
                val dir = when {
                    dragX.value < -w * 0.25f || velocity < -1500f -> 1    // next
                    dragX.value > w * 0.25f || velocity > 1500f -> -1     // previous
                    else -> 0
                }
                if (dir == 0) dragX.animateTo(0f, settle, initialVelocity = velocity)
                else {
                    dragX.animateTo(-dir * w, tween(160, easing = LinearOutSlowInEasing), initialVelocity = velocity)
                    state.withController { c -> if (dir > 0) c.seekToNextMediaItem() else if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0L) }
                    dragX.snapTo(dir * w * 0.6f)  // the new song comes in from the other side
                    dragX.animateTo(0f, settle)
                }
            },
        )
}

@Composable
fun MiniPlayer(state: AppState, row: LibraryRow?, modifier: Modifier = Modifier) {
    val tick by frameTick(state.playing, state.durationMs, 1080)
    Column(modifier.fillMaxWidth().background(SableColors.surface1).grain().navigationBarsPadding().clickable { state.go(Route.NowPlaying) }) {
        Canvas(Modifier.fillMaxWidth().height(2.dp).graphicsLayer()) {  // own layer: the 150 ms progress redraw stays here
            tick
            drawRect(SableColors.line)
            drawRect(SableColors.accent, size = size.copy(width = size.width * state.progress()))
        }
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            // Cover and title swipe sideways to change songs; a tap anywhere still opens Now Playing.
            Row(Modifier.weight(1f).then(songSwipe(state)), verticalAlignment = Alignment.CenterVertically) {
            if (row != null) AlbumArt(row.albumId, row.uri, row.album, 44.dp, Modifier.size(44.dp), px = 128)
            Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                Text(row?.let(::titleOf) ?: "", style = SableType.body, color = SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row?.let { clean(it.artist) } ?: "", style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
            IconButton(if (state.playing) Glyph.PAUSE else Glyph.PLAY, { state.withController { c -> if (c.isPlaying) c.pause() else c.play() } })
            IconButton(Glyph.NEXT, { state.withController { it.seekToNext() } }, size = 20.dp)
        }
    }
}

@Composable
fun NowPlayingScreen(state: AppState, row: LibraryRow?, rowOf: (Long) -> LibraryRow? = { null }) {
    val gain by NowPlaying.gain.collectAsState()
    val sleep by app.sable.playback.SleepTimer.state.collectAsState()
    var sleepOpen by remember { mutableStateOf(false) }
    val json = analysisJson(state.currentTrackId)
    val shape = remember(json) { SongShape.of(json) }
    val tick by frameTick(state.playing, state.durationMs, 1000)
    LaunchedEffect(state.playing) { while (true) { state.syncPosition(); delay(1000) } }
    val pressScale by animateFloatAsState(if (state.playing) 1f else 0.94f, Motion.slow(), label = "art")
    // Gestures move only in the draw phase (graphicsLayer translation: no relayout per frame), follow the finger the
    // same frame (undispatched), and settle on a spring that carries the finger's speed, so nothing jumps or stalls.
    val scope = rememberCoroutineScope()
    fun follow(a: Animatable<Float, *>, to: Float) { scope.launch(start = CoroutineStart.UNDISPATCHED) { a.snapTo(to) } }
    val settle = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    // Swipe down to close: the screen follows the finger, then either slides away or settles back.
    val dragY = remember { Animatable(0f) }
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }
    val swipe = Modifier
        .graphicsLayer {
            translationY = dragY.value
            alpha = 1f - (dragY.value / (closeAt * 4)).coerceIn(0f, 0.5f)
            compositingStrategy = CompositingStrategy.ModulateAlpha  // no offscreen copy of the whole screen per frame
        }
        .draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { d -> follow(dragY, (dragY.value + d).coerceAtLeast(0f)) },
            onDragStopped = { velocity ->
                if (dragY.value > closeAt || velocity > 2500f) state.pop()
                else dragY.animateTo(0f, settle, initialVelocity = velocity)
            },
        )
    // Swipe the cover sideways to change songs: left for the next one, right for the previous one.
    val artSwipe = songSwipe(state)
    // Decode the neighbours' covers ahead of time, so a swipe never shows a blank square while the new one loads.
    val context = LocalContext.current
    LaunchedEffect(state.queueIndex, state.queueIds) {
        for (i in listOf(state.queueIndex + 1, state.queueIndex - 1)) {
            val r = state.queueIds.getOrNull(i)?.let(QueueId::trackId)?.let(rowOf) ?: continue
            ArtCache.load(context, r.albumId, r.uri, 1024)
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
    Column(Modifier.fillMaxSize().then(swipe).grain()) {
        TopBar("NOW PLAYING", { state.pop() }) {
            IconButton(Glyph.QUEUE, { state.go(Route.Queue) })
        }
        sleepStatus(sleep)?.let { Text(it, style = SableType.caption, color = SableColors.accent, modifier = Modifier.padding(horizontal = Space.xl)) }
        if (sleepOpen) SleepSheet { sleepOpen = false }
        // A short window (split screen, a small pane) puts the cover beside the controls instead of above them.
        BoxWithConstraints(Modifier.weight(1f)) {
            val compact = maxHeight < 440.dp
            val gap = if (compact) Space.s else Space.xl
            val shapeH = if (compact) 40.dp else 56.dp
            val info: @Composable ColumnScope.() -> Unit = {
            // Tap the title for its album, the artist line for the artist.
            Text(row?.let(::titleOf) ?: "", style = SableType.title, color = SableColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = row != null) { row?.let { state.go(Route.Album(albumKey(it))) } })
            Text(listOfNotNull(row?.artist, row?.album).map(::clean).filter { it.isNotBlank() }.joinToString(" · "), style = SableType.body, color = SableColors.textSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp).clickable(enabled = row != null) { row?.let { state.go(Route.Artist(artistOf(it))) } })
            Spacer(Modifier.height(gap))
            if (shape != null) {
                LoudnessShape(shape, { tick; state.progress() }, Modifier.fillMaxWidth().height(shapeH).graphicsLayer()) { state.seekFraction(it) }  // own layer
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
                Text(mmss(state.durationMs), style = SableType.caption, color = SableColors.textTertiary)
            }
            Row(Modifier.fillMaxWidth().padding(top = if (compact) Space.s else Space.l), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                // Repeat on the left, sleep on the right: both lit in the accent while on.
                IconButton(if (state.repeatMode == Player.REPEAT_MODE_ONE) Glyph.REPEAT_ONE else Glyph.REPEAT, { state.cycleRepeat() }, size = 22.dp,
                    color = if (state.repeatMode == Player.REPEAT_MODE_OFF) SableColors.textTertiary else SableColors.accent)
                IconButton(Glyph.PREV, { state.withController { it.seekToPrevious() } }, size = 26.dp)
                Box(
                    Modifier.size(if (compact) 48.dp else 72.dp).background(SableColors.accent, CircleShape).clickable { state.withController { c -> if (c.isPlaying) c.pause() else c.play() } },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (state.playing) Glyph.PAUSE else Glyph.PLAY, size = 30.dp, color = SableColors.onAccent) }
                IconButton(Glyph.NEXT, { state.withController { it.seekToNext() } }, size = 26.dp)
                IconButton(Glyph.MOON, { sleepOpen = true }, size = 20.dp, color = if (sleep != null) SableColors.accent else SableColors.textTertiary)
            }
            Text(
                when (state.repeatMode) { Player.REPEAT_MODE_ALL -> "Repeating the queue"; Player.REPEAT_MODE_ONE -> "Repeating this song"; else -> "" },
                style = SableType.caption, color = SableColors.accent, modifier = Modifier.fillMaxWidth().padding(top = Space.xs), textAlign = TextAlign.Center,
            )
            }
            if (compact) {
                Row(Modifier.fillMaxSize().padding(horizontal = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(row.albumId, row.uri, row.album, 320.dp, Modifier.weight(0.3f).aspectRatio(1f).then(artSwipe).scale(pressScale), px = 1024)
                    Column(Modifier.weight(0.7f).padding(start = Space.m), verticalArrangement = Arrangement.Center, content = info)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = Space.xl), verticalArrangement = Arrangement.Center) {
                    AlbumArt(row.albumId, row.uri, row.album, 320.dp, Modifier.fillMaxWidth().aspectRatio(1f).then(artSwipe).scale(pressScale), px = 1024)
                    Spacer(Modifier.height(gap))
                    info()
                }
            }
        }
        // The engine at work, in words: the applied gain and why (hidden during a blind A/B round).
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.xl, vertical = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("%+.1f dB".format(gain.gainDb), style = SableType.headline, color = SableColors.accent)
                Text(worldLabel(gain), style = SableType.caption, color = SableColors.textTertiary)
            }
            Pill("Why", selected = false) { state.whyTrack = state.currentTrackId }
        }
    }
}

@Composable
private fun Clock(state: AppState) {
    val t by produceState(0L, state.playing, state.currentQueueId) {
        while (true) { value = state.position(); delay(1000 - state.position() % 1000 + 5) }
    }
    Text(mmss(t), style = SableType.caption, color = SableColors.textTertiary)
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
                        Text(r?.let(::titleOf) ?: "", style = SableType.body, color = if (current) SableColors.accent else SableColors.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r?.artist ?: "", style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val g = plan[id]
                    if (g != null) Text("%+.1f".format(g), style = SableType.mono, color = SableColors.textTertiary, modifier = Modifier.width(44.dp))
                    Text(if (QueueId.albumIntent(id)) "album" else "song", style = SableType.caption, color = SableColors.textTertiary)
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
