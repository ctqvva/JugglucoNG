package tk.glucodata.ui.restricted

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.RestrictedModeConfig

class RestrictedModeRoutesTests {
    private val closed = RestrictedModeConfig(enabled = true, allowJournal = false, allowCalibration = false)
    private val open = RestrictedModeConfig(enabled = true, allowJournal = true, allowCalibration = true)

    @Test fun everydayScreensStayOpen() {
        for (route in listOf(null, "dashboard", "stats", "sensors", "history")) {
            assertTrue(route.toString(), RestrictedModeRoutes.isOpenWhileLocked(route, closed))
        }
    }

    @Test fun settingsAndEverythingUnderThemNeedFullMode() {
        for (route in listOf(
            "settings",
            "settings/restricted-mode",
            "settings/alerts",
            "settings/data-smoothing",
            "settings/calibrations",
            "settings/calibrations/model-table",
            "settings/journal/history",
            "settings/debug",
        )) {
            assertFalse(route, RestrictedModeRoutes.isOpenWhileLocked(route, open))
        }
    }

    @Test fun journalAndCalibrationFollowTheirSwitches() {
        assertFalse(RestrictedModeRoutes.isOpenWhileLocked("journal", closed))
        assertTrue(RestrictedModeRoutes.isOpenWhileLocked("journal", open))
        for (route in listOf("calibrations", "calibrations/model-table")) {
            assertFalse(route, RestrictedModeRoutes.isOpenWhileLocked(route, closed))
            assertTrue(route, RestrictedModeRoutes.isOpenWhileLocked(route, open))
        }
    }

    @Test fun anUnknownRouteIsClosed() {
        assertFalse(RestrictedModeRoutes.isOpenWhileLocked("some/new-screen", open))
    }

    @Test fun daysLeftRoundDown() {
        val day = 24L * 60L * 60L * 1000L
        assertEquals(0, restrictedSensorDaysLeft(-1L))
        assertEquals(0, restrictedSensorDaysLeft(0L))
        assertEquals(0, restrictedSensorDaysLeft(day - 1))
        assertEquals(1, restrictedSensorDaysLeft(day))
        assertEquals(13, restrictedSensorDaysLeft(14 * day - 1))
    }
}
