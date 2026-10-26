package app.sable.engine

import java.nio.ByteBuffer

/** JNI entry points into the portable C++ engine (engine/). Buffers must be direct, native-order float. */
object SableEngine {
    init {
        System.loadLibrary("sable_jni")
    }

    @JvmStatic external fun trackCreate(sampleRate: Int, channels: Int, channelMask: Int, decodeClipped: Boolean): Long
    @JvmStatic external fun trackPushPass1(handle: Long, buffer: ByteBuffer, frames: Int)
    @JvmStatic external fun trackEndPass1(handle: Long): Boolean
    @JvmStatic external fun trackBeginPass2(handle: Long)
    @JvmStatic external fun trackPushPass2(handle: Long, buffer: ByteBuffer, frames: Int)
    @JvmStatic external fun trackEndPass2(handle: Long): Boolean
    @JvmStatic external fun trackResult(handle: Long): String
    @JvmStatic external fun trackDestroy(handle: Long)

    @JvmStatic external fun jitCreate(sampleRate: Int, channels: Int, channelMask: Int): Long
    @JvmStatic external fun jitBegin(handle: Long, middle: Boolean)
    @JvmStatic external fun jitPush(handle: Long, buffer: ByteBuffer, frames: Int)
    @JvmStatic external fun jitEnd(handle: Long)
    @JvmStatic external fun jitResult(handle: Long, ceilingDbtp: Double): String
    @JvmStatic external fun jitDestroy(handle: Long)

    /** In-engine decode (kind 0 = FLAC via dr_flac, 1 = MP3 via minimp3). Empty string if the file cannot be opened. */
    @JvmStatic external fun analyzeNativeFd(fd: Int, kind: Int): String
    @JvmStatic external fun jitNativeFd(fd: Int, kind: Int, ceilingDbtp: Double): String

    /** Gain of every queue item (MATH §17.1, §18, §21, §22). JSON schema: docs/plan-json.md. */
    @JvmStatic external fun planQueue(inputJson: String): String

    /** One volume-key feedback event (MATH §19). JSON in/out: engine/include/sable/plan.h. */
    @JvmStatic external fun learnUpdate(inputJson: String): String

    /** Quiet-mode contour design (MATH §24) with low/high shelf biquads {b0,b1,b2,a1,a2} at [sampleRate]. */
    @JvmStatic external fun contourDesign(calDb: Double, sampleRate: Double): String

    /** Engine-decodable formats: FLAC and MP3 (owner decisions 2026-10-26). */
    fun nativeKind(mime: String?): Int? = when (mime) {
        "audio/flac" -> 0
        "audio/mpeg" -> 1
        else -> null
    }
}
