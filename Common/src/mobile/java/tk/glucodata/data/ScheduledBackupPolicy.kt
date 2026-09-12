package tk.glucodata.data

import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
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
    private val fileTimestampFormatter = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd_HH-mm-ss'Z'",
        Locale.US
    )
    private val fileNamePattern = Regex(
        "^Juggluco_AutoBackup_(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}Z)\\.json\\.(?:gz|zst)$"
    )

    fun fileName(timestamp: Long, compression: ExportCompression): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "$FILE_PREFIX${formatter.format(Date(timestamp))}${compression.fileSuffix}"
    }

    fun nextRunAt(now: ZonedDateTime, config: ScheduledBackupConfig): ZonedDateTime {
        val localTime = now.toLocalDate().atTime(config.hour, config.minute).atZone(now.zone)
        return if (localTime.isAfter(now)) localTime else localTime.plusDays(1)
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
        dailyKeep: Int,
        weeklyKeep: Int,
        monthlyKeep: Int,
        zoneId: ZoneId
    ): List<ScheduledBackupEntry> {
        val dated = entries.mapNotNull { entry ->
            backupTimestamp(entry.displayName)?.let { timestamp -> entry to timestamp }
        }.sortedWith(
            compareByDescending<Pair<ScheduledBackupEntry, Long>> { it.second }
                .thenByDescending { it.first.displayName }
        )
        val keepIds = mutableSetOf<String>()

        fun <T> keepNewestPerPeriod(limit: Int, period: (Long) -> T) {
            dated.distinctBy { period(it.second) }
                .take(limit.coerceAtLeast(1))
                .forEach { keepIds += it.first.documentId }
        }

        fun localDate(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        keepNewestPerPeriod(dailyKeep) { timestamp -> localDate(timestamp) }
        keepNewestPerPeriod(weeklyKeep) { timestamp ->
            val date = localDate(timestamp)
            date.get(IsoFields.WEEK_BASED_YEAR) to date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        }
        keepNewestPerPeriod(monthlyKeep) { timestamp -> YearMonth.from(localDate(timestamp)) }

        return dated.map { it.first }.filterNot { it.documentId in keepIds }
    }

    private fun compareCount(label: String, previous: Int, current: Int): String? {
        return if (previous > 0 && current * 2 < previous) {
            "$label fell from $previous to $current"
        } else {
            null
        }
    }

    private fun backupTimestamp(name: String): Long? {
        val stamp = fileNamePattern.matchEntire(name)?.groupValues?.get(1) ?: return null
        return runCatching {
            LocalDateTime.parse(stamp, fileTimestampFormatter)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()
    }
}
