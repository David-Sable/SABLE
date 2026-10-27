package app.sable.ui

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import app.sable.ui.theme.Motion
import app.sable.ui.theme.Radius
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Album art from MediaStore (or the file's embedded picture), cached, faded in slowly. */
object ArtCache {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val missing = HashSet<String>()

    suspend fun load(context: Context, albumId: Long, trackUri: String?, px: Int): ImageBitmap? {
        val key = "$albumId@$px"
        cache.get(key)?.let { return it }
        if (synchronized(missing) { key in missing }) return null
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId), Size(px, px), null)
            }.getOrNull() ?: trackUri?.let { u ->
                runCatching {
                    MediaMetadataRetriever().use { r ->
                        r.setDataSource(context, Uri.parse(u))
                        r.embeddedPicture?.let { b ->
                            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
                            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
                        }
                    }
                }.getOrNull()
            }
        }?.asImageBitmap()?.also { it.prepareToDraw() }  // upload to the GPU now, not in the first frame that shows it
        if (bmp != null) cache.put(key, bmp) else synchronized(missing) { missing.add(key) }
        return bmp
    }
}

@Composable
fun AlbumArt(albumId: Long, trackUri: String?, title: String, size: Dp, modifier: Modifier = Modifier, px: Int = 512) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(null, albumId, px) { value = ArtCache.load(context, albumId, trackUri, px) }
    val shown by animateFloatAsState(if (img != null) 1f else 0f, Motion.slow(), label = "art")
    Box(
        modifier.clip(RoundedCornerShape(Radius.art))
            .background(Brush.linearGradient(listOf(SableColors.surface3, SableColors.surface1))),
        contentAlignment = Alignment.Center,
    ) {
        if (img == null) {
            Text(title.trim().take(1).uppercase(), style = SableType.title.copy(fontSize = SableType.title.fontSize * (size.value / 120f).coerceIn(0.6f, 3f)),
                color = SableColors.textTertiary)
        }
        img?.let { Image(it, null, contentScale = ContentScale.Crop, alpha = shown, modifier = Modifier.matchParentSize()) }  // paint alpha: no offscreen copy
    }
}
