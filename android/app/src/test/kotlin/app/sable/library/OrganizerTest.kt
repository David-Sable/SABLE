package app.sable.library

import app.sable.data.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2026-10-30: Organize tidies Google Drive downloads into Music/Artist/Album; folder names must be safe and stable. */
class OrganizerTest {
    private fun row(album: String, albumArtist: String, artist: String = albumArtist) = LibraryRow(
        mediaId = 1, uri = "", relativePath = "Download/x/", title = "t", displayName = "t.flac", artist = artist, album = album,
        albumArtist = albumArtist, albumId = 1, disc = 1, track = 1, durationMs = 1, analysed = true, gainStandardDb = null, character = null,
    )

    @Test fun albumFoldersFromTags() {
        assertEquals("Music/JPEGMAFIA/I LAY DOWN MY LIFE FOR YOU - DIRECTOR'S CUT/", Organizer.target(row("I LAY DOWN MY LIFE FOR YOU: DIRECTOR'S CUT", "JPEGMAFIA")))
        assertEquals("Music/Charli XCX/Charli/", Organizer.target(row("Charli", "Charli XCX")))
        assertEquals("Music/AC-DC/Back in Black/", Organizer.target(row("Back in Black", "AC/DC").let { it.copy(albumArtist = "AC-DC") }))
        assertEquals("Music/ACDC/Highway/", Organizer.target(row("Highway", "AC/DC")))
    }

    @Test fun songsWithoutAlbumOrArtist() {
        assertEquals("Music/Tinashe/", Organizer.target(row("", "", "Tinashe")))
        assertEquals("Music/Unknown artist/", Organizer.target(row("<unknown>", "<unknown>", "<unknown>")))
        assertEquals("Music/X/What/", Organizer.target(row("What?...", "X")))
    }
}
