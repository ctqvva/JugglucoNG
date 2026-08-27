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
    fun dailyWeeklyAndMonthlySchedulesUseLocalCalendarTime() {
        val zone = ZoneId.of("Europe/London")
        val now = ZonedDateTime.of(2026, 8, 27, 10, 30, 0, 0, zone)

        assertEquals(
            ZonedDateTime.of(2026, 8, 28, 3, 0, 0, 0, zone),
            ScheduledBackupPolicy.nextRunAt(now, config(ScheduledBackupFrequency.DAILY))
        )
        assertEquals(
            ZonedDateTime.of(2026, 8, 30, 3, 0, 0, 0, zone),
            ScheduledBackupPolicy.nextRunAt(
                now,
                config(ScheduledBackupFrequency.WEEKLY).copy(weeklyDay = 7)
            )
        )
        assertEquals(
            ZonedDateTime.of(2026, 9, 1, 3, 0, 0, 0, zone),
            ScheduledBackupPolicy.nextRunAt(
                now,
                config(ScheduledBackupFrequency.MONTHLY).copy(monthlyDay = 1)
            )
        )
    }

    @Test
    fun monthlyScheduleUsesLastDayWhenChosenDayDoesNotExist() {
        val zone = ZoneId.of("Europe/London")
        val now = ZonedDateTime.of(2027, 1, 31, 10, 0, 0, 0, zone)

        assertEquals(
            ZonedDateTime.of(2027, 2, 28, 3, 0, 0, 0, zone),
            ScheduledBackupPolicy.nextRunAt(
                now,
                config(ScheduledBackupFrequency.MONTHLY).copy(monthlyDay = 31)
            )
        )
    }

    @Test
    fun monthlyScheduleHandlesLeapYearsAndNonLeapCenturies() {
        val zone = ZoneId.of("Europe/London")
        val leapYear = ZonedDateTime.of(2028, 1, 31, 10, 0, 0, 0, zone)
        val nonLeapCentury = ZonedDateTime.of(2100, 1, 31, 10, 0, 0, 0, zone)
        val february29 = config(ScheduledBackupFrequency.MONTHLY).copy(monthlyDay = 29)

        assertEquals(
            ZonedDateTime.of(2028, 2, 29, 3, 0, 0, 0, zone),
            ScheduledBackupPolicy.nextRunAt(leapYear, february29)
        )
        assertEquals(
            ZonedDateTime.of(2100, 2, 28, 3, 0, 0, 0, zone),
            ScheduledBackupPolicy.nextRunAt(nonLeapCentury, february29)
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
    fun retentionOnlyDeletesOlderScheduledBackupFiles() {
        val entries = listOf(
            ScheduledBackupEntry("new", "Juggluco_AutoBackup_new.json.zst", 300),
            ScheduledBackupEntry("middle", "Juggluco_AutoBackup_middle.json.gz", 200),
            ScheduledBackupEntry("old", "Juggluco_AutoBackup_old.json.zst", 100),
            ScheduledBackupEntry("manual", "Juggluco_Everything_2026-08-27.json.zst", 50),
            ScheduledBackupEntry("other", "notes.txt", 10)
        )

        assertEquals(
            listOf("old"),
            ScheduledBackupPolicy.entriesToDelete(entries, keep = 2).map { it.documentId }
        )
    }

    @Test
    fun defaultScheduleIsDailyWithSevenRecoveryPoints() {
        assertEquals(ScheduledBackupFrequency.DAILY, ScheduledBackupFrequency.fromStorage(null))
        assertEquals(ScheduledBackupFrequency.DAILY, ScheduledBackupFrequency.fromStorage("unknown"))
        assertEquals(7, ScheduledBackupFrequency.DAILY.defaultRetention)
        assertEquals(4, ScheduledBackupFrequency.WEEKLY.defaultRetention)
        assertEquals(6, ScheduledBackupFrequency.MONTHLY.defaultRetention)
    }

    private fun config(frequency: ScheduledBackupFrequency) = ScheduledBackupConfig(
        enabled = false,
        destination = null,
        frequency = frequency,
        hour = 3,
        minute = 0,
        weeklyDay = 7,
        monthlyDay = 1,
        compression = ExportCompression.ZSTD,
        retentionCount = frequency.defaultRetention,
        lastSuccessAtMillis = 0,
        lastFileName = null,
        lastAttemptAtMillis = 0,
        lastError = null,
        integrityWarning = null,
        baselineMetrics = null,
        pendingMetrics = null
    )

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
