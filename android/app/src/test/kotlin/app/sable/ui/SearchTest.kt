package app.sable.ui

import app.sable.data.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private var nextId = 1L
    private fun row(title: String, artist: String, album: String, albumId: Long, track: Int = 1) = LibraryRow(
        mediaId = nextId++, uri = "", relativePath = "Music/", title = title, displayName = "$title.flac", artist = artist, album = album,
        albumArtist = artist, albumId = albumId, disc = 1, track = track, durationMs = 180_000, analysed = true, gainStandardDb = null, character = null,
    )

    private val rows = listOf(
        row("Pneumonia", "Danny Brown", "Atrocity Exhibition", 1, 5),
        row("Ain't It Funny", "Danny Brown", "Atrocity Exhibition", 1, 6),
        row("Halo", "Beyoncé", "I Am... Sasha Fierce", 2),
        row("Heaven", "Brown Sugar Band", "Sweet", 3),
        row("They Said", "Someone", "Other", 4),
    )
    private val index = SearchIndex(rows, albumsOf(rows))

    @Test fun accentsAndCapitalsDoNotMatter() {
        assertEquals("beyonce", searchForm("Beyoncé"))
        assertEquals(listOf("Beyoncé"), index.search("BEYONCE").artists.map { it.name })
        assertEquals(listOf("Halo"), index.search("beyonce").songs.map { it.title })
    }

    @Test fun everyWordMustMatch_asTheStartOfAWord() {
        assertEquals(listOf("Atrocity Exhibition"), index.search("atro ex").albums.map { it.title })
        assertEquals(listOf("Ain't It Funny"), index.search("funny danny").songs.map { it.title })
        // Two letters only match the start of a word: "he" finds "Heaven", not "They".
        assertEquals(listOf("Heaven"), index.search("he").songs.map { it.title })
        // From three letters on, inside a word too.
        assertEquals(listOf("Pneumonia"), index.search("monia").songs.map { it.title })
    }

    @Test fun namesStartingWithTheQueryComeFirst() {
        // "brown": the artist Brown Sugar Band starts with it, Danny Brown only contains it.
        assertEquals(listOf("Brown Sugar Band", "Danny Brown"), index.search("brown").artists.map { it.name })
        // A song whose title matches comes before songs found through their artist.
        assertEquals("Heaven", index.search("heaven").songs.first().title)
    }

    @Test fun emptyAndUnmatched() {
        assertTrue(index.search("   ").isEmpty)
        assertTrue(index.search("zzz").isEmpty)
        assertEquals("ain t it funny", searchForm("Ain't  It Funny!"))
    }
}
