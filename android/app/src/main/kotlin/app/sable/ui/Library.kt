package app.sable.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sable.data.LibraryRow
import app.sable.ui.theme.Motion
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space

data class AlbumGroup(val key: String, val title: String, val artist: String, val tracks: List<LibraryRow>) {
    val albumId get() = tracks.first().albumId
    val coverUri get() = tracks.first().uri
    val single get() = key.startsWith("single|")
}

fun albumsOf(rows: List<LibraryRow>): List<AlbumGroup> = rows.groupBy(::albumKey).map { (k, t) ->
    val sorted = t.sortedWith(compareBy({ it.disc }, { it.track }, { titleOf(it).lowercase() }))
    AlbumGroup(k, if (k.startsWith("single|")) titleOf(t.first()) else clean(t.first().album).ifBlank { "Unknown album" }, artistOf(t.first()), sorted)
}.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))

// ---------------- Shared pieces ----------------

@Composable
fun TopBar(title: String?, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.s, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(Glyph.BACK, onBack) else Spacer(Modifier.width(Space.m))
        if (title != null) Text(title, style = SableType.overline, color = SableColors.textSecondary, modifier = Modifier.weight(1f).padding(start = Space.xs))
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun IconButton(glyph: Glyph, onClick: () -> Unit, size: Dp = 22.dp, color: Color = SableColors.textPrimary) {
    // A glyph that changes (play to pause, repeat to repeat-one) fades into the new one.
    Box(Modifier.size(48.dp).softClick(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.animation.Crossfade(glyph, animationSpec = androidx.compose.animation.core.tween(140), label = "glyph") { g -> Icon(g, size = size, color = color) }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) SableColors.accent else Color.Transparent, Motion.medium(), label = "pill")
    val fg by animateColorAsState(if (selected) SableColors.onAccent else SableColors.textPrimary, Motion.medium(), label = "pillText")
    val shape = RoundedCornerShape(50)
    Text(
        text, style = SableType.label, color = fg,
        modifier = modifier.softClick(pressedScale = 0.96f, onClick = onClick).background(bg, shape)
            .border(1.dp, if (selected) SableColors.accent else SableColors.line, shape).padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(r: LibraryRow, index: Int?, playing: Boolean, subtitle: String, onClick: () -> Unit, onLong: (() -> Unit)? = null, onWhy: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong?.let { f -> { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); f() } }).padding(start = Space.gutter, end = Space.xs, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("$index", style = SableType.caption, color = if (playing) SableColors.accent else SableColors.textTertiary, modifier = Modifier.width(30.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(songTitle(r), style = SableType.body, color = if (playing) SableColors.accent else SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(creditText(subtitle), style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(mmss(r.durationMs), style = SableType.caption, color = SableColors.textTertiary)
        IconButton(Glyph.INFO, onWhy, size = 18.dp, color = if (r.analysed) SableColors.textTertiary else SableColors.line)
    }
}

/** A credit line with " feat. " a step quieter than the names, so every artist reads clearly and the line stays calm. */
fun creditText(s: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val i = s.indexOf(" feat. ")
    if (i < 0) { append(s); return@buildAnnotatedString }
    append(s.substring(0, i))
    pushStyle(androidx.compose.ui.text.SpanStyle(color = SableColors.textTertiary)); append(" feat. "); pop()
    append(s.substring(i + 7))
}

// ---------------- Library ----------------

@Composable
fun LibraryScreen(state: AppState, rows: List<LibraryRow>, bottomPad: Dp) {
    val albums = remember(rows) { albumsOf(rows) }
    val tabs = listOf("Home", "Albums", "Artists", "Songs", "Folders")
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.gutter, end = Space.xs, top = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SableMark(28.dp)
            Spacer(Modifier.width(Space.m))
            Text("SABLE", style = SableType.wordmark, color = SableColors.textPrimary, modifier = Modifier.weight(1f))
            IconButton(Glyph.SEARCH, { state.go(Route.Search) })
            IconButton(Glyph.SETTINGS, { state.go(Route.Settings) })
        }
        val analysed = rows.count { it.analysed }
        Text(
            if (rows.isEmpty()) "Looking for music…" else "${rows.size} songs · ${if (analysed == rows.size) "all listened to" else "$analysed of ${rows.size} listened to"}",
            style = SableType.caption, color = SableColors.textTertiary, modifier = Modifier.padding(start = Space.gutter, top = 2.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Space.gutter, end = Space.gutter, top = Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            tabs.forEachIndexed { i, t -> TabLabel(t, state.libraryTab == i) { state.libraryTab = i } }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SableColors.line))
        val pad = PaddingValues(bottom = bottomPad + Space.l)
        // Each tab keeps its own scroll position when you switch tabs and come back.
        val tabState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        // Switching tabs is instant: the old fade snapped the content to black and back, which read as the albums reloading.
        Box {
        tabState.SaveableStateProvider("tab${state.libraryTab}") {
        when (state.libraryTab) {
            0 -> HomeTab(state, rows, bottomPad)
            1 -> LazyVerticalGrid(
                GridCells.Fixed(2), contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = bottomPad + Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xl),
            ) {
                items(albums, key = { it.key }) { a -> AlbumTile(a) { state.go(Route.Album(a.key)) } }
            }
            2 -> {
                val artists = remember(albums) { albums.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
                LazyColumn(contentPadding = pad) {
                    items(artists.keys.toList(), key = { it }) { name ->
                        val list = artists.getValue(name)
                        ListRow(name, "${count(list.size, "album")} · ${count(list.sumOf { it.tracks.size }, "song")}") { state.go(Route.Artist(name)) }
                    }
                }
            }
            3 -> {
                val songs = remember(rows) { rows.sortedBy { titleOf(it).lowercase() } }
                LazyColumn(contentPadding = pad) {
                    item(key = "shuffle") {
                        // Every song, shuffled, each balanced in its own world.
                        Row(Modifier.padding(start = Space.gutter, top = Space.l, bottom = Space.s)) {
                            Pill("Shuffle all", selected = false) { state.playSongWorld(songs.shuffled(), 0); state.go(Route.NowPlaying) }
                        }
                    }
                    items(songs.size, key = { songs[it].mediaId }) { i ->
                        val r = songs[i]
                        SongRow(r, null, state.currentTrackId == r.mediaId, songSubtitle(r), { state.playSongWorld(songs, i) }, { state.menuTrack = r.mediaId }) { state.whyTrack = r.mediaId }
                    }
                }
            }
            else -> {
                val folders = remember(rows) { rows.groupBy { it.relativePath }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
                LazyColumn(contentPadding = pad) {
                    items(folders.keys.toList(), key = { it }) { path ->
                        ListRow(unexplicit(path.trimEnd('/').substringAfterLast('/').ifBlank { path }), count(folders.getValue(path).size, "song")) { state.go(Route.Folder(path)) }
                    }
                }
            }
        }
        }
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    val w by animateDpAsState(if (selected) 22.dp else 0.dp, Motion.slow(), label = "tab")
    val c by animateColorAsState(if (selected) SableColors.textPrimary else SableColors.textTertiary, Motion.medium(), label = "tabText")
    Column(Modifier.softClick(pressedScale = 0.96f, onClick = onClick)) {
        Text(text, style = SableType.headline, color = c, modifier = Modifier.padding(vertical = Space.s))
        Box(Modifier.width(w).height(2.dp).background(SableColors.accent))
    }
}

@Composable
internal fun AlbumTile(a: AlbumGroup, onClick: () -> Unit) {
    Column(Modifier.softClick(pressedScale = 0.97f, haptic = false, onClick = onClick)) {
        AlbumArt(a.albumId, a.coverUri, a.title, 170.dp, Modifier.fillMaxWidth().aspectRatio(1f), px = 384)
        Text(a.title, style = SableType.body, color = SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
        Text(if (a.single) "${a.artist} · Single" else a.artist, style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ListRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.gutter, vertical = 14.dp)) {
        Text(title, style = SableType.body, color = SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Album / artist / folder ----------------

@Composable
fun AlbumScreen(state: AppState, rows: List<LibraryRow>, key: String, bottomPad: Dp) {
    val album = remember(rows, key) { albumsOf(rows).firstOrNull { it.key == key } } ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad + Space.l)) {
        item { TopBar(null, { state.pop() }) }
        item {
            Column(Modifier.padding(horizontal = Space.gutter)) {
                AlbumArt(album.albumId, album.coverUri, album.title, 300.dp, Modifier.fillMaxWidth().aspectRatio(1f), px = 1024)
                Text(album.title, style = SableType.display, color = SableColors.textPrimary, modifier = Modifier.padding(top = Space.xl))
                Text(album.artist, style = SableType.headline, color = SableColors.textSecondary, modifier = Modifier.padding(top = Space.xs))
                Text(if (album.single) "Single · ${mmss(album.tracks.sumOf { it.durationMs })}" else "${album.tracks.size} songs · ${mmss(album.tracks.sumOf { it.durationMs })}", style = SableType.caption, color = SableColors.textTertiary,
                    modifier = Modifier.padding(top = Space.xs))
                Row(Modifier.padding(top = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill(if (album.single) "Play" else "Play album", selected = true) { if (album.single) state.playSongWorld(album.tracks, 0) else state.playAlbum(album.tracks); state.go(Route.NowPlaying) }
                    if (!album.single) Pill("Shuffle songs", selected = false) {
                        val shuffled = album.tracks.shuffled()
                        state.playSongWorld(shuffled, 0); state.go(Route.NowPlaying)
                    }
                }
                if (!album.single) Text(
                    "Play album keeps the artist's contrast between songs: one gain for the whole album. Tap a song to balance it, and what follows, each in its own world.",
                    style = SableType.caption, color = SableColors.textTertiary, modifier = Modifier.padding(top = Space.m, bottom = Space.s),
                )
            }
        }
        items(album.tracks.size, key = { album.tracks[it].mediaId }) { i ->
            val r = album.tracks[i]
            SongRow(r, r.track.takeIf { it > 0 } ?: (i + 1), state.currentTrackId == r.mediaId, credit(r), { state.playSongWorld(album.tracks, i) }, { state.menuTrack = r.mediaId }) { state.whyTrack = r.mediaId }
        }
    }
}

@Composable
fun ArtistScreen(state: AppState, rows: List<LibraryRow>, name: String, bottomPad: Dp) {
    val albums = remember(rows, name) { albumsOf(rows).filter { it.artist == name } }
    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = bottomPad + Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Box(Modifier.offset(x = -Space.gutter)) { TopBar(null, { state.pop() }) }  // the grid's gutter would push the arrow right
                Text(name, style = SableType.display, color = SableColors.textPrimary, modifier = Modifier.padding(top = Space.s))
                Text("${count(albums.size, "album")} · ${count(albums.sumOf { it.tracks.size }, "song")}", style = SableType.caption, color = SableColors.textTertiary)
            }
        }
        items(albums, key = { it.key }) { a -> AlbumTile(a) { state.go(Route.Album(a.key)) } }
    }
}

@Composable
fun FolderScreen(state: AppState, rows: List<LibraryRow>, path: String, bottomPad: Dp) {
    val songs = remember(rows, path) { rows.filter { it.relativePath == path }.sortedWith(compareBy({ it.disc }, { it.track }, { it.displayName.lowercase() })) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad + Space.l)) {
        item { TopBar(null, { state.pop() }) }
        item {
            Text(unexplicit(path.trimEnd('/').substringAfterLast('/')), style = SableType.display, color = SableColors.textPrimary, modifier = Modifier.padding(horizontal = Space.gutter))
            Text(count(songs.size, "song"), style = SableType.caption, color = SableColors.textTertiary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
        }
        items(songs.size, key = { songs[it].mediaId }) { i ->
            val r = songs[i]
            SongRow(r, null, state.currentTrackId == r.mediaId, songSubtitle(r), { state.playSongWorld(songs, i) }, { state.menuTrack = r.mediaId }) { state.whyTrack = r.mediaId }
        }
    }
}
