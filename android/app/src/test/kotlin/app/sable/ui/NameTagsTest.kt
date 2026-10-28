package app.sable.ui

import app.sable.data.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameTagsTest {
    private fun row(displayName: String, title: String = displayName.substringBeforeLast('.'), artist: String = "<unknown>", albumArtist: String = "<unknown>") = LibraryRow(
        mediaId = 1, uri = "", relativePath = "Music/", title = title, displayName = displayName, artist = artist, album = "",
        albumArtist = albumArtist, albumId = 1, disc = 1, track = 1, durationMs = 180_000, analysed = true, gainStandardDb = null, character = null,
    )

    @Test fun readsArtistAndTitleFromATaglessName() {
        val r = row("Tinashe - Brainrot [Official Audio].mp3")
        assertEquals("Tinashe" to "Brainrot", nameTags(r))
        assertEquals("Brainrot", titleOf(r))
        assertEquals("Tinashe", artistOf(r))
        assertEquals("Tinashe", songSubtitle(r))
    }

    @Test fun dropsOnlyTheTrailingBracket() = assertEquals("A" to "Song (Part 2)", nameTags(row("A - Song (Part 2) (Lyrics).flac")))

    @Test fun tagsWinOverTheName() {
        assertNull(nameTags(row("Tinashe - Brainrot.mp3", artist = "Someone Else")))
        assertNull(nameTags(row("Tinashe - Brainrot.mp3", title = "Real Title")))
        assertEquals("Real Title", titleOf(row("Tinashe - Brainrot.mp3", title = "Real Title")))
    }

    @Test fun aNameWithoutADashIsLeftAlone() {
        val r = row("Brainrot.mp3")
        assertNull(nameTags(r))
        assertEquals("Brainrot", titleOf(r))
        assertEquals("Unknown artist", artistOf(r))
    }

    @Test fun emptySidesAreRejected() = assertNull(nameTags(row(" - Brainrot.mp3")))
}
