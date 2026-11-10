package tk.glucodata.data.calibration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCalibrationEnablementPolicyTests {
    private val matches: (String, String) -> Boolean = { left, right ->
        left.equals(right, ignoreCase = true) ||
            setOf(left.lowercase(), right.lowercase()) == setOf("sensor-new", "new-alias")
    }

    @Test
    fun disablingReplacementSensorDoesNotDisablePreviousSensor() {
        val disabled = SensorCalibrationEnablementPolicy.update(
            disabledSensorIds = emptySet(),
            sensorId = "sensor-new",
            disabled = true,
            matches = matches
        )

        assertFalse(SensorCalibrationEnablementPolicy.isEnabled(disabled, "sensor-new", matches))
        assertTrue(SensorCalibrationEnablementPolicy.isEnabled(disabled, "sensor-old", matches))
    }

    @Test
    fun legacyGlobalDisabledStateMigratesToCurrentSensorOnly() {
        val migrated = SensorCalibrationEnablementPolicy.migrateLegacyGlobalState(
            rawEnabled = true,
            autoEnabled = false,
            currentSensorId = "sensor-new",
            existing = SensorCalibrationDisabledSets(emptySet(), emptySet()),
            matches = matches
        )

        assertTrue(SensorCalibrationEnablementPolicy.isEnabled(migrated.raw, "sensor-new", matches))
        assertFalse(SensorCalibrationEnablementPolicy.isEnabled(migrated.auto, "sensor-new", matches))
        assertTrue(SensorCalibrationEnablementPolicy.isEnabled(migrated.auto, "sensor-old", matches))
    }

    @Test
    fun enablingCanonicalSensorRemovesDisabledAlias() {
        val disabled = SensorCalibrationEnablementPolicy.update(
            disabledSensorIds = setOf("new-alias"),
            sensorId = "sensor-new",
            disabled = false,
            matches = matches
        )

        assertTrue(disabled.isEmpty())
        assertTrue(SensorCalibrationEnablementPolicy.isEnabled(disabled, "sensor-new", matches))
    }
}
