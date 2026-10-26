package app.sable.library

import java.nio.charset.Charset

/**
 * Existing loudness tags in a file (MATH §28): ReplayGain and R128, read only to show next to Sable's decision on the
 * Why sheet. Never used for the gain, never written. FLAC Vorbis comments and ID3v2.3/2.4 TXXX frames.
 */
object GainTags {
    data class Tag(val name: String, val value: String)

    private val wanted = setOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_ALBUM_GAIN", "R128_TRACK_GAIN", "R128_ALBUM_GAIN")

    fun read(head: ByteArray): List<Tag> = runCatching {
        when {
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> flac(head)
            head.size >= 10 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> id3(head)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** Human-readable value: R128 tags are Q7.8 dB relative to -23 LUFS. */
    fun describe(t: Tag): String = when (t.name) {
        "R128_TRACK_GAIN", "R128_ALBUM_GAIN" -> t.value.trim().toIntOrNull()?.let { "%+.2f dB (R128, to -23 LUFS)".format(it / 256.0) } ?: t.value
        else -> t.value.trim()
    }

    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    private fun be(b: ByteArray, i: Int, n: Int): Int { var v = 0; for (k in 0 until n) v = (v shl 8) or (b[i + k].toInt() and 0xFF); return v }
    private fun syncsafe(b: ByteArray, i: Int) = ((b[i].toInt() and 0x7F) shl 21) or ((b[i + 1].toInt() and 0x7F) shl 14) or
        ((b[i + 2].toInt() and 0x7F) shl 7) or (b[i + 3].toInt() and 0x7F)

    private fun flac(b: ByteArray): List<Tag> {
        var p = 4
        while (p + 4 <= b.size) {
            val last = (b[p].toInt() and 0x80) != 0
            val type = b[p].toInt() and 0x7F
            val len = be(b, p + 1, 3)
            val start = p + 4
            if (type == 4 && start + len <= b.size) {  // VORBIS_COMMENT
                var q = start
                q += 4 + le32(b, q)  // vendor string
                val n = le32(b, q); q += 4
                val out = ArrayList<Tag>()
                repeat(n) {
                    val l = le32(b, q); q += 4
                    if (l < 0 || q + l > b.size) return out
                    val kv = String(b, q, l, Charsets.UTF_8); q += l
                    val k = kv.substringBefore('=').uppercase()
                    if (k in wanted) out += Tag(k, kv.substringAfter('='))
                }
                return out
            }
            if (last) break
            p = start + len
        }
        return emptyList()
    }

    private fun id3(b: ByteArray): List<Tag> {
        val ver = b[3].toInt()
        val size = syncsafe(b, 6)
        val end = minOf(b.size, 10 + size)
        var p = 10
        val out = ArrayList<Tag>()
        while (p + 10 <= end) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val len = if (ver >= 4) syncsafe(b, p + 4) else be(b, p + 4, 4)
            val start = p + 10
            if (len <= 0 || start + len > end) break
            if (id == "TXXX" && len > 1) {
                val enc = b[start].toInt()
                val cs: Charset = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
                val text = String(b, start + 1, len - 1, cs)
                val parts = text.split('\u0000').filter { it.isNotEmpty() }
                if (parts.size >= 2 && parts[0].uppercase() in wanted) out += Tag(parts[0].uppercase(), parts[1])
            }
            p = start + len
        }
        return out
    }
}
