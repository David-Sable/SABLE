package app.sable.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import app.sable.playback.AbSession
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

@Composable
fun MiniPlayer(state: AppState, row: LibraryRow?, modifier: Modifier = Modifier) {
    val tick by frameTick(state.playing, state.durationMs, 1080)
    Column(modifier.fillMaxWidth().background(SableColors.surface1).grain().navigationBarsPadding().clickable { state.go(Route.NowPlaying) }) {
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            tick
            drawRect(SableColors.line)
            drawRect(SableColors.accent, size = size.copy(width = size.width * state.progress()))
        }
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (row != null) AlbumArt(row.albumId, row.uri, row.album, 44.dp, Modifier.size(44.dp), px = 128)
            Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                Text(row?.let(::titleOf) ?: "", style = SableType.body, color = SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row?.let { clean(it.artist) } ?: "", style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(if (state.playing) Glyph.PAUSE else Glyph.PLAY, { state.withController { c -> if (c.isPlaying) c.pause() else c.play() } })
            IconButton(Glyph.NEXT, { state.withController { it.seekToNext() } }, size = 20.dp)
        }
    }
}

@Composable
fun NowPlayingScreen(state: AppState, row: LibraryRow?) {
    val gain by NowPlaying.gain.collectAsState()
    val ab by AbSession.condition.collectAsState()
    val json = analysisJson(state.currentTrackId)
    val shape = remember(json) { SongShape.of(json) }
    val tick by frameTick(state.playing, state.durationMs, 1000)
    LaunchedEffect(state.playing) { while (true) { state.syncPosition(); delay(1000) } }
    val pressScale by animateFloatAsState(if (state.playing) 1f else 0.94f, Motion.slow(), label = "art")
    // Swipe down to close: the screen follows the finger, then either slides away or settles back.
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }
    val swipe = Modifier
        .offset { IntOffset(0, dragY.value.roundToInt()) }
        .graphicsLayer { alpha = 1f - (dragY.value / (closeAt * 4)).coerceIn(0f, 0.5f) }
        .draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { d -> scope.launch { dragY.snapTo((dragY.value + d).coerceAtLeast(0f)) } },
            onDragStopped = { velocity ->
                if (dragY.value > closeAt || velocity > 2500f) state.pop()
                else dragY.animateTo(0f, Motion.medium())
            },
        )
    if (row == null) {
        Column(Modifier.fillMaxSize().grain()) {
            TopBar("NOW PLAYING", { state.pop() })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Nothing playing yet", style = SableType.title, color = SableColors.textTertiary)
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().then(swipe).grain()) {
        TopBar("NOW PLAYING", { state.pop() }) { IconButton(Glyph.QUEUE, { state.go(Route.Queue) }) }
        Column(Modifier.weight(1f).padding(horizontal = Space.xl), verticalArrangement = Arrangement.Center) {
            if (row != null) {
                AlbumArt(row.albumId, row.uri, row.album, 320.dp, Modifier.fillMaxWidth().aspectRatio(1f).scale(pressScale), px = 1024)
            }
            Spacer(Modifier.height(Space.xl))
            Text(row?.let(::titleOf) ?: "", style = SableType.title, color = SableColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(row?.artist, row?.album).map(::clean).filter { it.isNotBlank() }.joinToString(" · "), style = SableType.body, color = SableColors.textSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(Space.xl))
            if (shape != null) {
                LoudnessShape(shape, { tick; state.progress() }, Modifier.fillMaxWidth().height(56.dp)) { state.seekFraction(it) }
            } else {
                Canvas(Modifier.fillMaxWidth().height(56.dp)) {
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
            Row(Modifier.fillMaxWidth().padding(top = Space.l), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(Glyph.PREV, { state.withController { it.seekToPrevious() } }, size = 26.dp)
                Box(
                    Modifier.size(72.dp).background(SableColors.accent, CircleShape).clickable { state.withController { c -> if (c.isPlaying) c.pause() else c.play() } },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (state.playing) Glyph.PAUSE else Glyph.PLAY, size = 30.dp, color = SableColors.onAccent) }
                IconButton(Glyph.NEXT, { state.withController { it.seekToNext() } }, size = 26.dp)
            }
        }
        // The engine at work, in words: the applied gain and why (hidden during a blind A/B round).
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.xl, vertical = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ab != null) {
                Text("Blind A/B round in progress", style = SableType.caption, color = SableColors.textTertiary, modifier = Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f)) {
                    Text("%+.1f dB".format(gain.gainDb), style = SableType.headline, color = SableColors.accent)
                    Text(worldLabel(gain), style = SableType.caption, color = SableColors.textTertiary)
                }
                Pill("Why", selected = false) { state.whyTrack = state.currentTrackId }
            }
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
        g.albumRun -> "album world"
        else -> "song world"
    }
    return "$world · ${g.route}"
}

@Composable
fun QueueScreen(state: AppState, rows: List<LibraryRow>) {
    val byId = remember(rows) { rows.associateBy { it.mediaId } }
    val plan by NowPlaying.plan.collectAsState()
    val ab by AbSession.condition.collectAsState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, state.queueIndex - 1))
    Column(Modifier.fillMaxSize()) {
        TopBar("QUEUE", { state.pop() })
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = Space.xl)) {
            items(state.queueIds.size, key = { state.queueIds[it] }) { i ->
                val id = state.queueIds[i]
                val r = QueueId.trackId(id)?.let { byId[it] }
                val current = i == state.queueIndex
                Row(
                    Modifier.fillMaxWidth().clickable { state.withController { it.seekTo(i, 0L) } }
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
                    if (g != null && ab == null) Text("%+.1f".format(g), style = SableType.mono, color = SableColors.textTertiary, modifier = Modifier.width(44.dp))
                    Text(if (QueueId.albumIntent(id)) "album" else "song", style = SableType.caption, color = SableColors.textTertiary)
                }
            }
        }
    }
}

fun bottomBarHeight(): Dp = 76.dp
