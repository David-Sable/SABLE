package app.sable.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sable.data.LibraryRow
import app.sable.data.PlayEntity
import app.sable.data.SableDatabase
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space

private const val DAY_MS = 24L * 60 * 60 * 1000

/** What Home shows, worked out from the listening history (newest play first) and the library. */
/** One tile in Recently played: the album when you played the album, the song when you played songs (shuffle, a tapped song). */
sealed interface Recent { val key: String }
data class RecentAlbum(val album: AlbumGroup) : Recent { override val key get() = "a|${album.key}" }
data class RecentSong(val row: LibraryRow) : Recent { override val key get() = "s|${row.mediaId}" }

class HomeData(val recent: List<Recent>, val most: List<Pair<LibraryRow, Int>>, val rediscover: List<AlbumGroup>) {
    val isEmpty get() = recent.isEmpty()

    companion object {
        fun of(plays: List<PlayEntity>, rows: List<LibraryRow>, now: Long = System.currentTimeMillis()): HomeData {
            val byId = rows.associateBy { it.mediaId }
            val albums = albumsOf(rows)
            val albumByKey = albums.associateBy { it.key }
            // Recently played, newest first: an album you played as an album, a song you played on its own (a single counts as its album).
            val recent = plays.mapNotNull { p ->
                val r = byId[p.trackId] ?: return@mapNotNull null
                val album = albumByKey[albumKey(r)]
                if ((p.albumWorld || album?.single == true) && album != null) RecentAlbum(album) else RecentSong(r)
            }.distinctBy { it.key }.take(12)
            // Most played over the last 30 days: songs with at least two plays, most first.
            val most = plays.filter { it.playedAt >= now - 30 * DAY_MS }.groupingBy { it.trackId }.eachCount()
                .filter { it.value >= 2 }.entries.sortedByDescending { it.value }
                .mapNotNull { (id, n) -> byId[id]?.let { it to n } }.take(5)
            // Not heard in a while: albums last played more than 30 days ago (longest ago first), then albums never played,
            // in an order that changes once a day so the row stays put while you look at it.
            val lastPlayed = HashMap<String, Long>()
            for (p in plays) byId[p.trackId]?.let(::albumKey)?.let { lastPlayed.putIfAbsent(it, p.playedAt) }
            val stale = albums.filter { (lastPlayed[it.key] ?: return@filter false) < now - 30 * DAY_MS }.sortedBy { lastPlayed[it.key] }
            val never = albums.filter { it.key !in lastPlayed && !it.single }.shuffled(java.util.Random(now / DAY_MS))
            return HomeData(recent, most, (stale + never).take(10))
        }
    }
}

/** Home: a short, quiet look at your own listening. Nothing here is a recommendation from anywhere else. */
@Composable
fun HomeTab(state: AppState, rows: List<LibraryRow>, bottomPad: Dp) {
    val home by LibraryData.home.collectAsState()
    val favIds = rememberFavorites()
    val favs = remember(favIds, rows) { val byId = rows.associateBy { it.mediaId }; favIds.mapNotNull(byId::get) }
    val playlists = rememberPlaylists()
    val h = home ?: return
    if (h.isEmpty && favs.isEmpty() && playlists.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SableMark(64.dp)
            Text("What you play shows up here", style = SableType.body, color = SableColors.textSecondary, modifier = Modifier.padding(top = Space.l))
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = bottomPad + Space.l)) {
        if (h.recent.isNotEmpty()) item(key = "recent") { RecentRow(h.recent, state) }
        if (favs.isNotEmpty()) item(key = "favs") {
            SongTiles("Favourites", favs, state, if (favs.size > 1) ({ state.playSongWorld(favs.shuffled(), 0); state.go(Route.NowPlaying) }) else null) { artistOf(it) }
        }
        if (h.most.isNotEmpty()) item(key = "most") {
            val plays = h.most.associate { (r, n) -> r.mediaId to n }
            SongTiles("Most played this month", h.most.map { it.first }, state) { "${artistOf(it)} · ${plays[it.mediaId]} plays" }
        }
        if (playlists.isNotEmpty()) {
            item(key = "plHead") { Heading("Playlists") }
            items(playlists.size, key = { "pl${playlists[it].first.id}" }) { i ->
                val (p, songs) = playlists[i]
                ListRow(p.name, "${songs.size} song${if (songs.size == 1) "" else "s"}") { state.go(Route.Playlist(p.id)) }
            }
        }
        if (h.rediscover.isNotEmpty()) item(key = "rediscover") { AlbumRow("Not heard in a while", h.rediscover, state) }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = SableType.heading, color = SableColors.textPrimary, modifier = Modifier.padding(start = Space.gutter, top = Space.xl, bottom = Space.m))

@Composable
private fun RecentRow(recent: List<Recent>, state: AppState) {
    val songs = recent.filterIsInstance<RecentSong>().map { it.row }
    // A song's credit may take two lines: every tile keeps room for them, or the page below moves as such a tile scrolls past.
    val captionLines = if (songs.isEmpty()) 1 else 2
    Column {
        Heading("Recently played")
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            items(recent, key = { it.key }) { item ->
                Box(Modifier.width(136.dp)) {
                    when (item) {
                        is RecentAlbum -> AlbumTile(item.album, captionLines) { state.go(Route.Album(item.album.key)) }
                        // A song plays from here, with the other recent songs after it (each in its own world).
                        is RecentSong -> SongTile(item.row, credit(item.row), captionLines, state) { state.playSongWorld(songs, songs.indexOf(item.row)) }
                    }
                }
            }
        }
    }
}

/** A row of songs as covers, like every other row on Home; a long press opens the song's menu, as in a list. */
@Composable
private fun SongTiles(title: String, songs: List<LibraryRow>, state: AppState, shuffle: (() -> Unit)? = null, caption: (LibraryRow) -> String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(end = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Heading(title) }
            if (shuffle != null) Text("Shuffle", style = SableType.label, color = SableColors.textSecondary,
                modifier = Modifier.padding(top = Space.xl, bottom = Space.m).softClick(onClick = shuffle))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            items(songs, key = { it.mediaId }) { r ->
                Box(Modifier.width(136.dp)) { SongTile(r, caption(r), 1, state) { state.playSongWorld(songs, songs.indexOf(r)) } }
            }
        }
    }
}

@Composable
private fun SongTile(r: LibraryRow, caption: String, captionLines: Int, state: AppState, onClick: () -> Unit) {
    Column(Modifier.softClick(pressedScale = 0.97f, haptic = false, onLong = { state.menuTrack = r.mediaId }, onClick = onClick)) {
        AlbumArt(r.albumId, r.uri, r.album, 170.dp, Modifier.fillMaxWidth().aspectRatio(1f), px = 384)
        Text(songTitle(r), style = SableType.name, color = if (state.currentTrackId == r.mediaId) SableColors.accent else SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
        CreditLine(caption, SableType.caption, SableColors.textSecondary, maxLines = captionLines, minLines = captionLines)
    }
}

@Composable
private fun AlbumRow(title: String, albums: List<AlbumGroup>, state: AppState) {
    Column {
        Heading(title)
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            items(albums, key = { it.key }) { a -> Box(Modifier.width(136.dp)) { AlbumTile(a) { state.go(Route.Album(a.key)) } } }
        }
    }
}
