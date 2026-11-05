package tk.glucodata.share.lumiflex

import android.content.Context
import androidx.annotation.Keep
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Durable network-constrained retry; periodic work also recovers interrupted or coalesced wakes. */
@Keep
class LumiflexCloudSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val settings = LumiflexCloudSettings(applicationContext)
        if (!settings.enabled || !settings.isSignedIn()) return Result.success()
        val result = LumiflexCloudUploader.flushNow(applicationContext)
        if (!settings.enabled || !settings.isSignedIn()) return Result.success()
        return when (result) {
            is LumiflexCloudUploader.FlushResult.Success ->
                if (settings.syncStatus().pending > 0) Result.retry() else Result.success()
            is LumiflexCloudUploader.FlushResult.Failed ->
                if (result.kind == LumiflexCloudError.Kind.TRANSIENT) Result.retry()
                else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        internal const val WORK_NAME = "lumiflex-cloud-sync"
        internal const val PERIODIC_NAME = "lumiflex-cloud-recovery"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        @JvmStatic
        fun initialize(context: Context) {
            val settings = LumiflexCloudSettings(context)
            val session = settings.session()
            val status = settings.syncStatus()
            val pending = LumiflexCloudUploader.pendingCount(context)
            val queued = settings.enabled && session.signedIn
            settings.recordSync(session,
                if (queued) LumiflexCloudSettings.SyncState.QUEUED else status.state,
                pending, message = if (queued) "" else status.message, retrying = !queued && status.retrying,
            )
            val manager = WorkManager.getInstance(context)
            if (!settings.enabled || !settings.isSignedIn()) {
                manager.cancelUniqueWork(WORK_NAME)
                manager.cancelUniqueWork(PERIODIC_NAME)
                return
            }
            manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LumiflexCloudSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(network).build(),
            )
            // Explicit resume/login/manual sync must not be swallowed by an older running request.
            enqueue(context, replace = true)
        }

        fun enqueue(context: Context, replace: Boolean = false) {
            val settings = LumiflexCloudSettings(context)
            if (!settings.enabled || !settings.isSignedIn()) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LumiflexCloudSyncWorker>()
                    .setConstraints(network)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
