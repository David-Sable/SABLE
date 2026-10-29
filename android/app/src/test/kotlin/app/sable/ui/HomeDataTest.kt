package app.sable.ui

import app.sable.data.LibraryRow
import app.sable.data.PlayEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDataTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun row(id: Long, albumId: Long, album: String) = LibraryRow(
        mediaId = id, uri = "", relativePath = "Music/", title = "Song $id", displayName = "$id.flac", artist = "Artist", album = album,
        albumArtist = "Artist", albumId = albumId, disc = 1, track = id.toInt(), durationMs = 180_000, analysed = true, gainStandardDb = null, character = null,
    )

    private val rows = listOf(row(1, 10, "A"), row(2, 10, "A"), row(3, 20, "B"), row(4, 30, "C"), row(5, 40, "D"))

    private fun play(track: Long, daysAgo: Long) = PlayEntity(trackId = track, playedAt = now - daysAgo * day, albumWorld = false)

    @Test fun emptyHistoryIsEmpty() = assertTrue(HomeData.of(emptyList(), rows, now).isEmpty)

    private fun albumPlay(track: Long, daysAgo: Long) = play(track, daysAgo).copy(albumWorld = true)
    private fun names(r: List<Recent>) = r.map { when (it) { is RecentAlbum -> "album ${it.album.title}"; is RecentSong -> "song ${it.row.mediaId}" } }

    @Test fun recentAlbumsInOrderOfLastPlay() {
        val plays = listOf(albumPlay(3, 0), albumPlay(1, 1), albumPlay(2, 2), albumPlay(3, 3))  // newest first, as the database gives them
        assertEquals(listOf("album B", "album A"), names(HomeData.of(plays, rows, now).recent))
    }

    /** 2026-10-29: songs played on shuffle showed only as their albums, so a song played once was nowhere on Home. */
    @Test fun songsPlayedOnTheirOwnShowAsSongs() {
        val plays = listOf(play(2, 0), play(1, 1), albumPlay(3, 2), play(2, 3))
        assertEquals(listOf("song 2", "song 1", "album B"), names(HomeData.of(plays, rows, now).recent))
    }

    @Test fun mostPlayedNeedsTwoPlaysInThirtyDays() {
        val plays = listOf(play(1, 1), play(1, 2), play(1, 3), play(2, 1), play(3, 1), play(3, 2), play(4, 40), play(4, 41))
        val most = HomeData.of(plays, rows, now).most.map { it.first.mediaId to it.second }
        assertEquals(listOf(1L to 3, 3L to 2), most)
    }

    @Test fun notHeardInAWhileStartsWithTheLongestAgoThenNeverPlayed() {
        val plays = listOf(play(1, 0), play(3, 40), play(4, 60))
        val again = HomeData.of(plays, rows, now).rediscover.map { it.title }
        assertEquals(listOf("C", "B", "D"), again)  // A was played today; D was never played
    }
}
