package app.sable.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import app.sable.data.SableDatabase
import app.sable.data.TrackEntity

/**
 * Mirrors MediaStore's music into the tracks table. A track keeps its content hash while its size and
 * modification time are unchanged; otherwise the hash is recomputed (cheap: no decoding) and, if the audio is
 * the same, the existing analysis is reused (renames/moves never trigger re-analysis).
 */
class LibraryScanner(private val context: Context) {
    private val dao = SableDatabase.get(context).library()
    private val albumTags = context.getSharedPreferences("sable_albumtag", Context.MODE_PRIVATE)

    /**
     * MediaStore names an album after the song's folder when the file has no album tag. An album that is exactly its folder's
     * name is checked against the file's own tag (once per file version), so untagged songs do not become one fake album
     * that "Play album" would balance with one shared gain (MATH §17).
     */
    private fun hasAlbumTag(id: Long, uri: Uri, size: Long, modified: Long): Boolean {
        val key = "$id:$size:$modified"
        albumTags.getString(key, null)?.let { return it == "1" }
        val has = runCatching {
            MediaMetadataRetriever().use { r -> r.setDataSource(context, uri); !r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).isNullOrBlank() }
        }.getOrDefault(true)  // unreadable: leave MediaStore's answer alone
        albumTags.edit().putString(key, if (has) "1" else "0").apply()
        return has
    }

    suspend fun syncMediaStore(): Int {
        val known = dao.allTracks().associateBy { it.mediaId }
        val out = ArrayList<TrackEntity>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DISC_NUMBER, MediaStore.Audio.Media.CD_TRACK_NUMBER, MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val size = c.getLong(11)
                val modified = c.getLong(12)
                val prev = known[id]
                val keepHash = prev?.takeIf { it.sizeBytes == size && it.dateModified == modified }?.contentHash
                val trackNo = c.getString(7)?.substringBefore('/')?.toIntOrNull() ?: (c.getInt(8) % 1000)
                val disc = c.getString(6)?.substringBefore('/')?.toIntOrNull() ?: (c.getInt(8) / 1000).coerceAtLeast(1)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val relPath = c.getString(13) ?: ""
                val msAlbum = c.getString(4) ?: ""
                val album = if (msAlbum.isNotBlank() && msAlbum.equals(relPath.trimEnd('/').substringAfterLast('/'), ignoreCase = true) && !hasAlbumTag(id, uri, size, modified)) "" else msAlbum
                out += TrackEntity(
                    mediaId = id,
                    uri = uri.toString(),
                    relativePath = relPath,
                    displayName = c.getString(14) ?: "",
                    title = c.getString(1) ?: "",
                    artist = c.getString(2) ?: "",
                    albumArtist = c.getString(3) ?: (c.getString(2) ?: ""),
                    album = album,
                    albumId = c.getLong(5),
                    disc = disc,
                    track = trackNo,
                    durationMs = c.getLong(9),
                    mime = c.getString(10) ?: "",
                    sizeBytes = size,
                    dateModified = modified,
                    contentHash = keepHash,
                )
            }
        }
        dao.upsertTracks(out)
        dao.deleteMissing(out.map { it.mediaId })
        return out.size
    }
}
