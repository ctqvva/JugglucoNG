package tk.glucodata.data.calibration

internal data class SensorCalibrationDisabledSets(
    val raw: Set<String>,
    val auto: Set<String>
)

internal object SensorCalibrationEnablementPolicy {
    fun isEnabled(
        disabledSensorIds: Set<String>,
        sensorId: String,
        matches: (String, String) -> Boolean
    ): Boolean = disabledSensorIds.none { stored -> matches(stored, sensorId) }

    fun update(
        disabledSensorIds: Set<String>,
        sensorId: String,
        disabled: Boolean,
        matches: (String, String) -> Boolean
    ): Set<String> {
        val updated = disabledSensorIds
            .filterNot { stored -> matches(stored, sensorId) }
            .toMutableSet()
        if (disabled) updated.add(sensorId)
        return updated
    }

    fun migrateLegacyGlobalState(
        rawEnabled: Boolean,
        autoEnabled: Boolean,
        currentSensorId: String,
        existing: SensorCalibrationDisabledSets,
        matches: (String, String) -> Boolean
    ): SensorCalibrationDisabledSets {
        return SensorCalibrationDisabledSets(
            raw = if (rawEnabled) existing.raw else update(existing.raw, currentSensorId, true, matches),
            auto = if (autoEnabled) existing.auto else update(existing.auto, currentSensorId, true, matches)
        )
    }
}
