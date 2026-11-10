package tk.glucodata.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalSensorModePolicyTests {
    @Test
    fun storedHistoricalModeWinsOverCurrentSensorMode() {
        val mode = HistoricalSensorModePolicy.resolve(
            activeViewMode = 0,
            isImported = false,
            resolvedStoredMode = 3,
            hasRawCalibration = false,
            hasAutoCalibration = true,
            matchesActiveSensor = false
        )

        assertEquals(3, mode)
    }

    @Test
    fun singleCalibrationLaneRecoversMissingHistoricalMode() {
        val rawMode = HistoricalSensorModePolicy.resolve(
            activeViewMode = 0,
            isImported = false,
            resolvedStoredMode = null,
            hasRawCalibration = true,
            hasAutoCalibration = false,
            matchesActiveSensor = false
        )
        val autoMode = HistoricalSensorModePolicy.resolve(
            activeViewMode = 3,
            isImported = false,
            resolvedStoredMode = null,
            hasRawCalibration = false,
            hasAutoCalibration = true,
            matchesActiveSensor = false
        )

        assertEquals(1, rawMode)
        assertEquals(0, autoMode)
    }

    @Test
    fun unknownPreviousSensorDefaultsToAutoAndActiveSensorUsesCurrentMode() {
        val previousMode = HistoricalSensorModePolicy.resolve(
            activeViewMode = 3,
            isImported = false,
            resolvedStoredMode = null,
            hasRawCalibration = false,
            hasAutoCalibration = false,
            matchesActiveSensor = false
        )
        val activeMode = HistoricalSensorModePolicy.resolve(
            activeViewMode = 3,
            isImported = false,
            resolvedStoredMode = null,
            hasRawCalibration = false,
            hasAutoCalibration = false,
            matchesActiveSensor = true
        )

        assertEquals(0, previousMode)
        assertEquals(3, activeMode)
    }

    @Test
    fun importedHistoryAlwaysUsesAutoLane() {
        val mode = HistoricalSensorModePolicy.resolve(
            activeViewMode = 3,
            isImported = true,
            resolvedStoredMode = 1,
            hasRawCalibration = true,
            hasAutoCalibration = false,
            matchesActiveSensor = true
        )

        assertEquals(0, mode)
    }
}
