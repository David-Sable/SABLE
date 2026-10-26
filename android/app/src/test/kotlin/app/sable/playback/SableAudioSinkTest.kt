package app.sable.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

/** M5 exit criterion "gain processor null test", plus item boundaries, ramps and the 16-bit dithered path (MATH §23). */
@UnstableApi
class SableAudioSinkTest {
    /** A fake real sink: records what it is given, optionally takes only `chunk` bytes per call. */
    private class Inner(val acceptsFloat: Boolean = true, val chunk: Int = Int.MAX_VALUE) {
        val received = ArrayList<Byte>()
        var configured: Format? = null
        var calls = 0
        val sink: AudioSink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, m, args ->
            when (m.name) {
                "getFormatSupport" -> {
                    val f = args[0] as Format
                    if (f.pcmEncoding == C.ENCODING_PCM_16BIT || (acceptsFloat && f.pcmEncoding == C.ENCODING_PCM_FLOAT))
                        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
                }
                "configure" -> { configured = (args[0] as AudioSink.AudioSinkConfig).format; null }
                "handleBuffer" -> {
                    calls++
                    val b = args[0] as ByteBuffer
                    val n = minOf(chunk, b.remaining())
                    repeat(n) { received.add(b.get()) }
                    !b.hasRemaining()
                }
                else -> when (m.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as AudioSink

        fun floats(): FloatArray {
            val bb = ByteBuffer.wrap(received.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(received.size / 4) { bb.getFloat() }
        }

        fun shorts(): ShortArray {
            val bb = ByteBuffer.wrap(received.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray(received.size / 2) { bb.getShort() }
        }
    }

    private fun format(encoding: Int, rate: Int = 44100) =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(encoding).setChannelCount(2).setSampleRate(rate).build()

    private fun config(encoding: Int, key: String) =
        AudioSink.AudioSinkConfig.Builder(format(encoding)).setTimeline(Timeline.EMPTY).setMediaPeriodId(MediaPeriodId(key)).build()

    private fun sink(inner: Inner, table: GainTable) = SableAudioSink(inner.sink, table, keyOf = { _, uid -> uid })

    private fun pcm16(n: Int, seed: Int): Pair<ByteBuffer, ShortArray> {
        val r = Random(seed)
        val s = ShortArray(n) { r.nextInt(-32768, 32768).toShort() }
        val b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        s.forEach { b.putShort(it) }
        b.flip()
        return b to s
    }

    private fun pcmFloat(x: FloatArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        b.flip()
        return b
    }

    @Test fun nullTest_0dB_isBitExact_for16BitAndFloat() {
        val t = GainTable().apply { put("A", 0.0, 0.0, false) }
        val inner = Inner()
        val s = sink(inner, t)
        s.configure(config(C.ENCODING_PCM_16BIT, "A"))
        assertEquals(C.ENCODING_PCM_FLOAT, inner.configured!!.pcmEncoding)
        val (b, x) = pcm16(20000, 1)
        assertTrue(s.handleBuffer(b, 0, 1))
        assertEquals(0, b.remaining())
        val y = inner.floats()
        for (i in x.indices) assertEquals(x[i] / 32768f, y[i], 0f)

        val r = Random(2)
        val xf = FloatArray(20000) { (r.nextDouble(-1.5, 1.5)).toFloat() }  // overs pass untouched
        val inner2 = Inner()
        val s2 = sink(inner2, t)
        s2.configure(config(C.ENCODING_PCM_FLOAT, "A"))
        s2.handleBuffer(pcmFloat(xf), 0, 1)
        val yf = inner2.floats()
        for (i in xf.indices) assertEquals(xf[i].toRawBits(), yf[i].toRawBits())
    }

    @Test fun gainIsOneFloatMultiply() {
        val t = GainTable().apply { put("A", -7.3, -7.3, false) }
        val inner = Inner()
        val s = sink(inner, t)
        s.configure(config(C.ENCODING_PCM_16BIT, "A"))
        val (b, x) = pcm16(4000, 3)
        s.handleBuffer(b, 0, 1)
        val g = GainKernel.linOf(-7.3)
        val y = inner.floats()
        for (i in x.indices) assertEquals((x[i] / 32768f) * g, y[i], 0f)
    }

    @Test fun itemBoundary_naturalVsManual_stepsAtFirstSample() {
        val t = GainTable().apply {
            put("A", 0.0, 0.0, false)
            put("B", -6.0, -5.5, false)  // natural (seamed) vs manual
        }
        val ones = FloatArray(200) { 0.5f }
        // Gapless: configure B without a flush -> natural gain from B's first sample.
        val inner = Inner()
        val s = sink(inner, t)
        s.configure(config(C.ENCODING_PCM_FLOAT, "A"))
        s.handleBuffer(pcmFloat(ones), 0, 1)
        s.configure(config(C.ENCODING_PCM_FLOAT, "B"))
        s.handleBuffer(pcmFloat(ones), 1000, 1)
        val y = inner.floats()
        assertEquals(0.5f, y[199], 0f)
        assertEquals(0.5f * GainKernel.linOf(-6.0), y[200], 0f)
        // User skip: flush, then configure B -> manual gain.
        val inner2 = Inner()
        val s2 = sink(inner2, t)
        s2.configure(config(C.ENCODING_PCM_FLOAT, "A"))
        s2.handleBuffer(pcmFloat(ones), 0, 1)
        s2.flush()
        s2.configure(config(C.ENCODING_PCM_FLOAT, "B"))
        s2.handleBuffer(pcmFloat(ones), 1000, 1)
        assertEquals(0.5f * GainKernel.linOf(-5.5), inner2.floats()[200], 0f)
        // A seek inside B (flush + configure of the same item) keeps B's gain.
        s2.flush()
        s2.configure(config(C.ENCODING_PCM_FLOAT, "B"))
        s2.handleBuffer(pcmFloat(ones), 2000, 1)
        assertEquals(0.5f * GainKernel.linOf(-5.5), inner2.floats().last(), 0f)
    }

    @Test fun inTrackChange_isRateLimitedRaisedCosine() {
        val t = GainTable().apply { put("A", -6.0, -6.0, false) }
        val inner = Inner()
        val s = sink(inner, t)
        val fs = 44100
        s.configure(config(C.ENCODING_PCM_FLOAT, "A"))
        t.put("A", -2.0, -2.0, false)  // e.g. JIT -> full analysis: +4 dB
        val n = fs * 8
        s.handleBuffer(pcmFloat(FloatArray(n * 2) { 1f }), 0, 1)
        val y = inner.floats()
        val db = DoubleArray(n) { 20 * log10(y[2 * it].toDouble()) }
        // Duration max(0.5, (pi/2)*4) = 6.28 s; peak slope <= 1 dB/s (checked over 10 ms steps).
        val step = fs / 100
        var i = 0
        while (i + step < n) {
            assertTrue("slope at $i", (db[i + step] - db[i]) / 0.01 <= 1.0 + 1e-3)
            assertTrue(db[i + step] >= db[i] - 1e-9)
            i += step
        }
        assertEquals(-6.0, db[0], 0.01)
        assertEquals(-2.0, db[(6.3 * fs).toInt()], 1e-6)
        // User change: 20 ms crossfade.
        t.put("A", -12.0, -12.0, true)
        s.handleBuffer(pcmFloat(FloatArray(fs / 10 * 2) { 1f }), 9_000_000, 1)
        val z = inner.floats()
        val start = n * 2
        assertEquals(GainKernel.linOf(-12.0), z[start + 2 * (fs * 21 / 1000)], 0f)
    }

    @Test fun partialConsumption_convertsOnce_andAdvancesTheSourceAtTheEnd() {
        val t = GainTable().apply { put("A", -3.0, -3.0, false) }
        val inner = Inner(chunk = 1000)
        val s = sink(inner, t)
        s.configure(config(C.ENCODING_PCM_16BIT, "A"))
        val (b, x) = pcm16(2000, 5)  // 8000 bytes of float out
        var calls = 0
        while (!s.handleBuffer(b, 0, 1)) {
            assertEquals(4000, b.remaining())
            calls++
        }
        assertEquals(7, calls)
        assertEquals(0, b.remaining())
        val y = inner.floats()
        assertEquals(x.size, y.size)
        for (i in x.indices) assertEquals((x[i] / 32768f) * GainKernel.linOf(-3.0), y[i], 0f)
    }

    @Test fun sixteenBitOutput_isTpdfDithered_unbiased_withinOneLsb() {
        val t = GainTable().apply { put("A", 0.0, 0.0, false) }
        val inner = Inner(acceptsFloat = false)
        val s = sink(inner, t)
        s.configure(config(C.ENCODING_PCM_FLOAT, "A"))
        assertEquals(C.ENCODING_PCM_16BIT, inner.configured!!.pcmEncoding)
        val v = 1000.25f / 32768f  // a quarter LSB above an integer
        val n = 200_000
        s.handleBuffer(pcmFloat(FloatArray(n) { v }), 0, 1)
        val q = inner.shorts()
        var sum = 0.0
        for (k in q) {
            assertTrue(abs(k - 1000.25) < 2.0)
            sum += k
        }
        assertEquals(1000.25, sum / n, 0.01)  // TPDF dither is unbiased
        assertTrue(q.toSet().size >= 3)       // and actually dithers
    }

    @Test fun abExcerpt_fadesBothEdges_20ms() {
        val t = GainTable().apply { put("A", 0.0, 0.0, false, excerptMs = 1000) }
        val inner = Inner()
        val s = sink(inner, t)
        s.configure(config(C.ENCODING_PCM_FLOAT, "A"))
        s.handleBuffer(pcmFloat(FloatArray(44100 * 2) { 1f }), 0, 1)
        val y = inner.floats()
        val n = 44100 / 50
        assertTrue(y[0] < 1e-4f)                       // starts from silence
        assertTrue(y[2 * (n / 2)] in 0.45f..0.55f)      // half way through the fade-in
        assertEquals(1f, y[2 * n], 0f)                  // unity after 20 ms
        assertEquals(1f, y[2 * (44100 - n - 1)], 0f)
        assertTrue(y[2 * 44099] < 1e-4f)                // ends in silence
        for (k in 1 until n) assertTrue(y[2 * k] >= y[2 * (k - 1)])  // monotone fade-in
    }

    @Test fun contour_crossfadesIn_thenFiltersExactly_andOffIsBitExact() {
        // A "filter" that is a pure x2 gain (b0 = 2) makes the wet path easy to check exactly.
        val x2 = doubleArrayOf(2.0, 0.0, 0.0, 0.0, 0.0)
        val one = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        val t = GainTable().apply { put("A", 0.0, 0.0, false) }
        val inner = Inner()
        val s = sink(inner, t)
        s.configure(config(C.ENCODING_PCM_FLOAT, "A"))
        s.handleBuffer(pcmFloat(FloatArray(200) { 0.25f }), 0, 1)  // contour off: untouched
        t.contour = GainTable.Contour(1) { x2 to one }
        s.handleBuffer(pcmFloat(FloatArray(44100 / 10 * 2) { 0.25f }), 1, 1)
        val y = inner.floats()
        assertEquals(0.25f, y[199], 0f)
        val n = 44100 / 50
        assertTrue(y[200 + 2 * (n / 2)] in 0.36f..0.39f)  // half way: 0.25 + 0.5 * 0.25
        assertEquals(0.5f, y[200 + 2 * (n + 5)], 0f)       // fully wet: exactly the filter output
        for (k in 1 until n) assertTrue(y[200 + 2 * k] >= y[200 + 2 * (k - 1)])
        t.contour = null
        s.handleBuffer(pcmFloat(FloatArray(44100 / 10 * 2) { 0.25f }), 2, 1)
        assertEquals(0.25f, inner.floats().last(), 0f)      // off again: bit-exact dry path
    }
}
