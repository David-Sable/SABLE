package app.sable.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Identity of a track's audio, independent of file name, location and tags (MATH §28 "Cache identity").
 * SHA-256 over the codec type, sample rate, channel count and every compressed audio access unit (bytes and
 * timestamps) as delivered by MediaExtractor. Tag edits, renames and moves keep the hash; any change to the
 * audio changes it. No decoding: this is I/O plus hashing only.
 */
object ContentHasher {
    fun hash(context: Context, uri: Uri): String {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(t)
            val f = ex.getTrackFormat(t)
            val md = MessageDigest.getInstance("SHA-256")
            // A format the platform cannot fully read (e.g. ALAC reported as "audio/unknown" on the A17) lacks these keys:
            // hash with 0 rather than crash.
            md.update("${f.getString(MediaFormat.KEY_MIME)}|${f.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)}|${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)}".toByteArray())
            val buf = ByteBuffer.allocate(1 shl 20)
            val ts = ByteArray(8)
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val time = ex.sampleTime
                for (i in 0 until 8) ts[i] = (time ushr (8 * i)).toByte()
                md.update(ts)
                buf.limit(n)
                md.update(buf)
                ex.advance()
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        } finally {
            ex.release()
        }
    }
}
