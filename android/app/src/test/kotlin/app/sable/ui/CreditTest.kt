package app.sable.ui

import app.sable.data.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2026-10-29: David's albums credit features only in the title; the album page showed no artist under each song. */
class CreditTest {
    private fun row(title: String, artist: String) = LibraryRow(
        mediaId = 1, uri = "", relativePath = "Music/", title = title, displayName = "$title.flac", artist = artist, album = "A",
        albumArtist = artist, albumId = 1, disc = 1, track = 1, durationMs = 180_000, analysed = true, gainStandardDb = null, character = null,
    )

    @Test fun featuresMoveFromTheTitleToTheCredit() {
        val r = row("Free The Frail (feat. Helena Deland)", "JPEGMAFIA")
        assertEquals("Free The Frail", songTitle(r))
        assertEquals("JPEGMAFIA feat. Helena Deland", credit(r))
        val s = row("THIS SONG IS A SAFE SPACE (ft. FREAKYMAFIACULT, Black Sheep Refugees)", "JPEGMAFIA")
        assertEquals("THIS SONG IS A SAFE SPACE", songTitle(s))
        assertEquals("JPEGMAFIA feat. FREAKYMAFIACULT, Black Sheep Refugees", credit(s))
        assertEquals("Injury Reserve feat. Zelooperz", credit(row("SS San Francisco (Feat. Zelooperz)", "Injury Reserve")))
        assertEquals("DOTS FREESTYLE REMIX", songTitle(row("DOTS FREESTYLE REMIX [feat. Buzzy Lee & Abdu Ali]", "JPEGMAFIA")))
    }

    /** 2026-10-29: RINA's Tunnel Vision is tagged "Rina Sawayama feat. Shamir & Shamir"; EXPERIMENTAL RAP has "1. 投影の芸術". */
    @Test fun eachNameOnceAndNoTrackNumberInTitles() {
        assertEquals("Rina Sawayama feat. Shamir", credit(row("Tunnel Vision", "Rina Sawayama feat. Shamir & Shamir")))
        assertEquals("Charli XCX & Christine and the Queens", credit(row("Gone", "Charli XCX & Christine and the Queens")))
        assertEquals("JPEGMAFIA x Danny Brown feat. redveil", credit(row("Kingdom Hearts Key (ft. redveil)", "JPEGMAFIA x Danny Brown")))
        assertEquals("A, B", onceEach("A, B, a"))
        assertEquals("投影の芸術", songTitle(row("1. 投影の芸術", "JPEGMAFIA")))
        assertEquals("1999", songTitle(row("1999", "Charli XCX & Troye Sivan")))
        assertEquals("10-20-40", songTitle(row("10-20-40", "Rina Sawayama")))
    }

    @Test fun plainSongsKeepTheirArtist() {
        val r = row("Hustle Bones", "Death Grips")
        assertEquals("Hustle Bones", songTitle(r))
        assertEquals("Death Grips", credit(r))
        assertNull(featuredOf("Featherweight (Remix)"))
        // Already credited in the artist tag: not said twice.
        assertEquals("Injury Reserve, Freddie Gibbs", credit(row("Wax On (Feat. Freddie Gibbs)", "Injury Reserve, Freddie Gibbs")))
    }
}
