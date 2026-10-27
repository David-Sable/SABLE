package app.sable.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WhyTextTest {
    @Test fun intimatePiece_readsLikeTheSpec() {
        assertEquals(
            "Intimate, quiet piece: raised 3.1 dB, held 1.0 dB under target to keep it quiet.",
            WhyText.sentence("quiet and intimate", 3.1, listOf("ANCHOR_MATCH" to 4.1, "INTIMACY" to -1.0), "full", albumRun = false),
        )
    }

    @Test fun cappedAlbumAndSeam() {
        assertEquals(
            "Part of the album: one gain for every song keeps its contrast, lowered 1.3 dB, 1.6 dB short of target so its peaks stay clean.",
            WhyText.sentence("steady", -1.34, listOf("ALBUM" to 0.21, "PEAK_CAP" to -1.56), "full", albumRun = true),
        )
        assertEquals(
            "Dense, loud master: lowered 8.0 dB, nudged up 0.4 dB to flow from the previous song.",
            WhyText.sentence("sustained and dense", -8.0, listOf("ANCHOR_MATCH" to -8.4, "SEAM" to 0.4), "full", albumRun = false),
        )
    }

    @Test fun provisionalAndJit() {
        assertEquals("Not analysed yet: started 6.0 dB down to be safe while Sable listens.", WhyText.sentence(null, -6.0, emptyList(), "provisional", false))
        assertEquals("Still listening: raised 1.4 dB from a quick estimate of short excerpts. Held 2.1 dB short so peaks stay clean.",
            WhyText.sentence(null, 1.42, listOf("JIT" to 1.42, "PEAK_CAP" to -2.09), "jit", false))
    }

    @Test fun flowingTracks_sayWhyTheyShareTheAlbumGain() {
        assertEquals(
            "Flows straight into its neighbour on the album, so they share one gain and the join stays seamless: lowered 4.2 dB.",
            WhyText.sentence("steady", -4.2, listOf("ALBUM" to -4.2, "FLOW" to 0.0), "full", albumRun = true),
        )
    }
}
