package app.sable.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.sable.data.SableDatabase
import app.sable.data.TrackEntity

/**
 * What Android Auto (and any other media browser) can browse: Albums, Artists and Songs, plus Shuffle all.
 *
 * Playable ids are queue requests ("<trackId>:<A|S>") with a third part saying where they were picked, so a tap in the car
 * starts the same queue the app would ([expand]): "Play album" is the album world from its first song; a song in an
 * album, or in Songs, is the song world starting at that song, with the songs before it in the queue too (MATH §17: one album
 * gain only when the album is asked for).
 */
object LibraryTree {
    const val ROOT = "root"
    private const val ALBUMS = "albums"
    private const val ARTISTS = "artists"
    private const val SONGS = "songs"
    private const val ALBUM = "album/"    // album/<albumId>
    private const val ARTIST = "artist/"  // artist/<name>
    private const val FROM_ALBUM = "album"
    private const val FROM_SONGS = "songs"
    private const val SHUFFLE = "shuffle"

    /** Auto shows a browsable node's children as a grid with this hint (androidx.media's content style keys). */
    private val grid = Bundle().apply { putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2) }

    fun root(): MediaItem = folder(ROOT, "Sable", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(context: Context, parentId: String): List<MediaItem> {
        val all = SableDatabase.get(context).library().allTracks()
        return when {
            parentId == ROOT -> listOf(
                folder(ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, grid),
                folder(ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder(SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            )
            parentId == ALBUMS -> albums(all)
            parentId == ARTISTS -> all.groupBy(::artistOf).keys.filter { it.isNotBlank() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { folder(ARTIST + it, it, MediaMetadata.MEDIA_TYPE_ARTIST, grid) }
            parentId == SONGS -> {
                val songs = songOrder(all)
                listOfNotNull(songs.firstOrNull()?.let { playable(it, false, SHUFFLE, title = "Shuffle all") }) +
                    songs.map { playable(it, false, FROM_SONGS) }
            }
            parentId.startsWith(ARTIST) -> albums(all.filter { artistOf(it) == parentId.removePrefix(ARTIST) })
            parentId.startsWith(ALBUM) -> {
                val tracks = albumTracks(all, parentId.removePrefix(ALBUM).toLongOrNull())
                listOfNotNull(tracks.firstOrNull()?.let { playable(it, true, FROM_ALBUM, title = "Play album") }) +
                    tracks.map { playable(it, false, FROM_ALBUM) }
            }
            else -> emptyList()
        }
    }

    /**
     * A single item picked in a browser becomes the queue the app would start: the album (album world) for "Play album", the
     * whole album or Songs list starting at that song (song world) for a song, everything shuffled for Shuffle all. Null for anything else
     * (the app's own requests carry no third part and pass through unchanged).
     */
    suspend fun expand(context: Context, requestId: String): Pair<List<String>, Int>? {
        val p = requestId.split(':')
        val from = p.getOrNull(2) ?: return null
        val id = p[0].toLongOrNull() ?: return null
        val all = SableDatabase.get(context).library().allTracks()
        val picked = all.firstOrNull { it.mediaId == id } ?: return null
        return when (from) {
            FROM_ALBUM -> {
                val tracks = albumTracks(all, picked.albumId)
                if (p[1] == "A") tracks.map { QueueId.request(it.mediaId, true) } to 0
                else tracks.map { QueueId.request(it.mediaId, false) } to tracks.indexOfFirst { it.mediaId == id }.coerceAtLeast(0)
            }
            FROM_SONGS -> songOrder(all).let { s -> s.map { QueueId.request(it.mediaId, false) } to s.indexOfFirst { it.mediaId == id }.coerceAtLeast(0) }
            SHUFFLE -> all.shuffled().map { QueueId.request(it.mediaId, false) } to 0
            else -> null
        }
    }

    private fun artistOf(t: TrackEntity) = t.albumArtist.ifBlank { t.artist }

    private fun songOrder(all: List<TrackEntity>) = all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.ifBlank { it.displayName } })

    private fun albumTracks(all: List<TrackEntity>, albumId: Long?) =
        all.filter { it.albumId == albumId }.sortedWith(compareBy({ it.disc }, { it.track }, { it.title }))

    private fun albums(tracks: List<TrackEntity>): List<MediaItem> =
        tracks.groupBy { it.albumId }.values.map { g -> g.minWith(compareBy({ it.disc }, { it.track })) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.album })
            .map { first ->
                MediaItem.Builder().setMediaId(ALBUM + first.albumId).setMediaMetadata(
                    MediaMetadata.Builder().setTitle(first.album.ifBlank { "Unknown album" }).setArtist(artistOf(first))
                        .setArtworkUri(ArtProvider.uriFor(first.mediaId))
                        .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM).build()
                ).build()
            }

    private fun playable(t: TrackEntity, album: Boolean, from: String, title: String? = null) =
        MediaItem.Builder().setMediaId("${QueueId.request(t.mediaId, album)}:$from").setMediaMetadata(
            MediaMetadata.Builder().setTitle(title ?: t.title.ifBlank { t.displayName }).setArtist(t.artist).setAlbumTitle(t.album)
                .setArtworkUri(ArtProvider.uriFor(t.mediaId))
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
        ).build()

    private fun folder(id: String, title: String, type: Int, extras: Bundle? = null) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(type).setExtras(extras).build()
        ).build()
}
