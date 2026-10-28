package app.sable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sable.data.LibraryRow
import app.sable.playback.NowPlaying
import app.sable.playback.OutputState
import app.sable.playback.PlaybackPrefs
import app.sable.ui.theme.Motion
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun SableApp(state: AppState, rows: List<LibraryRow>, onRescan: () -> Unit, onAnalyseWhileCharging: () -> Unit) {
    val byId = remember(rows) { rows.associateBy { it.mediaId } }
    val current = state.currentTrackId?.let { byId[it] }
    BackHandler(enabled = state.back.size > 1) { state.pop() }
    Box(Modifier.fillMaxSize().background(SableColors.base)) {
        val showMini = current != null && state.route !is Route.NowPlaying
        val pad = if (showMini) bottomBarHeight() else 0.dp
        // Each screen keeps its scroll position (and tab) while it is in the back stack: going back or swiping Now
        // Playing down returns to the same spot. A screen that leaves the stack forgets it, so reopening starts fresh.
        val saved = rememberSaveableStateHolder()
        val inStack = state.back.map { it.toString() }.toSet()
        val known = remember { mutableSetOf<String>() }
        LaunchedEffect(inStack) {
            (known - inStack).forEach(saved::removeState)
            known.clear(); known += inStack
        }
        // While Now Playing is up, the screen beneath it is not composed (it is the same route list), so dragging the sheet down
        // would drag it over black. The screen is recorded into a layer while it is on top; when Now Playing opens, the last
        // frame is kept as a picture and drawn beneath the sheet, and the real screen takes over at the same frame the sheet goes.
        val layer = rememberGraphicsLayer()
        val sheetUp = state.route is Route.NowPlaying
        val recordedRoute = remember { arrayOfNulls<Route>(1) }
        var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(sheetUp) {
            if (!sheetUp) { frozen = null; return@LaunchedEffect }
            val beneath = state.back.getOrNull(state.back.lastIndex - 1)
            frozen = if (recordedRoute[0] == beneath) runCatching { layer.toImageBitmap() }.getOrNull() else null
        }
        Box(Modifier.fillMaxSize().drawWithContent {
            if (state.route !is Route.NowPlaying) {
                recordedRoute[0] = state.route
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            } else {
                frozen?.let { drawImage(it) }
                drawContent()
            }
        }) {
        AnimatedContent(
            state.route,
            transitionSpec = {
                // No cross-fade: two half-see-through screens on black dip to nearly black in the middle, which tires the eyes.
                // Every screen is solid and only the one on top moves or fades: going forward the new screen comes in over the
                // old one, which stays put; going back the leaving screen goes away over the one it uncovers. Now Playing is a
                // sheet that slides up from the bottom and slides back down (also after a swipe, from where the finger left it).
                val leaving = initialState !in state.back  // going back: the screen we are on is leaving for good
                // Other screens rise a hair (about 17 dp) as they fade in and sink as they fade out.
                val enter = if (targetState is Route.NowPlaying) slideInVertically(Motion.slow()) { it }
                    else if (leaving) EnterTransition.None else slideInVertically(Motion.medium()) { it / 50 }
                val exit = if (leaving && initialState is Route.NowPlaying) slideOutVertically(Motion.medium()) { it }
                    else if (leaving) slideOutVertically(Motion.medium()) { it / 50 } else ExitTransition.KeepUntilTransitionsFinished
                val t = enter togetherWith exit
                t.targetContentZIndex = if (initialState !in state.back) -1f else 1f  // going back: the newcomer goes underneath
                t
            },
            label = "route",
        ) { r ->
            val revealed = remember { r.toString() in known }  // back to a screen that was already there: it is simply uncovered
            val a by transition.animateFloat(
                transitionSpec = { Motion.medium() }, label = "screen",
            ) {
                when (it) {
                    EnterExitState.Visible -> 1f
                    EnterExitState.PreEnter -> if (revealed || r is Route.NowPlaying) 1f else 0f
                    EnterExitState.PostExit -> if (r is Route.NowPlaying || r in state.back) 1f else 0f  // covered: stays; left for good: fades away
                }
            }
            Box(Modifier.graphicsLayer { alpha = a }.then(if (r is Route.NowPlaying) Modifier else Modifier.background(SableColors.base))) {  // Now Playing paints its own, so the background moves with its sheet
            saved.SaveableStateProvider(r.toString()) {
            when (r) {
                Route.Library -> LibraryScreen(state, rows, pad)
                is Route.Album -> AlbumScreen(state, rows, r.key, pad)
                is Route.Artist -> ArtistScreen(state, rows, r.name, pad)
                is Route.Folder -> FolderScreen(state, rows, r.path, pad)
                Route.NowPlaying -> NowPlayingScreen(state, current) { byId[it] }
                Route.Queue -> QueueScreen(state, rows, pad)
                Route.Settings -> SettingsScreen(state, rows, pad, onRescan, onAnalyseWhileCharging)
                Route.Search -> SearchScreen(state, rows, pad)
            }
            }
            }
        }
        }
        AnimatedVisibility(
            showMini, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.slow()) { it } + fadeIn(Motion.slow()), exit = slideOutVertically(Motion.medium()) { it } + fadeOut(Motion.medium()),
        ) { MiniPlayer(state, current) }
        state.menuTrack?.let { id -> byId[id]?.let { SongMenu(state, it) { state.menuTrack = null } } }
        state.whyTrack?.let { id -> byId[id]?.let { WhySheet(state, it) { state.whyTrack = null } } }
    }
}

/** The MATH.md version this build implements (keep equal to the header of MATH.md). */
const val MATH_DOC_VERSION = "MATH-1.31"

@Composable
fun SettingsScreen(state: AppState, rows: List<LibraryRow>, bottomPad: androidx.compose.ui.unit.Dp, onRescan: () -> Unit, onAnalyseWhileCharging: () -> Unit) {
    val context = LocalContext.current
    val s by PlaybackPrefs.settings.collectAsState()
    val gain by NowPlaying.gain.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("SETTINGS", { state.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl)) {
            Group("Listening level")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (m in PlaybackPrefs.Mode.entries) Pill(if (s.advanced) "${m.label}  ${m.targetLufs.toInt()}" else m.label, s.mode == m) {
                    PlaybackPrefs.update(context) { it.copy(mode = m) }
                }
            }
            Hint(if (s.advanced) "Normal balances every song to −14 LUFS. Quiet balances to −19 LUFS for late nights and quiet rooms."
                else "Sable listens to every song and sets its level for you. Quiet is the same balance, softer, with a gentle bass lift so it still sounds full at low volume.")
            Group("Battery")
            BatteryRow()
            Group("Library")
            val analysed = rows.count { it.analysed }
            Text("${rows.size} songs · $analysed listened to", style = SableType.body, color = SableColors.textPrimary)
            Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("Rescan now", false, onClick = onRescan)
                Pill("Listen while charging", false, onClick = onAnalyseWhileCharging)
            }
            Group("Advanced settings")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("Off", !s.advanced) { PlaybackPrefs.update(context) { it.copy(advanced = false) } }
                Pill("On", s.advanced) { PlaybackPrefs.update(context) { it.copy(advanced = true) } }
            }
            Hint(if (s.advanced) "Your own fine-tuning below is in use. Turn this off to go back to Sable's automatic choices; your settings are kept for next time."
                else "Everything is automatic. Turn this on to fine-tune albums, quiet songs, each output, the Quiet contour and learning.")
            Reveal(s.advanced, expand = false) {  // the last section: fade in place, no per-frame re-layout
                Column {
                    Group("Quiet-mode contour")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Pill("Off", !s.contour) { PlaybackPrefs.update(context) { it.copy(contour = false) } }
                        Pill("On", s.contour) { PlaybackPrefs.update(context) { it.copy(contour = true) } }
                    }
                    Hint("At low volume your ears lose bass first. In Quiet mode this adds a gentle lift (about +2 dB below 200 Hz, a touch of air) from the ISO 226 equal-loudness contours, so the balance sounds like it does at normal level. The only processing Sable does besides gain; peaks stay protected. On by default; turn it off for level changes only.")
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
                    val route by OutputState.route.collectAsState()
                    Text("Now: ${route.label}", style = SableType.body, color = SableColors.textPrimary)
                    Text("Level offset %+.1f dB".format(s.cal(route)), style = SableType.caption, color = SableColors.textSecondary, modifier = Modifier.padding(top = Space.m))
                    Slider(
                        value = s.cal(route).toFloat(), valueRange = -3f..3f, steps = 11,
                        onValueChange = { v -> PlaybackPrefs.update(context) { it.copy(routeCal = it.routeCal + (route.key to Math.round(v * 2) / 2.0)) } },
                        colors = SliderDefaults.colors(thumbColor = SableColors.accent, activeTrackColor = SableColors.accent, inactiveTrackColor = SableColors.line),
                    )
                    Text("Peak ceiling %.1f dBTP".format(s.ceiling(route)), style = SableType.caption, color = SableColors.textSecondary)
                    Slider(
                        value = s.ceiling(route).toFloat(), valueRange = -3f..-0.5f, steps = 4,
                        onValueChange = { v -> PlaybackPrefs.update(context) { it.copy(routeCeiling = it.routeCeiling + (route.key to Math.round(v * 2) / 2.0)) } },
                        colors = SliderDefaults.colors(thumbColor = SableColors.accent, activeTrackColor = SableColors.accent, inactiveTrackColor = SableColors.line),
                    )
                    Hint("Each output keeps its own settings. Use the level offset if this output sounds louder or quieter than your others. The ceiling keeps peaks clean; Bluetooth starts lower because the phone re-encodes the audio.")
                    Row(Modifier.padding(top = Space.s)) {
                        Pill("Reset ${route.label}", false) { PlaybackPrefs.update(context) { it.copy(routeCal = it.routeCal - route.key, routeCeiling = it.routeCeiling - route.key) } }
                    }
                    Group("Learning")
                    Hint("If you turn the volume up or down in the first 20 seconds of a song, Sable takes it as a hint. It learns a small offset (at most 2 dB) for that song. When you have turned two songs by one artist the same way, their other songs follow; a song you taught yourself always keeps its own. Offsets fade by half every 30 days and apply from the next play. See and reset them on each song's Why sheet.")
                    Row(Modifier.padding(top = Space.m)) { Pill("Forget everything learned", false) { app.sable.playback.ListenerLearning(context, android.os.Handler(android.os.Looper.getMainLooper())).resetAll() } }
                    Group("About")
                    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SableMark(36.dp)
                        Spacer(Modifier.width(Space.m))
                        Text("SABLE $version · Intent Engine $MATH_DOC_VERSION", style = SableType.body, color = SableColors.textPrimary)
                    }
                    Hint("Every number Sable shows comes from the engine described in MATH.md. Sound effects the phone adds after Sable (Dolby Atmos, EQ, Adapt Sound) still apply.")
                }
            }
            Spacer(Modifier.height(Space.xxxl + endPad(bottomPad)))  // the last lines stay clear of the mini player
        }
    }
}

/** One UI keeps "Sleeping apps" from stopping background listening and playback when Sable is Unrestricted. */
@Composable
private fun BatteryRow() {
    val context = LocalContext.current
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    var unrestricted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName); kotlinx.coroutines.delay(1500) }
    }
    Text(if (unrestricted) "Unrestricted: Sable can listen and play in the background." else "Optimized: One UI may put Sable to sleep in the background.",
        style = SableType.body, color = if (unrestricted) SableColors.textPrimary else SableColors.accent)
    if (!unrestricted) {
        Row(Modifier.padding(top = Space.m)) {
            Pill("Allow unrestricted", true) {
                val i = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(i) }.onFailure {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                }
            }
        }
    }
    Hint("By hand: Settings → Apps → SABLE → Battery → Unrestricted. Also check Settings → Battery → Background usage limits: Sable should not be in Sleeping or Deep sleeping apps.")
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
