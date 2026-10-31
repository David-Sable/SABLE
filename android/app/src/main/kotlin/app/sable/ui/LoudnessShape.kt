package app.sable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.sable.ui.theme.SableColors
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.pow

private val MoodIvory = androidx.compose.ui.graphics.Color(0xFFE8E1D3)  // the accent
// The four corners of the mood map (MATH §8.5), muted so they sit with the ivory; none is brass, which stays the play button's.
private val MoodSlate = Color(0xFF7B93B8)  // quiet and dark
private val MoodSage = Color(0xFFA3BE9E)   // quiet and bright
private val MoodPlum = Color(0xFFB084AC)   // loud and dark
private val MoodClay = Color(0xFFDEA07A)   // loud and bright
private const val MoodColourGain = 1.7f    // a dense song's tone stays near the middle: lean on it a little
private const val MoodLevelHalfDb = 6f

/** The engine's view of a song: short-term loudness every 0.5 s and the anchor (the body Sable balances to). */
data class SongShape(val lufs: List<Double?>, val anchor: List<Pair<Double, Double>>, val durationSec: Double, val tilt: List<Double?> = emptyList(),
                     val bodyLufs: Double? = null) {
    companion object {
        fun of(json: JSONObject?): SongShape? {
            json ?: return null
            val s = json.optJSONArray("shape") ?: return null
            val l = List(s.length()) { i -> if (s.isNull(i)) null else s.optDouble(i) }
            val a = json.optJSONArray("anchor")
            val anchor = if (a == null) emptyList() else List(a.length()) { i -> a.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }
            val fs = json.optDouble("sampleRateHz", 0.0)
            val dur = if (fs > 0) json.optDouble("frames", 0.0) / fs else l.size * 0.5
            val tl = json.optJSONArray("shapeTilt")  // MATH §8.5 (analysis 1.9 and later): the song's brightness every 0.5 s
            val tilt = if (tl == null) emptyList() else List(tl.length()) { i -> if (tl.isNull(i)) null else tl.optDouble(i) }
            // The anchor's loudness as Sable balanced it (perceptual figure first, §16.2), for the thoughts line.
            val body = listOf("anchorPle", "anchorLufs").firstNotNullOfOrNull { k -> json.optDouble(k, Double.NaN).takeIf { it.isFinite() } }
            return SongShape(l, anchor, dur, tilt, body)
        }
    }
}

/** The bottom and top of the silhouette in LUFS: the song's own range (10th percentile minus a margin, at most 24 LU deep) so dense masters still show shape. */
private fun silhouetteRange(shape: SongShape): Pair<Double, Double> {
    val sorted = shape.lufs.filterNotNull().filter { !it.isNaN() }.sorted()
    val top = sorted.lastOrNull() ?: -10.0
    val p10 = sorted.getOrNull(sorted.size / 10) ?: (top - 24.0)
    return maxOf(top - 24.0, minOf(p10 - 4.0, top - 8.0)) to top
}

/** How many LU the silhouette's full height stands for. */
internal fun silhouetteSpan(shape: SongShape): Double = silhouetteRange(shape).let { (floor, top) -> top - floor }

/** The silhouette's heights 0..1, one per 0.5 s. */
internal fun silhouetteHeights(shape: SongShape): List<Float> {
    val vals = shape.lufs.map { it ?: Double.NaN }
    val (floor, top) = silhouetteRange(shape)
    // Normalised heights 0..1, lightly smoothed (3-point) so the silhouette reads as a shape, not a measurement.
    val h = vals.map { v -> if (v.isNaN()) 0f else ((v - floor) / (top - floor)).coerceIn(0.0, 1.0).toFloat() }
    return List(h.size) { i -> (h[maxOf(0, i - 1)] + 2 * h[i] + h[minOf(h.size - 1, i + 1)]) / 4f }
}

/**
 * The song's mood over time (display only; MATH §8.5): how bright it sounds and how much is going on, one entry per 0.5 s.
 * [tone] is -1 (dark) .. +1 (bright), half against the song's own usual and half against the library, so songs differ from
 * each other as well as inside themselves. [energy] is the silhouette's height smoothed over 4 s, 0..1. [level] is that height
 * in dB from the song's middle. [captions] are already held, so a word stays long enough to read.
 */
class SongMood(val tone: FloatArray, val energy: FloatArray, val level: FloatArray, private val captions: Array<String>) {
    fun at(i: Int) = i.coerceIn(0, tone.size - 1)
    fun tint(i: Int): Color {
        val k = at(i)
        val x = (tone[k] * MoodColourGain).coerceIn(-1f, 1f)
        val y = (level[k] / MoodLevelHalfDb).coerceIn(-1f, 1f)
        val corner = lerp(lerp(MoodSlate, MoodSage, (x + 1f) / 2f), lerp(MoodPlum, MoodClay, (x + 1f) / 2f), (y + 1f) / 2f)
        return lerp(MoodIvory, corner, minOf(0.95f, maxOf(abs(x), abs(y)).pow(0.6f)))
    }
    /** Two plain words for the moment, e.g. "rising · warm". */
    fun caption(i: Int): String = captions[at(i)]
    companion object {
        fun of(shape: SongShape): SongMood? {
            val h = silhouetteHeights(shape)
            val n = h.size
            if (n < 8 || shape.tilt.none { it?.isFinite() == true }) return null
            fun around(i: Int, r: Int, f: (Int) -> Double?): Double? =
                (maxOf(0, i - r)..minOf(n - 1, i + r)).mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
            val smooth = List(n) { i -> around(i, 8) { shape.tilt.getOrNull(it)?.takeIf { v -> v.isFinite() } } }  // +-4 s
            val sorted = smooth.filterNotNull().sorted()
            if (sorted.isEmpty()) return null
            val med = sorted[sorted.size / 2]
            val spread = maxOf(1.0, (sorted[sorted.size * 9 / 10] - sorted[sorted.size / 10]) / 2.0)  // a flat song stays near the middle
            val tone = FloatArray(n) { i ->
                smooth[i]?.let {
                    val own = ((it - med) / spread).coerceIn(-1.0, 1.0)
                    val library = ((it - LIBRARY_TILT) / LIBRARY_TILT_HALF).coerceIn(-1.0, 1.0)
                    (0.55 * own + 0.45 * library).toFloat()
                } ?: 0f
            }
            val energy = FloatArray(n) { i -> (maxOf(0, i - 4)..minOf(n - 1, i + 4)).map { h[it] }.average().toFloat() }
            val mid = energy.sorted()[n / 2]
            val span = silhouetteSpan(shape).toFloat()  // the height's scale in dB
            val level = FloatArray(n) { (energy[it] - mid) * span }
            val move = FloatArray(n) { i ->  // the 6 s ahead against the 6 s behind
                val ahead = (i until minOf(n, i + 12)).map { h[it] }.average().toFloat()
                val behind = if (i == 0) h[0] else (maxOf(0, i - 12) until i).map { h[it] }.average().toFloat()
                (ahead - behind) * span
            }
            val first = held(List(n) { i -> levelWord(level[i], move[i]) })
            val second = held(List(n) { i -> toneWord(tone[i]) })
            return SongMood(tone, energy, level, held(List(n) { "${first[it]} · ${second[it]}" }).toTypedArray())
        }

        private fun levelWord(db: Float, move: Float) = when {
            move > 2f && db < 0.8f -> "rising"
            move < -2f && db > -5f -> "easing"
            db < -5f -> "hushed"
            db < -1.5f -> "soft"
            db > 0.8f -> "full"
            else -> "steady"
        }

        private fun toneWord(t: Float) = when {
            t > 0.4f -> "bright"
            t > 0.12f -> "warm"
            t < -0.4f -> "dark"
            t < -0.12f -> "deep"
            else -> "clear"
        }

        /** A word lasts at least [HOLD] entries (6 s): a shorter run joins the run before it (the first joins the next). */
        internal fun held(words: List<String>): List<String> {
            val runs = ArrayList<Pair<String, Int>>()
            fun add(list: MutableList<Pair<String, Int>>, w: String, k: Int) {
                if (list.lastOrNull()?.first == w) list[list.size - 1] = w to list.last().second + k else list += w to k
            }
            words.forEach { add(runs, it, 1) }
            while (runs.size > 1) {
                val i = runs.indexOfFirst { it.second < HOLD }
                if (i < 0) break
                val j = if (i > 0) i - 1 else 1
                runs[j] = runs[j].first to runs[j].second + runs[i].second
                runs.removeAt(i)
                val joined = ArrayList<Pair<String, Int>>()
                runs.forEach { add(joined, it.first, it.second) }
                runs.clear()
                runs += joined
            }
            return runs.flatMap { (w, k) -> List(k) { w } }
        }

        private const val HOLD = 12
        private const val LIBRARY_TILT = -1.1       // dB/oct: the median song on the owner's 420 songs
        private const val LIBRARY_TILT_HALF = 2.0
    }
}

/** A song's silhouette, its heights and its mood, worked out once (off the main thread) and kept for the songs around now. */
class SongView(val shape: SongShape, val heights: List<Float>, val mood: SongMood?)

/**
 * Now Playing opens with the playing song's picture already worked out: the analysis is read and turned into a [SongView] when
 * the song starts (SableApp), not in the frames of the opening slide (measured on the A17: 8-10 ms in the slide's second frame).
 */
object SongViews {
    private val cache = android.util.LruCache<Long, SongView>(8)
    fun peek(trackId: Long?): SongView? = trackId?.let { cache.get(it) }
    suspend fun load(context: android.content.Context, trackId: Long?): SongView? {
        trackId ?: return null
        peek(trackId)?.let { return it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val json = app.sable.data.SableDatabase.get(context).library().analysisForTrack(trackId, app.sable.data.SableDatabase.MATH_VERSION)?.json
            val shape = json?.let { runCatching { SongShape.of(JSONObject(it)) }.getOrNull() } ?: return@withContext null
            SongView(shape, silhouetteHeights(shape), SongMood.of(shape)).also { cache.put(trackId, it) }
        }
    }
}

/**
 * Beautiful, not technical: a soft loudness silhouette, the anchor glowing in the accent, the played part brighter.
 * [progress] is read at draw time, so the playhead moves at the display rate without recomposition.
 */
@Composable
fun LoudnessShape(shape: SongShape, progress: () -> Float, modifier: Modifier = Modifier, anchorLit: () -> Float = { 0f }, mood: Boolean = false, view: SongView? = null, onSeek: ((Float) -> Unit)? = null) {
    val pts = remember(shape, view) { view?.heights ?: silhouetteHeights(shape) }
    // Mood colours (display only): the silhouette's colour follows the song's brightness (spectral tilt) and level, MATH §8.5.
    // Ivory is the middle, four muted colours the corners. Built once per song; drawing it costs nothing more than the plain fill.
    val moodStops = remember(shape, pts, mood) {
        val m = if (mood) (view?.mood ?: SongMood.of(shape)) else null
        if (m == null || pts.size < 2) null else {
            val n = minOf(48, pts.size)
            Array(n) { s ->
                val i = (s * (pts.size - 1f) / (n - 1f)).toInt()
                s / (n - 1f) to m.tint(i).copy(alpha = 0.42f + 0.50f * m.energy[m.at(i)])  // brighter at the song's peak, dimmer in quiet parts
            }
        }
    }
    // A new song: the silhouette reshapes from the old one into it, on the panel spring (draw phase only); the anchor's glow fades in behind.
    val before = remember { arrayOf<List<Float>?>(null) }
    val from = remember(pts) { before[0]?.takeIf { it !== pts && it.size >= 2 }?.let { old -> FloatArray(pts.size) { i -> old[i * (old.size - 1) / maxOf(1, pts.size - 1)] } } }
    val morph = remember(pts) { androidx.compose.animation.core.Animatable(if (from != null) 0f else 1f) }  // from the first frame: the new shape never flashes
    androidx.compose.runtime.LaunchedEffect(pts) {
        before[0] = pts
        morph.animateTo(1f, app.sable.ui.theme.Motion.panel(0.001f))
    }
    // Under the finger the playhead firms up, so it is clear what is being moved.
    var held by remember { mutableStateOf(false) }
    val firm = androidx.compose.animation.core.animateFloatAsState(if (held) 1f else 0f, app.sable.ui.theme.Motion.page(0.01f), label = "playhead")
    val seekMod = if (onSeek == null) Modifier else Modifier
        .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { held = true }, onDragEnd = { held = false }, onDragCancel = { held = false }) { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) }
        }
    Canvas(modifier.then(seekMod)) {
        if (pts.size < 2) return@Canvas
        val w = size.width
        val hgt = size.height
        val dur = shape.durationSec.coerceAtLeast(0.5)
        val path = Path()
        val stepX = w / (pts.size - 1)
        val m = morph.value
        fun y(i: Int) = hgt - (0.06f + 0.94f * (if (from != null && m < 1f) from[i] + (pts[i] - from[i]) * m else pts[i])) * hgt
        path.moveTo(0f, hgt)
        path.lineTo(0f, y(0))
        for (i in 1 until pts.size) {
            val x0 = (i - 1) * stepX
            val x1 = i * stepX
            path.quadraticTo(x0, y(i - 1), (x0 + x1) / 2, (y(i - 1) + y(i)) / 2)
        }
        path.lineTo(w, y(pts.size - 1))
        path.lineTo(w, hgt)
        path.close()
        // Anchor bands: a soft glow behind the body of the song.
        for ((a, b) in shape.anchor) {
            val x0 = (a / dur * w).toFloat()
            val x1 = (b / dur * w).toFloat()
            drawRect(SableColors.accent.copy(alpha = 0.06f * m), Offset(x0, 0f), Size(x1 - x0, hgt))
        }
        if (moodStops != null) drawPath(path, Brush.horizontalGradient(colorStops = moodStops, startX = 0f, endX = w))
        else drawPath(path, SableColors.textTertiary.copy(alpha = 0.35f))
        for ((a, b) in shape.anchor) {
            clipRect(left = (a / dur * w).toFloat(), right = (b / dur * w).toFloat()) {
                // While the song is in an anchor it glows a little brighter (anchorLit 0..1, eased by the caller).
                val lit = anchorLit()
                // With mood colours the glow is a light veil, or it would paint ivory over most of the song (the anchor is 85 % of a typical one).
                if (moodStops != null) drawPath(path, Brush.verticalGradient(listOf(SableColors.accent.copy(alpha = (0.06f + 0.14f * lit) * m), Color.Transparent)))
                else drawPath(path, Brush.verticalGradient(listOf(SableColors.accent.copy(alpha = (0.55f + 0.30f * lit) * m), SableColors.accent.copy(alpha = (0.12f + 0.10f * lit) * m))))
            }
        }
        val px = progress().coerceIn(0f, 1f) * w
        clipRect(right = px) { drawPath(path, SableColors.textPrimary.copy(alpha = if (moodStops != null) 0.14f else 0.22f)) }
        drawLine(SableColors.accent, Offset(px, 0f), Offset(px, hgt), strokeWidth = 2f + 3f * firm.value, cap = StrokeCap.Round)
        drawPath(path, SableColors.line, style = Stroke(1f))
    }
}
