package app.sable.ui

import java.text.Normalizer
import app.sable.data.LibraryRow

/** Lower case, accents removed ("Beyoncé" finds "beyonce"), punctuation as spaces: the form both sides are compared in. */
fun searchForm(s: String): String {
    val plain = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    return plain.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

data class ArtistHit(val name: String, val albums: Int, val songs: Int)
data class SearchResults(val artists: List<ArtistHit>, val albums: List<AlbumGroup>, val songs: List<LibraryRow>) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/**
 * Library search, on the phone only. Every word typed must match: as the start of a word (fast to type: "atro ex"
 * finds "Atrocity Exhibition") or, from three letters on, anywhere in a word. Results that start with the whole query
 * come first, then matches in the name itself, then matches that need the artist or album too.
 */
class SearchIndex(rows: List<LibraryRow>, albums: List<AlbumGroup>) {
    private class Entry<T>(val item: T, val name: String, val all: String, val sortKey: String)

    private val artists = albums.groupBy { it.artist }.map { (name, list) ->
        val f = searchForm(name)
        Entry(ArtistHit(name, list.size, list.sumOf { it.tracks.size }), f, f, name.lowercase())
    }
    private val albumEntries = albums.map { a -> Entry(a, searchForm(a.title), searchForm("${a.title} ${a.artist}"), a.title.lowercase()) }
    private val songs = rows.map { r ->
        Entry(r, searchForm(titleOf(r)), searchForm("${titleOf(r)} ${clean(r.artist)} ${clean(r.albumArtist)} ${clean(r.album)}"), titleOf(r).lowercase())
    }

    fun search(query: String): SearchResults {
        val q = searchForm(query)
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        val words = q.split(' ')
        return SearchResults(rank(artists, q, words), rank(albumEntries, q, words), rank(songs, q, words))
    }

    private fun <T> rank(entries: List<Entry<T>>, q: String, words: List<String>): List<T> =
        entries.mapNotNull { e -> score(e, q, words)?.let { it to e } }
            .sortedWith(compareBy({ it.first }, { it.second.sortKey }))
            .map { it.second.item }

    private fun score(e: Entry<*>, q: String, words: List<String>): Int? = when {
        e.name.startsWith(q) -> 0
        words.all { matches(e.name, it) } -> 1
        words.all { matches(e.all, it) } -> 2
        else -> null
    }

    private fun matches(text: String, word: String): Boolean =
        text.startsWith(word) || text.contains(" $word") || (word.length >= 3 && text.contains(word))
}
