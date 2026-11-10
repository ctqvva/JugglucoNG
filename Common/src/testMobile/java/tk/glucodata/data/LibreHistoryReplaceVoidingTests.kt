package tk.glucodata.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A Libre's old export stored a history value as a row of its own; a re-sync
 * replaces it with the stream reading of that minute. The value recorded from
 * the history row is voided by the same replace, so no crash in between can
 * leave it on screen with the row it was judged against already gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LibreHistoryReplaceVoidingTests {
    private val serial = "libre-replace-voiding"
    private val minute = 1_790_000_040_000L
    private val historyAt = minute + 5_000L

    @Test
    fun replacingAHistoryRowVoidsTheValueRecordedFromItInTheSameWrite() = runBlocking {
        val database = HistoryDatabase.getInstance(ApplicationProvider.getApplicationContext())
        val repository = HistoryRepository(ApplicationProvider.getApplicationContext())
        database.historyDao().insertAll(
            listOf(HistoryReading(timestamp = historyAt, sensorSerial = serial, value = 108f, rawValue = 0f, rate = null))
        )
        database.readingDisplayDao().sealAll(
            listOf(ReadingDisplay(minute, serial, 108f, viewMode = 0, calibrationFingerprint = 0L, recordedAt = minute))
        )

        val outcome = repository.replaceSensorBuckets(
            sensorSerial = serial,
            readings = listOf(HistoryReading(timestamp = minute + 30_000L, sensorSerial = serial, value = 115f, rawValue = 108f, rate = null)),
            bucketDurationMs = 60_000L,
            historyTimestamps = setOf(historyAt),
        )

        assertEquals(HistoryRepository.BucketReplaceOutcome.STORED, outcome)
        assertNull(database.readingDisplayDao().getBetween(minute, minute).firstOrNull())
        assertTrue(database.historyDao().getSensorReadingsInTimeRange(serial, minute, minute + 60_000L).all { it.value == 115f })
    }
}
