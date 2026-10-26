package app.sable.decode

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming decoder: MediaExtractor + MediaCodec, never holds a whole track in memory.
 * Requests float PCM so lossy overs are kept (MATH §1.1); reports when a decoder only gives 16-bit.
 * Trims encoder delay / padding declared by the container (LAME/Xing, iTunSMPB) for gapless-correct analysis.
 */
class AudioDecoder(private val context: Context, private val uri: Uri) {

    data class Format(
        val mime: String,
        val sampleRate: Int,
        val channels: Int,
        val waveChannelMask: Int,  // WAVEFORMATEXTENSIBLE-style mask (0 = default order)
        val floatOutput: Boolean,
        val durationUs: Long,
    )

    data class Stats(val frames: Long, val decodeNanos: Long, val sinkNanos: Long)

    /** Sink receives a direct native-order float buffer (interleaved) and a frame count. */
    fun interface Sink {
        fun onPcm(buffer: ByteBuffer, frames: Int)
    }

    var format: Format? = null
        private set

    /**
     * Decode [startUs, startUs + durationUs). With startUs > 0 the extractor seeks to the previous sync
     * sample and output before startUs is discarded sample-accurately.
     */
    fun decode(startUs: Long = 0L, durationUs: Long = Long.MAX_VALUE, onFormat: (Format) -> Unit = {}, sink: Sink): Stats {
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

        val codec = MediaCodec.createDecoderByType(mime)
        val cfg = MediaFormat(inFormat).apply { setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT) }
        codec.configure(cfg, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var fmt: Format? = null
        var out: ByteBuffer? = null
        var framesOut = 0L
        var toSkip = if (startUs == 0L) encDelay.toLong() else 0L  // encoder delay only applies at the stream start
        val endUs = if (durationUs == Long.MAX_VALUE) Long.MAX_VALUE else startUs + durationUs
        // Padding: hold back the last `encPadding` frames until EOS proves they are the tail.
        var held: FloatArray = FloatArray(0)
        var heldFrames = 0
        var sinkNanos = 0L
        val t0 = System.nanoTime()

        fun emit(src: FloatArray, offsetFrames: Int, frames: Int, ch: Int) {
            if (frames <= 0) return
            val need = frames * ch * 4
            if (out == null || out!!.capacity() < need) out = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
            val b = out!!
            b.clear()
            b.asFloatBuffer().put(src, offsetFrames * ch, frames * ch)
            b.limit(need)
            val s0 = System.nanoTime()
            sink.onPcm(b, frames)
            sinkNanos += System.nanoTime() - s0
            framesOut += frames
        }

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
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
                val oi = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        val androidMask = if (of.containsKey(MediaFormat.KEY_CHANNEL_MASK)) of.getInteger(MediaFormat.KEY_CHANNEL_MASK) else 0
                        val ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        fmt = Format(
                            mime, of.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch,
                            if (ch > 2) androidMask ushr 2 else 0,  // AudioFormat.CHANNEL_OUT_* = WAVE bit << 2
                            enc == AudioFormat.ENCODING_PCM_FLOAT, durationTotal,
                        )
                        format = fmt
                        onFormat(fmt)
                    }
                    oi >= 0 -> {
                        val f = fmt ?: error("output before format")
                        val ch = f.channels
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset).limit(info.offset + info.size)
                        val samples: FloatArray = if (f.floatOutput) {
                            FloatArray(info.size / 4).also { ob.order(ByteOrder.nativeOrder()).asFloatBuffer().get(it) }
                        } else {
                            val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                            FloatArray(info.size / 2) { sb.get(it) / 32768f }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        var frames = samples.size / ch
                        var offset = 0
                        // Sample-accurate excerpt start.
                        if (startUs > 0 && info.presentationTimeUs < startUs) {
                            val skip = ((startUs - info.presentationTimeUs) * f.sampleRate / 1_000_000L).toInt().coerceAtMost(frames)
                            offset += skip
                            frames -= skip
                        }
                        if (toSkip > 0) {
                            val skip = minOf(toSkip, frames.toLong()).toInt()
                            offset += skip
                            frames -= skip
                            toSkip -= skip
                        }
                        // Excerpt end.
                        if (endUs != Long.MAX_VALUE) {
                            val remaining = ((endUs - startUs) * f.sampleRate / 1_000_000L) - framesOut - heldFrames
                            if (remaining <= 0) frames = 0 else if (frames > remaining) frames = remaining.toInt()
                        }
                        if (encPadding > 0 && startUs == 0L && endUs == Long.MAX_VALUE) {
                            // Combine held + new, emit all but the last encPadding frames.
                            val combined = FloatArray((heldFrames + frames) * ch)
                            System.arraycopy(held, 0, combined, 0, heldFrames * ch)
                            System.arraycopy(samples, offset * ch, combined, heldFrames * ch, frames * ch)
                            val total = heldFrames + frames
                            val emitN = maxOf(0, total - encPadding)
                            emit(combined, 0, emitN, ch)
                            held = combined.copyOfRange(emitN * ch, total * ch)
                            heldFrames = total - emitN
                        } else {
                            emit(samples, offset, frames, ch)
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        if (endUs != Long.MAX_VALUE && framesOut >= (endUs - startUs) * f.sampleRate / 1_000_000L) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        val total = System.nanoTime() - t0
        return Stats(framesOut, total - sinkNanos, sinkNanos)
    }
}
