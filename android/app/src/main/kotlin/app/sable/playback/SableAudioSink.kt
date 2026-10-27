package app.sable.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Gains for the items of the current queue, keyed by playlist window uid (stable while the queue is edited).
 * Written by the player controller (after each plan), read by the audio thread at item starts and per buffer.
 */
class GainTable {
    /** naturalDb: after a gapless transition (seamed, §18); manualDb: started by the user (no seam). */
    data class Entry(
        val naturalDb: Double, val manualDb: Double, val version: Long, val userChange: Boolean, val excerptMs: Long = 0,
        val edgeIn: Boolean = true, val edgeOut: Boolean = true,
    ) {
        fun db(manual: Boolean) = if (manual) manualDb else naturalDb
    }

    private val map = ConcurrentHashMap<Any, Entry>()
    private val versions = AtomicLong()

    /** The Quiet contour (§24) when on: coefficients for a playback rate, and a version that changes with the design. */
    class Contour(val version: Long, val coeffs: (Int) -> Pair<DoubleArray, DoubleArray>?)

    @Volatile var contour: Contour? = null

    /** Used if an item starts before it has been planned: the provisional gain for the current target (§22.1). */
    @Volatile var fallbackDb = -6.0

    operator fun get(key: Any?): Entry? = key?.let { map[it] }

    /** Sets an item's gains. A change bumps the version, which the audio thread applies as an in-track ramp (§23.3). */
    fun put(key: Any, naturalDb: Double, manualDb: Double, userChange: Boolean, excerptMs: Long = 0, edgeIn: Boolean = true, edgeOut: Boolean = true) {
        val old = map[key]
        if (old != null && old.naturalDb == naturalDb && old.manualDb == manualDb && old.excerptMs == excerptMs &&
            old.edgeIn == edgeIn && old.edgeOut == edgeOut) return
        map[key] = Entry(naturalDb, manualDb, versions.incrementAndGet(), userChange, excerptMs, edgeIn, edgeOut)
    }

    fun retain(keys: Set<Any>) = map.keys.retainAll(keys)
}

/**
 * Applies Sable's gain in float to everything Media3 plays (MATH §23), in front of the real sink.
 *
 * Media3 1.11 only runs custom AudioProcessors on its 16-bit path, so the gain is applied here instead: each PCM buffer
 * is converted to float and scaled before it reaches [inner] (configured for float output where the device allows,
 * otherwise 16-bit with TPDF dither). The renderer reconfigures the sink at the first sample of every queue item, with
 * that item's period, so each item gets its own gain from its first sample (§23.2), gapless items included.
 * A configure without a flush since the last buffer is a natural transition (seamed gain); after a flush (seek, skip,
 * new queue) the item was started by the user (unseamed gain, §18).
 */
@UnstableApi
class SableAudioSink(
    private val inner: AudioSink,
    private val gains: GainTable,
    private val keyOf: (Timeline, Any?) -> Any? = ::windowUidOf,
    private val onItemGain: (key: Any?, db: Double, manual: Boolean) -> Unit = { _, _, _ -> },
) : ForwardingAudioSink(inner) {
    private val kernel = GainKernel()
    private var pcm = false
    private var inEncoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var outFloat = true
    private var currentKey: Any? = null
    private var manualStart = true
    private var appliedVersion = -1L
    private var flushedSinceBuffer = true
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var pendingSrc: ByteBuffer? = null
    private var pendingPts = Long.MIN_VALUE
    private var contourVersion = -1L
    private var contourRate = 0

    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (!isPcm(format)) return AudioSink.SINK_FORMAT_UNSUPPORTED  // every item must pass through the gain stage
        val asFloat = inner.getFormatSupport(format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build())
        val as16 = inner.getFormatSupport(format.buildUpon().setPcmEncoding(C.ENCODING_PCM_16BIT).build())
        return if (asFloat == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY || as16 == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    @Suppress("DEPRECATION")
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val f = audioSinkConfig.format
        require(isPcm(f)) { "Sable plays PCM only (gain stage), got ${f.sampleMimeType}/${f.pcmEncoding}" }
        val floatFormat = f.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
        outFloat = inner.getFormatSupport(floatFormat) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        val innerFormat = if (outFloat) floatFormat else f.buildUpon().setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        super.configure(
            AudioSink.AudioSinkConfig.Builder(innerFormat)
                .setPreferredBufferSizeOverride(audioSinkConfig.preferredBufferSizeOverride)
                .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
                .setTimeline(audioSinkConfig.timeline)
                .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
                .build()
        )
        pcm = true
        inEncoding = f.pcmEncoding
        channels = f.channelCount
        sampleRate = f.sampleRate
        val key = keyOf(audioSinkConfig.timeline, audioSinkConfig.mediaPeriodId?.periodUid)
        if (key != null && key == currentKey) return  // same item (e.g. a seek inside it): keep its gain
        currentKey = key
        manualStart = flushedSinceBuffer
        val e = gains[key]
        val db = e?.db(manualStart) ?: gains.fallbackDb
        appliedVersion = e?.version ?: -1L
        kernel.stepTo(db)  // §23.2: the new item's gain from its first sample
        if (e != null && e.excerptMs > 0) kernel.setEdges(e.excerptMs * sampleRate / 1000, sampleRate / 50, e.edgeIn, e.edgeOut)  // §30.2: 20 ms fades
        onItemGain(key, db, manualStart)
    }

    /** §24: follow the table's contour (crossfade on a change; instant at a new rate). */
    private fun applyContour() {
        val c = gains.contour
        val v = c?.version ?: -1L
        if (v == contourVersion && sampleRate == contourRate) return
        val instant = sampleRate != contourRate
        contourVersion = v
        contourRate = sampleRate
        val co = c?.coeffs?.invoke(sampleRate)
        kernel.setContour(co?.first, co?.second, sampleRate, instant)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!pcm) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (pendingSrc !== buffer || pendingPts != presentationTimeUs) {
            applyTableChange()
            applyContour()
            val frames = buffer.remaining() / (GainKernel.bytesPerSample(inEncoding) * channels)
            val need = frames * channels * (if (outFloat) 4 else 2)
            if (out.capacity() < need) out = ByteBuffer.allocateDirect(need * 2).order(ByteOrder.nativeOrder())
            out.clear()
            kernel.process(buffer.duplicate(), inEncoding, channels, out, outFloat)
            out.flip()
            pendingSrc = buffer
            pendingPts = presentationTimeUs
        }
        flushedSinceBuffer = false
        val done = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (done) {
            buffer.position(buffer.limit())
            pendingSrc = null
        }
        return done
    }

    /** §23.3: the plan changed for the playing item (analysis finished, mode switched): ramp to the new gain. */
    private fun applyTableChange() {
        val e = gains[currentKey] ?: return
        if (e.version == appliedVersion) return
        appliedVersion = e.version
        val db = e.db(manualStart)
        kernel.rampTo(db, e.userChange, sampleRate)
        onItemGain(currentKey, db, manualStart)
    }

    override fun flush() {
        kernel.resetFilter()
        pendingSrc = null
        flushedSinceBuffer = true
        super.flush()
    }

    override fun reset() {
        pendingSrc = null
        flushedSinceBuffer = true
        currentKey = null
        super.reset()
    }

    companion object {
        fun isPcm(f: Format) = MimeTypes.AUDIO_RAW == f.sampleMimeType && GainKernel.isSupported(f.pcmEncoding)

        fun windowUidOf(timeline: Timeline, periodUid: Any?): Any? {
            if (periodUid == null || timeline.isEmpty) return null
            val period = timeline.getPeriodByUid(periodUid, Timeline.Period())
            return timeline.getWindow(period.windowIndex, Timeline.Window()).uid
        }
    }
}
