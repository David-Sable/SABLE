package app.sable.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FileTextTest {
    private fun le32(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }

    @Test fun flacKeepsEmoji() {
        val vc = ByteArrayOutputStream()
        val vendor = "ref".toByteArray()
        le32(vc, vendor.size); vc.write(vendor)
        val c = listOf("TITLE=💯", "ARTIST=JPEGMAFIA", "ALBUM=LP!", "ALBUM ARTIST=JPEGMAFIA", "ARTIST=Other")
        le32(vc, c.size)
        for (s in c) { val b = s.toByteArray(Charsets.UTF_8); le32(vc, b.size); vc.write(b) }
        val body = vc.toByteArray()
        val f = ByteArrayOutputStream()
        f.write("fLaC".toByteArray())
        f.write(0x00); f.write(byteArrayOf(0, 0, 34)); f.write(ByteArray(34))  // STREAMINFO, not last
        f.write(0x84); f.write(byteArrayOf(0, (body.size shr 8).toByte(), body.size.toByte())); f.write(body)  // last, VORBIS_COMMENT
        val t = FileText.read(ByteArrayInputStream(f.toByteArray()))
        assertEquals("💯", t["TITLE"])
        assertEquals("JPEGMAFIA", t["ARTIST"])  // the first of repeated values
        assertEquals("LP!", t["ALBUM"])
        assertEquals("JPEGMAFIA", t["ALBUMARTIST"])
    }

    private fun frame(id: String, enc: Int, text: ByteArray, v4: Boolean): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(id.toByteArray()); val len = text.size + 1
        if (v4) { o.write((len shr 21) and 0x7F); o.write((len shr 14) and 0x7F); o.write((len shr 7) and 0x7F); o.write(len and 0x7F) }
        else { o.write(len shr 24); o.write(len shr 16); o.write(len shr 8); o.write(len) }
        o.write(0); o.write(0); o.write(enc); o.write(text)
        return o.toByteArray()
    }

    private fun id3(ver: Int, frames: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("ID3".toByteArray()); o.write(ver); o.write(0); o.write(0)
        val n = frames.size
        o.write((n shr 21) and 0x7F); o.write((n shr 14) and 0x7F); o.write((n shr 7) and 0x7F); o.write(n and 0x7F)
        o.write(frames)
        return o.toByteArray()
    }

    @Test fun id3Utf8AndUtf16() {
        val f = frame("TIT2", 3, "🔥".toByteArray(Charsets.UTF_8), true) + frame("TPE1", 1, "Artist 🥺".toByteArray(Charsets.UTF_16), true)
        val t = FileText.read(ByteArrayInputStream(id3(4, f)))
        assertEquals("🔥", t["TITLE"])
        assertEquals("Artist 🥺", t["ARTIST"])
        val g = frame("TALB", 1, "😱".toByteArray(Charsets.UTF_16), false)
        assertEquals("😱", FileText.read(ByteArrayInputStream(id3(3, g)))["ALBUM"])
    }

    @Test fun otherFormatsAndJunkGiveNothing() {
        assertEquals(emptyMap<String, String>(), FileText.read(ByteArrayInputStream(ByteArray(0))))
        assertEquals(emptyMap<String, String>(), FileText.read(ByteArrayInputStream("OggS....".toByteArray())))
        assertEquals(emptyMap<String, String>(), FileText.read(ByteArrayInputStream("fLaC".toByteArray())))
    }
}
