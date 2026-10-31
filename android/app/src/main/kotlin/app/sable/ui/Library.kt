package app.sable.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.clip
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
            Text(songTitle(r), style = SableType.name, color = if (playing) SableColors.accent else SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) CreditLine(subtitle, SableType.caption, SableColors.textSecondary)
        }
        Text(mmss(r.durationMs), style = SableType.caption, color = SableColors.textTertiary)
        IconButton(Glyph.INFO, onWhy, size = 18.dp, color = if (r.analysed) SableColors.textTertiary else SableColors.line)
    }
}

/** A credit line with " feat. " a step quieter than the names, so every artist reads clearly and the line stays calm. */
fun creditText(s: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val i = Regex("""(?<=[ \n])feat\. """).find(s)?.range?.first ?: -1
    if (i < 0) { append(s); return@buildAnnotatedString }
    append(s.substring(0, i))
    pushStyle(androidx.compose.ui.text.SpanStyle(color = SableColors.textTertiary)); append("feat."); pop()
    append(s.substring(i + 5))
}

/**
 * "Credit · Album" on one line when it fits. When it does not (30 of David's 392 songs credit features, some four names
 * long), the album moves to the next line and the credit may take two, so no name is cut off (David, 2026-10-29).
 * A credit alone breaks before "feat." rather than inside a name.
 */
@Composable
fun CreditLine(s: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 3, minLines: Int = 1) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val text = remember(s, maxPx, style) {
            val fits = measurer.measure(s, style, maxLines = 1, softWrap = false).size.width <= maxPx
            when {
                fits -> s
                " · " in s -> s.replaceFirst(" · ", "\n")
                else -> s.replaceFirst(" feat. ", "\nfeat. ")
            }
        }
        Text(creditText(text), style = style, color = color, maxLines = maxLines, minLines = minLines, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Library ----------------

@Composable
fun LibraryScreen(state: AppState, rows: List<LibraryRow>, bottomPad: Dp) {
    val model = LibraryData.model.collectAsState().value
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
        // The tabs are pages side by side: swipe between them or tap a name. One underline travels between the names with the page,
        // stretching a little as it goes.
        val pager = androidx.compose.foundation.pager.rememberPagerState(state.libraryTab) { tabs.size }
        // state.libraryTab says where we are going; only a finger's swipe writes it from the pager. Writing every settled page back
        // made a quick second tap stop the first scroll halfway, and that halfway page then won (David, 2026-10-30: landed on Songs).
        var swiped by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(pager) {
            pager.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) swiped = true }
        }
        androidx.compose.runtime.LaunchedEffect(pager) {
            androidx.compose.runtime.snapshotFlow { pager.isScrollInProgress }.collect { moving ->
                if (!moving && swiped) { swiped = false; state.libraryTab = pager.settledPage }
            }
        }
        // One slide by pixels, however far: animateScrollToPage first jumps to within three pages of the target, a full layout
        // that froze the first frame for 100 ms on the far tabs (measured, 2026-10-30).
        androidx.compose.runtime.LaunchedEffect(state.libraryTab) {
            val to = state.libraryTab
            val page = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
            val by = (to - pager.currentPage - pager.currentPageOffsetFraction) * page
            if (page > 0 && kotlin.math.abs(by) > 1f) pager.animateScrollBy(by, Motion.page())
            if (pager.currentPage != to || pager.currentPageOffsetFraction != 0f) pager.scrollToPage(to)
        }
        val tabLeft = remember { androidx.compose.runtime.mutableStateListOf(*Array(tabs.size) { 0f }) }
        Box(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Space.gutter, end = Space.gutter, top = Space.l)
                .drawBehind {
                    val at = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, tabs.size - 1f)
                    val i = at.toInt().coerceAtMost(tabs.size - 2)
                    val f = at - i
                    val w = 22.dp.toPx()
                    val stretch = 4f * f * (1f - f) * 18.dp.toPx()
                    drawRect(SableColors.accent, androidx.compose.ui.geometry.Offset(tabLeft[i] + (tabLeft[i + 1] - tabLeft[i]) * f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(w + stretch, 2.dp.toPx()))
                },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                tabs.forEachIndexed { i, t ->
                    TabLabel(t, { (if (swiped) pager.targetPage else state.libraryTab) == i }, Modifier.onPlaced { tabLeft[i] = it.positionInParent().x }) { swiped = false; state.libraryTab = i }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SableColors.line))
        val pad = PaddingValues(bottom = bottomPad + Space.l)
        // Each tab keeps its own scroll position when you switch tabs and come back.
        val tabState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        val dragged by pager.interactionSource.collectIsDraggedAsState()
        val landing by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { pager.isScrollInProgress && !dragged && kotlin.math.abs(pager.currentPageOffsetFraction) < 0.4f } }
        // Every tab stays built, so a tap on a far tab only slides finished pages past. They are built one at a time just after the
        // library appears, while nothing is moving; the pages between used to be built in the middle of the slide (David, 2026-10-30).
        var built by androidx.compose.runtime.mutableIntStateOf(1)
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(500)
            while (built < tabs.size - 1) { kotlinx.coroutines.delay(120); built++ }
        }
        androidx.compose.foundation.pager.HorizontalPager(
            pager, Modifier.fillMaxSize(), beyondViewportPageCount = built,
            // A touch on a pager still moving is taken as a sideways drag, whichever way the finger goes: while a page lands, the
            // pager lets go of touches so the page's own list can scroll at once (David, 2026-10-30).
            userScrollEnabled = !landing,
            flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(pager, snapAnimationSpec = Motion.page(1f)),
        ) { page ->
        tabState.SaveableStateProvider("tab$page") {
        when (page) {
            0 -> HomeTab(state, rows, bottomPad)
            1 -> LazyVerticalGrid(
                GridCells.Fixed(2), contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = bottomPad + Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xl),
            ) {
                items(model?.albums.orEmpty(), key = { it.key }) { a -> AlbumTile(a) { state.go(Route.Album(a.key)) } }
            }
            2 -> if (model != null) {
                LazyColumn(contentPadding = pad) {
                    items(model.artistNames, key = { it }) { name ->
                        val list = model.artists.getValue(name)
                        ListRow(name, "${count(list.size, "album")} · ${count(list.sumOf { it.tracks.size }, "song")}", cover = list.maxByOrNull { it.tracks.size }, round = true) { state.go(Route.Artist(name)) }
                    }
                }
            }
            3 -> if (model != null) {
                val songs = model.songs
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
            else -> if (model != null) {
                LazyColumn(contentPadding = pad) {
                    item(key = "organize") { OrganizeRow(rows) }
                    items(model.folderNames, key = { it }) { path ->
                        ListRow(unexplicit(path.trimEnd('/').substringAfterLast('/').ifBlank { path }), count(model.folders.getValue(path).size, "song"), cover = model.folderCover[path]) { state.go(Route.Folder(path)) }
                    }
                }
            }
        }
        }
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: () -> Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c by animateColorAsState(if (selected()) SableColors.textPrimary else SableColors.textTertiary, Motion.medium(), label = "tabText")
    Column(modifier.softClick(pressedScale = 0.96f, onClick = onClick)) {
        Text(text, style = SableType.tab, color = c, modifier = Modifier.padding(vertical = Space.s))
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
internal fun AlbumTile(a: AlbumGroup, captionLines: Int = 1, onClick: () -> Unit) {
    val spot = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    Column(Modifier.softClick(pressedScale = 0.97f, haptic = false) { AlbumFlight.open(a.key, a.albumId, a.coverUri, spot[0]); onClick() }) {
        AlbumArt(a.albumId, a.coverUri, a.title, 170.dp, Modifier.fillMaxWidth().aspectRatio(1f)
            .onGloballyPositioned { spot[0] = it.boundsInRoot() }
            .graphicsLayer { alpha = if (AlbumFlight.hides(a.key)) 0f else 1f }, px = 384)
        Text(a.title, style = SableType.name, color = SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
        Text(if (a.single) "${a.artist} · Single" else a.artist, style = SableType.caption, color = SableColors.textSecondary, maxLines = captionLines, minLines = captionLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ListRow(title: String, subtitle: String, cover: AlbumGroup? = null, round: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.gutter, vertical = if (cover != null) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A cover beside the name, as in search (round for an artist: a face, not an album), so a list of names is not a bare column.
        if (cover != null) AlbumArt(cover.albumId, cover.coverUri, cover.title, 48.dp,
            Modifier.size(48.dp).then(if (round) Modifier.clip(androidx.compose.foundation.shape.CircleShape) else Modifier), px = 128)
        Column(Modifier.weight(1f).padding(start = if (cover != null) Space.m else 0.dp)) {
            Text(title, style = SableType.name, color = SableColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- Album / artist / folder ----------------

@Composable
fun AlbumScreen(state: AppState, rows: List<LibraryRow>, key: String, bottomPad: Dp) {
    val model = LibraryData.model.collectAsState().value
    val album = remember(model, key) { model?.albums?.firstOrNull { it.key == key } } ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad + Space.l)) {
        item { TopBar(null, { state.pop() }) }
        item {
            Column(Modifier.padding(horizontal = Space.gutter)) {
                AlbumArt(album.albumId, album.coverUri, album.title, 300.dp, Modifier.fillMaxWidth().aspectRatio(1f).albumHeader(album.key), px = 1024)
                Text(album.title, style = SableType.display, color = SableColors.textPrimary, modifier = Modifier.padding(top = Space.xl))
                Text(album.artist, style = SableType.name, color = SableColors.textSecondary, modifier = Modifier.padding(top = Space.xs))
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
    val model = LibraryData.model.collectAsState().value
    val albums = remember(model, name) { model?.artists?.get(name).orEmpty() }
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
