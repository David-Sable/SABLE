package app.sable.ui

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import app.sable.data.LibraryRow
import app.sable.playback.QueueId

sealed interface Route {
    data object Library : Route
    data class Album(val key: String) : Route
    data class Artist(val name: String) : Route
    data class Folder(val path: String) : Route
    data object NowPlaying : Route
    data object Queue : Route
    data object Settings : Route
}

/** UI state shared by every screen: navigation, the media controller, and the playing item. */
@Stable
class AppState {
    val back = mutableStateListOf<Route>(Route.Library)
    val route: Route get() = back.last()
    var libraryTab by mutableIntStateOf(0)
    var whyTrack by mutableStateOf<Long?>(null)

    var controller by mutableStateOf<MediaController?>(null)
    private val pending = ArrayList<(MediaController) -> Unit>()

    var playing by mutableStateOf(false)
    var currentQueueId by mutableStateOf<String?>(null)
    val currentTrackId: Long? get() = currentQueueId?.let(QueueId::trackId)
    var durationMs by mutableLongStateOf(0L)
    private var positionMs = 0L
    private var positionAt = 0L
    var queueIds by mutableStateOf<List<String>>(emptyList())
    var queueIndex by mutableIntStateOf(0)

    fun go(r: Route) { if (route != r) back.add(r) }
    fun pop(): Boolean = if (back.size > 1) { back.removeAt(back.lastIndex); true } else false

    fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: pending.add(action)
    }

    fun attach(c: MediaController) {
        controller = c
        c.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = refresh(player)
        })
        refresh(c)
        pending.forEach { it(c) }
        pending.clear()
    }

    fun refresh(p: Player) {
        playing = p.isPlaying
        currentQueueId = p.currentMediaItem?.mediaId
        durationMs = p.duration.coerceAtLeast(0L)
        positionMs = p.currentPosition
        positionAt = SystemClock.elapsedRealtime()
        queueIds = List(p.mediaItemCount) { p.getMediaItemAt(it).mediaId }
        queueIndex = p.currentMediaItemIndex
    }

    /** Smooth position between player updates (read at draw time). */
    fun position(): Long = if (playing) positionMs + (SystemClock.elapsedRealtime() - positionAt) else positionMs

    fun progress(): Float = if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun syncPosition() { controller?.let { positionMs = it.currentPosition; positionAt = SystemClock.elapsedRealtime() } }

    fun seekFraction(f: Float) = withController { c -> if (c.duration > 0) c.seekTo((f * c.duration).toLong()); refresh(c) }

    /** Album world: Play album (MATH §17.1). */
    fun playAlbum(tracks: List<LibraryRow>, start: Int = 0) = play(tracks.map { MediaItem.Builder().setMediaId(QueueId.request(it.mediaId, true)).build() }, start)

    /** Song world: the tapped song and everything after it, each balanced in its own world. */
    fun playSongWorld(tracks: List<LibraryRow>, from: Int) =
        play(tracks.drop(from).map { MediaItem.Builder().setMediaId(QueueId.request(it.mediaId, false)).build() }, 0)

    fun play(items: List<MediaItem>, start: Int) = withController { c ->
        c.setMediaItems(items, start, 0L)
        c.prepare()
        c.play()
    }
}

fun albumKey(r: LibraryRow) = "${r.albumId}|${r.albumArtist}"
/** MediaStore writes "<unknown>" for missing tags. */
fun clean(tag: String) = if (tag == "<unknown>") "" else tag
fun artistOf(r: LibraryRow) = clean(r.albumArtist).ifBlank { clean(r.artist) }.ifBlank { "Unknown artist" }
fun titleOf(r: LibraryRow) = r.title.ifBlank { r.displayName }
fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
