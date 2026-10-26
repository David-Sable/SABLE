package app.sable.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class GainTagsTest {
    private fun le32(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }

    @Test fun flacVorbisComments() {
        val vc = ByteArrayOutputStream()
        val vendor = "ref".toByteArray()
        le32(vc, vendor.size); vc.write(vendor)
        val c = listOf("TITLE=x", "REPLAYGAIN_TRACK_GAIN=-7.85 dB", "R128_TRACK_GAIN=-1280")
        le32(vc, c.size)
        for (s in c) { val b = s.toByteArray(); le32(vc, b.size); vc.write(b) }
        val body = vc.toByteArray()
        val f = ByteArrayOutputStream()
        f.write("fLaC".toByteArray())
        f.write(0x00); f.write(byteArrayOf(0, 0, 34)); f.write(ByteArray(34))  // STREAMINFO
        f.write(0x84); f.write(byteArrayOf(0, (body.size shr 8).toByte(), body.size.toByte())); f.write(body)  // last, VORBIS_COMMENT
        val tags = GainTags.read(f.toByteArray())
        assertEquals(listOf("REPLAYGAIN_TRACK_GAIN", "R128_TRACK_GAIN"), tags.map { it.name })
        assertEquals("-5.00 dB (R128, to -23 LUFS)", GainTags.describe(tags[1]))
    }

    @Test fun id3v24Txxx() {
        val payload = byteArrayOf(3) + "replaygain_album_gain".toByteArray() + byteArrayOf(0) + "-6.10 dB".toByteArray()
        val frame = ByteArrayOutputStream()
        frame.write("TXXX".toByteArray()); frame.write(byteArrayOf(0, 0, 0, payload.size.toByte())); frame.write(byteArrayOf(0, 0)); frame.write(payload)
        val fb = frame.toByteArray()
        val h = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, fb.size.toByte())
        val tags = GainTags.read(h + fb)
        assertEquals(1, tags.size)
        assertEquals("REPLAYGAIN_ALBUM_GAIN", tags[0].name)
        assertEquals("-6.10 dB", GainTags.describe(tags[0]))
        assertEquals(emptyList<GainTags.Tag>(), GainTags.read("RIFF....".toByteArray()))
    }
}
