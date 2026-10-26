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

    /** FLAC: full analysis / JIT decoded inside the engine (dr_flac). Empty string if the file cannot be opened. */
    @JvmStatic external fun analyzeFlacFd(fd: Int): String
    @JvmStatic external fun jitFlacFd(fd: Int, ceilingDbtp: Double): String
}
