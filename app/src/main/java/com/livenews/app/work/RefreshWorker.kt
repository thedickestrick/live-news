package com.livenews.app.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.livenews.app.LiveNewsApp
import com.livenews.app.widget.NewsWidget
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Refreshes headlines when the app is not on screen, which is what keeps the widget current. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as LiveNewsApp
        return try {
            val outcome = app.repository.refresh()
            // Also when nothing new came in, so the widget's "updated" time and any error state are current.
            NewsWidget().updateAll(applicationContext)
            if (outcome.total > 0 && outcome.failed == outcome.total && runAttemptCount < 2) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "refresh-periodic"
        private const val ONCE = "refresh-once"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** [minutes] 0 switches background refresh off. Android allows nothing more frequent than 15 minutes. */
        fun schedule(context: Context, minutes: Int, keepExisting: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (minutes <= 0) {
                workManager.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(online)
                .build()
            val policy = if (keepExisting) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
            workManager.enqueueUniquePeriodicWork(PERIODIC, policy, request)
        }

        /** One refresh as soon as there is a connection (the widget's refresh button). */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
        }
    }
}
