package app.sable.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.sable.data.SableDatabase
import app.sable.library.LibraryScanner
import app.sable.library.TrackAnalyzer

/**
 * Background library scan: sync MediaStore, then hash and analyze every pending track. Runs as a foreground
 * (mediaProcessing) worker so One UI does not kill it mid-track; charging-only by default. Resumable: progress
 * is the database itself, so a killed scan simply continues with what is still pending.
 */
class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        setForeground(foreground("Preparing your library"))
        val scanner = LibraryScanner(applicationContext)
        scanner.syncMediaStore()
        val dao = SableDatabase.get(applicationContext).library()
        val analyzer = TrackAnalyzer(applicationContext)
        val pending = dao.pending(SableDatabase.MATH_VERSION)
        pending.forEachIndexed { i, t ->
            if (isStopped) return Result.retry()
            setProgress(workDataOf("done" to i, "total" to pending.size, "title" to t.title))
            setForeground(foreground("Listening ${i + 1} of ${pending.size}: ${t.title}"))
            runCatching { analyzer.ensureAnalyzed(t) }
        }
        return Result.success()
    }

    private fun foreground(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Library analysis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sable is listening to your library")
            .setContentText(text)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(NOTIFICATION_ID, n, type)
    }

    companion object {
        private const val CHANNEL = "analysis"
        private const val NOTIFICATION_ID = 41
        const val NAME = "library-scan"

        /** Enqueue a scan. Charging-only by default (spec: heavy scans run while charging). */
        fun enqueue(context: Context, requireCharging: Boolean = true) {
            val req = OneTimeWorkRequestBuilder<ScanWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(requireCharging).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
