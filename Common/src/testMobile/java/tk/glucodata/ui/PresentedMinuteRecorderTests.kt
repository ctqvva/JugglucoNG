package tk.glucodata.ui

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import tk.glucodata.data.HistoryRepository

class PresentedMinuteRecorderTests {
    @Before fun before() = PresentedMinuteRecorder.reset()
    @After fun after() = PresentedMinuteRecorder.reset()
    private val minute = HistoryRepository.PresentedMinute(60_000L, 120f, "A", 0)

    @Test fun failedOrDisabledWriteCanBeRetried() = runBlocking {
        assertEquals(0, PresentedMinuteRecorder.recordVisible(listOf(minute)) { null })
        var attempts = 0
        assertEquals(1, PresentedMinuteRecorder.recordVisible(listOf(minute)) { attempts++; 1 })
        assertEquals(0, PresentedMinuteRecorder.recordVisible(listOf(minute)) { attempts++; 1 })
        assertEquals(1, attempts)
    }

    @Test fun existingSealedRecordIsRememberedButUnsealedMinutesAreRevised() = runBlocking {
        var attempts = 0
        val write: suspend (List<HistoryRepository.PresentedMinute>) -> Int? = { attempts++; 0 }
        PresentedMinuteRecorder.recordVisible(listOf(minute), write)
        PresentedMinuteRecorder.recordVisible(listOf(minute), write)
        assertEquals(1, attempts)
        PresentedMinuteRecorder.releaseUnsealed(0L)
        PresentedMinuteRecorder.recordVisible(listOf(minute.copy(displayMgdl = 130f)), write)
        assertEquals(2, attempts)
    }

    @Test fun aMinuteWrittenForOneLineIsStillUnseenOnTheOther() = runBlocking {
        // Seen on auto, sealed. A switch to raw shows the raw line for the first
        // time, so the raw line's record of the same minute is still owed.
        val written = ArrayList<List<HistoryRepository.PresentedMinute>>()
        val write: suspend (List<HistoryRepository.PresentedMinute>) -> Int? = { written += it; it.size }
        PresentedMinuteRecorder.recordVisible(listOf(minute), write)
        PresentedMinuteRecorder.recordVisible(listOf(minute.copy(viewMode = 2)), write)
        assertEquals("0 and 2 are the same main line", 1, written.size)
        PresentedMinuteRecorder.recordVisible(listOf(minute.copy(displayMgdl = 98f, viewMode = 1)), write)
        PresentedMinuteRecorder.recordVisible(listOf(minute.copy(displayMgdl = 98f, viewMode = 3)), write)
        assertEquals(listOf(listOf(0), listOf(1)), written.map { batch -> batch.map { it.viewMode } })
    }
}
