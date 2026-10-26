package app.sable.decode

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Streaming decoder: MediaExtractor + MediaCodec, never holds a whole track in memory.
 * Requests float PCM so lossy overs are kept (MATH §1.1). Decoders are not trusted to report their output
 * encoding correctly (the A17's FLAC decoder labels 16-bit output as float): the real sample width is
 * measured from buffer sizes and timestamps and used instead. Trims declared encoder delay / padding.
 */
class AudioDecoder(private val context: Context, private val uri: Uri) {

    data class Format(
        val mime: String,
        val codecName: String,
        val sampleRate: Int,
        val channels: Int,
        val waveChannelMask: Int,  // WAVEFORMATEXTENSIBLE-style mask (0 = default order)
        val floatOutput: Boolean,  // true only if the decoder really delivers float samples
        val durationUs: Long,
    )

    data class Stats(val frames: Long, val decodeNanos: Long, val sinkNanos: Long)

    /** Sink receives a direct native-order float buffer (interleaved) and a frame count. */
    fun interface Sink {
        fun onPcm(buffer: ByteBuffer, frames: Int)
    }

    private enum class Pcm { FLOAT, I16, I24, I32 }

    var format: Format? = null
        private set

    fun decode(startUs: Long = 0L, durationUs: Long = Long.MAX_VALUE, onFormat: (Format) -> Unit = {}, boost: PerfBoost? = null, sink: Sink): Stats {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); error("no audio track") }
        extractor.selectTrack(trackIndex)
        val inFormat = extractor.getTrackFormat(trackIndex)
        val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
        val durationTotal = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else -1L
        val encDelay = if (inFormat.containsKey(MediaFormat.KEY_ENCODER_DELAY)) inFormat.getInteger(MediaFormat.KEY_ENCODER_DELAY) else 0
        val encPadding = if (inFormat.containsKey(MediaFormat.KEY_ENCODER_PADDING)) inFormat.getInteger(MediaFormat.KEY_ENCODER_PADDING) else 0
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val codec = createDecoder(mime)
        val cfg = MediaFormat(inFormat).apply { setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT) }
        codec.configure(cfg, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleRate = 0
        var channels = 0
        var androidMask = 0
        var claimed = Pcm.I16
        var pcm: Pcm? = null  // measured
        var pending: Pair<ByteArray, Long>? = null  // first buffer, held until the width is known
        var out: ByteBuffer? = null
        var framesOut = 0L
        var toSkip = if (startUs == 0L) encDelay.toLong() else 0L
        val endUs = if (durationUs == Long.MAX_VALUE) Long.MAX_VALUE else startUs + durationUs
        val trimTail = encPadding > 0 && startUs == 0L && endUs == Long.MAX_VALUE
        var held = FloatArray(0)
        var heldFrames = 0
        var sinkNanos = 0L
        val t0 = System.nanoTime()

        fun emit(src: FloatArray, offsetFrames: Int, frames: Int) {
            if (frames <= 0) return
            val need = frames * channels * 4
            if (out == null || out!!.capacity() < need) out = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
            val b = out!!
            b.clear()
            b.asFloatBuffer().put(src, offsetFrames * channels, frames * channels)
            b.limit(need)
            val s0 = System.nanoTime()
            sink.onPcm(b, frames)
            val dt = System.nanoTime() - s0
            sinkNanos += dt
            boost?.report(dt)
            framesOut += frames
        }

        fun toFloats(bytes: ByteArray, kind: Pcm): FloatArray {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return when (kind) {
                Pcm.FLOAT -> FloatArray(bytes.size / 4).also { bb.asFloatBuffer().get(it) }
                Pcm.I16 -> { val s = bb.asShortBuffer(); FloatArray(bytes.size / 2) { s.get(it) / 32768f } }
                Pcm.I32 -> { val s = bb.asIntBuffer(); FloatArray(bytes.size / 4) { (s.get(it) / 2147483648.0).toFloat() } }
                Pcm.I24 -> FloatArray(bytes.size / 3) {
                    val i = it * 3
                    val v = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() shl 16)
                    v / 8388608f
                }
            }
        }

        fun process(bytes: ByteArray, ptsUs: Long, kind: Pcm) {
            val samples = toFloats(bytes, kind)
            var frames = samples.size / channels
            var offset = 0
            if (startUs > 0 && ptsUs < startUs) {  // sample-accurate excerpt start
                val skip = ((startUs - ptsUs) * sampleRate / 1_000_000L).toInt().coerceIn(0, frames)
                offset += skip; frames -= skip
            }
            if (toSkip > 0) {  // encoder delay
                val skip = minOf(toSkip, frames.toLong()).toInt()
                offset += skip; frames -= skip; toSkip -= skip
            }
            if (endUs != Long.MAX_VALUE) {  // excerpt end
                val remaining = (endUs - startUs) * sampleRate / 1_000_000L - framesOut
                frames = if (remaining <= 0) 0 else minOf(frames.toLong(), remaining).toInt()
            }
            if (trimTail) {  // hold back encoder padding until EOS proves it is the tail
                val combined = FloatArray((heldFrames + frames) * channels)
                System.arraycopy(held, 0, combined, 0, heldFrames * channels)
                System.arraycopy(samples, offset * channels, combined, heldFrames * channels, frames * channels)
                val total = heldFrames + frames
                val emitN = maxOf(0, total - encPadding)
                emit(combined, 0, emitN)
                held = combined.copyOfRange(emitN * channels, total * channels)
                heldFrames = total - emitN
            } else {
                emit(samples, offset, frames)
            }
        }

        // Fast path: convert straight from the codec's buffer into the reusable direct buffer (no copies, no garbage).
        fun processDirect(ob: ByteBuffer, ptsUs: Long, kind: Pcm) {
            val width = when (kind) { Pcm.I16 -> 2; Pcm.I24 -> 3; else -> 4 }
            var frames = ob.remaining() / (width * channels)
            var offset = 0
            if (startUs > 0 && ptsUs < startUs) {
                val skip = ((startUs - ptsUs) * sampleRate / 1_000_000L).toInt().coerceIn(0, frames)
                offset += skip; frames -= skip
            }
            if (toSkip > 0) {
                val skip = minOf(toSkip, frames.toLong()).toInt()
                offset += skip; frames -= skip; toSkip -= skip
            }
            if (endUs != Long.MAX_VALUE) {
                val remaining = (endUs - startUs) * sampleRate / 1_000_000L - framesOut
                frames = if (remaining <= 0) 0 else minOf(frames.toLong(), remaining).toInt()
            }
            if (frames <= 0) return
            val n = frames * channels
            val need = n * 4
            if (out == null || out!!.capacity() < need) out = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
            val b = out!!
            b.clear()
            val dst = b.asFloatBuffer()
            val src = ob.order(ByteOrder.LITTLE_ENDIAN)
            val base = src.position() + offset * channels * width
            when (kind) {
                Pcm.FLOAT -> { src.position(base); val fb = src.asFloatBuffer(); fb.limit(n); dst.put(fb) }
                Pcm.I16 -> for (i in 0 until n) dst.put(src.getShort(base + 2 * i) / 32768f)
                Pcm.I32 -> for (i in 0 until n) dst.put((src.getInt(base + 4 * i) / 2147483648.0).toFloat())
                Pcm.I24 -> for (i in 0 until n) {
                    val j = base + 3 * i
                    val v = (src.get(j).toInt() and 0xFF) or ((src.get(j + 1).toInt() and 0xFF) shl 8) or (src.get(j + 2).toInt() shl 16)
                    dst.put(v / 8388608f)
                }
            }
            b.limit(need)
            val s0 = System.nanoTime()
            sink.onPcm(b, frames)
            val dt = System.nanoTime() - s0
            sinkNanos += dt
            boost?.report(dt)
            framesOut += frames
        }

        fun decideWidth(firstBytes: Int, firstPts: Long, nextPts: Long?): Pcm {
            if (nextPts == null || nextPts <= firstPts) return claimed
            val frames = ((nextPts - firstPts) * sampleRate / 1_000_000.0).roundToInt()
            if (frames <= 0) return claimed
            val width = firstBytes.toDouble() / (frames * channels)
            val measured = when {
                width < 2.5 -> Pcm.I16
                width < 3.5 -> Pcm.I24
                claimed == Pcm.FLOAT -> Pcm.FLOAT
                else -> Pcm.I32
            }
            if (measured != claimed) Log.w(TAG, "${codec.name}: output labelled $claimed but measured $measured (${"%.2f".format(width)} bytes/sample)")
            return measured
        }

        fun announce() {
            val f = Format(mime, codec.name, sampleRate, channels, if (channels > 2) androidMask ushr 2 else 0, pcm == Pcm.FLOAT, durationTotal)
            format = f
            Log.i(TAG, "${codec.name}: $mime ${sampleRate} Hz ${channels} ch, output $pcm")
            onFormat(f)
        }

        try {
            while (!outputDone) {
                var progressed = false
                while (!inputDone) {
                    val ii = codec.dequeueInputBuffer(0)
                    if (ii < 0) break
                    progressed = true
                    run {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0 || extractor.sampleTime >= endUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                while (!outputDone) {
                val oi = codec.dequeueOutputBuffer(info, if (progressed) 0L else 2_000L)
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                progressed = true
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    androidMask = if (of.containsKey(MediaFormat.KEY_CHANNEL_MASK)) of.getInteger(MediaFormat.KEY_CHANNEL_MASK) else 0
                    claimed = when (if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT) {
                        AudioFormat.ENCODING_PCM_FLOAT -> Pcm.FLOAT
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> Pcm.I24
                        AudioFormat.ENCODING_PCM_32BIT -> Pcm.I32
                        else -> Pcm.I16
                    }
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset).limit(info.offset + info.size)
                    val pts = info.presentationTimeUs
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val p = pcm
                    if (p != null && !trimTail) {
                        if (info.size > 0) processDirect(ob, pts, p)
                        codec.releaseOutputBuffer(oi, false)
                        if (eos) outputDone = true
                        if (endUs != Long.MAX_VALUE && sampleRate > 0 && framesOut >= (endUs - startUs) * sampleRate / 1_000_000L) outputDone = true
                        continue
                    }
                    val bytes = ByteArray(info.size).also { ob.get(it) }
                    codec.releaseOutputBuffer(oi, false)
                    if (p != null) {
                        if (bytes.isNotEmpty()) process(bytes, pts, p)
                    } else if (pending == null && !eos && bytes.isNotEmpty()) {
                        pending = bytes to pts  // wait for the next timestamp to measure the width
                    } else {
                        val first = pending
                        val k = if (first != null) decideWidth(first.first.size, first.second, pts) else decideWidth(bytes.size, pts, null)
                        pcm = k
                        announce()
                        if (first != null) process(first.first, first.second, k)
                        if (bytes.isNotEmpty()) process(bytes, pts, k)
                        pending = null
                    }
                    if (eos) outputDone = true
                    if (endUs != Long.MAX_VALUE && sampleRate > 0 && framesOut >= (endUs - startUs) * sampleRate / 1_000_000L) outputDone = true
                }
                }
            }
            pending?.let { (fb, fp) ->
                val k = decideWidth(fb.size, fp, null)
                pcm = k
                announce()
                process(fb, fp, k)
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return Stats(framesOut, System.nanoTime() - t0 - sinkNanos, sinkNanos)
    }

    /** Prefer Android's reference software decoders (c2.android.*): they honour float output. */
    private fun createDecoder(mime: String): MediaCodec {
        val name = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .map { it.name }
            .sortedBy { if (it.startsWith("c2.android.")) 0 else 1 }
            .firstOrNull()
        return if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
    }

    companion object {
        private const val TAG = "SableDecode"
    }
}
