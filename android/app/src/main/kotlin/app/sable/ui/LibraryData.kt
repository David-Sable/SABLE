package app.sable.ui

import android.content.Context
import app.sable.data.FavoriteEntity
import app.sable.data.LibraryRow
import app.sable.data.PlaylistEntity
import app.sable.data.PlaylistTrackEntity
import app.sable.data.SableDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Everything the Library's tabs list, worked out once per change of the library, off the main thread. */
class LibraryModel(val rows: List<LibraryRow>) {
    val albums = albumsOf(rows)
    val artists = albums.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val artistNames = artists.keys.toList()
    val songs = rows.sortedBy { titleOf(it).lowercase() }
    val folders = rows.groupBy { it.relativePath }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val folderNames = folders.keys.toList()
    val folderCover = folders.mapValues { (_, t) -> albumsOf(t).maxByOrNull { it.tracks.size } }
}

/**
 * The library, Home and the saved lists, held for the whole process. A screen reads what is already here, so it never sorts on the
 * main thread and never starts from an empty list: before, every tab and page did that work again each time it came back, which
 * was the stutter on some tab switches and not others (David, 2026-10-30). Null means not loaded yet, never "nothing there".
 */
object LibraryData {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val modelNow = MutableStateFlow<LibraryModel?>(null)
    private val homeNow = MutableStateFlow<HomeData?>(null)
    private val favoritesNow = MutableStateFlow<List<FavoriteEntity>>(emptyList())
    private val playlistsNow = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    private val entriesNow = MutableStateFlow<List<PlaylistTrackEntity>>(emptyList())
    val model: StateFlow<LibraryModel?> = modelNow
    val home: StateFlow<HomeData?> = homeNow
    val favorites: StateFlow<List<FavoriteEntity>> = favoritesNow
    val playlists: StateFlow<List<PlaylistEntity>> = playlistsNow
    val entries: StateFlow<List<PlaylistTrackEntity>> = entriesNow

    fun start(context: Context) {
        if (started) return
        started = true
        val db = SableDatabase.get(context.applicationContext)
        scope.launch { db.favorites().observeAll().collect { favoritesNow.value = it } }
        scope.launch { db.playlists().observePlaylists().collect { playlistsNow.value = it } }
        scope.launch { db.playlists().observeEntries().collect { entriesNow.value = it } }
        scope.launch { db.library().observeRows(SableDatabase.MATH_VERSION).collectLatest { modelNow.value = LibraryModel(it) } }
        scope.launch {
            combine(db.plays().observeAll(), modelNow.filterNotNull()) { plays, m -> plays to m }
                .collectLatest { (plays, m) -> homeNow.value = HomeData.of(plays, m.rows) }
        }
    }
}
