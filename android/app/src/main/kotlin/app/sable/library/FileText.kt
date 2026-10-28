package app.sable.library

import java.io.InputStream
import java.nio.charset.Charset

/**
 * Title, artist, album and album artist as the file itself spells them. Android's MediaStore and MediaMetadataRetriever both
 * turn characters outside the Basic Multilingual Plane (emoji) into "?", so a song named with an emoji, as JPEGMAFIA's
 * hundred-points song is, shows as "????". The tag itself holds it intact: FLAC Vorbis comments are UTF-8 and ID3v2
 * declares its encoding. Keys: TITLE, ARTIST, ALBUM, ALBUMARTIST (only those the file has). Other formats give an empty map.
 */
object FileText {
    private const val MAX_BLOCK = 8 shl 20

    fun read(input: InputStream): Map<String, String> = runCatching {
        val magic = ByteArray(4)
        if (!fill(input, magic)) return@runCatching emptyMap()
        when {
            String(magic, Charsets.ISO_8859_1) == "fLaC" -> flac(input)
            String(magic, 0, 3, Charsets.ISO_8859_1) == "ID3" -> id3(input, magic[3].toInt())
            else -> emptyMap()
        }
    }.getOrDefault(emptyMap())

    private fun fill(i: InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) return false; n += r }
        return true
    }

    private fun skip(i: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val s = i.skip(left)
            if (s > 0) left -= s else { val r = i.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt()); if (r < 0) return; left -= r }
        }
    }

    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    private fun be(b: ByteArray, i: Int, n: Int): Int { var v = 0; for (k in 0 until n) v = (v shl 8) or (b[i + k].toInt() and 0xFF); return v }
    private fun syncsafe(b: ByteArray, i: Int) = ((b[i].toInt() and 0x7F) shl 21) or ((b[i + 1].toInt() and 0x7F) shl 14) or
        ((b[i + 2].toInt() and 0x7F) shl 7) or (b[i + 3].toInt() and 0x7F)

    private fun flac(input: InputStream): Map<String, String> {
        val h = ByteArray(4)
        while (fill(input, h)) {
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = be(h, 1, 3)
            if (type == 4) {  // VORBIS_COMMENT
                if (len > MAX_BLOCK) return emptyMap()
                val b = ByteArray(len)
                if (!fill(input, b)) return emptyMap()
                return vorbis(b)
            }
            if (last) break
            skip(input, len.toLong())
        }
        return emptyMap()
    }

    private fun vorbis(b: ByteArray): Map<String, String> {
        var q = 0
        q += 4 + le32(b, q)  // vendor string
        val n = le32(b, q); q += 4
        val out = LinkedHashMap<String, String>()
        repeat(n) {
            val l = le32(b, q); q += 4
            if (l < 0 || q + l > b.size) return out
            val kv = String(b, q, l, Charsets.UTF_8); q += l
            val key = when (kv.substringBefore('=').uppercase()) {
                "TITLE" -> "TITLE"
                "ARTIST" -> "ARTIST"
                "ALBUM" -> "ALBUM"
                "ALBUMARTIST", "ALBUM ARTIST" -> "ALBUMARTIST"
                else -> return@repeat
            }
            out.putIfAbsent(key, kv.substringAfter('='))  // the first of repeated values
        }
        return out
    }

    private fun id3(input: InputStream, ver: Int): Map<String, String> {
        val rest = ByteArray(6)
        if (!fill(input, rest)) return emptyMap()
        val size = syncsafe(rest, 2)
        if (size <= 0 || size > MAX_BLOCK) return emptyMap()
        val b = ByteArray(size)
        if (!fill(input, b)) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var p = 0
        while (p + 10 <= b.size) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val len = if (ver >= 4) syncsafe(b, p + 4) else be(b, p + 4, 4)
            val start = p + 10
            if (len <= 0 || start + len > b.size) break
            val key = when (id) { "TIT2" -> "TITLE"; "TPE1" -> "ARTIST"; "TALB" -> "ALBUM"; "TPE2" -> "ALBUMARTIST"; else -> null }
            if (key != null) {
                val cs: Charset = when (b[start].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
                val text = String(b, start + 1, len - 1, cs).substringBefore('\u0000')
                if (text.isNotEmpty()) out.putIfAbsent(key, text)
            }
            p = start + len
        }
        return out
    }
}
