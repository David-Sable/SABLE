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

    @Test fun twoAlbumChangesAndTwoSongChanges_noAlbumTwice() {
        repeat(50) { seed ->
            val c = pickFlowChanges(library, Random(seed))
            assertEquals(AB_CHANGES, c.size)
            assertEquals(2, c.count { it.third })
            for ((x, y, album) in c) {
                assertTrue(x.durationMs > AB_FLOW_MIN_SONG_MS && y.durationMs > AB_FLOW_MIN_SONG_MS && x.analysed && y.analysed)
                if (album) {
                    assertEquals(x.albumId, y.albumId)
                    assertEquals(x.track + 1, y.track)
                    assertTrue(x.albumId in 1L..3L)
                } else assertTrue(x.albumId != y.albumId)
            }
            val albums = c.flatMap { (x, y, album) -> if (album) listOf(x.albumId) else listOf(x.albumId, y.albumId) }
            assertEquals(albums.size, albums.toSet().size)
        }
    }

    @Test fun smallLibrary_givesFewerChanges() {
        assertEquals(0, pickFlowChanges(listOf(row(1, 1)), Random(1)).size)
        assertEquals(1, pickFlowChanges(listOf(row(1, 1), row(1, 2)), Random(1)).size)
    }
}
