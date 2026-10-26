package app.sable.decode

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process

/**
 * Asks the scheduler for performance on the calling thread via Android's Performance Hint API (ADPF):
 * we report each work unit's duration against a short target, which moves the thread to the big cores
 * (Cortex-A78 on the A17) while it is busy. Used for just-in-time analysis and charging scans.
 */
class PerfBoost(context: Context, targetNanos: Long = 2_000_000L) : AutoCloseable {
    private val session = context.getSystemService(PerformanceHintManager::class.java)
        ?.createHintSession(intArrayOf(Process.myTid()), targetNanos)

    fun report(actualNanos: Long) {
        if (actualNanos > 0) session?.reportActualWorkDuration(actualNanos)
    }

    @Volatile private var alive = false
    private var heart: Thread? = null

    /** For long native calls: keep reporting "over target" so the boost holds until close(). */
    fun keepAlive(target: Long = 2_000_000L): PerfBoost {
        alive = true
        heart = Thread {
            while (alive) {
                report(target * 2)
                try { Thread.sleep(40) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        return this
    }

    override fun close() {
        alive = false
        heart?.interrupt()
        session?.close()
    }
}
