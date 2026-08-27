package tk.glucodata.data

import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class ScheduledBackupEntry(
    val documentId: String,
    val displayName: String,
    val lastModified: Long
)

internal data class ScheduledBackupAnomaly(val reasons: List<String>) {
    val description: String get() = reasons.joinToString("; ")
}

internal object ScheduledBackupPolicy {
    private const val FILE_PREFIX = "Juggluco_AutoBackup_"

    fun fileName(timestamp: Long, compression: ExportCompression): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "$FILE_PREFIX${formatter.format(Date(timestamp))}${compression.fileSuffix}"
    }

    fun nextRunAt(now: ZonedDateTime, config: ScheduledBackupConfig): ZonedDateTime {
        val localTime = now.toLocalDate().atTime(config.hour, config.minute).atZone(now.zone)
        return when (config.frequency) {
            ScheduledBackupFrequency.DAILY -> {
                if (localTime.isAfter(now)) localTime else localTime.plusDays(1)
            }
            ScheduledBackupFrequency.WEEKLY -> {
                var candidate = localTime.with(
                    TemporalAdjusters.nextOrSame(DayOfWeek.of(config.weeklyDay.coerceIn(1, 7)))
                )
                if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1)
                candidate
            }
            ScheduledBackupFrequency.MONTHLY -> {
                var month = YearMonth.from(localTime)
                var candidate = month.atDay(config.monthlyDay.coerceIn(1, month.lengthOfMonth()))
                    .atTime(config.hour, config.minute)
                    .atZone(now.zone)
                if (!candidate.isAfter(now)) {
                    month = month.plusMonths(1)
                    candidate = month.atDay(config.monthlyDay.coerceIn(1, month.lengthOfMonth()))
                        .atTime(config.hour, config.minute)
                        .atZone(now.zone)
                }
                candidate
            }
        }
    }

    fun delayMillis(now: ZonedDateTime, config: ScheduledBackupConfig): Long {
        return Duration.between(now, nextRunAt(now, config)).toMillis().coerceAtLeast(0L)
    }

    fun detectAnomaly(
        previous: ScheduledBackupMetrics?,
        current: ScheduledBackupMetrics
    ): ScheduledBackupAnomaly? {
        if (previous == null) return null
        val reasons = buildList {
            if (previous.compression == current.compression &&
                previous.byteSize > 0L && current.byteSize > 0L &&
                current.byteSize * 2L < previous.byteSize
            ) {
                add("backup size fell from ${previous.byteSize} to ${current.byteSize} bytes")
            }
            compareCount("history readings", previous.historyReadings, current.historyReadings)?.let(::add)
            compareCount("journal entries", previous.journalEntries, current.journalEntries)?.let(::add)
            compareCount("food presets", previous.journalFoods, current.journalFoods)?.let(::add)
            compareCount("insulin presets", previous.insulinPresets, current.insulinPresets)?.let(::add)
            compareCount("calibrations", previous.calibrations, current.calibrations)?.let(::add)
        }
        return reasons.takeIf { it.isNotEmpty() }?.let(::ScheduledBackupAnomaly)
    }

    fun entriesToDelete(
        entries: List<ScheduledBackupEntry>,
        keep: Int
    ): List<ScheduledBackupEntry> {
        return entries
            .filter { isScheduledBackupFile(it.displayName) }
            .sortedWith(
                compareByDescending<ScheduledBackupEntry> { it.lastModified }
                    .thenByDescending { it.displayName }
            )
            .drop(keep.coerceAtLeast(1))
    }

    private fun compareCount(label: String, previous: Int, current: Int): String? {
        return if (previous > 0 && current * 2 < previous) {
            "$label fell from $previous to $current"
        } else {
            null
        }
    }

    private fun isScheduledBackupFile(name: String): Boolean {
        return name.startsWith(FILE_PREFIX) &&
            (name.endsWith(ExportCompression.GZIP.fileSuffix) ||
                name.endsWith(ExportCompression.ZSTD.fileSuffix))
    }
}
