package app.sable.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import app.sable.data.LibraryRow
import app.sable.data.SableDatabase
import app.sable.playback.AbSession
import app.sable.playback.QueueId
import app.sable.ui.theme.Motion
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.random.Random

/** Which kind of round (MATH §30.2 clips, §30.6 song changes); each keeps its own tally. */
enum class AbKind(val prefix: String) { CLIPS("ab_"), CHANGES("abflow_") }

/** One blind round (MATH §30.4): label i plays condition mapping[i]. [count] is songs (clips) or song changes. */
class AbRound(val kind: AbKind, val count: Int, val mapping: List<AbSession.Condition>) {
    var current by mutableStateOf(0)
    var picked by mutableStateOf<Int?>(null)
}

object AbTally {
    private fun prefs(context: Context) = context.getSharedPreferences("sable", Context.MODE_PRIVATE)
    fun get(context: Context, kind: AbKind, c: AbSession.Condition) = prefs(context).getInt("${kind.prefix}${c.key}", 0)
    fun add(context: Context, kind: AbKind, c: AbSession.Condition) {
        val p = prefs(context)
        p.edit().putInt("${kind.prefix}${c.key}", p.getInt("${kind.prefix}${c.key}", 0) + 1).apply()
    }
    fun reset(context: Context) {
        val e = prefs(context).edit()
        for (k in AbKind.entries) AbSession.Condition.entries.forEach { e.remove("${k.prefix}${it.key}") }
        e.apply()
    }
}

/**
 * §30.5: is the tally more than luck? A listener picking at random chooses Sable in 1 of 3 rounds, so the chance of
 * Sable winning [k] or more of [n] rounds by luck is the binomial tail P(X ≥ k), X ~ Bin(n, 1/3).
 */
object AbVerdict {
    const val MIN_ROUNDS = 10
    const val ALPHA = 0.05

    fun chanceByLuck(k: Int, n: Int): Double {
        var term = Math.pow(2.0 / 3.0, n.toDouble())  // P(X = 0)
        var tail = 0.0
        for (i in 0..n) {
            if (i >= k) tail += term
            term *= (n - i).toDouble() / (i + 1) * 0.5  // P(X = i+1) from P(X = i), odds (1/3)/(2/3)
        }
        return tail.coerceIn(0.0, 1.0)
    }

    /** One plain sentence about Sable's [k] wins in [n] rounds. */
    fun sentence(k: Int, n: Int): String {
        if (n < MIN_ROUNDS) return "After $MIN_ROUNDS rounds this will tell you whether your picks are real or luck."
        val p = chanceByLuck(k, n)
        val odds = "about 1 time in ${Math.round(1 / p)}"
        return when {
            p < ALPHA -> "Picking at random, Sable would win this often only $odds. You can hear the engine."
            p < 0.5 -> "Picking at random, Sable would win this often $odds, so it could still be luck. Keep going."
            else -> "So far that is what random picks would give. Keep going."
        }
    }
}

const val AB_SONGS = 6
const val AB_EXCERPT_MS = 25_000L  // §30.2 D_ab
const val AB_CHANGES = 4
const val AB_FLOW_MS = 20_000L     // §30.6 D_flow
const val AB_FLOW_MIN_SONG_MS = 60_000L

/** §30.2: six analysed songs from different albums, each a 25 s excerpt centred on its anchor. */
suspend fun buildAbSequence(context: Context, rows: List<LibraryRow>, rnd: Random = Random.Default): Pair<Int, List<MediaItem>> =
    withContext(Dispatchers.IO) {
        val dao = SableDatabase.get(context).library()
        val picks = rows.filter { it.analysed && it.durationMs > 10_000 }.groupBy(::albumKey).values.shuffled(rnd).map { it.random(rnd) }.take(AB_SONGS)
        val items = picks.map { r ->
            val j = dao.analysisForTrack(r.mediaId, SableDatabase.MATH_VERSION)?.json?.let(::JSONObject)
            val centreMs = ((j?.optDouble("anchorCentreSec", Double.NaN) ?: Double.NaN).takeIf { !it.isNaN() } ?: (r.durationMs / 2000.0)) * 1000
            val len = minOf(AB_EXCERPT_MS, r.durationMs)
            val start = (centreMs.toLong() - len / 2).coerceIn(0L, r.durationMs - len)
            MediaItem.Builder().setMediaId(QueueId.request(r.mediaId, albumIntent = false))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(start + len).build())
                .build()
        }
        picks.size to items
    }

/**
 * §30.6: four song changes in random order, two inside complete albums (consecutive tracks, album world) and two between
 * songs of different albums (song world); no album twice. Each change is the last 20 s of one song, then the first 20 s
 * of the next. Returns the number of changes and the queue (two items per change).
 */
suspend fun buildFlowSequence(rows: List<LibraryRow>, rnd: Random = Random.Default): Pair<Int, List<MediaItem>> =
    withContext(Dispatchers.Default) {
        val changes = pickFlowChanges(rows, rnd)
        fun clip(r: LibraryRow, start: Long, album: Boolean) = MediaItem.Builder().setMediaId(QueueId.request(r.mediaId, albumIntent = album))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(start + AB_FLOW_MS).build())
            .build()
        changes.size to changes.flatMap { (x, y, album) -> listOf(clip(x, x.durationMs - AB_FLOW_MS, album), clip(y, 0L, album)) }
    }

/** §30.6 selection: (first song, second song, album world) for each change, in play order. */
fun pickFlowChanges(rows: List<LibraryRow>, rnd: Random): List<Triple<LibraryRow, LibraryRow, Boolean>> {
    val albums = rows.groupBy(::albumKey).values.map { a -> a.sortedWith(compareBy({ it.disc }, { it.track })) }.shuffled(rnd)
    val used = HashSet<String>()
    val changes = ArrayList<Triple<LibraryRow, LibraryRow, Boolean>>()  // first, second, album world
    fun long(r: LibraryRow) = r.analysed && r.durationMs > AB_FLOW_MIN_SONG_MS
    for (a in albums) {
        if (changes.size == AB_CHANGES / 2) break
        if (a.size < 2 || !a.all { it.analysed }) continue  // album world needs the whole album analysed (§17.2)
        val pairs = a.zipWithNext().filter { (x, y) -> x.disc == y.disc && y.track == x.track + 1 && long(x) && long(y) }  // really in a row
        if (pairs.isEmpty()) continue
        val (x, y) = pairs.random(rnd)
        changes += Triple(x, y, true)
        used += albumKey(x)
    }
    val singles = albums.filter { albumKey(it.first()) !in used }.mapNotNull { a -> a.filter(::long).randomOrNull(rnd) }
    for (k in 0 until minOf(AB_CHANGES - changes.size, singles.size / 2)) changes += Triple(singles[2 * k], singles[2 * k + 1], false)
    changes.shuffle(rnd)
    return changes
}

@Composable
fun AbScreen(state: AppState, rows: List<LibraryRow>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var round by remember { mutableStateOf<AbRound?>(null) }
    var kind by remember { mutableStateOf(AbKind.CLIPS) }
    var note by remember { mutableStateOf<String?>(null) }
    var tallyVersion by remember { mutableStateOf(0) }
    val active by AbSession.condition.collectAsState()
    fun end() {
        AbSession.condition.value = null
        state.withController { it.pause() }
        round = null
    }
    fun start(k: AbKind) {
        scope.launch {
            end()
            val (n, items) = if (k == AbKind.CLIPS) buildAbSequence(context, rows) else buildFlowSequence(rows)
            if (n < (if (k == AbKind.CLIPS) 3 else 2)) {
                note = "Not enough analysed songs for this round yet."
                return@launch
            }
            note = null
            AbSession.transitions = k == AbKind.CHANGES
            val nr = AbRound(k, n, AbSession.Condition.entries.shuffled())
            nr.current = Random.nextInt(3)
            AbSession.condition.value = nr.mapping[nr.current].key  // set before the queue is planned
            round = nr
            state.play(items, 0)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("BLIND A/B", { if (round != null) end(); state.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl)) {
            val r = round
            if (r == null) {
                Text("Can you hear the engine?", style = SableType.display, color = SableColors.textPrimary, modifier = Modifier.padding(top = Space.l))
                Row(Modifier.padding(top = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill("Clips", selected = kind == AbKind.CLIPS) { kind = AbKind.CLIPS }
                    Pill("Song changes", selected = kind == AbKind.CHANGES) { kind = AbKind.CHANGES }
                }
                Text(
                    if (kind == AbKind.CLIPS)
                        "Sable plays $AB_SONGS songs from different albums, 25 seconds each, back to back. Three versions, labelled 1, 2 and 3, " +
                            "are Sable, plain −14 LUFS normalization and no normalization. You won't know which is which. All three are matched to the same " +
                            "overall loudness, so only the balance from song to song differs. Switch between them as often as you like, then pick the one " +
                            "that felt most even."
                    else
                        "Sable plays $AB_CHANGES song changes: the last 20 seconds of one song running straight into the first 20 seconds of the next. " +
                            "Some are two tracks in a row from one album, some are two songs from different albums. Three versions, labelled 1, 2 and 3, " +
                            "are Sable, plain −14 LUFS normalization and no normalization, all matched to the same overall loudness. Listen to how each " +
                            "song hands over to the next, then pick the version where the changes felt most natural.",
                    style = SableType.body, color = SableColors.textSecondary, modifier = Modifier.padding(top = Space.l),
                )
                Spacer(Modifier.height(Space.xl))
                Pill("Start a round", selected = true) { start(kind) }
                note?.let { Text(it, style = SableType.caption, color = SableColors.textTertiary, modifier = Modifier.padding(top = Space.s)) }
                Tally(context, tallyVersion, kind)
            } else {
                val i = state.queueIndex
                Text(
                    when {
                        r.picked != null -> "Revealed"
                        r.kind == AbKind.CLIPS -> "Song ${i + 1} of ${r.count}"
                        else -> "Song change ${i / 2 + 1} of ${r.count} · ${if (i % 2 == 0) "the ending" else "the next song"}"
                    },
                    style = SableType.overline, color = SableColors.textTertiary, modifier = Modifier.padding(top = Space.l),
                )
                Row(Modifier.fillMaxWidth().padding(top = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    for (l in 0..2) {
                        LabelButton("${l + 1}", selected = r.current == l, Modifier.weight(1f)) {
                            r.current = l
                            AbSession.condition.value = r.mapping[l].key  // 20 ms crossfade (§23.3)
                        }
                    }
                }
                Text("Switch freely. The change takes about half a second to reach the speaker.", style = SableType.caption, color = SableColors.textTertiary,
                    modifier = Modifier.padding(top = Space.s))
                Spacer(Modifier.height(Space.xl))
                if (r.picked == null) {
                    Text(
                        if (r.kind == AbKind.CLIPS) "Which one sounded most even from song to song?" else "Which one made the song changes feel most natural?",
                        style = SableType.headline, color = SableColors.textPrimary,
                    )
                    Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (l in 0..2) Pill("${l + 1}", selected = false) {
                            r.picked = l
                            AbTally.add(context, r.kind, r.mapping[l])
                            tallyVersion++
                        }
                    }
                }
                AnimatedVisibility(r.picked != null, enter = fadeIn(Motion.slow())) {
                    Column {
                        for (l in 0..2) {
                            val c = r.mapping[l]
                            Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                                Text("${l + 1}", style = SableType.title, color = if (l == r.picked) SableColors.accent else SableColors.textSecondary,
                                    modifier = Modifier.padding(end = Space.l))
                                Text(c.label, style = SableType.body, color = SableColors.textPrimary, modifier = Modifier.weight(1f))
                                if (l == r.picked) Text("your pick", style = SableType.caption, color = SableColors.accent)
                            }
                        }
                        Text(
                            if (r.picked?.let { r.mapping[it] } == AbSession.Condition.SABLE) "You picked Sable." else "You picked ${r.picked?.let { r.mapping[it].label }}.",
                            style = SableType.headline, color = SableColors.textPrimary, modifier = Modifier.padding(top = Space.m),
                        )
                        Row(Modifier.padding(top = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Pill("New round", selected = true) { start(r.kind) }
                            Pill("Done", selected = false) { end() }
                        }
                        Tally(context, tallyVersion, r.kind)
                    }
                }
                if (active == null && r.picked == null) Text("Playback ended.", style = SableType.caption, color = SableColors.textTertiary)
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

@Composable
private fun LabelButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) SableColors.accent else Color.Transparent, Motion.medium(), label = "ab")
    val fg by animateColorAsState(if (selected) SableColors.onAccent else SableColors.textPrimary, Motion.medium(), label = "abText")
    Box(
        modifier.aspectRatio(1f).background(bg, RoundedCornerShape(18.dp)).border(1.dp, if (selected) SableColors.accent else SableColors.line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = SableType.number, color = fg) }
}

@Composable
private fun Tally(context: Context, version: Int, kind: AbKind) {
    val counts = remember(version, kind) { AbSession.Condition.entries.associateWith { AbTally.get(context, kind, it) } }
    val total = counts.values.sum()
    if (total == 0) return
    Spacer(Modifier.height(Space.xl))
    Text(if (kind == AbKind.CLIPS) "YOUR ROUNDS · CLIPS" else "YOUR ROUNDS · SONG CHANGES", style = SableType.overline, color = SableColors.textTertiary)
    for ((c, n) in counts) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(c.label, style = SableType.body, color = SableColors.textSecondary, modifier = Modifier.weight(1f))
            Text("$n of $total", style = SableType.body, color = if (c == AbSession.Condition.SABLE) SableColors.accent else SableColors.textPrimary)
        }
    }
    Text(AbVerdict.sentence(counts.getValue(AbSession.Condition.SABLE), total), style = SableType.caption, color = SableColors.textSecondary,
        modifier = Modifier.padding(top = Space.s))
}
