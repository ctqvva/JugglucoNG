package tk.glucodata.alerts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.NotificationMultiSensorSource
import tk.glucodata.SensorIdentity

/** Shares only a diagnostic result with the dashboard; never publishes a glucose reading. */
internal object DualSensorRuntime {
    data class Display(
        val enabled: Boolean = false,
        val showEstimate: Boolean = false,
        val result: DualSensorConsensus.Result = DualSensorConsensus.Result()
    )
    private val engine = DualSensorConsensus()
    private val mutableDisplay = MutableStateFlow(Display())
    val display = mutableDisplay.asStateFlow()
    private var settings: Triple<Int, Int, Boolean>? = null

    @Synchronized
    fun reset() {
        engine.reset()
        mutableDisplay.value = Display()
    }

    @Synchronized
    fun evaluate(config: AlertConfig, nowMs: Long): DualSensorConsensus.Result {
        if (!config.enabled && !config.experimentalEstimateEnabled) {
            reset()
            return mutableDisplay.value.result
        }
        val currentSettings = Triple(config.disagreementPercent, config.durationMinutes ?: 5, config.enabled)
        if (settings != currentSettings) engine.reset()
        settings = currentSettings
        val ids = NotificationMultiSensorSource.selectedSensorIds(SensorIdentity.resolveMainSensor())
        val readings = if (ids.size == 2) ids.mapNotNull { id ->
            val snapshot = runCatching {
                CurrentDisplaySource.resolveCurrent(DualSensorConsensus.MAX_AGE_MS, id)
            }.getOrNull() ?: return@mapNotNull null
            // A resolver fallback must never turn two requested sensors into one physical sensor.
            if (!SensorIdentity.matches(id, snapshot.sensorId)) return@mapNotNull null
            DualSensorConsensus.Reading(
                SensorIdentity.resolveAppSensorId(id) ?: id, snapshot.timeMillis,
                snapshot.primaryValue * if (snapshot.isMmol) 18.0182f else 1f, snapshot.viewMode
            )
        } else emptyList()
        val result = engine.evaluate(readings, nowMs, config.disagreementPercent, config.durationMinutes ?: 5)
        mutableDisplay.value = Display(true, config.experimentalEstimateEnabled, result)
        return result
    }
}
