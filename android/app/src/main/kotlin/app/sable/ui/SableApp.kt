package app.sable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sable.data.LibraryRow
import app.sable.playback.AbSession
import app.sable.playback.NowPlaying
import app.sable.playback.PlaybackPrefs
import app.sable.ui.theme.Motion
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space

@Composable
fun SableApp(state: AppState, rows: List<LibraryRow>, onRescan: () -> Unit, onAnalyseWhileCharging: () -> Unit) {
    val byId = remember(rows) { rows.associateBy { it.mediaId } }
    val current = state.currentTrackId?.let { byId[it] }
    BackHandler(enabled = state.back.size > 1) { state.pop() }
    Box(Modifier.fillMaxSize().background(SableColors.base)) {
        val showMini = current != null && state.route !is Route.NowPlaying && state.route !is Route.Ab
        val pad = if (showMini) bottomBarHeight() else 0.dp
        AnimatedContent(
            state.route,
            transitionSpec = {
                if (targetState is Route.NowPlaying) slideInVertically(Motion.slow()) { it / 6 } + fadeIn(Motion.slow()) togetherWith fadeOut(Motion.medium())
                else fadeIn(Motion.slow()) togetherWith fadeOut(Motion.medium())
            },
            label = "route",
        ) { r ->
            when (r) {
                Route.Library -> LibraryScreen(state, rows, pad)
                is Route.Album -> AlbumScreen(state, rows, r.key, pad)
                is Route.Artist -> ArtistScreen(state, rows, r.name, pad)
                is Route.Folder -> FolderScreen(state, rows, r.path, pad)
                Route.NowPlaying -> NowPlayingScreen(state, current)
                Route.Queue -> QueueScreen(state, rows)
                Route.Ab -> {
                    DisposableEffect(Unit) { onDispose { AbSession.condition.value = null } }  // leaving A/B ends the round
                    AbScreen(state, rows)
                }
                Route.Settings -> SettingsScreen(state, rows, onRescan, onAnalyseWhileCharging)
            }
        }
        AnimatedVisibility(
            showMini, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.slow()) { it } + fadeIn(Motion.slow()), exit = slideOutVertically(Motion.medium()) { it } + fadeOut(Motion.medium()),
        ) { MiniPlayer(state, current) }
        state.whyTrack?.let { id -> byId[id]?.let { WhySheet(state, it) { state.whyTrack = null } } }
    }
}

@Composable
fun SettingsScreen(state: AppState, rows: List<LibraryRow>, onRescan: () -> Unit, onAnalyseWhileCharging: () -> Unit) {
    val context = LocalContext.current
    val s by PlaybackPrefs.settings.collectAsState()
    val gain by NowPlaying.gain.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("SETTINGS", { state.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl)) {
            Group("Listening level")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (m in PlaybackPrefs.Mode.entries) Pill("${m.label}  ${m.targetLufs.toInt()}", s.mode == m) { PlaybackPrefs.update(context) { it.copy(mode = m) } }
            }
            Hint("Standard balances every song to −14 LUFS. Quiet balances to −19 LUFS for late nights and quiet rooms.")
            Group("Albums")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (a in PlaybackPrefs.AlbumMode.entries) Pill(a.label, s.albumMode == a) { PlaybackPrefs.update(context) { it.copy(albumMode = a) } }
            }
            Hint(
                when (s.albumMode) {
                    PlaybackPrefs.AlbumMode.AUTO -> "Auto: Play album keeps one gain for the album. Tapping a song balances it and what follows in their own world."
                    PlaybackPrefs.AlbumMode.TRACK -> "Every song is balanced on its own, even inside an album."
                    PlaybackPrefs.AlbumMode.ALBUM -> "Songs from the same album always share one gain, even in shuffle."
                },
            )
            Group("Intimate pieces")
            Text("Held %.1f dB under target".format(s.intimacyLu), style = SableType.body, color = SableColors.textPrimary)
            Slider(
                value = s.intimacyLu.toFloat(), onValueChange = { v -> PlaybackPrefs.update(context) { it.copy(intimacyLu = (Math.round(v * 2) / 2.0)) } },
                valueRange = 0f..2f, steps = 3,
                colors = SliderDefaults.colors(thumbColor = SableColors.accent, activeTrackColor = SableColors.accent, inactiveTrackColor = SableColors.line),
            )
            Hint("Quiet, intimate songs stay a little quieter than the rest, as they were meant to be. 0 turns this off.")
            Group("Output")
            Text("${gain.route.ifBlank { "Phone speaker" }} · peaks kept under %.1f dBTP".format(gain.ceilingDbtp), style = SableType.body, color = SableColors.textPrimary)
            Hint("Calibration per output, the quiet-mode contour and learning from your volume keys arrive in the next milestone.")
            Group("Library")
            val analysed = rows.count { it.analysed }
            Text("${rows.size} songs · $analysed listened to", style = SableType.body, color = SableColors.textPrimary)
            Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("Rescan now", false, onClick = onRescan)
                Pill("Listen while charging", false, onClick = onAnalyseWhileCharging)
            }
            Group("About")
            Text("SABLE 0.6 · Intent Engine MATH-1.12", style = SableType.body, color = SableColors.textPrimary)
            Hint("Every number Sable shows comes from the engine described in MATH.md. Nothing after Sable (Dolby Atmos, EQ, Adapt Sound) is undone.")
            Row(Modifier.padding(top = Space.m)) { Pill("Reset A/B results", false) { AbTally.reset(context) } }
            Spacer(Modifier.height(Space.xxxl))
        }
    }
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(Space.xl))
    Text(title.uppercase(), style = SableType.overline, color = SableColors.textTertiary, modifier = Modifier.padding(bottom = Space.m))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = SableType.caption, color = SableColors.textTertiary, modifier = Modifier.fillMaxWidth().padding(top = Space.s))
}
