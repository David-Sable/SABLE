package app.sable.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
class HomeData(val recent: List<AlbumGroup>, val most: List<Pair<LibraryRow, Int>>, val rediscover: List<AlbumGroup>) {
    val isEmpty get() = recent.isEmpty()

    companion object {
        fun of(plays: List<PlayEntity>, rows: List<LibraryRow>, now: Long = System.currentTimeMillis()): HomeData {
            val byId = rows.associateBy { it.mediaId }
            val albums = albumsOf(rows)
            val albumByKey = albums.associateBy { it.key }
            // Recently played: albums in the order you last played something from them.
            val recent = plays.mapNotNull { byId[it.trackId]?.let(::albumKey) }.distinct().mapNotNull(albumByKey::get).take(12)
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
    val context = LocalContext.current
    val playsFlow = remember { SableDatabase.get(context).plays().observeAll() }
    val plays by playsFlow.collectAsState(initial = null)
    val home = remember(plays, rows) { plays?.let { HomeData.of(it, rows) } }
    val favIds = rememberFavorites()
    val favs = remember(favIds, rows) { val byId = rows.associateBy { it.mediaId }; favIds.mapNotNull(byId::get) }
    val playlists = rememberPlaylists()
    if (home == null) return
    if (home.isEmpty && favs.isEmpty() && playlists.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SableMark(64.dp)
            Text("What you play shows up here", style = SableType.body, color = SableColors.textSecondary, modifier = Modifier.padding(top = Space.l))
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = Space.l, bottom = bottomPad + Space.l)) {
        if (home.recent.isNotEmpty()) item(key = "recent") { AlbumRow("Recently played", home.recent, state) }
        if (favs.isNotEmpty()) {
            item(key = "favHead") {
                Row(Modifier.fillMaxWidth().padding(end = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Heading("Favourites") }
                    if (favs.size > 1) Pill("Shuffle", selected = false, Modifier.padding(top = Space.l)) { state.playSongWorld(favs.shuffled(), 0); state.go(Route.NowPlaying) }
                }
            }
            val shown = favs.take(6)
            items(shown.size, key = { "fav${shown[it].mediaId}" }) { i ->
                val r = shown[i]
                SongRow(r, null, state.currentTrackId == r.mediaId, songSubtitle(r), { state.playSongWorld(favs, i) }, { state.menuTrack = r.mediaId }) {
                    state.whyTrack = r.mediaId
                }
            }
        }
        if (home.most.isNotEmpty()) {
            item(key = "mostHead") { Heading("Most played this month") }
            val songs = home.most.map { it.first }
            items(home.most.size, key = { "most${home.most[it].first.mediaId}" }) { i ->
                val (r, n) = home.most[i]
                SongRow(r, null, state.currentTrackId == r.mediaId, "${artistOf(r)} · $n plays", { state.playSongWorld(songs, i) }, { state.menuTrack = r.mediaId }) {
                    state.whyTrack = r.mediaId
                }
            }
        }
        if (playlists.isNotEmpty()) {
            item(key = "plHead") { Heading("Playlists") }
            items(playlists.size, key = { "pl${playlists[it].first.id}" }) { i ->
                val (p, songs) = playlists[i]
                ListRow(p.name, "${songs.size} song${if (songs.size == 1) "" else "s"}") { state.go(Route.Playlist(p.id)) }
            }
        }
        if (home.rediscover.isNotEmpty()) item(key = "rediscover") { AlbumRow("Not heard in a while", home.rediscover, state) }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = SableType.headline, color = SableColors.textPrimary, modifier = Modifier.padding(start = Space.gutter, top = Space.l, bottom = Space.s))

@Composable
private fun AlbumRow(title: String, albums: List<AlbumGroup>, state: AppState) {
    Column {
        Heading(title)
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            items(albums, key = { it.key }) { a -> Box(Modifier.width(136.dp)) { AlbumTile(a) { state.go(Route.Album(a.key)) } } }
        }
    }
}
