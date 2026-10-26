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

/** One blind round (MATH §30.4): label i plays condition mapping[i]. */
class AbRound(val songs: List<LibraryRow>, val mapping: List<AbSession.Condition>) {
    var current by mutableStateOf(0)
    var picked by mutableStateOf<Int?>(null)
}

object AbTally {
    fun get(context: Context, c: AbSession.Condition) = context.getSharedPreferences("sable", Context.MODE_PRIVATE).getInt("ab_${c.key}", 0)
    fun add(context: Context, c: AbSession.Condition) {
        val p = context.getSharedPreferences("sable", Context.MODE_PRIVATE)
        p.edit().putInt("ab_${c.key}", p.getInt("ab_${c.key}", 0) + 1).apply()
    }
    fun reset(context: Context) {
        val e = context.getSharedPreferences("sable", Context.MODE_PRIVATE).edit()
        AbSession.Condition.entries.forEach { e.remove("ab_${it.key}") }
        e.apply()
    }
}

const val AB_SONGS = 6
const val AB_EXCERPT_MS = 25_000L  // §30.2 D_ab

/** §30.2: six analysed songs from different albums, each a 25 s excerpt centred on its anchor. */
suspend fun buildAbSequence(context: Context, rows: List<LibraryRow>, rnd: Random = Random.Default): Pair<List<LibraryRow>, List<MediaItem>> =
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
        picks to items
    }

@Composable
fun AbScreen(state: AppState, rows: List<LibraryRow>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var round by remember { mutableStateOf<AbRound?>(null) }
    var tallyVersion by remember { mutableStateOf(0) }
    val active by AbSession.condition.collectAsState()
    fun end() {
        AbSession.condition.value = null
        state.withController { it.pause() }
        round = null
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("BLIND A/B", { if (round != null) end(); state.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl)) {
            val r = round
            if (r == null) {
                Text("Can you hear the engine?", style = SableType.display, color = SableColors.textPrimary, modifier = Modifier.padding(top = Space.l))
                Text(
                    "Sable plays $AB_SONGS songs from different albums, 25 seconds each, back to back. Three versions, labelled 1, 2 and 3, " +
                        "are Sable, plain −14 LUFS normalization and no normalization. You won't know which is which. All three are matched to the same " +
                        "overall loudness, so only the balance from song to song differs. Switch between them as often as you like, then pick the one " +
                        "that felt most even.",
                    style = SableType.body, color = SableColors.textSecondary, modifier = Modifier.padding(top = Space.l),
                )
                Spacer(Modifier.height(Space.xl))
                Pill("Start a round", selected = true) {
                    scope.launch {
                        val (songs, items) = buildAbSequence(context, rows)
                        if (songs.size < 3) return@launch
                        val nr = AbRound(songs, AbSession.Condition.entries.shuffled())
                        nr.current = Random.nextInt(3)
                        AbSession.condition.value = nr.mapping[nr.current].key  // set before the queue is planned
                        round = nr
                        state.play(items, 0)
                    }
                }
                Tally(context, tallyVersion)
            } else {
                Text(
                    if (r.picked == null) "Song ${state.queueIndex + 1} of ${r.songs.size}" else "Revealed",
                    style = SableType.overline, color = SableColors.textTertiary, modifier = Modifier.padding(top = Space.l),
                )
                Row(Modifier.fillMaxWidth().padding(top = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    for (i in 0..2) {
                        LabelButton("${i + 1}", selected = r.current == i, Modifier.weight(1f)) {
                            r.current = i
                            AbSession.condition.value = r.mapping[i].key  // 20 ms crossfade (§23.3)
                        }
                    }
                }
                Text("Switch freely. The change takes about half a second to reach the speaker.", style = SableType.caption, color = SableColors.textTertiary,
                    modifier = Modifier.padding(top = Space.s))
                Spacer(Modifier.height(Space.xl))
                if (r.picked == null) {
                    Text("Which one sounded most even from song to song?", style = SableType.headline, color = SableColors.textPrimary)
                    Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (i in 0..2) Pill("${i + 1}", selected = false) {
                            r.picked = i
                            AbTally.add(context, r.mapping[i])
                            tallyVersion++
                        }
                    }
                }
                AnimatedVisibility(r.picked != null, enter = fadeIn(Motion.slow())) {
                    Column {
                        for (i in 0..2) {
                            val c = r.mapping[i]
                            Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = SableType.title, color = if (i == r.picked) SableColors.accent else SableColors.textSecondary,
                                    modifier = Modifier.padding(end = Space.l))
                                Text(c.label, style = SableType.body, color = SableColors.textPrimary, modifier = Modifier.weight(1f))
                                if (i == r.picked) Text("your pick", style = SableType.caption, color = SableColors.accent)
                            }
                        }
                        Text(
                            if (r.picked?.let { r.mapping[it] } == AbSession.Condition.SABLE) "You picked Sable." else "You picked ${r.picked?.let { r.mapping[it].label }}.",
                            style = SableType.headline, color = SableColors.textPrimary, modifier = Modifier.padding(top = Space.m),
                        )
                        Row(Modifier.padding(top = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Pill("New round", selected = true) {
                                scope.launch {
                                    end()
                                    val (songs, items) = buildAbSequence(context, rows)
                                    if (songs.size < 3) return@launch
                                    val nr = AbRound(songs, AbSession.Condition.entries.shuffled())
                                    nr.current = Random.nextInt(3)
                                    AbSession.condition.value = nr.mapping[nr.current].key
                                    round = nr
                                    state.play(items, 0)
                                }
                            }
                            Pill("Done", selected = false) { end() }
                        }
                        Tally(context, tallyVersion)
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
private fun Tally(context: Context, version: Int) {
    val counts = remember(version) { AbSession.Condition.entries.associateWith { AbTally.get(context, it) } }
    val total = counts.values.sum()
    if (total == 0) return
    Spacer(Modifier.height(Space.xl))
    Text("YOUR ROUNDS", style = SableType.overline, color = SableColors.textTertiary)
    for ((c, n) in counts) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(c.label, style = SableType.body, color = SableColors.textSecondary, modifier = Modifier.weight(1f))
            Text("$n of $total", style = SableType.body, color = if (c == AbSession.Condition.SABLE) SableColors.accent else SableColors.textPrimary)
        }
    }
}
