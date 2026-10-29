package app.sable.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A library item from MediaStore. contentHash links it to its analysis (moves/renames keep the hash). */
@Entity(tableName = "tracks", indices = [Index("contentHash"), Index("albumId")])
data class TrackEntity(
    @PrimaryKey val mediaId: Long,
    val uri: String,
    val relativePath: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val albumId: Long,
    val disc: Int,
    val track: Int,
    val durationMs: Long,
    val mime: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val contentHash: String?,
)

/** Analysis cache keyed by audio content. Invalidated when mathVersion changes (MATH §25.8). */
@Entity(tableName = "analysis")
data class AnalysisEntity(
    @PrimaryKey val contentHash: String,
    val mathVersion: String,
    val json: String,
    val integratedLufs: Double?,
    val gainStandardDb: Double?,
    val gainQuietDb: Double?,
    val character: String,
    val decodeClipped: Boolean,
    val analyzedAt: Long,
    val decodeMs: Long,
    val engineMs: Long,
)

data class TrackWithAnalysis(
    val mediaId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mime: String,
    val contentHash: String?,
    val integratedLufs: Double?,
    val gainStandardDb: Double?,
    val gainQuietDb: Double?,
    val character: String?,
    val decodeMs: Long?,
    val engineMs: Long?,
)

data class LibraryRow(
    val mediaId: Long,
    val uri: String,
    val relativePath: String,
    val title: String,
    val displayName: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumId: Long,
    val disc: Int,
    val track: Int,
    val durationMs: Long,
    val analysed: Boolean,
    val gainStandardDb: Double?,
    val character: String?,
)

@Dao
interface LibraryDao {
    @Upsert suspend fun upsertTracks(tracks: List<TrackEntity>)
    @Query("SELECT * FROM tracks") suspend fun allTracks(): List<TrackEntity>
    @Query("DELETE FROM tracks WHERE mediaId NOT IN (:keep)") suspend fun deleteMissing(keep: List<Long>)
    @Query("UPDATE tracks SET contentHash = :hash WHERE mediaId = :id") suspend fun setHash(id: Long, hash: String)
    @Query("SELECT * FROM tracks WHERE mediaId = :id") suspend fun track(id: Long): TrackEntity?
    @Query("SELECT * FROM tracks WHERE albumId = :albumId AND albumArtist = :albumArtist ORDER BY disc, track, title")
    suspend fun albumTracks(albumId: Long, albumArtist: String): List<TrackEntity>

    /** The library for the player UI: every track with its album placement and whether it is analysed. */
    @Query(
        """SELECT t.mediaId, t.uri, t.relativePath, t.title, t.displayName, t.artist, t.album, t.albumArtist, t.albumId, t.disc, t.track, t.durationMs,
           a.contentHash IS NOT NULL AS analysed, a.gainStandardDb, a.character
           FROM tracks t LEFT JOIN analysis a ON a.contentHash = t.contentHash AND a.mathVersion = :math
           ORDER BY t.albumArtist COLLATE NOCASE, t.album COLLATE NOCASE, t.disc, t.track, t.title"""
    )
    fun observeRows(math: String): Flow<List<LibraryRow>>

    @Query(
        """SELECT a.* FROM tracks t JOIN analysis a ON a.contentHash = t.contentHash AND a.mathVersion = :math
           WHERE t.mediaId = :mediaId"""
    )
    suspend fun analysisForTrack(mediaId: Long, math: String): AnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAnalysis(a: AnalysisEntity)
    @Query("SELECT * FROM analysis WHERE contentHash = :hash AND mathVersion = :math") suspend fun analysis(hash: String, math: String): AnalysisEntity?

    @Query(
        """SELECT t.mediaId, t.title, t.artist, t.album, t.durationMs, t.mime, t.contentHash,
           a.integratedLufs, a.gainStandardDb, a.gainQuietDb, a.character, a.decodeMs, a.engineMs
           FROM tracks t LEFT JOIN analysis a ON a.contentHash = t.contentHash AND a.mathVersion = :math
           ORDER BY t.albumArtist, t.album, t.disc, t.track, t.title"""
    )
    fun observeLibrary(math: String): Flow<List<TrackWithAnalysis>>

    @Query(
        """SELECT t.* FROM tracks t LEFT JOIN analysis a ON a.contentHash = t.contentHash AND a.mathVersion = :math
           WHERE t.contentHash IS NULL OR a.contentHash IS NULL ORDER BY t.dateModified DESC"""
    )
    suspend fun pending(math: String): List<TrackEntity>
}

@Database(entities = [TrackEntity::class, AnalysisEntity::class], version = 1, exportSchema = true)
abstract class SableDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        const val MATH_VERSION = "1.9"
        @Volatile private var instance: SableDatabase? = null
        fun get(context: Context): SableDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SableDatabase::class.java, "sable.db").build().also { instance = it }
        }
    }
}
