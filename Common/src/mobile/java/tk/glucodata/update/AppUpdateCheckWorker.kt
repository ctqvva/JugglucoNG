package tk.glucodata.update

import android.content.Context
import androidx.annotation.Keep
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Daily background update check.
 *
 * Metadata only — the worker never downloads an APK and never starts an installer. A CGM app
 * popping up a system install dialog on its own, while a sensor session is running, would be a
 * genuinely bad outcome; the worker's whole job is to make the card in Settings already know the
 * answer by the time the user looks.
 */
@Keep
class AppUpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        if (!AppUpdateSettings.isAutoCheckEnabled(context)) return Result.success()
        if (!UpdateEligibility.isSupported(context)) return Result.success()

        val result = UpdateSourceClient.check(AppUpdateSettings.updateSource(context))
        AppUpdateSettings.recordCheck(context, result, System.currentTimeMillis())
        AppUpdateController.refreshFromSettings(context)

        // A transient network failure just waits for tomorrow's run; retrying would burn the
        // anonymous GitHub API quota that the manual "Check now" button also draws on.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "app_update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequest.Builder(
                AppUpdateCheckWorker::class.java,
                1, TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(2, TimeUnit.HOURS)
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
