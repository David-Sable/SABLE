package app.sable.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import app.sable.data.SableDatabase
import app.sable.ui.ArtCache
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * Covers for everything outside the app that shows what is playing: the notification and lock screen (through the media
 * session's artwork), Bluetooth cover art, and Android Auto's browse lists. `content://app.sable.art/track/<id>` returns
 * that track's cover as a JPEG (the same cover the app shows). Read-only; covers only, never audio.
 */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException(uri.toString())
        val art = runBlocking {
            val t = SableDatabase.get(ctx).library().track(id) ?: return@runBlocking null
            ArtCache.load(ctx, t.albumId, t.uri, SIZE_PX)
        } ?: throw FileNotFoundException("no cover")
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "sable-art") {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { art.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        }
        return read
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        /** 512 px: sharp on a car screen and the lock screen, small enough to send quickly. */
        private const val SIZE_PX = 512
        fun uriFor(trackId: Long): Uri = Uri.parse("content://app.sable.art/track/$trackId")
    }
}
