package app.sable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sable.data.LibraryRow
import app.sable.playback.NowPlayingGain
import app.sable.playback.PlaybackPrefs

/** M5 preview screen. The designed UI, Why sheet and A/B arrive in M6. */
object Palette {
    val ink = Color(0xFFE8E1D3)    // warm ivory accent (PLAN decision)
    val dim = Color(0xFF9A948A)
    val faint = Color(0xFF5E5A53)
    val line = Color(0xFF26241F)
    val bg = Color.Black
}

data class Album(val key: String, val title: String, val artist: String, val tracks: List<LibraryRow>)

data class NowPlayingUi(val title: String = "", val artist: String = "", val playing: Boolean = false, val hasItem: Boolean = false)

@Composable
fun PlayerScreen(
    rows: List<LibraryRow>,
    settings: PlaybackPrefs.Settings,
    now: NowPlayingUi,
    gain: NowPlayingGain,
    onPlayAlbum: (Album) -> Unit,
    onPlaySongWorld: (List<LibraryRow>, Int) -> Unit,
    onMode: (PlaybackPrefs.Mode) -> Unit,
    onAlbumMode: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRescan: () -> Unit,
) {
    val albums = remember(rows) {
        rows.groupBy { "${it.albumId}|${it.albumArtist}" }.map { (k, t) ->
            Album(k, t.first().album.ifBlank { "Unknown album" }, t.first().albumArtist.ifBlank { t.first().artist }, t)
        }
    }
    var tab by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SABLE", color = Palette.ink, fontSize = 24.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp, modifier = Modifier.weight(1f))
                Text("Rescan", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onRescan).padding(8.dp))
            }
            val analysed = rows.count { it.analysed }
            Text("${rows.size} songs · ${if (analysed == rows.size) "all analysed" else "$analysed analysed"}", color = Palette.faint, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (m in PlaybackPrefs.Mode.entries) {
                    Pill("${m.label} ${m.targetLufs.toInt()}", selected = settings.mode == m) { onMode(m) }
                }
                Spacer(Modifier.weight(1f))
                Pill("Albums: ${settings.albumMode.label}", selected = false, onClick = onAlbumMode)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Tab("Albums", tab == 0) { tab = 0 }
                Tab("Songs", tab == 1) { tab = 1 }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            if (tab == 0) {
                for (a in albums) {
                    item(key = a.key) {
                        AlbumRow(a, expanded = open == a.key, onToggle = { open = if (open == a.key) null else a.key }, onPlayAlbum = { onPlayAlbum(a) },
                            onSong = { i -> onPlaySongWorld(a.tracks, i) })
                    }
                }
            } else {
                val songs = rows.sortedBy { it.title.ifBlank { it.displayName }.lowercase() }
                items(songs.size, key = { songs[it].mediaId }) { i -> SongRow(songs[i], showAlbum = true) { onPlaySongWorld(songs, i) } }
            }
        }
        if (now.hasItem) NowBar(now, gain, onPrev, onPlayPause, onNext)
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text, fontSize = 13.sp, color = if (selected) Color.Black else Palette.ink,
        modifier = Modifier.background(if (selected) Palette.ink else Color.Transparent, shape)
            .border(1.dp, if (selected) Palette.ink else Palette.line, shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun Tab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Text(text, color = if (selected) Palette.ink else Palette.faint, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
        Box(Modifier.width(28.dp).height(2.dp).background(if (selected) Palette.ink else Color.Transparent))
    }
}

@Composable
private fun AlbumRow(a: Album, expanded: Boolean, onToggle: () -> Unit, onPlayAlbum: () -> Unit, onSong: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.title, color = Palette.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${a.artist} · ${a.tracks.size} songs", color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("▶\uFE0E", color = Palette.ink, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onPlayAlbum).padding(12.dp))
        }
        if (expanded) {
            Row(Modifier.padding(bottom = 6.dp)) { Pill("Play album", selected = true, onClick = onPlayAlbum) }
            Text("Play album keeps the album's own contrast (one gain). Tapping a song balances it and what follows in their own world.",
                color = Palette.faint, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            a.tracks.forEachIndexed { i, t -> SongRow(t, showAlbum = false) { onSong(i) } }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
    }
}

@Composable
private fun SongRow(t: LibraryRow, showAlbum: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.title.ifBlank { t.displayName }, color = Palette.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (showAlbum) "${t.artist} · ${t.album}" else t.artist, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (t.analysed) t.character ?: "" else "not analysed", color = Palette.faint, fontSize = 11.sp)
    }
}

@Composable
private fun NowBar(now: NowPlayingUi, g: NowPlayingGain, onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF0E0D0B)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(now.title, color = Palette.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now.artist, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Ctl("⏮\uFE0E", onPrev)
            if (now.playing) {
                // Drawn, not a glyph: Samsung's font renders the pause symbol as a colour emoji.
                Box(Modifier.size(44.dp).clickable(onClick = onPlayPause), contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(2) { Box(Modifier.width(4.dp).height(16.dp).background(Palette.ink)) }
                    }
                }
            } else Ctl("▶\uFE0E", onPlayPause)
            Ctl("⏭\uFE0E", onNext)
        }
        Spacer(Modifier.height(6.dp))
        Text(gainLine(g), color = Palette.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
    }
}

@Composable
private fun Ctl(symbol: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(symbol, color = Palette.ink, fontSize = 20.sp) }
}

fun gainLine(g: NowPlayingGain): String {
    if (g.mediaId.isEmpty()) return ""
    val world = when {
        g.albumRun -> "album world"
        g.source == "jit" -> "quick listen"
        g.source == "provisional" -> "listening…"
        else -> "song world"
    }
    val rules = g.rules.filter { it.first !in setOf("ANCHOR_MATCH", "ALBUM", "JIT", "PROVISIONAL") }
        .joinToString(" ") { "${it.first.lowercase()} %+.1f".format(it.second) }
    return "%+.1f dB · %s · %s (%.1f dBTP)%s".format(g.gainDb, world, g.route, g.ceilingDbtp, if (rules.isEmpty()) "" else " · $rules")
}
