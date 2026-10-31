package app.sable.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sable.data.LibraryRow
import app.sable.data.PlaylistEntity
import app.sable.data.PlaylistTrackEntity
import app.sable.data.SableDatabase
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Playlists you make. Playing one is the song world (each song balanced on its own), like tapping songs anywhere else. */
object Playlists {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun dao(context: Context) = SableDatabase.get(context).playlists()

    fun add(context: Context, playlistId: Long, trackId: Long) =
        io.launch { dao(context).add(PlaylistTrackEntity(playlistId = playlistId, trackId = trackId, position = System.currentTimeMillis())) }

    fun create(context: Context, name: String, firstTrack: Long?) = io.launch {
        val id = dao(context).create(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
        if (firstTrack != null) dao(context).add(PlaylistTrackEntity(playlistId = id, trackId = firstTrack, position = System.currentTimeMillis()))
    }

    fun remove(context: Context, entryId: Long) = io.launch { dao(context).remove(entryId) }
    fun swap(context: Context, a: PlaylistTrackEntity, b: PlaylistTrackEntity) = io.launch { dao(context).swap(a, b) }
    fun rename(context: Context, id: Long, name: String) = io.launch { dao(context).rename(id, name.trim()) }
    fun delete(context: Context, id: Long) = io.launch { dao(context).delete(id) }
}

/** Every playlist with its entries in order. */
@Composable
fun rememberPlaylists(): List<Pair<PlaylistEntity, List<PlaylistTrackEntity>>> {
    val lists by LibraryData.playlists.collectAsState()
    val entries by LibraryData.entries.collectAsState()
    return remember(lists, entries) { val byList = entries.groupBy { it.playlistId }; lists.map { it to byList[it.id].orEmpty() } }
}

/** Long-press a song → Add to playlist: pick one, or name a new one (the song goes in first). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(trackId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val playlists = rememberPlaylists()
    var name by remember { mutableStateOf("") }
    SableSheet(onDismiss) {
        val close = LocalSheetClose.current
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("Add to playlist", style = SableType.title, color = SableColors.textPrimary, modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.m))
            for ((p, songs) in playlists) {
                ListRow(p.name, "${songs.size} song${if (songs.size == 1) "" else "s"}") { Playlists.add(context, p.id, trackId); close() }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) Text("New playlist name", style = SableType.body, color = SableColors.textTertiary)
                    BasicTextField(
                        name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = SableType.body.copy(color = SableColors.textPrimary), cursorBrush = SolidColor(SableColors.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) { Playlists.create(context, name, trackId); close() } }),
                    )
                }
                Pill("Create", selected = name.isNotBlank()) { if (name.isNotBlank()) { Playlists.create(context, name, trackId); close() } }
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
}

@Composable
fun PlaylistScreen(state: AppState, rows: List<LibraryRow>, id: Long, bottomPad: Dp) {
    val context = LocalContext.current
    val entry = rememberPlaylists().firstOrNull { it.first.id == id } ?: return
    val (playlist, entries) = entry
    val byId = remember(rows) { rows.associateBy { it.mediaId } }
    val items = remember(entries, byId) { entries.mapNotNull { e -> byId[e.trackId]?.let { e to it } } }
    val songs = items.map { it.second }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var name by remember(playlist.name) { mutableStateOf(playlist.name) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad + Space.l)) {
        item { TopBar(null, { state.pop() }) }
        item {
            Column(Modifier.padding(horizontal = Space.gutter)) {
                if (editing) {
                    BasicTextField(
                        name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = SableType.display.copy(color = SableColors.textPrimary), cursorBrush = SolidColor(SableColors.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) Playlists.rename(context, id, name); editing = false }),
                    )
                } else Text(playlist.name, style = SableType.display, color = SableColors.textPrimary)
                Text("${songs.size} song${if (songs.size == 1) "" else "s"}", style = SableType.caption, color = SableColors.textTertiary)
                Row(Modifier.padding(top = Space.l, bottom = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (songs.isNotEmpty()) {
                        Pill("Play", selected = true) { state.playSongWorld(songs, 0); state.go(Route.NowPlaying) }
                        Pill("Shuffle", selected = false) { state.playSongWorld(songs.shuffled(), 0); state.go(Route.NowPlaying) }
                    }
                    Pill(if (editing) "Done" else "Rename", selected = false) {
                        if (editing && name.isNotBlank()) Playlists.rename(context, id, name)
                        editing = !editing
                    }
                    Pill(if (confirmDelete) "Tap to delete" else "Delete", selected = confirmDelete) {
                        if (confirmDelete) { Playlists.delete(context, id); state.pop() } else confirmDelete = true
                    }
                }
            }
        }
        if (songs.isEmpty()) item {
            Text("Long-press any song and choose Add to playlist.", style = SableType.body, color = SableColors.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l))
        }
        items(items.size, key = { items[it].first.id }) { i ->
            val (e, r) = items[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SongRow(r, null, state.currentTrackId == r.mediaId, songSubtitle(r), { state.playSongWorld(songs, i) }, { state.menuTrack = r.mediaId })
                }
                // Move up one place, or take it out of the playlist.
                if (i > 0) IconButton(Glyph.UP, { Playlists.swap(context, e, items[i - 1].first) }, size = 16.dp, color = SableColors.textTertiary)
                IconButton(Glyph.CLOSE, { Playlists.remove(context, e.id) }, size = 14.dp, color = SableColors.textTertiary)
            }
        }
    }
}
