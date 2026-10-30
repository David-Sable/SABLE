package app.sable.library

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.sable.data.LibraryRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tidies the music into Music/Artist/Album/ (David, 2026-10-30: downloads from Google Drive land in messy folders). Only the
 * file's folder changes, through MediaStore (the phone's own index), so the song keeps its id: favourites, playlists and history
 * stay attached, and analysis and learning are keyed by the audio itself. The audio is never touched. Before moving, every
 * file's old folder is written down, so [undo] puts each one back. Android asks the owner once to allow it
 * (MediaStore.createWriteRequest, made by the screen that offers this).
 */
object Organizer {
    data class Move(val mediaId: Long, val uri: Uri, val from: String, val to: String)
    data class Result(val moved: Int, val failed: Int)

    private const val LOG = "organize_log.json"

    /** Where each song belongs: Music/<album artist>/<album>/, or Music/<artist>/ for a song without an album. */
    fun target(r: LibraryRow): String {
        val artist = folder(r.albumArtist).ifBlank { folder(r.artist) }.ifBlank { "Unknown artist" }
        val album = folder(r.album)
        return if (album.isBlank()) "Music/$artist/" else "Music/$artist/$album/"
    }

    /** The songs not yet where they belong (songs on a memory card are left alone). */
    fun plan(rows: List<LibraryRow>): List<Move> = rows.mapNotNull { r ->
        val uri = Uri.parse(r.uri)
        if (MediaStore.getVolumeName(uri) != MediaStore.VOLUME_EXTERNAL_PRIMARY && MediaStore.getVolumeName(uri) != MediaStore.VOLUME_EXTERNAL) return@mapNotNull null
        val to = target(r)
        if (r.relativePath.equals(to, ignoreCase = true)) null else Move(r.mediaId, uri, r.relativePath, to)
    }

    /** Moves them (after the owner allowed it), writing the way back first. */
    fun apply(context: Context, moves: List<Move>): Result {
        writeLog(context, moves)
        return run(context, moves.map { it.uri to it.to })
    }

    /** The last organize, if it can still be undone. */
    fun undoable(context: Context): List<Move> = runCatching {
        val a = JSONArray(File(context.filesDir, LOG).readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Move(it.getLong("id"), Uri.parse(it.getString("uri")), it.getString("from"), it.getString("to")) } }
    }.getOrDefault(emptyList())

    /** Puts every file back where it was before the last organize. */
    fun undo(context: Context, moves: List<Move>): Result =
        run(context, moves.map { it.uri to it.from }).also { if (it.failed == 0) File(context.filesDir, LOG).delete() }

    private fun run(context: Context, moves: List<Pair<Uri, String>>): Result {
        var moved = 0; var failed = 0
        for ((uri, path) in moves) {
            val ok = runCatching {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }, null, null) > 0
            }.getOrDefault(false)
            if (ok) moved++ else failed++
        }
        return Result(moved, failed)
    }

    private fun writeLog(context: Context, moves: List<Move>) {
        val a = JSONArray()
        for (m in moves) a.put(JSONObject().put("id", m.mediaId).put("uri", m.uri.toString()).put("from", m.from).put("to", m.to))
        File(context.filesDir, LOG).writeText(a.toString())
    }

    /** A tag as a folder name: no characters a file system refuses, no "<unknown>", not too long. */
    internal fun folder(tag: String): String = tag.takeIf { it != "<unknown>" }.orEmpty()
        .replace(":", " -").replace(Regex("""[\\/*?"<>|]"""), "").replace(Regex("""\s+"""), " ")
        .trim().trimEnd('.').trim().take(100)
}
