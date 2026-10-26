package app.sable.library

import android.content.Context
import android.net.Uri
import app.sable.data.AnalysisEntity
import app.sable.data.SableDatabase
import app.sable.data.TrackEntity
import app.sable.decode.AudioDecoder
import app.sable.decode.PerfBoost
import app.sable.engine.SableEngine
import org.json.JSONObject

/** Full two-pass analysis of one track (MATH §12.4) through the shared C++ TrackPipeline. */
class TrackAnalyzer(private val context: Context) {
    private val dao = SableDatabase.get(context).library()

    data class Result(val analysis: AnalysisEntity, val reused: Boolean)

    /** Hash if needed, reuse a cached analysis of identical audio, otherwise analyze and store. */
    suspend fun ensureAnalyzed(track: TrackEntity): Result {
        val uri = Uri.parse(track.uri)
        val hash = track.contentHash ?: ContentHasher.hash(context, uri).also { dao.setHash(track.mediaId, it) }
        dao.analysis(hash, SableDatabase.MATH_VERSION)?.let { return Result(it, true) }
        val a = analyze(uri, hash)
        dao.putAnalysis(a)
        return Result(a, false)
    }

    fun analyze(uri: Uri, hash: String, boost: Boolean = true, forceMediaCodec: Boolean = false): AnalysisEntity {
        if (!forceMediaCodec && context.contentResolver.getType(uri) == "audio/flac") analyzeFlac(uri, hash, boost)?.let { return it }
        val perf = if (boost) PerfBoost(context) else null
        var handle = 0L
        var decodeNs = 0L
        var engineNs = 0L
        val dec = AudioDecoder(context, uri)
        try {
            val s1 = dec.decode(
                onFormat = { f -> handle = SableEngine.trackCreate(f.sampleRate, f.channels, f.waveChannelMask, !f.floatOutput && isLossy(f.mime)) },
                boost = perf,
                sink = { buf, n -> SableEngine.trackPushPass1(handle, buf, n) },
            )
            decodeNs += s1.decodeNanos
            engineNs += s1.sinkNanos
            var t = System.nanoTime()
            var again = SableEngine.trackEndPass1(handle)
            engineNs += System.nanoTime() - t
            while (again) {
                SableEngine.trackBeginPass2(handle)
                val s2 = AudioDecoder(context, uri).decode(boost = perf, sink = { buf, n -> SableEngine.trackPushPass2(handle, buf, n) })
                decodeNs += s2.decodeNanos
                engineNs += s2.sinkNanos
                t = System.nanoTime()
                again = SableEngine.trackEndPass2(handle)
                engineNs += System.nanoTime() - t
            }
            return entity(hash, SableEngine.trackResult(handle), decodeNs / 1_000_000, engineNs / 1_000_000)
        } finally {
            if (handle != 0L) SableEngine.trackDestroy(handle)
            perf?.close()
        }
    }

    /** FLAC: decoded inside the engine (dr_flac, owner decision 2026-10-26); decode time is part of engineMs. */
    private fun analyzeFlac(uri: Uri, hash: String, boost: Boolean): AnalysisEntity? {
        val perf = if (boost) PerfBoost(context).keepAlive() else null
        try {
            val t0 = System.nanoTime()
            val json = context.contentResolver.openFileDescriptor(uri, "r")?.use { SableEngine.analyzeFlacFd(it.fd) } ?: return null
            if (json.isEmpty()) return null
            return entity(hash, json, 0, (System.nanoTime() - t0) / 1_000_000)
        } finally {
            perf?.close()
        }
    }

    private fun entity(hash: String, json: String, decodeMs: Long, engineMs: Long): AnalysisEntity {
            val o = JSONObject(json)
            fun d(k: String) = if (o.isNull(k)) null else o.getDouble(k)
            return AnalysisEntity(
                contentHash = hash,
                mathVersion = SableDatabase.MATH_VERSION,
                json = json,
                integratedLufs = d("integratedLufs"),
                gainStandardDb = o.getJSONObject("standard").optDouble("gainDb").takeIf { !it.isNaN() },
                gainQuietDb = o.getJSONObject("quiet").optDouble("gainDb").takeIf { !it.isNaN() },
                character = o.optString("character"),
                decodeClipped = o.optBoolean("decodeClipped"),
                analyzedAt = System.currentTimeMillis(),
                decodeMs = decodeMs,
                engineMs = engineMs,
            )
    }

    companion object {
        fun isLossy(mime: String) = mime != "audio/flac" && mime != "audio/raw" && mime != "audio/alac" && !mime.contains("wav")
    }
}

/** Just-in-time analysis from excerpts (MATH §22.2): [0,10 s) and 15 s windows at 30/50/70 % of the duration. */
class JitAnalyzer(private val context: Context) {
    data class Result(val json: String, val millis: Long)

    fun analyze(uri: Uri, durationMs: Long, ceilingDbtp: Double = -1.0): Result {
        val t0 = System.nanoTime()
        if (context.contentResolver.getType(uri) == "audio/flac") {
            PerfBoost(context).keepAlive().use {
                val json = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> SableEngine.jitFlacFd(pfd.fd, ceilingDbtp) }
                if (!json.isNullOrEmpty()) return Result(json, (System.nanoTime() - t0) / 1_000_000)
            }
        }
        val dur = durationMs * 1000L
        val windows = buildList {
            add(Triple(0L, minOf(10_000_000L, dur), false))
            for (p in listOf(0.3, 0.5, 0.7)) {
                val c = (dur * p).toLong()
                val s = maxOf(0L, c - 7_500_000L)
                add(Triple(s, minOf(15_000_000L, dur - s), true))
            }
        }
        var handle = 0L
        val perf = PerfBoost(context)
        try {
            for ((start, len, middle) in windows) {
                if (len <= 0) continue
                AudioDecoder(context, uri).decode(
                    startUs = start, durationUs = len,
                    onFormat = { f ->
                        if (handle == 0L) handle = SableEngine.jitCreate(f.sampleRate, f.channels, f.waveChannelMask)
                        SableEngine.jitBegin(handle, middle)
                    },
                    boost = perf,
                    sink = { buf, n -> SableEngine.jitPush(handle, buf, n) },
                )
                SableEngine.jitEnd(handle)
            }
            return Result(SableEngine.jitResult(handle, ceilingDbtp), (System.nanoTime() - t0) / 1_000_000)
        } finally {
            if (handle != 0L) SableEngine.jitDestroy(handle)
            perf.close()
        }
    }
}
