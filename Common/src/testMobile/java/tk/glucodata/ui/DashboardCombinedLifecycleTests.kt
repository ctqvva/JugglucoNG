package tk.glucodata.ui

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.ui.util.resolveDashboardSensorLifecycle
import tk.glucodata.ui.util.resolveDashboardSensorStatus

class DashboardCombinedLifecycleTests {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60_000L

    @Test fun nativeHistoryShellDoesNotGiveTheCombinedSourceAStartDateOrFourteenDayExpiry() {
        val id = "COMB-1234ABCD"
        val shell = longArrayOf(0, 2, now - 6 * 60 * 60_000L, now + 14 * day, now + 14 * day)
        for (managed in listOf(null, ManagedSensorUiSnapshot(id, "Combine sensors", ""))) {
            val summary = resolveDashboardSensorLifecycle(id, managed, shell, 14, now)
            assertEquals("", summary.daysText)
            assertEquals(0, summary.currentDay)
            assertEquals(0f, summary.progress)
            assertEquals(999L, summary.remainingHours)
        }
        assertEquals("", resolveDashboardSensorStatus(id, 0, now, "Warm-up"))
        assertEquals("", resolveDashboardSensorStatus(id, "Sensor expired"))
    }

    @Test fun physicalSensorKeepsItsActualSeventeenOfTwentyThreeDayLifecycle() {
        val start = now - 16 * day
        val native = longArrayOf(0, 0, start, start + 23 * day, start + 23 * day)
        val before = resolveDashboardSensorLifecycle("physical-test", null, native, 14, now)
        assertEquals("17 / 23", before.daysText)
        resolveDashboardSensorLifecycle("COMB-1234ABCD", null, native, 14, now)
        assertEquals(before, resolveDashboardSensorLifecycle("physical-test", null, native, 14, now))
        assertEquals(start, native[2])
        assertEquals(start + 23 * day, native[3])
    }
}
