package tk.glucodata.alerts

import tk.glucodata.CurrentDisplaySource
import tk.glucodata.NotificationMultiSensorSource
import tk.glucodata.SensorIdentity

/** Independent disagreement warning; Combine mode supplies its own paired assessment. */
internal object DualSensorRuntime {
    private val engine = SensorGroupConsensus()
    private var settings: Triple<Int, Int, Boolean>? = null

    @Synchronized
    fun reset() {
        engine.reset()
    }

    @Synchronized
    fun evaluate(config: AlertConfig, nowMs: Long): DualSensorConsensus.Result {
        val combined = tk.glucodata.combined.CombinedSensorRegistry.config()
        if (combined.enabled) {
            val result = tk.glucodata.combined.CombinedSensorRegistry.result(nowMs) ?: DualSensorConsensus.Result()
            return result
        }
        if (!config.enabled) {
            reset()
            return DualSensorConsensus.Result()
        }
        val currentSettings = Triple(config.disagreementPercent, config.durationMinutes ?: 5, config.enabled)
        if (settings != currentSettings) engine.reset()
        settings = currentSettings
        val ids = NotificationMultiSensorSource.selectedSensorIds(SensorIdentity.resolveMainSensor())
        val readings = if (ids.size >= 2) ids.mapNotNull { id ->
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
        val result = engine.evaluate(readings.takeIf { it.size == ids.size }.orEmpty(), nowMs, config.disagreementPercent, config.durationMinutes ?: 5)
        return result
    }
}
