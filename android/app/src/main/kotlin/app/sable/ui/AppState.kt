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
import kotlinx.coroutines.launch

sealed interface Route {
    data object Library : Route
    data class Album(val key: String) : Route
    data class Artist(val name: String) : Route
    data class Folder(val path: String) : Route
    data class Playlist(val id: Long) : Route
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
    /** The song whose "Add to playlist" sheet is open. */
    var playlistTrack by mutableStateOf<Long?>(null)
    /** The sleep timer sheet (opened from Now Playing, drawn over everything by SableApp). */
    var sleepSheet by mutableStateOf(false)
    /** Now Playing is being drawn once, invisibly, to have its pictures ready before its first opening (SableApp). */
    var npPrewarm by mutableStateOf(false)
    /** The cover's flight between the mini player and Now Playing (SableApp draws it): where each real cover is on screen (root
     *  coordinates) and how far the sheet is open. While it flies, the real covers are hidden. */
    var miniCover by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var npCover by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** How far below its resting place the sheet is placed right now (px). The opening flight aims at Now Playing's cover moved up
     *  by this, i.e. where it will rest: aiming at the cover as it rose with the sheet bent the path down and up again, a bounce
     *  (David, 2026-10-29). Written while placing the sheet, read while drawing the flight. */
    var npSheetOffset = 0f
    /**
     * Now Playing's one motion (0 away .. 1 open): the sheet, the cover, the words, the mini player and the screen behind all follow
     * this single value. The finger holds it (swipe up on the mini player, down on Now Playing) and lets go onto a spring that keeps
     * the finger's speed and never overshoots; a tap or Back runs the same spring from rest. Before, the sheet, the cover's flight and
     * the finger's leftover offset ran on three clocks and drifted apart mid-way (David, 2026-10-29: "our signature").
     */
    val sheet = androidx.compose.animation.core.Animatable(0f)
    fun sheetProgress(): Float = sheet.value
    /** The finger's speed (progress per second) handed to the spring that the next open/close starts (SableApp). */
    var sheetVelocity = 0f
    /** Set by SableApp: the coroutine scope that moves the sheet and the sheet's full travel in px. */
    var sheetScope: kotlinx.coroutines.CoroutineScope? = null
    var sheetTravel = 1f
    /** Which path the cover takes: closing rides the sheet down; opening flies straight out of the mini player (SableApp). */
    var sheetLeaving = false
    /** The finger moved the sheet by [dpx] px (up is positive), from Now Playing ([leaving]) or from the mini player. */
    fun dragSheet(dpx: Float, leaving: Boolean) {
        sheetLeaving = leaving
        sheetScope?.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { sheet.snapTo((sheet.value + dpx / sheetTravel).coerceIn(0f, 1f)) }
    }
    /** Android's back gesture on Now Playing: the sheet follows the thumb part of the way, and the rest happens when it lets go. */
    fun backSheet(progress: Float) {
        sheetLeaving = true
        sheetScope?.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { sheet.snapTo(1f - 0.3f * progress) }
    }
    fun cancelBackSheet() { sheetScope?.launch { sheet.animateTo(1f, app.sable.ui.theme.Motion.sheetSpring) } }
    /** The finger let go at [vpx] px/s (up is positive): open or close, decided by where it is and where it was heading. */
    fun releaseSheet(vpx: Float) {
        val v = vpx / sheetTravel
        val p = sheet.value
        val open = if (kotlin.math.abs(v) > 1f) v > 0f else p + 0.15f * v > 0.5f
        val isOpen = back.any { it is Route.NowPlaying }
        if (open != isOpen) { sheetVelocity = v; if (open) go(Route.NowPlaying) else pop() }
        else sheetScope?.launch { sheet.animateTo(if (open) 1f else 0f, app.sable.ui.theme.Motion.sheetSpring, initialVelocity = v) }
    }
    fun coverFlying(): Boolean = !debugNoFlight && miniCover != null && npCover != null && sheetProgress().let { it > 0f && it < 1f }
    var whyTrack by mutableStateOf<Long?>(null)
    var searchQuery by mutableStateOf("")  // kept while browsing from a result and back
    var menuTrack by mutableStateOf<Long?>(null)  // the long-pressed song (SongMenu)

    var controller by mutableStateOf<MediaController?>(null)
    private val pending = ArrayList<(MediaController) -> Unit>()

    var playing by mutableStateOf(false)
    // Debug switches for measuring the Now Playing animation piece by piece (`--es cmd animflag`); all off in normal use.
    // The cover flies from the mini player into Now Playing and back into the corner: the default and only motion (David, 2026-10-29:
    // "that was premium feeling"). `animflag noflight 1` turns it off for measuring.
    var debugNoFlight by mutableStateOf(false)
    var debugNoCapture by mutableStateOf(false)
    var debugNoRecord by mutableStateOf(false)
    // Now Playing's sheet, live-tunable (`--es cmd animflag`): how far it is up (0 away .. 1 open; read in draw/graphics lambdas only),
    // the speed factor of the sheet and the cover's flight (1 = as designed, 2 = twice as slow), and the pieces of the choreography.
    var sheetP by androidx.compose.runtime.mutableFloatStateOf(0f)
    var animDur by androidx.compose.runtime.mutableFloatStateOf(0.85f)  // Now Playing's slide: x0.85 = ~440 ms in, ~320 ms out at 1x (1.25 was chosen at 0.5x and dragged at 1x; David, 2026-10-29)
    var animDepth by mutableStateOf(true)      // the screen behind steps back (smaller, dimmer) while the sheet is up
    var animCorners by mutableStateOf(true)    // the sheet's top corners are round until it fills the screen
    var animStagger by mutableStateOf(false)    // the words and controls arrive after the cover, following the sheet
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
    /** The songs a swipe reaches, as the player sees them (repeat wraps round); -1 when there is none. */
    var nextIndex by mutableIntStateOf(-1)
    var previousIndex by mutableIntStateOf(-1)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)

    fun go(r: Route) {
        // Now Playing is one sheet: asked for again from a screen above it (the queue), go back down to it instead of stacking a second.
        if (r is Route.NowPlaying && r in back) while (route !is Route.NowPlaying) back.removeAt(back.lastIndex)
        else if (route != r) back.add(r)
    }
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

    /** How the last song change moved along the queue: +1 forward, -1 back, 0 a jump. The cover, the words and the mini player go that way. */
    var songStep = 0
        private set

    fun refresh(p: Player) {
        val id = p.currentMediaItem?.mediaId
        val index = p.currentMediaItemIndex
        if (id != currentQueueId || index != queueIndex) songStep = when {
            index == nextIndex && p.mediaItemCount == queueIds.size -> 1
            index == previousIndex && p.mediaItemCount == queueIds.size -> -1
            else -> 0
        }
        playing = p.isPlaying
        currentQueueId = p.currentMediaItem?.mediaId
        durationMs = p.duration.coerceAtLeast(0L)
        positionMs = p.currentPosition
        positionAt = SystemClock.elapsedRealtime()
        queueIds = List(p.mediaItemCount) { p.getMediaItemAt(it).mediaId }
        queueIndex = p.currentMediaItemIndex
        nextIndex = p.nextMediaItemIndex
        previousIndex = p.previousMediaItemIndex
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

    /**
     * Song world: plays from the tapped song, each song balanced in its own world. The songs before it are in the queue too, so
     * Previous and a swipe back reach the one before (before 0.31.1 the queue started at the tapped song and a swipe back
     * could only restart it).
     */
    fun playSongWorld(tracks: List<LibraryRow>, from: Int) =
        play(tracks.map { MediaItem.Builder().setMediaId(QueueId.request(it.mediaId, false)).build() }, from.coerceIn(0, maxOf(0, tracks.size - 1)))

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
fun clean(tag: String) = if (tag == "<unknown>") "" else unexplicit(tag)
/** Display only: "(Explicit)", "[Explicit]" and a trailing "Explicit" are store labels, not part of the name. */
fun unexplicit(s: String) = s.replace(EXPLICIT, "").trim().ifEmpty { s }
private val EXPLICIT = Regex("""\s*[(\[]\s*explicit(\s+version)?\s*[)\]]|\s+explicit$""", RegexOption.IGNORE_CASE)
/** "1 song", "2 songs". */
fun count(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
/**
 * A file with no tags at all is named like "Tinashe - Brainrot [Official Audio]": read Artist and Title from the name for
 * display only (null when the song has an artist tag, or the name is not "Artist - Title"). Nothing stored or learned changes.
 */
fun nameTags(r: LibraryRow): Pair<String, String>? {
    if (clean(r.artist).isNotBlank() || clean(r.albumArtist).isNotBlank()) return null
    val stem = r.displayName.substringBeforeLast('.', r.displayName).trim()
    if (r.title.isNotBlank() && r.title.trim() != stem) return null  // a real title tag
    val parts = stem.replace(Regex("""\s*[\[(][^\])]*[\])]\s*$"""), "").split(" - ", limit = 2)
    if (parts.size != 2) return null
    val artist = parts[0].trim()
    val title = parts[1].trim()
    return if (artist.isEmpty() || title.isEmpty()) null else artist to title
}
/** "Artist · Album", without a dangling dot when the song has no album. */
fun songSubtitle(r: LibraryRow) = listOf(credit(r), clean(r.album)).filter { it.isNotBlank() }.joinToString(" · ")
fun artistOf(r: LibraryRow) = clean(r.albumArtist).ifBlank { clean(r.artist) }.ifBlank { nameTags(r)?.first ?: "Unknown artist" }
fun titleOf(r: LibraryRow) = nameTags(r)?.second ?: unexplicit(r.title.ifBlank { r.displayName })
/**
 * Featured artists are often written only in the title ("Free The Frail (feat. Helena Deland)"; the artist tag says just
 * JPEGMAFIA). For display, the title loses that part and the credit line carries it: "JPEGMAFIA feat. Helena Deland".
 */
private val FEAT = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|featuring)\s+([^)\]]+?)\s*[)\]]|\s+(?:feat\.|ft\.|featuring)\s+(.+)$""", RegexOption.IGNORE_CASE)
/** The featured names written in a title, or null. */
fun featuredOf(title: String): String? = FEAT.find(title)?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).trim().ifBlank { null } }
/**
 * The title as shown beside its credit line: without the "(feat. …)" part, and without a track number written into the title
 * itself ("1. 投影の芸術"; a number with no dot after it, like "1999", stays).
 */
fun songTitle(r: LibraryRow): String = titleOf(r).let { t -> t.replace(FEAT, "").replace(TRACK_NO, "").trim().ifEmpty { t } }
private val TRACK_NO = Regex("""^\d{1,3}\.\s+""")
/** The song's artist with everyone featured on it, each name once. */
fun credit(r: LibraryRow): String {
    val main = clean(r.artist).ifBlank { nameTags(r)?.first ?: "Unknown artist" }
    val feat = featuredOf(titleOf(r)) ?: return onceEach(main)
    return onceEach(if (main.contains(feat, ignoreCase = true)) main else "$main feat. $feat")
}
/**
 * A credit with each name once: some files spell a name twice ("Rina Sawayama feat. Shamir & Shamir"). A repeated name goes
 * with the separator before it; display only, the file keeps its tag.
 */
fun onceEach(s: String): String {
    val seen = HashSet<String>()
    val out = StringBuilder()
    var sep = ""
    var from = 0
    for (m in NAME_SEP.findAll(s) + sequenceOf(null)) {
        val name = s.substring(from, m?.range?.first ?: s.length).trim()
        if (name.isNotEmpty() && seen.add(name.lowercase())) out.append(sep).append(name)
        if (m == null) break
        sep = m.value; from = m.range.last + 1
    }
    return out.toString().ifEmpty { s }
}
private val NAME_SEP = Regex("""\s*,\s*|\s+&\s+|\s+and\s+|\s+feat\.\s+|\s+x\s+""")
fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
