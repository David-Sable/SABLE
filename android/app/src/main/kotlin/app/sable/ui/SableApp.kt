@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package app.sable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
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
    androidx.activity.compose.PredictiveBackHandler(enabled = state.back.size > 1) { gesture ->
        val sheet = state.route is Route.NowPlaying
        if (!sheet) state.backing = state.route
        fun clear() { state.backing = null; state.backP = 0f }
        try {
            gesture.collect { if (sheet) state.backSheet(it.progress) else state.backP = it.progress }
            state.pop()
            if (!sheet) state.sheetScope?.launch { kotlinx.coroutines.delay(320); clear() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (sheet) state.cancelBackSheet()
            else state.sheetScope?.launch { androidx.compose.animation.core.animate(state.backP, 0f, animationSpec = app.sable.ui.theme.Motion.page()) { v, _ -> state.backP = v }; clear() }
            throw e
        }
    }
    SharedTransitionLayout(Modifier.fillMaxSize().background(SableColors.base)) {
    val sharedScope = this
    Box(Modifier.fillMaxSize()) {
        val showMini = current != null && state.route !is Route.NowPlaying
        // Room for the mini player whenever something is loaded, also under Now Playing: the screen beneath keeps its layout
        // instead of laying itself out again each time the sheet opens and closes.
        val pad = if (current != null) bottomBarHeight() else 0.dp
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(state.currentTrackId) { SongViews.load(context, state.currentTrackId) }  // Now Playing's picture, ready before it opens
        // Each screen keeps its scroll position (and tab) while it is in the back stack: going back or swiping Now
        // Playing down returns to the same spot. A screen that leaves the stack forgets it, so reopening starts fresh.
        val saved = rememberSaveableStateHolder()
        val inStack = state.back.map { it.toString() }.toSet()
        val known = remember { mutableSetOf<String>() }
        LaunchedEffect(inStack) {
            (known - inStack).forEach(saved::removeState)
            known.clear(); known += inStack
        }
        // Now Playing is a sheet over the screens, not one of them: the screen it opened over stays alive beneath it (stepping back as
        // the sheet rises), so opening needs no snapshot of it and closing rebuilds nothing (measured on the A17: closing cost two
        // frames of 30-50 ms rebuilding the library, opening a 15-28 ms frame copying the snapshot). Screens opened from Now
        // Playing (queue, album, artist) go on a layer above the sheet.
        val np = state.back.indexOfFirst { it is Route.NowPlaying }
        val baseRoute: Route? = if (np > 0) state.back[np - 1] else state.route
        val aboveRoute: Route? = if (np >= 0 && np < state.back.lastIndex) state.route else null
        val spec: AnimatedContentTransitionScope<Route?>.() -> ContentTransform = {
            // No cross-fade: two half-see-through screens on black dip to nearly black in the middle, which tires the eyes.
            // Every screen is solid and only the one on top moves or fades: going forward the new screen comes in over the
            // old one, which stays put; going back the leaving screen goes away over the one it uncovers.
            val leaving = initialState != null && initialState !in state.back  // going back: the screen we are on is leaving for good
            // Screens rise a little (about 26 dp) as they arrive and sink as they leave.
            // Pages are the quickest of Now Playing's spring family (Motion.page): no timed curve, no overshoot.
            val enter = if (leaving || targetState == null) EnterTransition.None else slideInVertically(Motion.page(androidx.compose.ui.unit.IntOffset.VisibilityThreshold)) { it / 32 }
            val exit = if (leaving) slideOutVertically(Motion.page(androidx.compose.ui.unit.IntOffset.VisibilityThreshold)) { it / 32 } else ExitTransition.KeepUntilTransitionsFinished
            val t = (enter togetherWith exit) using null  // no size animation: a page opening over Now Playing grew out of the top-left corner as a rectangle
            t.targetContentZIndex = if (initialState != null && initialState !in state.back) -1f else 1f  // going back: the newcomer goes underneath
            t
        }
        val screen: @Composable AnimatedContentScope.(Route?, Boolean) -> Unit = { r, beneathSheet ->
            if (r != null && r !is Route.NowPlaying) {
                val revealed = remember { r.toString() in known }  // back to a screen that was already there: it is simply uncovered
                val a by transition.animateFloat(transitionSpec = { spring(dampingRatio = 1f, stiffness = 1600f, visibilityThreshold = 0.001f) }, label = "screen") {  // solid within about 130 ms: longer, a page's words sat see-through over the covers beneath
                    when (it) {
                        EnterExitState.Visible -> 1f
                        EnterExitState.PreEnter -> if (revealed) 1f else 0f
                        EnterExitState.PostExit -> if (r in state.back) 1f else 0f  // covered: stays; left for good: fades away
                    }
                }
                Box(Modifier.graphicsLayer {
                    alpha = a
                    if (r == state.backing) {  // the back gesture: this page steps back with the thumb, its corners rounding
                        val s = 1f - 0.07f * state.backP
                        scaleX = s; scaleY = s
                        translationX = 24.dp.toPx() * state.backP
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp * state.backP)
                        clip = true
                    }
                }.then(if (beneathSheet) Modifier.stepBack(state) else Modifier).background(SableColors.base)) {  // inside the step-back clip: covered by the sheet, it is not painted
                    saved.SaveableStateProvider(r.toString()) {
                        when (r) {
                            Route.Library -> LibraryScreen(state, rows, pad)
                            is Route.Album -> AlbumScreen(state, rows, r.key, pad)
                            is Route.Artist -> ArtistScreen(state, rows, r.name, pad)
                            is Route.Folder -> FolderScreen(state, rows, r.path, pad)
                            is Route.Playlist -> PlaylistScreen(state, rows, r.id, pad)
                            Route.Queue -> QueueScreen(state, rows, pad)
                            Route.Settings -> SettingsScreen(state, rows, pad, onRescan, onAnalyseWhileCharging)
                            Route.Search -> SearchScreen(state, rows, pad)
                            Route.NowPlaying -> Unit
                        }
                    }
                    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(SableColors.base))  // lists never scroll under the clock
                }
            }
        }
        AnimatedContent(baseRoute, transitionSpec = spec, label = "route") { r -> screen(r, true) }
        AlbumFlightLayer(Modifier.zIndex(0.5f))
        // The sheet is built once and kept, hidden below the screen while closed (and not drawn), so opening it is only a slide:
        // measured on the A17, building it in the slide's first frame took 35-55 ms. It slides up from the bottom and back down (also
        // after a swipe, from where the finger left it) and paints its own background. Its enter/exit state drives the cover's
        // flight from and back into the mini player, as an AnimatedVisibility would.
        val npOpen = np >= 0
        val npTransition = updateTransition(if (npOpen) EnterExitState.Visible else EnterExitState.PostExit, label = "nowPlaying")
        val npScope = remember(npTransition) { object : AnimatedVisibilityScope { override val transition = npTransition } }
        // One spring to the open or closed place, from wherever the sheet is (the finger may have moved it) and at the finger's speed.
        state.sheetScope = androidx.compose.runtime.rememberCoroutineScope()
        LaunchedEffect(npOpen) {
            val v = state.sheetVelocity; state.sheetVelocity = 0f
            state.sheetLeaving = !npOpen
            state.sheet.animateTo(if (npOpen) 1f else 0f, Motion.sheetSpring, initialVelocity = v)
        }
        // The first opening after a start (or a new song) drew the sheet for the first time: its cover upload and first draw stalled
        // about five frames while the mini player's cover had already gone. So it is drawn once, invisibly, as soon as a song is there.
        LaunchedEffect(current?.mediaId) {
            if (current == null || npOpen) return@LaunchedEffect
            state.npPrewarm = true
            repeat(3) { withFrameNanos { } }
            state.npPrewarm = false
        }
        if (current != null) Box(
            Modifier.fillMaxSize().zIndex(2f)  // over the mini player: the sheet rises out of it instead of behind it
                .layout { m, c ->
                    val p = m.measure(c)
                    state.sheetTravel = p.height.toFloat()
                    layout(p.width, p.height) {
                        val y = if (state.npPrewarm && !npOpen) 0 else ((1f - state.sheet.value) * p.height).roundToInt()
                        state.npSheetOffset = y.toFloat()
                        p.place(0, y)
                    }
                }
                .graphicsLayer { alpha = if (state.npPrewarm && !npOpen) 0.004f else 1f }
                .drawWithContent { if (state.sheet.value > 0f || state.npPrewarm) drawContent() },
        ) {
            saved.SaveableStateProvider(Route.NowPlaying.toString()) {
                val onScreen = rememberUpdatedState(npOpen)
                androidx.compose.runtime.CompositionLocalProvider(LocalOnScreen provides onScreen) {
                    NowPlayingScreen(state, current, sharedScope, npScope, rowOf = { byId[it] })
                }
            }
        }
        // The cover's flight: drawn between the mini player's cover and Now Playing's at the sheet's progress, over both (draw phase
        // only: nothing is laid out or rebuilt per frame). At 0 it is exactly the mini player's cover, at 1 exactly Now Playing's.
        val flyArt = current?.let { c -> remember(c.albumId) { c.albumId } }
        // The cover rides on the sheet (exactly where Now Playing's cover sits on it) for the upper part of the travel, so the words
        // under it never pass over or under it. Below RIDE_FROM it leaves the sheet on one smooth curve (cubic Hermite: it leaves at
        // the sheet's own speed, so no kink, and never passes the mini player's cover, so no dip and rise) and lands on it exactly.
        Spacer(Modifier.fillMaxSize().zIndex(2.5f).drawBehind {
            if (!state.coverFlying() || flyArt == null) return@drawBehind
            val img = ArtCache.peekAny(flyArt, 1024) ?: return@drawBehind
            val a = state.miniCover ?: return@drawBehind
            val rest = state.npCover?.translate(0f, -state.npSheetOffset) ?: return@drawBehind
            val p = state.sheetProgress().coerceIn(0f, 1f)
            val travel = state.sheetTravel
            val l: Float; val t: Float; val w: Float; val h: Float
            if (!state.sheetLeaving) {
                // Opening: straight from the mini player's corner to where the cover rests, growing all the way (David, 2026-10-29: "come
                // from the side like it was"). The two paths meet at both ends, so switching between them never jumps.
                l = a.left + (rest.left - a.left) * p; t = a.top + (rest.top - a.top) * p
                w = a.width + (rest.width - a.width) * p; h = a.height + (rest.height - a.height) * p
            } else if (p >= RIDE_FROM) {
                l = rest.left; t = rest.top + (1f - p) * travel; w = rest.width; h = rest.height  // on the sheet
            } else {
                val u = p / RIDE_FROM
                val leave = rest.top + (1f - RIDE_FROM) * travel  // where it leaves the sheet
                // From the mini player's cover (u = 0, heading straight at the leave point) to the leave point (u = 1, moving with the
                // sheet: -travel per unit of progress, x and size not changing).
                fun hermite(from: Float, to: Float, endSlope: Float): Float {
                    val u2 = u * u; val u3 = u2 * u
                    return (2 * u3 - 3 * u2 + 1) * from + (u3 - 2 * u2 + u) * (to - from) + (-2 * u3 + 3 * u2) * to + (u3 - u2) * endSlope
                }
                l = hermite(a.left, rest.left, 0f); t = hermite(a.top, leave, -travel * RIDE_FROM)
                w = hermite(a.width, rest.width, 0f); h = hermite(a.height, rest.height, 0f)
            }
            val side = minOf(img.width, img.height)  // the cover's centre square, as the covers crop it
            val path = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, l + w, t + h, androidx.compose.ui.geometry.CornerRadius(app.sable.ui.theme.Radius.art.toPx())))
            }
            clipPath(path) {
                drawImage(
                    img, srcOffset = androidx.compose.ui.unit.IntOffset((img.width - side) / 2, (img.height - side) / 2),
                    srcSize = androidx.compose.ui.unit.IntSize(side, side),
                    dstOffset = androidx.compose.ui.unit.IntOffset(l.roundToInt(), t.roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                )
            }
        })
        AnimatedContent(aboveRoute, transitionSpec = spec, label = "above", modifier = Modifier.zIndex(3f)) { r -> screen(r, false) }
        // Under the sheet, over screens opened from it. Its contents fade with the sheet's own progress (gone by a quarter of the way
        // up), so a swipe up from it holds its fade too; nothing slides against the sheet at the bottom of the screen. The bar itself
        // stays solid: while the whole bar faded, the words of the page beneath it (an album's name under the last row of covers)
        // showed through it during every open and close (David, 2026-10-30: "LALISA in the background").
        // A page opened from Now Playing (the queue): the bar slides up with it instead of appearing over the sheet at once.
        val overSheet by androidx.compose.animation.core.animateFloatAsState(if (aboveRoute != null) 1f else 0f, Motion.page(0.001f), label = "barOverSheet")
        if (current != null) Box(
            Modifier.align(Alignment.BottomCenter).zIndex(if (aboveRoute != null) 4f else 1f)
                .graphicsLayer { if (aboveRoute != null) translationY = size.height * (1f - overSheet) }
                .background(SableColors.surface1),
        ) {
            Box(Modifier.graphicsLayer { alpha = if (aboveRoute != null) 1f else (1f - state.sheet.value / 0.25f).coerceIn(0f, 1f) }) {
                MiniPlayer(state, current, shared = sharedScope, rowOf = { byId[it] })
            }
        }
        Box(Modifier.zIndex(5f)) {  // the sheets cover everything, Now Playing included
            state.menuTrack?.let { id -> byId[id]?.let { SongMenu(state, it) { state.menuTrack = null } } }
            state.playlistTrack?.let { id -> AddToPlaylistSheet(id) { state.playlistTrack = null } }
            state.whyTrack?.let { id -> byId[id]?.let { WhySheet(state, it) { state.whyTrack = null } } }
            if (state.sleepSheet) SleepSheet { state.sleepSheet = false }
        }
    }
    }
}

/** Above this much of the way open the cover rides on the sheet; below it, it eases into the mini player's cover. */
private const val RIDE_FROM = 0.55f
private const val BACK_SHRINK = 0f  // the screen behind only dims: shrinking it as the sheet rose read as bouncing (David, 2026-10-29)
private const val BACK_DIM = 0.5f

/** The screen under Now Playing while it opens: it steps back (smaller and dimmer) as the sheet rises. */
private fun Modifier.stepBack(state: AppState): Modifier = this
    .graphicsLayer {
        val p = if (state.animDepth) state.sheetP else 0f
        if (p > 0f) {
            val k = 1f - BACK_SHRINK * p
            scaleX = k; scaleY = k
        }
    }
    .drawWithContent {
        val p = if (state.animDepth) state.sheetP else 0f
        // Fully under the sheet: not drawn at all. While the sheet moves the whole screen is drawn: clipping it to the strip above the
        // sheet left a black band under a fast swipe, because the sheet's position arrives a frame after it moved (David, 2026-10-29).
        if (state.sheetP >= 0.999f) return@drawWithContent
        drawContent()
        if (p > 0f) drawRect(androidx.compose.ui.graphics.Color.Black, alpha = BACK_DIM * p)
    }

/** The MATH.md version this build implements (keep equal to the header of MATH.md). */
const val MATH_DOC_VERSION = "MATH-1.37"

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
            Text("${count(rows.size, "song")} · ${if (analysed == rows.size) "all listened to" else "$analysed listened to"}", style = SableType.body, color = SableColors.textPrimary)
            Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("Rescan now", false, onClick = onRescan)
                Pill("Listen while charging", false, onClick = onAnalyseWhileCharging)
            }
            Group("Backup")
            BackupRow()
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
                    Hint("Quiet mode lifts the bass a little (about 2 dB) so songs still sound full at low volume. Off means level changes only.")
                    Group("Quieter / Louder buttons")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Pill("Off", !s.carTap) { PlaybackPrefs.update(context) { it.copy(carTap = false) } }
                        Pill("On", s.carTap) { PlaybackPrefs.update(context) { it.copy(carTap = true) } }
                    }
                    Hint("On Now Playing, the lock screen and Android Auto, for places where the phone can't see the volume knob, like a car stereo. A tap teaches Sable the same way the volume keys do.")
                    Group("Mood colours")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Pill("Off", !s.mood) { PlaybackPrefs.update(context) { it.copy(mood = false) } }
                        Pill("On", s.mood) { PlaybackPrefs.update(context) { it.copy(mood = true) } }
                    }
                    Hint("Tints the strip on Now Playing: brass where the song is brighter, blue where it is darker. Display only; the sound never changes.")
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
                    // The device by name ("Bluetooth · JBL Charge 5"), as the learning names it; the offset below applies to every output of this kind.
                    val placeName = remember(route) { app.sable.playback.ListenerLearning(context, android.os.Handler(android.os.Looper.getMainLooper())).currentPlace().label }
                    Text("Now: $placeName", style = SableType.body, color = SableColors.textPrimary)
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
                    Hint("Each kind of output keeps its own offset and ceiling. Bluetooth starts 1 dB lower because the phone re-encodes the audio.")
                    Row(Modifier.padding(top = Space.s)) {
                        Pill("Reset ${route.label}", false) { PlaybackPrefs.update(context) { it.copy(routeCal = it.routeCal - route.key, routeCeiling = it.routeCeiling - route.key) } }
                    }
                    Group("Learning")
                    Hint("Turning the volume in a song's first 20 seconds teaches Sable, up to 2 dB per song, separately for each place. It halves each month; each song's Why sheet shows and resets it.")
                    Row(Modifier.padding(top = Space.m)) { Pill("Forget everything learned", false) { app.sable.playback.ListenerLearning(context, android.os.Handler(android.os.Looper.getMainLooper())).resetAll() } }
                    Group("About")
                    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SableMark(36.dp)
                        Spacer(Modifier.width(Space.m))
                        Text("SABLE $version · Intent Engine $MATH_DOC_VERSION", style = SableType.body, color = SableColors.textPrimary)
                    }
                    Hint("Every number comes from the engine in MATH.md. The phone's own effects (Dolby Atmos, EQ, Adapt Sound) still apply after Sable.")
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

/** Save everything Sable knows about you to one file in Downloads/Sable, or put it back (also on a new phone). */
@Composable
private fun BackupRow() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var note by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { note = runCatching { app.sable.data.Backup.restore(context, uri) }.getOrElse { "Could not restore: ${it.message}" } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Pill("Save backup", false) { scope.launch { note = runCatching { "Saved to " + app.sable.data.Backup.save(context) }.getOrElse { "Could not save: ${it.message}" } } }
        Pill("Restore…", false) { pick.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
    }
    note?.let { Text(it, style = SableType.body, color = SableColors.accent, modifier = Modifier.padding(top = Space.m)) }
    val days = remember(note) { app.sable.data.Backup.lastSavedAt(context).takeIf { it > 0 }?.let { ((System.currentTimeMillis() - it) / 86_400_000L).toInt() } }
    Hint("Sable saves a copy by itself every week. Last saved: ${when (days) { null -> "not yet"; 0 -> "today"; 1 -> "yesterday"; else -> "$days days ago" }}.")
    Hint("One small file with your settings, what Sable learned, your history, favourites and playlists (never music). Restore replaces those on this phone with the file's.")
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
