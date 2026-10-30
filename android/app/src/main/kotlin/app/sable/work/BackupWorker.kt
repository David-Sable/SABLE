package app.sable.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sable.data.Backup
import java.util.concurrent.TimeUnit

/** Saves a backup by itself about once a week, so what Sable learned is safe even if nobody remembers to (David, 2026-10-30). */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result =
        runCatching { Backup.save(applicationContext, auto = true) }.fold({ Result.success() }, { Result.retry() })

    companion object {
        private const val NAME = "weekly-backup"
        private const val CATCH_UP = "backup-catch-up"
        private const val DAYS = 7L
        private val quiet = Constraints.Builder().setRequiresBatteryNotLow(true).build()

        /** Called when the app opens. One UI can hold periodic work back for a sleeping app, so an overdue copy is asked for here too. */
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            val weekly = PeriodicWorkRequestBuilder<BackupWorker>(DAYS, TimeUnit.DAYS).setConstraints(quiet).build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, weekly)
            val last = Backup.lastSavedAt(context)
            if (last != 0L && System.currentTimeMillis() - last > TimeUnit.DAYS.toMillis(DAYS + 1)) saveNow(context)
        }

        fun saveNow(context: Context) {
            val once = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(quiet).build()
            WorkManager.getInstance(context).enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.KEEP, once)
        }
    }
}
