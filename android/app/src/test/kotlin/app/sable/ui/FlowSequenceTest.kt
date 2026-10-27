package app.sable.ui

import app.sable.data.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FlowSequenceTest {
    private var nextId = 0L
    private fun row(album: Long, track: Int, durationMs: Long = 200_000, analysed: Boolean = true) = LibraryRow(
        ++nextId, "", "", "t$nextId", "", "artist", "album $album", "artist", album, 1, track, durationMs, analysed, null, null,
    )

    private val library = buildList {
        for (a in 1L..3L) for (t in 1..4) add(row(a, t))        // three complete albums
        for (a in 10L..15L) add(row(a, 1))                      // single songs from six more albums
        add(row(20, 1)); add(row(20, 3))                        // tracks 1 and 3 only: not in a row
        add(row(21, 1)); add(row(21, 2, analysed = false))      // not fully analysed
        add(row(22, 1, durationMs = 30_000)); add(row(22, 2))   // too short
    }

    @Test fun albumRound_isFourAlbumChanges_songRound_isFourBetweenAlbums_noAlbumTwice() {
        val lib = library + (30L..33L).flatMap { a -> (1..3).map { row(a, it) } }  // enough complete albums for 4
        repeat(50) { seed ->
            for (album in listOf(true, false)) {
                val c = pickFlowChanges(lib, album, Random(seed))
                assertEquals(AB_CHANGES, c.size)
                for ((x, y, inAlbum) in c) {
                    assertEquals(album, inAlbum)
                    assertTrue(x.durationMs > AB_FLOW_MIN_SONG_MS && y.durationMs > AB_FLOW_MIN_SONG_MS && x.analysed && y.analysed)
                    if (album) {
                        assertEquals(x.albumId, y.albumId)
                        assertEquals(x.track + 1, y.track)
                        assertTrue(x.albumId in 1L..3L || x.albumId in 30L..33L)
                    } else assertTrue(x.albumId != y.albumId)
                }
                val albums = c.flatMap { (x, y, inAlbum) -> if (inAlbum) listOf(x.albumId) else listOf(x.albumId, y.albumId) }
                assertEquals(albums.size, albums.toSet().size)
            }
        }
    }

    @Test fun smallLibrary_givesFewerChanges() {
        assertEquals(0, pickFlowChanges(listOf(row(1, 1)), true, Random(1)).size)
        assertEquals(1, pickFlowChanges(listOf(row(1, 1), row(1, 2)), true, Random(1)).size)
        assertEquals(1, pickFlowChanges(listOf(row(1, 1), row(2, 1)), false, Random(1)).size)
    }
}
