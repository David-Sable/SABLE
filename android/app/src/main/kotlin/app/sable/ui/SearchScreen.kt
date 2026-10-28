package app.sable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sable.data.LibraryRow
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space

@Composable
fun SearchScreen(state: AppState, rows: List<LibraryRow>, bottomPad: Dp) {
    val index = remember(rows) { SearchIndex(rows, albumsOf(rows)) }
    val query = state.searchQuery
    val results = remember(index, query) { index.search(query) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) keyboard?.hide() }  // reading the results, not typing

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.s, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.BACK, { state.pop() })
            Box(Modifier.weight(1f).padding(start = Space.xs), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Songs, albums, artists", style = SableType.headline, color = SableColors.textTertiary)
                BasicTextField(
                    query, { state.searchQuery = it }, Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = SableType.headline.copy(color = SableColors.textPrimary), singleLine = true,
                    cursorBrush = SolidColor(SableColors.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }
            if (query.isNotEmpty()) IconButton(Glyph.CLOSE, { state.searchQuery = ""; focus.requestFocus(); keyboard?.show() }, size = 18.dp, color = SableColors.textSecondary)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SableColors.line))
        when {
            query.isBlank() -> Hint("Type part of a title, album or artist. Accents and capitals don't matter.")
            results.isEmpty -> Hint("Nothing in your library matches “${query.trim()}”.")
            else -> LazyColumn(state = list, contentPadding = PaddingValues(bottom = bottomPad + Space.l)) {
                if (results.artists.isNotEmpty()) {
                    item(key = "h-artists") { Heading("Artists") }
                    items(results.artists.size, key = { "ar-" + results.artists[it].name }) { i ->
                        val a = results.artists[i]
                        ResultRow(a.name, "${a.albums} album${if (a.albums == 1) "" else "s"} · ${a.songs} songs") { state.go(Route.Artist(a.name)) }
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item(key = "h-albums") { Heading("Albums") }
                    items(results.albums.size, key = { "al-" + results.albums[it].key }) { i ->
                        val a = results.albums[i]
                        Row(
                            Modifier.fillMaxWidth().clickable { state.go(Route.Album(a.key)) }.padding(horizontal = Space.gutter, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AlbumArt(a.albumId, a.coverUri, a.title, 48.dp, Modifier.size(48.dp), px = 128)
                            Column(Modifier.weight(1f).padding(start = Space.m)) {
                                Text(a.title, style = SableType.body, color = SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${a.artist} · ${a.tracks.size} songs", style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (results.songs.isNotEmpty()) {
                    item(key = "h-songs") { Heading("Songs") }
                    // Tapping a song plays it and the songs listed after it, each in its own world (as in the Songs tab).
                    items(results.songs.size, key = { "s-" + results.songs[it].mediaId }) { i ->
                        val r = results.songs[i]
                        SongRow(r, null, state.currentTrackId == r.mediaId, songSubtitle(r),
                            { keyboard?.hide(); state.playSongWorld(results.songs, i) }, { keyboard?.hide(); state.menuTrack = r.mediaId }) { state.whyTrack = r.mediaId }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text.uppercase(), style = SableType.overline, color = SableColors.textTertiary, modifier = Modifier.padding(start = Space.gutter, top = Space.xl, bottom = Space.s))
}

@Composable
private fun ResultRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.gutter, vertical = 12.dp)) {
        Text(title, style = SableType.body, color = SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = SableType.caption, color = SableColors.textTertiary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l))
}
