package app.sable.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The per-sample gain stage of playback (MATH §23). Converts decoder PCM (8/16/24/32-bit or float) to float, multiplies
 * by the gain, and writes float or, if the output cannot take float, 16-bit with TPDF dither (§23.4).
 * process() allocates nothing and takes no locks.
 */
class GainKernel(seed: Long = 0x5AB1E5AB1EL) {
    /** The gain currently applied (dB): the target of the running ramp, or the steady gain. */
    var targetDb = 0.0
        private set

    private var lin = 1.0f            // steady linear gain, (float) 10^(g/20) (§21 step 5)
    private var rampN = 0             // ramp length in frames; 0 = steady
    private var rampI = 0
    private var rampInDb = false      // true: raised cosine in dB (§23.3 rate-limited); false: linear-amplitude crossfade
    private var fromDb = 0.0
    private var fromLin = 1.0
    private var toLin = 1.0
    private var instLin = 1.0         // the gain at the last processed frame
    private var edgeTotal = -1L       // §30.2 excerpt length in frames (-1: not an excerpt)
    private var edgeN = 0             // fade length in frames
    private var edgePos = 0L          // frames of the current item processed so far
    private var edgeIn = true         // fade in at the start / out at the end (§30.6: not across a real transition)
    private var edgeOut = true
    // §24 Quiet contour: low shelf then high shelf ({b0, b1, b2, a1, a2}), TDF-II in double, per channel,
    // mixed in with a 20 ms raised-cosine crossfade when switched on or off.
    private var lo: DoubleArray? = null
    private var hi: DoubleArray? = null
    private val zLo = DoubleArray(2 * MAX_CH)
    private val zHi = DoubleArray(2 * MAX_CH)
    private var wetFrom = 0.0
    private var wetTo = 0.0
    private var wet = 0.0
    private var wetN = 0
    private var wetI = 0
    private var s0 = seed xor 0x9E3779B97F4A7C15uL.toLong()
    private var s1 = seed * 0x2545F4914F6CDD1DL or 1L

    /** §23.2: a new item starts exactly at its first sample with its own gain (a step, no ramp). */
    fun stepTo(db: Double) {
        targetDb = db
        lin = linOf(db)
        instLin = lin.toDouble()
        rampN = 0
        edgeTotal = -1L
        edgePos = 0L
    }

    /**
     * §30.2: the item just started is an excerpt of [totalFrames]; fade its first and last [fadeFrames] in and out.
     * §30.6: a song change keeps its real transition, so the edge on that side is not faded ([fadeIn], [fadeOut]).
     */
    fun setEdges(totalFrames: Long, fadeFrames: Int, fadeIn: Boolean = true, fadeOut: Boolean = true) {
        edgeTotal = totalFrames
        edgeN = max(1, fadeFrames)
        edgePos = 0L
        edgeIn = fadeIn
        edgeOut = fadeOut
    }

    /**
     * §23.3: an in-track change. Analysis updates are rate-limited (raised cosine in dB over max(0.5 s, (π/2)|Δ| s));
     * user changes (mode, A/B) are a 20 ms raised-cosine crossfade in linear amplitude. Starts from the instantaneous gain.
     */
    fun rampTo(db: Double, user: Boolean, sampleRate: Int) {
        if (db == targetDb && rampN == 0) return
        fromLin = instLin
        fromDb = 20.0 * log10(instLin)
        toLin = 10.0.pow(db / 20.0)
        targetDb = db
        lin = linOf(db)
        rampInDb = !user
        val seconds = if (user) 0.020 else max(0.5, PI / 2 * abs(db - fromDb))
        rampN = max(1, (seconds * sampleRate).roundToInt())
        rampI = 0
    }

    /** Turns the contour on (coefficients) or off (null). [instant] skips the crossfade (a new item, a new rate). */
    fun setContour(low: DoubleArray?, high: DoubleArray?, sampleRate: Int, instant: Boolean = false) {
        if (low != null && high != null) {
            if (lo == null) { zLo.fill(0.0); zHi.fill(0.0) }
            lo = low
            hi = high
        }
        wetFrom = wet
        wetTo = if (low != null) 1.0 else 0.0
        if (instant) {
            wet = wetTo
            wetN = 0
            if (wetTo == 0.0) lo = null
        } else {
            wetN = max(1, (0.020 * sampleRate).roundToInt())
            wetI = 0
        }
    }

    /** After a flush (seek, skip): the filter history belongs to audio that will not be played. */
    fun resetFilter() {
        zLo.fill(0.0)
        zHi.fill(0.0)
    }

    val contourOn get() = lo != null && wetTo > 0.0

    /** Converts and scales all of [input] (position..limit) into [out]; advances both. */
    fun process(input: ByteBuffer, inEncoding: Int, channels: Int, out: ByteBuffer, outFloat: Boolean) {
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bps = bytesPerSample(inEncoding)
        val frames = src.remaining() / (bps * channels)
        var p = src.position()
        var q = out.position()
        out.order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            var g: Float = if (rampN == 0) lin else rampStep()
            if (edgeTotal >= 0) g *= edgeGain()
            val l = lo
            val h = hi
            if (l != null && wetN > 0) {
                wetI++
                wet = wetFrom + (wetTo - wetFrom) * (1.0 - cos(PI * wetI / wetN)) / 2.0
                if (wetI >= wetN) {
                    wetN = 0
                    wet = wetTo
                }
            }
            for (c in 0 until channels) {
                val x: Float = when (inEncoding) {
                    C.ENCODING_PCM_16BIT -> src.getShort(p) / 32768f
                    C.ENCODING_PCM_FLOAT -> src.getFloat(p)
                    C.ENCODING_PCM_24BIT -> ((src.get(p).toInt() and 0xFF) or ((src.get(p + 1).toInt() and 0xFF) shl 8) or
                        (src.get(p + 2).toInt() shl 16)) / 8388608f
                    C.ENCODING_PCM_32BIT -> (src.getInt(p) / 2147483648.0).toFloat()
                    C.ENCODING_PCM_8BIT -> ((src.get(p).toInt() and 0xFF) - 128) / 128f
                    else -> 0f
                }
                p += bps
                var y = x * g
                if (l != null && h != null && c < MAX_CH) {
                    val k = 2 * c
                    val u = y.toDouble()
                    val v = l[0] * u + zLo[k]
                    zLo[k] = l[1] * u - l[3] * v + zLo[k + 1]
                    zLo[k + 1] = l[2] * u - l[4] * v
                    val w = h[0] * v + zHi[k]
                    zHi[k] = h[1] * v - h[3] * w + zHi[k + 1]
                    zHi[k + 1] = h[2] * v - h[4] * w
                    y = if (wet >= 1.0) w.toFloat() else (u + wet * (w - u)).toFloat()
                }
                if (outFloat) {
                    out.putFloat(q, y); q += 4
                } else {
                    out.putShort(q, dither16(y)); q += 2
                }
            }
        }
        if (lo != null && wet == 0.0 && wetTo == 0.0) lo = null  // faded out
        input.position(input.position() + frames * bps * channels)
        out.position(q)
    }

    private fun rampStep(): Float {
        rampI++
        val w = (1.0 - cos(PI * rampI / rampN)) / 2.0
        instLin = if (rampInDb) 10.0.pow((fromDb + (targetDb - fromDb) * w) / 20.0) else fromLin + (toLin - fromLin) * w
        if (rampI >= rampN) { rampN = 0; instLin = lin.toDouble(); return lin }
        return instLin.toFloat()
    }

    private fun edgeGain(): Float {
        val k = edgePos++
        val fromEnd = if (edgeOut) edgeTotal - 1 - k else Long.MAX_VALUE
        val d = minOf(if (edgeIn) k else Long.MAX_VALUE, fromEnd)
        if (d >= edgeN) return 1f
        if (d < 0) return 0f  // past the expected end: silent until the next item starts
        return ((1.0 - cos(PI * (d + 0.5) / edgeN)) / 2.0).toFloat()
    }

    /** §23.4: d = (r1 + r2 - 1) LSB from xorshift128+, then round half-even with the only clamp in the chain. */
    private fun dither16(y: Float): Short {
        val d = next01() + next01() - 1.0
        val v = Math.rint(y * 32768.0 + d).coerceIn(-32768.0, 32767.0)
        return v.toInt().toShort()
    }

    private fun next01(): Double {
        var x = s0
        val z = s1
        s0 = z
        x = x xor (x shl 23)
        s1 = x xor z xor (x ushr 17) xor (z ushr 26)
        return ((s1 + z) ushr 11) * (1.0 / (1L shl 53))
    }

    companion object {
        const val MAX_CH = 8

        fun linOf(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }

        fun isSupported(encoding: Int) = bytesPerSample(encoding) > 0
    }
}
