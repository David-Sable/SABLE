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

    /** 2026-10-29: babygirl (one rule) ran out of lines after 12 s; every song now has its reason and its own figure. */
    @Test fun thoughtsSayOnlyWhatHappened() {
        assertEquals(
            listOf("turned down 12.4 dB · dense, loud master", "measures −2 LUFS · Normal aims for −14", "song world · Phone speaker"),
            WhyText.thoughts(WhyText.Facts("song world · Phone speaker", -12.4, listOf("ANCHOR_MATCH" to -12.4), "full",
                character = "sustained and dense", bodyLufs = -1.6, hint = false)),
        )
        assertEquals(
            listOf("lifted 1.2 dB · steady song", "measures −22 LUFS · Quiet aims for −19", "peaks kept clean: 2.4 dB under the rest",
                "+2.0 dB, as you set it here before", "a little bass kept for quiet listening", "song world · JBL", "hold the number to hear the original"),
            WhyText.thoughts(WhyText.Facts("song world · JBL", 1.2, listOf("ANCHOR_MATCH" to 3.6, "PEAK_CAP" to -2.4), "full", learnedDb = 2.0,
                contourOn = true, character = "steady", modeLabel = "Quiet", targetLufs = -19.0, bodyLufs = -22.4)),
        )
        assertEquals(
            listOf("turned down 15.3 dB · flows into its neighbour, one album gain", "keeps the album's own loud and quiet", "flowing album tracks · JBL"),
            WhyText.thoughts(WhyText.Facts("flowing album tracks · JBL", -15.3, listOf("ALBUM" to -15.3, "FLOW" to 0.0), "full",
                bodyLufs = -2.8, albumRun = true, hint = false)),
        )
        assertEquals(listOf("listening…"), WhyText.thoughts(WhyText.Facts("listening…", -6.0, emptyList(), "provisional")))
    }
}
