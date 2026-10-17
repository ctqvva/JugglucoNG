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
}
