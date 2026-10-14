package app.lararium.hearth.watch

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules background polling of the user's hearth for custody card approvals
 * and assistant replies.
 */
object HearthWatchScheduler {

    private const val PERIODIC_WORK_NAME = "hearth_watch"
    private const val ONE_TIME_WORK_NAME = "hearth_watch_refresh"

    /**
     * Enqueues unique periodic background work running every 15 minutes
     * when network is connected.
     */
    fun ensurePeriodic(context: Context) = runCatching {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<HearthWatchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Triggers an immediate one-time refresh when network is connected.
     */
    fun refreshNow(context: Context) = runCatching {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<HearthWatchWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
