package app.sable.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import app.sable.playback.LearningScoreboard
import app.sable.playback.PlaybackPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One file holding everything Sable knows about you: settings, what it learned (per song, artist and place), the learning
 * scoreboard, listening history, favourites and playlists. Saved to Downloads/Sable, so it survives a reset or moves to a
 * new phone. Songs are matched by their audio (content hash), then by title, artist and album, so they are found again
 * even when the new phone numbers them differently. Never holds audio.
 */
object Backup {
    private const val FORMAT = 1
    private val PREFS = listOf("sable", "sable_learning", LearningScoreboard.PREFS, "sable_player")
    private const val AUTO_PREFIX = "sable-auto-"
    private const val AUTO_KEPT = 4
    private const val FOLDER = "Sable"

    /** When anything was last saved (ms), 0 if never. Kept apart from the backed-up prefs, so a restore does not rewind it. */
    fun lastSavedAt(context: Context) = context.getSharedPreferences("sable_backup", Context.MODE_PRIVATE).getLong("lastAt", 0L)

    /** Saves the backup; returns where it went, for the screen. [auto]: the weekly copy (BackupWorker), of which the newest few stay. */
    suspend fun save(context: Context, auto: Boolean = false): String = withContext(Dispatchers.IO) {
        val db = SableDatabase.get(context)
        val plays = db.plays().all()
        val favs = db.favorites().all()
        val lists = db.playlists().allPlaylists()
        val entries = db.playlists().allEntries().groupBy { it.playlistId }
        val tracks = db.library().allTracks().associateBy { it.mediaId }
        val used = (plays.map { it.trackId } + favs.map { it.trackId } + entries.values.flatten().map { it.trackId }).toSet()

        val root = JSONObject().put("format", FORMAT).put("savedAt", System.currentTimeMillis())
        root.put("prefs", JSONObject().apply { for (name in PREFS) put(name, prefsToJson(context, name)) })
        root.put("tracks", JSONObject().apply {
            for (id in used) tracks[id]?.let { t ->
                put(id.toString(), JSONObject().put("hash", t.contentHash ?: "").put("title", t.title).put("artist", t.artist).put("album", t.album))
            }
        })
        root.put("plays", JSONArray().apply { plays.forEach { put(JSONObject().put("track", it.trackId).put("at", it.playedAt).put("album", it.albumWorld)) } })
        root.put("favorites", JSONArray().apply { favs.forEach { put(JSONObject().put("track", it.trackId).put("at", it.addedAt)) } })
        root.put("playlists", JSONArray().apply {
            for (p in lists) put(JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("tracks", JSONArray().apply {
                entries[p.id].orEmpty().forEach { put(JSONObject().put("track", it.trackId).put("pos", it.position)) }
            }))
        })

        val name = (if (auto) AUTO_PREFIX else "sable-backup-") + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("could not create the file")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(root.toString().toByteArray()) }
        context.getSharedPreferences("sable_backup", Context.MODE_PRIVATE).edit().putLong("lastAt", System.currentTimeMillis()).apply()
        if (auto) pruneAuto(context)
        "Downloads/$FOLDER/$name"
    }

    /** Only the weekly copies, and only the older ones; a backup saved by hand is never touched. */
    private fun pruneAuto(context: Context) {
        val found = ArrayList<Pair<String, Uri>>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$AUTO_PREFIX%"), null,
        )?.use { c ->
            while (c.moveToNext()) found += c.getString(1) to android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
        }
        found.sortedByDescending { it.first }.drop(AUTO_KEPT).forEach { (_, uri) -> runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    /** Replaces settings, learning, history, favourites and playlists with the backup's; returns a short summary. */
    suspend fun restore(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val root = JSONObject(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
        require(root.optInt("format") == FORMAT) { "not a Sable backup" }
        val db = SableDatabase.get(context)

        // Old track numbers -> this phone's, by audio first, then by tags.
        val here = db.library().allTracks()
        val byHash = here.filter { it.contentHash != null }.associateBy { it.contentHash!! }
        val byTags = here.associateBy { tagKey(it.title, it.artist, it.album) }
        val saved = root.getJSONObject("tracks")
        val map = HashMap<Long, Long>()
        for (k in saved.keys()) {
            val t = saved.getJSONObject(k)
            val found = byHash[t.optString("hash")] ?: byTags[tagKey(t.optString("title"), t.optString("artist"), t.optString("album"))]
            if (found != null) map[k.toLong()] = found.mediaId
        }

        val prefs = root.getJSONObject("prefs")
        for (name in PREFS) prefs.optJSONObject(name)?.let { jsonToPrefs(context, name, it) }
        PlaybackPrefs.reload(context)

        val plays = root.getJSONArray("plays").objects().mapNotNull { o ->
            map[o.getLong("track")]?.let { PlayEntity(trackId = it, playedAt = o.getLong("at"), albumWorld = o.optBoolean("album")) }
        }
        val favs = root.getJSONArray("favorites").objects().mapNotNull { o -> map[o.getLong("track")]?.let { FavoriteEntity(it, o.getLong("at")) } }
        val lists = root.getJSONArray("playlists").objects()
        db.withTransaction {
            db.plays().clear(); db.plays().addAll(plays)
            db.favorites().clear(); favs.forEach { db.favorites().add(it) }
            db.playlists().clearEntries(); db.playlists().clearPlaylists()
            for (o in lists) {
                val id = db.playlists().create(PlaylistEntity(name = o.getString("name"), createdAt = o.getLong("createdAt")))
                for (e in o.getJSONArray("tracks").objects()) map[e.getLong("track")]?.let {
                    db.playlists().add(PlaylistTrackEntity(playlistId = id, trackId = it, position = e.getLong("pos")))
                }
            }
        }
        val missing = saved.length() - map.size
        "Restored: ${favs.size} favourites, ${lists.size} playlists, ${plays.size} plays and everything learned" +
            if (missing > 0) " ($missing songs are not on this phone)" else ""
    }

    private fun tagKey(title: String, artist: String, album: String) = "${title.trim()}|${artist.trim()}|${album.trim()}".lowercase()

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun prefsToJson(context: Context, name: String) = JSONObject().apply {
        for ((k, v) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            val (type, value) = when (v) {
                is String -> "s" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is Boolean -> "b" to v
                is Set<*> -> "set" to JSONArray(v.map { it.toString() })
                else -> continue
            }
            put(k, JSONObject().put("t", type).put("v", value))
        }
    }

    private fun jsonToPrefs(context: Context, name: String, o: JSONObject) {
        val ed = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            when (e.getString("t")) {
                "s" -> ed.putString(k, e.getString("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "set" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        ed.commit()
    }
}
