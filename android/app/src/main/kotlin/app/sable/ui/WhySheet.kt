package app.sable.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.rotate
import app.sable.ui.theme.Motion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.sable.data.LibraryRow
import app.sable.data.SableDatabase
import app.sable.data.TrackEntity
import app.sable.library.GainTags
import app.sable.playback.ListenerLearning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.sable.playback.NowPlaying
import app.sable.playback.PlaybackPrefs
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space
import org.json.JSONObject

/**
 * The Why sheet (spec, Layer 5; MATH §21): what Sable measured, where the anchor is, the applied gain, every rule that
 * fired with its dB, and one plain-English line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhySheet(state: AppState, row: LibraryRow, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val json = analysisJson(row.mediaId)
    val live by NowPlaying.gain.collectAsState()
    val settings by PlaybackPrefs.settings.collectAsState()
    val isPlaying = state.currentTrackId == row.mediaId && live.mediaId == state.currentQueueId
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = SableColors.surface1, scrimColor = SableColors.base.copy(alpha = 0.6f),
        dragHandle = { Box(Modifier.padding(top = Space.m).size(36.dp, 4.dp).background(SableColors.line, RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl).navigationBarsPadding()) {
            Text("WHY", style = SableType.overline, color = SableColors.textTertiary, modifier = Modifier.padding(top = Space.m))
            Text(titleOf(row), style = SableType.title, color = SableColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
            Text("${clean(row.artist)} · ${clean(row.album)}", style = SableType.caption, color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.xl))
            if (json == null) {
                Text(
                    if (isPlaying) WhyText.sentence(null, live.gainDb, live.rules, live.source, live.albumRun) else "Sable hasn't listened to this song yet. It will before it plays.",
                    style = SableType.body, color = SableColors.textPrimary,
                )
                Spacer(Modifier.height(Space.xxl))
                return@Column
            }
            val decision = remember(json, settings.mode) { json.optJSONObject(if (settings.mode == PlaybackPrefs.Mode.QUIET) "quiet" else "standard") }
            val gainDb = if (isPlaying) live.gainDb else decision?.optDouble("gainDb") ?: 0.0
            val rules = if (isPlaying) live.rules else decision?.optJSONArray("rules")?.let { a ->
                List(a.length()) { i -> a.getJSONObject(i).let { it.optString("id") to it.optDouble("deltaDb") } }
            } ?: emptyList()
            val character = json.optString("character")
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%+.1f".format(gainDb), style = SableType.number, color = SableColors.accent)
                Text(" dB", style = SableType.headline, color = SableColors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                if (isPlaying) "${worldLabel(live)} · ceiling %.1f dBTP".format(live.ceilingDbtp)
                else "On its own, in ${settings.mode.label} (${settings.mode.targetLufs.toInt()} LUFS)",
                style = SableType.caption, color = SableColors.textTertiary,
            )
            Spacer(Modifier.height(Space.l))
            Text(
                WhyText.sentence(character, gainDb, rules, if (isPlaying) live.source else "full", isPlaying && live.albumRun),
                style = SableType.body, color = SableColors.textPrimary,
            )
            SongShape.of(json)?.let { shape ->
                Spacer(Modifier.height(Space.xl))
                LoudnessShape(shape, { if (isPlaying) state.progress() else 0f }, Modifier.fillMaxWidth().height(64.dp))
                Text(anchorText(shape), style = SableType.caption, color = SableColors.textTertiary, modifier = Modifier.padding(top = Space.s))
            }
            // Plain by default: the details (every measurement and rule) fold away. Advanced users see them open.
            var open by remember(settings.advanced) { mutableStateOf(settings.advanced) }
            Row(
                Modifier.fillMaxWidth().padding(top = Space.xl).clickable { open = !open }.padding(vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (open) "Hide details" else "Details", style = SableType.label, color = SableColors.textSecondary, modifier = Modifier.weight(1f))
                Icon(Glyph.DOWN, size = 16.dp, color = SableColors.textTertiary, modifier = Modifier.rotate(if (open) 180f else 0f))
            }
            AnimatedVisibility(open, enter = fadeIn(Motion.slow()) + expandVertically(Motion.slow()), exit = fadeOut(Motion.medium()) + shrinkVertically(Motion.medium())) {
                Column {
                Section("What Sable heard")
                Measure("Loudness (BS.1770)", lufs(json, "integratedLufs"))
                Measure("Perceived loudness of the body", lufs(json, "anchorPle"))
                Measure("Loudness of the body (BS.1770)", lufs(json, "anchorLufs"))
                Measure("Loudness range", num(json, "lraLu", "LU"))
                Measure("True peak", num(json, "truePeakDbtp", "dBTP"))
                Measure("Character", character.ifBlank { "—" })
                Section("Rules")
                for ((id, d) in rules) Measure(WhyText.ruleLabel(id), "%+.1f dB".format(d))
                if (rules.isEmpty()) Text("None", style = SableType.caption, color = SableColors.textTertiary)
                if (isPlaying && (live.learnedDb != 0.0 || live.outputCalDb != 0.0 || live.contourOn)) {
                    Section("Your settings")
                    if (live.learnedDb != 0.0) Measure("Learned from your volume keys", "%+.1f dB".format(live.learnedDb))
                    if (live.outputCalDb != 0.0) Measure("Level offset for ${live.route}", "%+.1f dB".format(live.outputCalDb))
                    if (live.contourOn) Measure("Quiet-mode contour", "on")
                }
                Learned(row)
                FileTags(row)
                Section("Notes")
                val notes = buildList {
                    if (json.optBoolean("decodeClipped")) add("This file was decoded at 16-bit, so its true peak may read low. Sable adds 1 dB of safety.")
                    if (json.optBoolean("shortFile")) add("A very short file is measured as a whole.")
                    if (settings.mode == PlaybackPrefs.Mode.QUIET) add("Quiet mode lowers the same balance by 5 dB. Perceived balance is not re-measured at the lower level.")
                    add("Peaks are kept under the output's ceiling: -1 dBTP on the speaker and wired/USB, -2 dBTP on Bluetooth (codec not visible to apps).")
                    add("Sable measures what it plays; effects after it (Dolby Atmos, Adapt Sound, EQ) can change what you hear." +
                        effectsOnPhone().let { if (it.isEmpty()) "" else " This phone has: ${it.joinToString(", ")}." })
                }
                for (n in notes) Text("· $n", style = SableType.caption, color = SableColors.textSecondary, modifier = Modifier.padding(bottom = Space.xs))
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

/** §19.2: learned offsets for this song and its artist, with reset (visible and resettable). */
@Composable
private fun Learned(row: LibraryRow) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val learning = remember { ListenerLearning(context, android.os.Handler(android.os.Looper.getMainLooper())) }
    val track by produceState<TrackEntity?>(null, row.mediaId) {
        value = withContext(Dispatchers.IO) { SableDatabase.get(context).library().track(row.mediaId) }
    }
    val t = track ?: return
    val o = remember(t, version) { learning.offsets(t) }
    if (o.trackDb == 0.0 && o.artistDb == 0.0) return
    Section("Learned")
    if (o.trackDb != 0.0) Measure("This song (set %.0f days ago)".format(ListenerLearning.nowDays() - o.trackDays), "%+.1f dB".format(o.trackDb))
    if (o.artistDb != 0.0) Measure("${clean(t.artist)} (${o.artistTracks} song${if (o.artistTracks == 1) "" else "s"})", "%+.1f dB".format(o.artistDb))
    Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        if (o.trackDb != 0.0) Pill("Forget for this song", false) { learning.resetTrack(t); version++ }
        if (o.artistDb != 0.0) Pill("Forget for the artist", false) { learning.resetArtist(t); version++ }
    }
}

/** §28: ReplayGain / R128 tags already in the file, shown for comparison only. */
@Composable
private fun FileTags(row: LibraryRow) {
    val context = LocalContext.current
    val tags by produceState(emptyList<GainTags.Tag>(), row.mediaId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(android.net.Uri.parse(row.uri))?.use { s ->
                    val buf = ByteArray(256 * 1024)
                    var n = 0
                    while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                    GainTags.read(buf.copyOf(n))
                }
            }.getOrNull() ?: emptyList()
        }
    }
    if (tags.isEmpty()) return
    Section("Tags in the file (not used)")
    for (t in tags) Measure(t.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, GainTags.describe(t))
}

private fun effectsOnPhone(): List<String> = runCatching {
    android.media.audiofx.AudioEffect.queryEffects().map { it.name }
        .filter { n -> listOf("dolby", "soundalive", "adapt", "atmos", "virtualizer", "equalizer").any { n.contains(it, ignoreCase = true) } }
        .distinct().take(4)
}.getOrDefault(emptyList())

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(Space.xl))
    Text(title.uppercase(), style = SableType.overline, color = SableColors.textTertiary)
    Box(Modifier.padding(top = Space.s, bottom = Space.xs).fillMaxWidth().height(1.dp).background(SableColors.line))
}

@Composable
private fun Measure(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = SableType.body, color = SableColors.textSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.m))
        Text(value, style = SableType.body, color = SableColors.textPrimary)
    }
}

private fun lufs(j: JSONObject, k: String) = num(j, k, "LUFS")
private fun num(j: JSONObject, k: String, unit: String) = if (j.isNull(k) || !j.has(k)) "—" else "%.1f %s".format(j.optDouble(k), unit)

fun anchorText(s: SongShape): String {
    if (s.anchor.isEmpty()) return "No clear body: Sable balanced the whole song."
    val parts = s.anchor.joinToString(", ") { (a, b) -> "${mmss((a * 1000).toLong())}–${mmss((b * 1000).toLong())}" }
    return "The glow is the anchor, the body of the song Sable balances to: $parts."
}
