package app.sable.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import app.sable.ui.theme.Motion
import app.sable.ui.theme.Radius
import kotlin.math.abs
import kotlin.math.roundToInt

/** One cover travelling between its tile and its album page. */
data class Flight(val key: String, val albumId: Long, val uri: String?, val tile: Rect, val back: Boolean = false)

/**
 * Tapping an album: its cover flies from the tile to the top of the album page, and back the same way, as Now Playing's does from
 * the mini player. Drawn over the page from two rectangles, the tile and the page's cover; both real covers are hidden meanwhile.
 * A tile cut off by the screen's edge, or a page scrolled past its cover, simply has no flight.
 */
object AlbumFlight {
    var active by mutableStateOf<Flight?>(null)
    var header by mutableStateOf<Rect?>(null)
    private var last: Flight? = null

    fun open(key: String, albumId: Long, uri: String?, tile: Rect?) {
        last = null
        if (tile == null || abs(tile.width - tile.height) > 4f) return
        header = null
        last = Flight(key, albumId, uri, tile)
        active = last
    }

    fun close(key: String) {
        val f = last?.takeIf { it.key == key } ?: return
        val h = header ?: return
        if (h.top >= 0f && abs(h.width - h.height) <= 4f) active = f.copy(back = true)
    }

    fun hides(key: String) = active?.key == key
}

/** The album page's cover: where it is, and hidden while its flight is in the air. */
fun Modifier.albumHeader(key: String): Modifier = this
    .onGloballyPositioned { AlbumFlight.header = it.boundsInRoot() }
    .graphicsLayer { alpha = if (AlbumFlight.hides(key)) 0f else 1f }

@Composable
fun AlbumFlightLayer(modifier: Modifier = Modifier) {
    val f = AlbumFlight.active ?: return
    val p = remember(f) { Animatable(0f) }
    LaunchedEffect(f) {
        p.animateTo(1f, Motion.panel(0.001f))
        if (AlbumFlight.active == f) AlbumFlight.active = null
    }
    Canvas(modifier.fillMaxSize()) {
        val to = AlbumFlight.header ?: return@Canvas
        val img = ArtCache.peekAny(f.albumId, 1024) ?: return@Canvas
        val t = if (f.back) 1f - p.value else p.value
        val l = lerp(f.tile.left, to.left, t)
        val top = lerp(f.tile.top, to.top, t)
        val w = lerp(f.tile.width, to.width, t)
        val h = lerp(f.tile.height, to.height, t)
        val side = minOf(img.width, img.height)  // the cover's centre square, as the covers crop it
        clipPath(Path().apply { addRoundRect(RoundRect(l, top, l + w, top + h, CornerRadius(Radius.art.toPx()))) }) {
            drawImage(
                img, srcOffset = IntOffset((img.width - side) / 2, (img.height - side) / 2), srcSize = IntSize(side, side),
                dstOffset = IntOffset(l.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            )
        }
    }
}
