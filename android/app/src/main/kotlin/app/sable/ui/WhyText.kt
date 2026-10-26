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
        "ALBUM_PENDING" -> "Album not fully analysed yet, balanced on its own"
        "JIT" -> "Quick estimate from short excerpts"
        "PROVISIONAL" -> "Not analysed yet: started safely quiet"
        "SHORT_FILE" -> "Short file, measured as a whole"
        "UNDEFINED_LOUDNESS" -> "Silent, so left unchanged"
        else -> id.lowercase(Locale.US).replace('_', ' ')
    }

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
        val head = if (albumRun) "Part of the album: one gain for every song keeps its contrast, $verb" else "${characterPhrase(character)}: $verb"
        val clauses = buildList {
            r["INTIMACY"]?.let { add("held ${f(it)} dB under target to keep it quiet") }
            r["PEAK_CAP"]?.let { add("${f(it)} dB short of target so its peaks stay clean") }
            r["SEAM"]?.let { add("nudged ${if (it > 0) "up" else "down"} ${f(it)} dB to flow from the previous song") }
            if ("ALBUM_PENDING" in r) add("balanced on its own until the whole album is analysed")
        }
        return head + clauses.joinToString("") { ", $it" } + "."
    }
}
