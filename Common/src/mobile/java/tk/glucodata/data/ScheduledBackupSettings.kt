package tk.glucodata.data

import android.content.Context
import android.net.Uri

enum class ScheduledBackupFrequency(val defaultRetention: Int) {
    DAILY(5),
    WEEKLY(4),
    MONTHLY(6);

    companion object {
        fun fromStorage(value: String?): ScheduledBackupFrequency {
            return entries.firstOrNull { it.name == value } ?: DAILY
        }
    }
}

data class ScheduledBackupMetrics(
    val compression: ExportCompression,
    val byteSize: Long,
    val historyReadings: Int,
    val journalEntries: Int,
    val journalFoods: Int,
    val insulinPresets: Int,
    val calibrations: Int
)

data class ScheduledBackupConfig(
    val enabled: Boolean,
    val destination: Uri?,
    val hour: Int,
    val minute: Int,
    val compression: ExportCompression,
    val dailyRetention: Int,
    val weeklyRetention: Int,
    val monthlyRetention: Int,
    val lastSuccessAtMillis: Long,
    val lastFileName: String?,
    val lastAttemptAtMillis: Long,
    val lastError: String?,
    val integrityWarning: String?,
    val baselineMetrics: ScheduledBackupMetrics?,
    val pendingMetrics: ScheduledBackupMetrics?
)

object ScheduledBackupSettings {
    private const val PREFS = "scheduled_backups"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DESTINATION = "destination"
    private const val KEY_FREQUENCY = "frequency"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_WEEKLY_DAY = "weekly_day"
    private const val KEY_MONTHLY_DAY = "monthly_day"
    private const val KEY_COMPRESSION = "compression"
    private const val KEY_RETENTION = "retention"
    private const val KEY_DAILY_RETENTION = "daily_retention"
    private const val KEY_WEEKLY_RETENTION = "weekly_retention"
    private const val KEY_MONTHLY_RETENTION = "monthly_retention"
    private const val KEY_LAST_SUCCESS = "last_success"
    private const val KEY_LAST_FILE = "last_file"
    private const val KEY_LAST_ATTEMPT = "last_attempt"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_INTEGRITY_WARNING = "integrity_warning"
    private const val BASELINE_PREFIX = "baseline_"
    private const val PENDING_PREFIX = "pending_"

    val retentionOptions = listOf(1, 3, 4, 5, 6, 7, 14, 30)

    fun load(context: Context): ScheduledBackupConfig {
        val prefs = prefs(context)
        val legacyFrequency = ScheduledBackupFrequency.fromStorage(prefs.getString(KEY_FREQUENCY, null))
        val legacyRetention = prefs.getInt(KEY_RETENTION, legacyFrequency.defaultRetention)
            .takeIf { it in retentionOptions }
            ?: legacyFrequency.defaultRetention
        fun retention(key: String, tier: ScheduledBackupFrequency): Int {
            val fallback = if (legacyFrequency == tier) legacyRetention else tier.defaultRetention
            return prefs.getInt(key, fallback).takeIf { it in retentionOptions } ?: fallback
        }
        return ScheduledBackupConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            destination = prefs.getString(KEY_DESTINATION, null)?.let(Uri::parse),
            hour = prefs.getInt(KEY_HOUR, 3).coerceIn(0, 23),
            minute = prefs.getInt(KEY_MINUTE, 0).coerceIn(0, 59),
            compression = runCatching {
                ExportCompression.valueOf(prefs.getString(KEY_COMPRESSION, null) ?: "")
            }.getOrNull()
                ?.takeIf { it == ExportCompression.GZIP || it == ExportCompression.ZSTD }
                ?: ExportCompression.GZIP,
            dailyRetention = retention(KEY_DAILY_RETENTION, ScheduledBackupFrequency.DAILY),
            weeklyRetention = retention(KEY_WEEKLY_RETENTION, ScheduledBackupFrequency.WEEKLY),
            monthlyRetention = retention(KEY_MONTHLY_RETENTION, ScheduledBackupFrequency.MONTHLY),
            lastSuccessAtMillis = prefs.getLong(KEY_LAST_SUCCESS, 0L),
            lastFileName = prefs.getString(KEY_LAST_FILE, null),
            lastAttemptAtMillis = prefs.getLong(KEY_LAST_ATTEMPT, 0L),
            lastError = prefs.getString(KEY_LAST_ERROR, null),
            integrityWarning = prefs.getString(KEY_INTEGRITY_WARNING, null),
            baselineMetrics = readMetrics(prefs, BASELINE_PREFIX),
            pendingMetrics = readMetrics(prefs, PENDING_PREFIX)
        )
    }

    fun saveConfiguration(context: Context, config: ScheduledBackupConfig) {
        require(!config.enabled || config.destination != null) { "A backup folder is required" }
        require(config.dailyRetention in retentionOptions) { "Unsupported daily retention count" }
        require(config.weeklyRetention in retentionOptions) { "Unsupported weekly retention count" }
        require(config.monthlyRetention in retentionOptions) { "Unsupported monthly retention count" }
        require(config.compression != ExportCompression.NONE) {
            "Scheduled backups must use compression"
        }
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_DESTINATION, config.destination?.toString())
            .putInt(KEY_HOUR, config.hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, config.minute.coerceIn(0, 59))
            .putString(KEY_COMPRESSION, config.compression.name)
            .putInt(KEY_DAILY_RETENTION, config.dailyRetention)
            .putInt(KEY_WEEKLY_RETENTION, config.weeklyRetention)
            .putInt(KEY_MONTHLY_RETENTION, config.monthlyRetention)
            .remove(KEY_FREQUENCY)
            .remove(KEY_WEEKLY_DAY)
            .remove(KEY_MONTHLY_DAY)
            .remove(KEY_RETENTION)
            .apply()
    }

    fun recordSuccess(
        context: Context,
        timestamp: Long,
        fileName: String,
        metrics: ScheduledBackupMetrics
    ) {
        prefs(context).edit()
            .putLong(KEY_LAST_ATTEMPT, timestamp)
            .putLong(KEY_LAST_SUCCESS, timestamp)
            .putString(KEY_LAST_FILE, fileName)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_INTEGRITY_WARNING)
            .removeMetrics(PENDING_PREFIX)
            .putMetrics(BASELINE_PREFIX, metrics)
            .apply()
    }

    fun recordSuspiciousSuccess(
        context: Context,
        timestamp: Long,
        fileName: String,
        metrics: ScheduledBackupMetrics,
        warning: String
    ) {
        prefs(context).edit()
            .putLong(KEY_LAST_ATTEMPT, timestamp)
            .putLong(KEY_LAST_SUCCESS, timestamp)
            .putString(KEY_LAST_FILE, fileName)
            .remove(KEY_LAST_ERROR)
            .putString(KEY_INTEGRITY_WARNING, warning.take(1_000))
            .putMetrics(PENDING_PREFIX, metrics)
            .apply()
    }

    fun recordSuccessWhileWarningIsPending(
        context: Context,
        timestamp: Long,
        fileName: String,
        metrics: ScheduledBackupMetrics
    ) {
        prefs(context).edit()
            .putLong(KEY_LAST_ATTEMPT, timestamp)
            .putLong(KEY_LAST_SUCCESS, timestamp)
            .putString(KEY_LAST_FILE, fileName)
            .remove(KEY_LAST_ERROR)
            .putMetrics(PENDING_PREFIX, metrics)
            .apply()
    }

    fun recordFailure(context: Context, timestamp: Long, error: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_ATTEMPT, timestamp)
            .putString(KEY_LAST_ERROR, error.take(500))
            .apply()
    }

    fun acknowledgeIntegrityWarning(context: Context) {
        val current = load(context)
        prefs(context).edit()
            .remove(KEY_INTEGRITY_WARNING)
            .removeMetrics(PENDING_PREFIX)
            .also { editor -> current.pendingMetrics?.let { editor.putMetrics(BASELINE_PREFIX, it) } }
            .apply()
    }

    fun hasPersistedWritePermission(context: Context, destination: Uri?): Boolean {
        if (destination == null) return false
        return context.applicationContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == destination && permission.isWritePermission
        }
    }

    private fun readMetrics(
        prefs: android.content.SharedPreferences,
        prefix: String
    ): ScheduledBackupMetrics? {
        if (!prefs.contains(prefix + "compression")) return null
        val compression = runCatching {
            ExportCompression.valueOf(prefs.getString(prefix + "compression", null) ?: "")
        }.getOrNull() ?: return null
        return ScheduledBackupMetrics(
            compression = compression,
            byteSize = prefs.getLong(prefix + "bytes", 0L),
            historyReadings = prefs.getInt(prefix + "history", 0),
            journalEntries = prefs.getInt(prefix + "journal", 0),
            journalFoods = prefs.getInt(prefix + "foods", 0),
            insulinPresets = prefs.getInt(prefix + "insulins", 0),
            calibrations = prefs.getInt(prefix + "calibrations", 0)
        )
    }

    private fun android.content.SharedPreferences.Editor.putMetrics(
        prefix: String,
        metrics: ScheduledBackupMetrics
    ) = putString(prefix + "compression", metrics.compression.name)
        .putLong(prefix + "bytes", metrics.byteSize)
        .putInt(prefix + "history", metrics.historyReadings)
        .putInt(prefix + "journal", metrics.journalEntries)
        .putInt(prefix + "foods", metrics.journalFoods)
        .putInt(prefix + "insulins", metrics.insulinPresets)
        .putInt(prefix + "calibrations", metrics.calibrations)

    private fun android.content.SharedPreferences.Editor.removeMetrics(prefix: String) =
        remove(prefix + "compression")
            .remove(prefix + "bytes")
            .remove(prefix + "history")
            .remove(prefix + "journal")
            .remove(prefix + "foods")
            .remove(prefix + "insulins")
            .remove(prefix + "calibrations")

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
