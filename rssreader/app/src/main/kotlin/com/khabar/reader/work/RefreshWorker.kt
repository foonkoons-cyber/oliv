package com.khabar.reader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.khabar.reader.KhabarApp
import com.khabar.reader.data.Prefs
import java.util.concurrent.TimeUnit

/** Pulls every feed in the background so the timeline is already filled when the app opens. */
class RefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? KhabarApp ?: return Result.success()
        return try {
            val prefs = app.prefsRepository.current()
            app.repository.refreshAll(prefs.keepPerFeed)
            Result.success()
        } catch (e: Exception) {
            // Individual feed failures are already recorded per feed; reaching here means
            // something broader went wrong, and the next window is soon enough.
            Result.retry()
        }
    }
}

object RefreshScheduler {

    private const val UNIQUE_NAME = "khabar-refresh"

    /** Call whenever the interval or the network preference changes. Interval 0 turns it off. */
    fun apply(context: Context, prefs: Prefs) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (prefs.refreshIntervalHours <= 0) {
            manager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(
            prefs.refreshIntervalHours.toLong(), TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
