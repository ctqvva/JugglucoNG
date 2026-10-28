package tk.glucodata.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.Keep
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import tk.glucodata.R
import tk.glucodata.Log

@Keep
class ScheduledBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = backupMutex.withLock {
        val context = applicationContext
        val config = ScheduledBackupSettings.load(context)
        val forced = inputData.getBoolean(KEY_FORCED, false)
        if (!forced && !config.enabled) return@withLock Result.success()

        val destination = config.destination
        if (destination == null || !ScheduledBackupSettings.hasPersistedWritePermission(context, destination)) {
            ScheduledBackupSettings.recordFailure(
                context,
                System.currentTimeMillis(),
                context.getString(R.string.scheduled_backup_folder_error)
            )
            if (!forced) cancel(context)
            return@withLock Result.failure()
        }

        val startedAt = System.currentTimeMillis()
        val fileName = ScheduledBackupPolicy.fileName(startedAt, config.compression)
        try {
            Log.i(TAG, "Preparing scheduled backup: $fileName")
            val request = ExportPackageExporter.ExportRequest(
                includeSettings = true,
                includeHistory = true,
                includeCalibrations = true,
                historyDays = null,
                compression = config.compression
            )
            val backup = ScheduledBackupDocumentWriter.write(
                cacheDirectory = context.cacheDir,
                prepare = { file ->
                    ExportPackageExporter.exportToUri(context, Uri.fromFile(file), request).getOrThrow()
                },
                createDocument = { createDocument(destination, fileName, config.compression.mimeType) },
                openDocument = { uri -> context.contentResolver.openOutputStream(uri, "w") },
                deleteDocument = { uri ->
                    check(DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                        "Could not remove incomplete backup"
                    }
                },
                onCleanupFailure = { Log.stack(TAG, "Could not remove incomplete backup", it) }
            )
            val metrics = backup.summary.toMetrics(config.compression, backup.byteSize)
            val anomaly = ScheduledBackupPolicy.detectAnomaly(config.baselineMetrics, metrics)
            val warningAlreadyPending = config.integrityWarning != null

            when {
                anomaly != null -> {
                    val warning = anomaly.description
                    ScheduledBackupSettings.recordSuspiciousSuccess(
                        context,
                        System.currentTimeMillis(),
                        fileName,
                        metrics,
                        warning
                    )
                    Log.e(TAG, "BACKUP INTEGRITY WARNING: $warning; older backups were preserved")
                    ScheduledBackupIntegrityNotifier.show(context)
                }
                warningAlreadyPending -> {
                    ScheduledBackupSettings.recordSuccessWhileWarningIsPending(
                        context,
                        System.currentTimeMillis(),
                        fileName,
                        metrics
                    )
                    Log.w(TAG, "Backup completed while an integrity warning is awaiting review; pruning skipped")
                    ScheduledBackupIntegrityNotifier.show(context)
                }
                else -> {
                    // A missing baseline usually means a first run or a reinstall pointed at
                    // an existing backup folder. Keep every older file until one known-good
                    // comparison exists; otherwise the first run could destroy recovery points.
                    if (config.baselineMetrics != null) {
                        removeExpiredBackups(destination, config)
                    }
                    ScheduledBackupSettings.recordSuccess(
                        context,
                        System.currentTimeMillis(),
                        fileName,
                        metrics
                    )
                }
            }
            Log.i(
                TAG,
                "Scheduled backup completed: $fileName (${backup.byteSize} bytes, " +
                    "${System.currentTimeMillis() - startedAt} ms)"
            )
            if (!forced) appendNextRun(context, ScheduledBackupSettings.load(context))
            Result.success(Data.Builder().putString(KEY_OUTPUT_FILE, fileName).build())
        } catch (cancelled: CancellationException) {
            // WorkManager stops a run after ten minutes or when its constraints lapse. Unrecorded,
            // that looked like a backup that never produced a result (#627). The app's own cancel
            // (settings changed, schedule turned off) is not a failure.
            val reason = stopReason
            if (isStopped && reason != WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                ScheduledBackupSettings.recordFailure(
                    context,
                    System.currentTimeMillis(),
                    context.getString(R.string.scheduled_backup_stopped_by_system)
                )
            }
            Log.i(TAG, "Scheduled backup cancelled after ${System.currentTimeMillis() - startedAt} ms (stop reason $reason)")
            throw cancelled
        } catch (exception: Throwable) {
            // An OutOfMemoryError is how a full-history export used to fail (#627). Escaping as
            // an Error would skip the failure record and the log, and a failed run never
            // appends the next scheduled one.
            val message = exception.localizedMessage ?: exception.javaClass.simpleName
            ScheduledBackupSettings.recordFailure(context, System.currentTimeMillis(), message)
            Log.stack(TAG, "Scheduled backup failed", exception)
            if (exception is IOException && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                if (forced) {
                    Result.failure()
                } else {
                    // The failure is persisted for the UI. Returning success here lets the
                    // already-appended calendar run execute; a failed chain would cancel it.
                    appendNextRun(context, ScheduledBackupSettings.load(context))
                    Result.success()
                }
            }
        }
    }

    private fun ExportPackageExporter.ExportSummary.toMetrics(
        compression: ExportCompression,
        byteSize: Long
    ) = ScheduledBackupMetrics(
        compression = compression,
        byteSize = byteSize,
        historyReadings = historyReadings,
        journalEntries = journalEntries,
        journalFoods = journalFoods,
        insulinPresets = insulinPresets,
        calibrations = calibrations
    )

    private fun createDocument(treeUri: Uri, fileName: String, mimeType: String): Uri {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        return DocumentsContract.createDocument(
            applicationContext.contentResolver,
            root,
            mimeType,
            fileName
        ) ?: error(applicationContext.getString(R.string.scheduled_backup_folder_error))
    }

    private fun removeExpiredBackups(treeUri: Uri, config: ScheduledBackupConfig) {
        val resolver = applicationContext.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val entries = mutableListOf<ScheduledBackupEntry>()
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                entries += ScheduledBackupEntry(
                    documentId = cursor.getString(idColumn),
                    displayName = cursor.getString(nameColumn),
                    lastModified = cursor.safeLong(modifiedColumn)
                )
            }
        }
        ScheduledBackupPolicy.entriesToDelete(
            entries = entries,
            dailyKeep = config.dailyRetention,
            weeklyKeep = config.weeklyRetention,
            monthlyKeep = config.monthlyRetention,
            zoneId = ZoneId.systemDefault()
        ).forEach { entry ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                .onFailure { Log.stack(TAG, "Could not remove expired backup ${entry.displayName}", it) }
        }
    }

    private fun Cursor.safeLong(column: Int): Long {
        return if (column >= 0 && !isNull(column)) getLong(column) else 0L
    }

    companion object {
        private const val TAG = "ScheduledBackup"
        private const val SCHEDULED_WORK_NAME = "scheduled_full_backup"
        private const val IMMEDIATE_WORK_NAME = "scheduled_full_backup_now"
        private const val KEY_FORCED = "forced"
        const val KEY_OUTPUT_FILE = "output_file"
        private const val MAX_RETRIES = 3
        private val backupMutex = Mutex()

        @JvmStatic
        fun initialize(context: Context) {
            val config = ScheduledBackupSettings.load(context)
            if (config.integrityWarning != null) {
                ScheduledBackupIntegrityNotifier.show(context)
            }
            if (!config.enabled || config.destination == null ||
                !ScheduledBackupSettings.hasPersistedWritePermission(context, config.destination)
            ) {
                cancel(context)
                return
            }
            enqueueScheduled(context, config, ExistingWorkPolicy.KEEP)
        }

        fun applyConfiguration(context: Context, config: ScheduledBackupConfig) {
            if (!config.enabled || config.destination == null ||
                !ScheduledBackupSettings.hasPersistedWritePermission(context, config.destination)
            ) {
                cancel(context)
                return
            }
            enqueueScheduled(context, config, ExistingWorkPolicy.REPLACE)
        }

        private fun appendNextRun(context: Context, config: ScheduledBackupConfig) {
            if (!config.enabled || config.destination == null ||
                !ScheduledBackupSettings.hasPersistedWritePermission(context, config.destination)
            ) return
            enqueueScheduled(context, config, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        private fun enqueueScheduled(
            context: Context,
            config: ScheduledBackupConfig,
            policy: ExistingWorkPolicy
        ) {
            val delay = ScheduledBackupPolicy.delayMillis(ZonedDateTime.now(), config)
            val request = OneTimeWorkRequest.Builder(ScheduledBackupWorker::class.java)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                SCHEDULED_WORK_NAME,
                policy,
                request
            )
        }

        fun runNow(context: Context): java.util.UUID {
            val request = OneTimeWorkRequest.Builder(ScheduledBackupWorker::class.java)
                .setInputData(Data.Builder().putBoolean(KEY_FORCED, true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            return request.id
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(SCHEDULED_WORK_NAME)
        }
    }
}
