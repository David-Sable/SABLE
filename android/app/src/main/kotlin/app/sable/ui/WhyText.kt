package app.sable.ui

import java.util.Locale
import kotlin.math.abs

/** Plain-English explanations for the Why sheet (spec: "Intimate piano piece: raised 3.1 dB, held 1 dB under target..."). */
object WhyText {
    private fun f(x: Double) = String.format(Locale.US, "%.1f", abs(x))

    fun characterPhrase(character: String?): String = when (character) {
        "quiet and intimate" -> "Intimate, quiet piece"
        "build and release" -> "A song that builds and releases"
        "dynamic arc" -> "A song with a wide dynamic arc"
        "sustained and dense" -> "Dense, loud master"
        "steady" -> "Steady song"
        else -> "This song"
    }

    fun ruleLabel(id: String): String = when (id) {
        "ANCHOR_MATCH" -> "Matched the body of the song to the target"
        "ALBUM" -> "One gain for the whole album"
        "PERCEPTUAL_BOUND" -> "Perceived loudness kept within 3 dB of the measurement"
        "ANCHOR_FALLBACK" -> "No clear body found, so the whole song was used"
        "INTIMACY" -> "Kept quieter on purpose (intimate piece)"
        "PEAK_CAP" -> "Held back so peaks stay under the ceiling"
        "LIMITER" -> "Peaks handled by the limiter"
        "SEAM" -> "Nudged to flow from the previous song"
        "FLOW" -> "Runs straight into the next track, so they share the album's level"
        "ALBUM_PENDING" -> "Album not fully analysed yet, balanced on its own"
        "JIT" -> "Quick estimate from short excerpts"
        "PROVISIONAL" -> "Not analysed yet: started safely quiet"
        "SHORT_FILE" -> "Short file, measured as a whole"
        "UNDEFINED_LOUDNESS" -> "Silent, so left unchanged"
        else -> id.lowercase(Locale.US).replace('_', ' ')
    }

    /** What the thoughts line knows about the playing song: the plan, plus the song's own analysis. */
    data class Facts(
        val world: String, val gainDb: Double, val rules: List<Pair<String, Double>>, val source: String,
        val learnedDb: Double = 0.0, val contourOn: Boolean = false, val character: String? = null,
        val modeLabel: String = "Normal", val targetLufs: Double = -14.0, val bodyLufs: Double? = null, val albumRun: Boolean = false,
        val hint: Boolean = true,
    )

    /**
     * Now Playing's thoughts line: what Sable did for this song, one short line at a time, turning slowly through the whole
     * song (first the main reason). Only facts from the plan and the analysis; nothing is shown that did not happen.
     */
    fun thoughts(x: Facts): List<String> {
        if (x.source == "provisional" || x.source == "jit") return listOf(x.world)
        val r = x.rules.toMap()
        // The figure sits right beside this line, so the line gives the direction and the reason, not the number again,
        // and stays within one line on the phone (~42 characters; "a song with a wide dynamic arc" was cut off).
        val verb = when {
            abs(x.gainDb) < 0.05 -> "left as it is"
            x.gainDb > 0 -> "lifted"
            else -> "turned down"
        }
        val why = when {
            "FLOW" in r -> "flows into the next track"
            // A song's own tap lesson moves it off the album's gain (David, 2026-10-31: taps stay per song), so say so.
            x.albumRun && abs(x.learnedDb) >= 0.05 -> "album gain, your ${if (x.learnedDb > 0) "+" else "−"}${f(x.learnedDb)} dB"
            x.albumRun -> "one gain for the album"
            else -> shortCharacter(x.character)
        }
        return buildList {
            add(if (why == null) verb else "$verb · $why")
            // The song's own figure only explains the gain in song world (an album's gain comes from the whole album).
            if (!x.albumRun && x.bodyLufs != null && x.bodyLufs.isFinite())
                add("measures ${n(x.bodyLufs)} LUFS · ${x.modeLabel} aims for ${n(x.targetLufs)}")
            if (x.albumRun) add("keeps the album's own loud and quiet")
            r["INTIMACY"]?.let { add("kept ${f(it)} dB quieter on purpose") }
            r["PEAK_CAP"]?.let { add("peaks kept clean: ${f(it)} dB under the rest") }
            if (abs(x.learnedDb) >= 0.05) add("${if (x.learnedDb > 0) "+" else "−"}${f(x.learnedDb)} dB, as you set it here before")
            if (x.contourOn) add("a little bass kept for quiet listening")
            add(x.world)
            if (x.hint) add("hold the number to hear the original")
        }
    }

    /** The song's character in a few words, for the one-line thoughts (the Why sheet keeps the full sentence). */
    private fun shortCharacter(character: String?): String? = when (character) {
        "quiet and intimate" -> "intimate, quiet piece"
        "build and release" -> "builds and releases"
        "dynamic arc" -> "wide dynamic arc"
        "sustained and dense" -> "dense, loud master"
        "steady" -> "steady song"
        else -> null
    }

    /** A whole-number LUFS figure with a true minus sign: −14, −1.6 → −2. */
    private fun n(x: Double): String = String.format(Locale.US, "%.0f", x).let { if (it.startsWith("-")) "−" + it.drop(1) else it }.let { if (it == "−0") "0" else it }

    /** One sentence for the applied gain and the rules that shaped it. */
    fun sentence(character: String?, gainDb: Double, rules: List<Pair<String, Double>>, source: String, albumRun: Boolean): String {
        val verb = when {
            abs(gainDb) < 0.05 -> "left as it is"
            gainDb > 0 -> "raised ${f(gainDb)} dB"
            else -> "lowered ${f(gainDb)} dB"
        }
        val r = rules.toMap()
        if (source == "provisional") return "Not analysed yet: started ${f(gainDb)} dB down to be safe while Sable listens."
        if (source == "jit") {
            return "Still listening: $verb from a quick estimate of short excerpts." + (r["PEAK_CAP"]?.let { " Held ${f(it)} dB short so peaks stay clean." } ?: "")
        }
        val head = when {
            "FLOW" in r -> "Flows straight into its neighbour on the album, so they share one gain and the join stays seamless: $verb"
            albumRun -> "Part of the album: one gain for every song keeps its contrast, $verb"
            else -> "${characterPhrase(character)}: $verb"
        }
        val clauses = buildList {
            r["INTIMACY"]?.let { add("held ${f(it)} dB under target to keep it quiet") }
            r["PEAK_CAP"]?.let { add("${f(it)} dB short of target so its peaks stay clean") }
            r["SEAM"]?.let { add("nudged ${if (it > 0) "up" else "down"} ${f(it)} dB to flow from the previous song") }
            if ("ALBUM_PENDING" in r) add("balanced on its own until the whole album is analysed")
        }
        return head + clauses.joinToString("") { ", $it" } + "."
    }
}
