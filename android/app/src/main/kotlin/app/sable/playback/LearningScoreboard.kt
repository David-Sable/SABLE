package app.sable.playback

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Where the scoreboard keeps its counters: a private SharedPreferences file on the phone ([PrefsCounterStore]), a map in tests. */
interface CounterStore {
    fun count(key: String): Int
    fun add(key: String, n: Int)
    fun text(key: String): String?
    fun setText(key: String, value: String)
    fun keys(): Set<String>
}

/**
 * The learning scoreboard: a private, on-device tally of whether listener learning (MATH §19) is working. It only counts;
 * it never changes a gain, and nothing leaves the phone. Per week and per place (the output device, §19.3) it keeps:
 *  - plays that could teach (a learning window opened), split into songs Sable already had a lesson for and new songs;
 *  - plays where the listener corrected the volume (a net change of 0.5 dB or more, or a Quieter or Louder tap), same split, and which way;
 *  - what became of those corrections: counted as a lesson, or ignored and why (the same reasons as §19.1);
 *  - plays that could not teach at all (put back where it was left, started mid-song, provisional gain);
 *  - touches that were put back, and touches outside the learning window (approximate: Android does not always report them).
 * The question it answers: do songs Sable has learned get corrected less often than new songs? Is a place leaning one way?
 */
class LearningScoreboard(
    private val store: CounterStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val offsetMs: () -> Long = { TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() },
) {
    fun played(placeId: String, label: String, known: Boolean) = bump(placeId, label, if (known) PLAYS_KNOWN else PLAYS_NEW)

    /** A window closed with a real correction of [up] or down; [known]: the song already had a lesson at this place. */
    fun moved(placeId: String, label: String, known: Boolean, up: Boolean) {
        bump(placeId, label, if (known) MOVED_KNOWN else MOVED_NEW)
        bump(placeId, label, if (up) MOVED_UP else MOVED_DOWN)
    }

    fun undone(placeId: String, label: String) = bump(placeId, label, UNDONE)

    /** What became of a correction: [TAUGHT] or one of the ignored reasons. */
    fun outcome(placeId: String, label: String, kind: String) = bump(placeId, label, if (kind in OUTCOMES) kind else IGN_OTHER)

    /** A play that could not teach: "resumed", "midsong" or "provisional". */
    fun off(placeId: String, label: String, kind: String) =
        bump(placeId, label, when (kind) { "resumed" -> OFF_RESUMED; "midsong" -> OFF_MIDSONG; else -> OFF_PROVISIONAL })

    fun outside(placeId: String, label: String) = bump(placeId, label, OUTSIDE)

    private fun bump(placeId: String, label: String, counter: String) {
        store.add("w${weekOf(nowMs())}|$placeId|$counter", 1)
        if (store.text("label|$placeId") != label) store.setText("label|$placeId", label)
    }

    /** The week (Monday to Sunday, in the phone's time zone) a moment belongs to: weeks since the first Monday of 1970 minus one. */
    private fun weekOf(ms: Long): Long = Math.floorDiv(Math.floorDiv(ms + offsetMs(), DAY_MS) + 3, 7L)

    /** Plain-language lines for the debug command: a legend, the totals per place, then each of the last [weeks] weeks. */
    fun report(weeks: Int = 8): List<String> {
        val cells = HashMap<Pair<Long, String>, MutableMap<String, Int>>()
        for (k in store.keys()) {
            if (!k.startsWith("w")) continue
            val week = k.substringBefore('|').drop(1).toLongOrNull() ?: continue
            val counter = k.substringAfterLast('|')
            val place = k.substringAfter('|').substringBeforeLast('|')
            val n = runCatching { store.count(k) }.getOrDefault(0)
            if (n > 0) cells.getOrPut(week to place) { HashMap() }.merge(counter, n, Int::plus)
        }
        if (cells.isEmpty()) return listOf("SCOREBOARD nothing recorded yet (it counts from the first song played after this version was installed)")
        val lines = ArrayList<String>()
        lines += "SCOREBOARD private tally on this phone. Corrected = volume changed 0.5 dB or more in a song's first 20 s, or Quieter or Louder tapped (once a play). Touches outside the window are approximate."
        val total = HashMap<String, MutableMap<String, Int>>()
        for ((wp, c) in cells) c.forEach { (k, v) -> total.getOrPut(wp.second) { HashMap() }.merge(k, v, Int::plus) }
        for (place in total.keys.sortedByDescending { total.getValue(it).values.sum() }) lines += "SCOREBOARD all weeks · ${labelOf(place)}: ${describe(total.getValue(place))}"
        for (w in cells.keys.map { it.first }.distinct().sortedDescending().take(weeks)) {
            val places = cells.keys.filter { it.first == w }.sortedByDescending { cells.getValue(it).values.sum() }
            for (p in places) lines += "SCOREBOARD week of ${monday(w)} · ${labelOf(p.second)}: ${describe(cells.getValue(p))}"
        }
        return lines
    }

    private fun labelOf(place: String) = store.text("label|$place") ?: place
    private fun monday(week: Long): String = LocalDate.ofEpochDay(week * 7 - 3).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))

    private fun describe(c: Map<String, Int>): String {
        fun n(k: String) = c[k] ?: 0
        fun pct(a: Int, b: Int) = if (b == 0) "-" else "${(100.0 * a / b).roundToInt()}%"
        val plays = n(PLAYS_NEW) + n(PLAYS_KNOWN)
        val moved = n(MOVED_NEW) + n(MOVED_KNOWN)
        val parts = ArrayList<String>()
        if (plays > 0) parts += "$plays plays could teach, $moved corrected (${pct(moved, plays)})"
        if (moved > 0) {
            parts += "corrections: ${n(MOVED_DOWN)} down, ${n(MOVED_UP)} up"
            val ignored = IGNORED.filter { n(it) > 0 }.joinToString(", ") { "${it.removePrefix("ign_")} ${n(it)}" }
            parts += "${n(TAUGHT)} counted as lessons" + if (ignored.isEmpty()) "" else ", ignored: $ignored"
        }
        if (n(PLAYS_KNOWN) > 0) parts += "songs already taught: ${n(MOVED_KNOWN)} of ${n(PLAYS_KNOWN)} corrected (${pct(n(MOVED_KNOWN), n(PLAYS_KNOWN))})"
        if (n(PLAYS_NEW) > 0) parts += "new songs: ${n(MOVED_NEW)} of ${n(PLAYS_NEW)} corrected (${pct(n(MOVED_NEW), n(PLAYS_NEW))})"
        val off = n(OFF_RESUMED) + n(OFF_MIDSONG) + n(OFF_PROVISIONAL)
        if (off > 0) parts += "$off plays could not teach (put back where left ${n(OFF_RESUMED)}, mid-song ${n(OFF_MIDSONG)}, provisional gain ${n(OFF_PROVISIONAL)})"
        if (n(UNDONE) > 0) parts += "${n(UNDONE)} touches put back"
        if (n(OUTSIDE) > 0) parts += "${n(OUTSIDE)} touches outside the window"
        return parts.joinToString("; ")
    }

    companion object {
        /** The file the counters live in: not the learning file, so "Forget" (which clears all learning) leaves the scoreboard alone. */
        const val PREFS = "sable_scoreboard"
        private const val DAY_MS = 86_400_000L
        const val TAUGHT = "taught"
        private const val PLAYS_NEW = "plays_new"
        private const val PLAYS_KNOWN = "plays_known"
        private const val MOVED_NEW = "moved_new"
        private const val MOVED_KNOWN = "moved_known"
        private const val MOVED_UP = "moved_up"
        private const val MOVED_DOWN = "moved_down"
        private const val UNDONE = "undone"
        private const val OUTSIDE = "outside"
        private const val OFF_RESUMED = "off_resumed"
        private const val OFF_MIDSONG = "off_midsong"
        private const val OFF_PROVISIONAL = "off_provisional"
        private const val IGN_OTHER = "ign_other"
        private val IGNORED = listOf("ign_route", "ign_paused", "ign_original", "ign_jump", "ign_section", "ign_capped", "ign_limit", IGN_OTHER)
        private val OUTCOMES = setOf(TAUGHT) + IGNORED
    }
}
