package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduledBackupPolicyTests {

    @Test
    fun scheduledBackupsUseUtcTimestampAndSelectedCompression() {
        assertEquals(
            "Juggluco_AutoBackup_2023-11-14_22-13-20Z.json.zst",
            ScheduledBackupPolicy.fileName(1_700_000_000_000L, ExportCompression.ZSTD)
        )
        assertEquals(
            "Juggluco_AutoBackup_2023-11-14_22-13-20Z.json.gz",
            ScheduledBackupPolicy.fileName(1_700_000_000_000L, ExportCompression.GZIP)
        )
    }

    @Test
    fun scheduledBackupRunsEveryDayAtLocalCalendarTime() {
        val zone = ZoneId.of("Europe/London")
        val now = ZonedDateTime.of(2026, 8, 27, 10, 30, 0, 0, zone)

        assertEquals(
            ZonedDateTime.of(2026, 8, 28, 3, 0, 0, 0, zone),
            ScheduledBackupPolicy.nextRunAt(now, config())
        )
    }

    @Test
    fun dailyScheduleKeepsLocalTimeAcrossDaylightSavingChange() {
        val zone = ZoneId.of("Europe/London")
        val now = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, zone)

        assertEquals(
            ZonedDateTime.of(2026, 10, 25, 3, 0, 0, 0, zone),
            ScheduledBackupPolicy.nextRunAt(now, config())
        )
    }

    @Test
    fun moreThanHalfSizeDropTriggersOnlyForMatchingCompression() {
        val previous = metrics(ExportCompression.ZSTD, bytes = 1_000)

        assertTrue(
            ScheduledBackupPolicy.detectAnomaly(
                previous,
                metrics(ExportCompression.ZSTD, bytes = 499)
            )!!.description.contains("backup size")
        )
        assertNull(
            ScheduledBackupPolicy.detectAnomaly(
                previous,
                metrics(ExportCompression.ZSTD, bytes = 500)
            )
        )
        assertNull(
            ScheduledBackupPolicy.detectAnomaly(
                previous,
                metrics(ExportCompression.GZIP, bytes = 100)
            )
        )
    }

    @Test
    fun criticalRecordCountCollapseTriggersAcrossCompressionChanges() {
        val previous = metrics(ExportCompression.GZIP, bytes = 1_000).copy(historyReadings = 100)
        val current = metrics(ExportCompression.ZSTD, bytes = 600).copy(historyReadings = 49)

        assertTrue(
            ScheduledBackupPolicy.detectAnomaly(previous, current)!!
                .description.contains("history readings fell from 100 to 49")
        )
    }

    @Test
    fun retentionKeepsSimultaneousDailyWeeklyAndMonthlyRecoveryPoints() {
        val zone = ZoneId.of("UTC")
        val scheduledDates = listOf(
            "2026-08-27", "2026-08-26", "2026-08-25", "2026-08-24", "2026-08-23",
            "2026-08-22", "2026-08-16", "2026-08-09", "2026-08-02", "2026-07-31",
            "2026-07-26", "2026-06-30", "2026-05-31", "2026-04-30", "2026-03-31",
            "2026-02-28"
        )
        val entries = scheduledDates.map(::scheduledEntry) + listOf(
            ScheduledBackupEntry("manual", "Juggluco_Everything_2026-08-27.json.zst", 50),
            ScheduledBackupEntry("unknown", "Juggluco_AutoBackup_unknown.json.zst", 10)
        )

        assertEquals(
            setOf("2026-08-22", "2026-08-02", "2026-07-26", "2026-02-28"),
            ScheduledBackupPolicy.entriesToDelete(
                entries = entries,
                dailyKeep = 5,
                weeklyKeep = 4,
                monthlyKeep = 6,
                zoneId = zone
            ).mapTo(mutableSetOf()) { it.documentId }
        )
    }

    @Test
    fun defaultRetentionMatchesTheRequestedRecoveryLadder() {
        assertEquals(ScheduledBackupFrequency.DAILY, ScheduledBackupFrequency.fromStorage(null))
        assertEquals(ScheduledBackupFrequency.DAILY, ScheduledBackupFrequency.fromStorage("unknown"))
        assertEquals(5, ScheduledBackupFrequency.DAILY.defaultRetention)
        assertEquals(4, ScheduledBackupFrequency.WEEKLY.defaultRetention)
        assertEquals(6, ScheduledBackupFrequency.MONTHLY.defaultRetention)
    }

    private fun config() = ScheduledBackupConfig(
        enabled = false,
        destination = null,
        hour = 3,
        minute = 0,
        compression = ExportCompression.GZIP,
        dailyRetention = 5,
        weeklyRetention = 4,
        monthlyRetention = 6,
        lastSuccessAtMillis = 0,
        lastFileName = null,
        lastAttemptAtMillis = 0,
        lastError = null,
        integrityWarning = null,
        baselineMetrics = null,
        pendingMetrics = null
    )

    private fun scheduledEntry(date: String): ScheduledBackupEntry {
        val timestamp = ZonedDateTime.parse("${date}T03:00:00Z").toInstant().toEpochMilli()
        return ScheduledBackupEntry(
            documentId = date,
            displayName = ScheduledBackupPolicy.fileName(timestamp, ExportCompression.GZIP),
            lastModified = timestamp
        )
    }

    private fun metrics(compression: ExportCompression, bytes: Long) = ScheduledBackupMetrics(
        compression = compression,
        byteSize = bytes,
        historyReadings = 100,
        journalEntries = 10,
        journalFoods = 5,
        insulinPresets = 5,
        calibrations = 5
    )
}
