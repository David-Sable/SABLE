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
    data object Search : Route
}

/** UI state shared by every screen: navigation, the media controller, and the playing item. */
@Stable
class AppState {
    val back = mutableStateListOf<Route>(Route.Library)
    val route: Route get() = back.last()
    var libraryTab by mutableIntStateOf(0)
    var whyTrack by mutableStateOf<Long?>(null)
    var searchQuery by mutableStateOf("")  // kept while browsing from a result and back
    var menuTrack by mutableStateOf<Long?>(null)  // the long-pressed song (SongMenu)

    var controller by mutableStateOf<MediaController?>(null)
    private val pending = ArrayList<(MediaController) -> Unit>()

    var playing by mutableStateOf(false)
    // Debug switches for measuring the Now Playing animation piece by piece (`--es cmd animflag`); all off in normal use.
    var debugNoFlight by mutableStateOf(false)
    var debugNoCapture by mutableStateOf(false)
    var debugNoRecord by mutableStateOf(false)
    // Now Playing's sheet, live-tunable (`--es cmd animflag`): how far it is up (0 away .. 1 open; read in draw/graphics lambdas only),
    // the speed factor of the sheet and the cover's flight (1 = as designed, 2 = twice as slow), and the pieces of the choreography.
    var sheetP by androidx.compose.runtime.mutableFloatStateOf(0f)
    var animDur by androidx.compose.runtime.mutableFloatStateOf(1.25f)
    var animDepth by mutableStateOf(true)      // the screen behind steps back (smaller, dimmer) while the sheet is up
    var animCorners by mutableStateOf(true)    // the sheet's top corners are round until it fills the screen
    var animStagger by mutableStateOf(false)    // the words and controls arrive after the cover, following the sheet
    var liveBeneath by mutableIntStateOf(0)    // screens other than Now Playing composed right now (0 = the frozen picture must stand in)
    var animGrow by mutableStateOf(false)      // Now Playing grows out of the mini player's bar (its edges open up) instead of sliding up
    var animSpring by mutableStateOf(false)    // the cover's flight settles on a spring instead of a timed curve
    var debugCaptureDelayMs by mutableStateOf(0L)
    var currentQueueId by mutableStateOf<String?>(null)
    val currentTrackId: Long? get() = currentQueueId?.let(QueueId::trackId)
    var durationMs by mutableLongStateOf(0L)
    private var positionMs = 0L
    private var positionAt = 0L
    var queueIds by mutableStateOf<List<String>>(emptyList())
    var queueIndex by mutableIntStateOf(0)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)

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
        repeatMode = p.repeatMode
    }

    /** Off → whole queue → this song → off. */
    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        refresh(c)
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

    /** Slips a song in right after the playing one (song world); with nothing loaded it just plays. */
    fun playNext(r: LibraryRow) = withController { c ->
        val item = MediaItem.Builder().setMediaId(QueueId.request(r.mediaId, false)).build()
        if (c.mediaItemCount == 0) play(listOf(item), 0) else c.addMediaItem(c.currentMediaItemIndex + 1, item)
    }

    /** Adds a song at the end of the queue (song world); with nothing loaded it just plays. */
    fun addToQueue(r: LibraryRow) = withController { c ->
        val item = MediaItem.Builder().setMediaId(QueueId.request(r.mediaId, false)).build()
        if (c.mediaItemCount == 0) play(listOf(item), 0) else c.addMediaItem(item)
    }

    /** Empties the queue after the playing song; the song itself keeps playing. */
    fun clearUpNext() = withController { c ->
        val from = c.currentMediaItemIndex + 1
        if (from < c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
        refresh(c)
    }

    /** Removes one song from the queue (never the playing one). */
    fun removeAt(i: Int) = withController { c -> if (i != c.currentMediaItemIndex && i in 0 until c.mediaItemCount) c.removeMediaItem(i); refresh(c) }

    fun play(items: List<MediaItem>, start: Int) = withController { c ->
        c.setMediaItems(items, start, 0L)
        c.prepare()
        c.play()
    }
}

/** A song with no album tag is a single, its own entry: MediaStore files all such songs of a folder under one fake album, which would be shown (and played) as a record that does not exist. */
fun albumKey(r: LibraryRow) = if (r.album.isBlank()) "single|${r.mediaId}" else "${r.albumId}|${r.albumArtist}"
/** MediaStore writes "<unknown>" for missing tags. */
fun clean(tag: String) = if (tag == "<unknown>") "" else tag
/** "Artist · Album", without a dangling dot when the song has no album. */
fun songSubtitle(r: LibraryRow) = listOf(clean(r.artist).ifBlank { "Unknown artist" }, clean(r.album)).filter { it.isNotBlank() }.joinToString(" · ")
fun artistOf(r: LibraryRow) = clean(r.albumArtist).ifBlank { clean(r.artist) }.ifBlank { "Unknown artist" }
fun titleOf(r: LibraryRow) = r.title.ifBlank { r.displayName }
fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
