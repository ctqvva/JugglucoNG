package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationManagerPolicyExtraTests {

    private fun entity(
        isRawMode: Boolean,
        isEnabled: Boolean = true,
        journalEntryId: Long? = null,
        sensorId: String = "SENSOR",
    ) = CalibrationEntity(
        timestamp = 1_700_000_000_000L,
        sensorId = sensorId,
        sensorValue = 6.5f,
        sensorValueRaw = 6.1f,
        userValue = 7.0f,
        isEnabled = isEnabled,
        isRawMode = isRawMode,
        journalEntryId = journalEntryId,
    )

    @Test
    fun matchesModeKeepsLaneCalibrationsSeparate() {
        assertTrue(CalibrationManager.matchesMode(entity(isRawMode = true), isRawMode = true))
        assertTrue(CalibrationManager.matchesMode(entity(isRawMode = false), isRawMode = false))
        assertFalse(CalibrationManager.matchesMode(entity(isRawMode = true), isRawMode = false))
        assertFalse(CalibrationManager.matchesMode(entity(isRawMode = false), isRawMode = true))
    }

    @Test
    fun journalCalibrationsCountInBothModesRegardlessOfEnabled() {
        val journal = entity(isRawMode = false, isEnabled = false, journalEntryId = 42L)
        assertTrue(CalibrationManager.matchesMode(journal, isRawMode = false))
        assertTrue(CalibrationManager.matchesMode(journal, isRawMode = true))
    }

    @Test
    fun algorithmStorageRoundTripsAndFallsBack() {
        CalibrationManager.CalibrationAlgorithm.values().forEach { algorithm ->
            assertEquals(algorithm, CalibrationManager.CalibrationAlgorithm.fromStorage(algorithm.storageValue))
        }
        assertEquals(
            CalibrationManager.CalibrationAlgorithm.ADAPTIVE_ENSEMBLE,
            CalibrationManager.CalibrationAlgorithm.fromStorage("unknown"),
        )
        assertEquals(
            CalibrationManager.CalibrationAlgorithm.ADAPTIVE_ENSEMBLE,
            CalibrationManager.CalibrationAlgorithm.fromStorage(null),
        )
    }

    @Test
    fun weightModeStorageRoundTripsAndFallsBack() {
        CalibrationManager.CalibrationWeightMode.values().forEach { mode ->
            assertEquals(mode, CalibrationManager.CalibrationWeightMode.fromStorage(mode.storageValue))
        }
        assertEquals(CalibrationManager.CalibrationWeightMode.FRESH, CalibrationManager.CalibrationWeightMode.fromStorage("nope"))
        assertEquals(CalibrationManager.CalibrationWeightMode.FRESH, CalibrationManager.CalibrationWeightMode.fromStorage(null))
    }

    @Test
    fun calibrationMatchesSensorRejectsBlankIds() {
        assertFalse(CalibrationManager.calibrationMatchesSensor(null, "ABC"))
        assertFalse(CalibrationManager.calibrationMatchesSensor("ABC", null))
        assertFalse(CalibrationManager.calibrationMatchesSensor("", "ABC"))
        assertFalse(CalibrationManager.calibrationMatchesSensor("   ", "ABC"))
    }
}
