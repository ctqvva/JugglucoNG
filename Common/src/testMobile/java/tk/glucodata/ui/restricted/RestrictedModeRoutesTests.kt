package tk.glucodata.ui.restricted

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.RestrictedMode
import tk.glucodata.RestrictedModeConfig
import tk.glucodata.RestrictedModeState
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.components.SensorType

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

    @Test fun journalFollowsItsSwitch() {
        assertFalse(RestrictedModeRoutes.isOpenWhileLocked("journal", closed))
        assertTrue(RestrictedModeRoutes.isOpenWhileLocked("journal", open))
    }

    @Test fun calibrationScreensNeedFullModeEvenWhenCalibrationIsAllowed() {
        // They hold the calibration settings and "clear all"; allowing calibration only
        // opens the add/edit sheet.
        for (route in listOf("calibrations", "calibrations/model-table")) {
            assertFalse(route, RestrictedModeRoutes.isOpenWhileLocked(route, open))
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

    @Test fun libreFamilyNameMatchesThePicker() {
        // ScanNfcV checks Libre by this name; the picker stores SensorType names.
        assertEquals(SensorType.LIBRE.name, RestrictedMode.LIBRE_FAMILY)
    }

    @Test fun addableSensorTypesFollowTheAdultsList() {
        assertEquals(null, RestrictedModeState(RestrictedModeConfig(enabled = false)).addableSensorTypes())
        assertEquals(
            emptySet<SensorType>(),
            RestrictedModeState(RestrictedModeConfig(enabled = true, allowedSensorTypes = setOf("LIBRE"))).addableSensorTypes(),
        )
        assertEquals(
            setOf(SensorType.LIBRE, SensorType.AIDEX),
            RestrictedModeState(
                RestrictedModeConfig(
                    enabled = true,
                    allowSensorChange = true,
                    allowedSensorTypes = setOf("LIBRE", "AIDEX", "NOT_A_FAMILY"),
                )
            ).addableSensorTypes(),
        )
    }

    @Test fun onlyBloodGlucoseEntriesTouchCalibration() {
        val bg = JournalEntryType.FINGERSTICK
        val other = JournalEntryType.entries.first { it != bg }
        assertTrue(journalChangeTouchesCalibration(null, listOf(bg)))
        assertTrue(journalChangeTouchesCalibration(bg, listOf(other))) // a BG entry changed into something else
        assertTrue(journalChangeTouchesCalibration(null, listOf(other, bg)))
        assertFalse(journalChangeTouchesCalibration(null, listOf(other)))
        assertFalse(journalChangeTouchesCalibration(other, listOf(other)))
    }
}
