package app.sable.library

import android.content.Context
import android.net.Uri
import app.sable.data.AnalysisEntity
import app.sable.data.SableDatabase
import app.sable.data.TrackEntity
import app.sable.decode.AudioDecoder
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

    fun analyze(uri: Uri, hash: String): AnalysisEntity {
        var handle = 0L
        var decodeNs = 0L
        var engineNs = 0L
        val dec = AudioDecoder(context, uri)
        try {
            val s1 = dec.decode(
                onFormat = { f -> handle = SableEngine.trackCreate(f.sampleRate, f.channels, f.waveChannelMask, !f.floatOutput && isLossy(f.mime)) },
                sink = { buf, n -> SableEngine.trackPushPass1(handle, buf, n) },
            )
            decodeNs += s1.decodeNanos
            engineNs += s1.sinkNanos
            var t = System.nanoTime()
            var again = SableEngine.trackEndPass1(handle)
            engineNs += System.nanoTime() - t
            while (again) {
                SableEngine.trackBeginPass2(handle)
                val s2 = AudioDecoder(context, uri).decode(sink = { buf, n -> SableEngine.trackPushPass2(handle, buf, n) })
                decodeNs += s2.decodeNanos
                engineNs += s2.sinkNanos
                t = System.nanoTime()
                again = SableEngine.trackEndPass2(handle)
                engineNs += System.nanoTime() - t
            }
            val json = SableEngine.trackResult(handle)
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
                decodeMs = decodeNs / 1_000_000,
                engineMs = engineNs / 1_000_000,
            )
        } finally {
            if (handle != 0L) SableEngine.trackDestroy(handle)
        }
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
        try {
            for ((start, len, middle) in windows) {
                if (len <= 0) continue
                AudioDecoder(context, uri).decode(
                    startUs = start, durationUs = len,
                    onFormat = { f ->
                        if (handle == 0L) handle = SableEngine.jitCreate(f.sampleRate, f.channels, f.waveChannelMask)
                        SableEngine.jitBegin(handle, middle)
                    },
                    sink = { buf, n -> SableEngine.jitPush(handle, buf, n) },
                )
                SableEngine.jitEnd(handle)
            }
            return Result(SableEngine.jitResult(handle, ceilingDbtp), (System.nanoTime() - t0) / 1_000_000)
        } finally {
            if (handle != 0L) SableEngine.jitDestroy(handle)
        }
    }
}
