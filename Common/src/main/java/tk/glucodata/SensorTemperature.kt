package tk.glucodata

import tk.glucodata.drivers.aidex.AiDexTemperatureStore
import tk.glucodata.drivers.anytime.AnytimeRegistry
import tk.glucodata.drivers.ottai.OttaiRegistry

/**
 * Latest known skin temperature for a sensor, from the same sources the
 * Statistics temperature card reads: the Anytime/Ottai/AiDex temperature
 * sidecars first (timestamped samples), then the native `temppolls` channel
 * (Sibionics, iCan and the native mirrors).
 *
 * Display/telemetry use only — never feeds glucose computation. Returns null
 * when no temperature is known; callers render that as empty, never invented.
 */
object SensorTemperature {

    fun latestTemperatureC(sensorId: String?): Float? {
        val serial = sensorId?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return null
        val context = Applic.app ?: return null
        return runCatching {
            val candidates = temperatureSensorCandidates(serial, context)
            candidates.firstNotNullOfOrNull { candidate ->
                AnytimeRegistry.loadTemperatureHistory(context, candidate)
                    .lastOrNull { isPlausible(it.temperatureC) }?.temperatureC
            } ?: candidates.firstNotNullOfOrNull { candidate ->
                OttaiRegistry.loadTemperatureHistory(context, candidate)
                    .lastOrNull { isPlausible(it.temperatureC) }?.temperatureC
            } ?: candidates.firstNotNullOfOrNull { candidate ->
                AiDexTemperatureStore.loadTemperatureHistory(context, candidate)
                    .lastOrNull { isPlausible(it.temperatureC) }?.temperatureC
            } ?: candidates.firstNotNullOfOrNull { candidate ->
                runCatching { Natives.getTemperatureDataByName(candidate) }.getOrNull()
                    ?.lastOrNull { it > 0 }?.let { it / 10f }
                    ?.takeIf { isPlausible(it) }
            }
        }.getOrNull()
    }

    private fun temperatureSensorCandidates(serial: String, context: android.content.Context): List<String> {
        val candidates = LinkedHashSet<String>()
        fun add(value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let(candidates::add)
        }
        add(serial)
        add(runCatching { SensorIdentity.resolveAppSensorId(serial) }.getOrNull())
        add(runCatching { SensorIdentity.resolveNativeSensorName(serial) }.getOrNull())
        add(runCatching { Natives.resolveFullSensorName(serial) }.getOrNull())
        candidates.toList().forEach { candidate ->
            add(runCatching { AnytimeRegistry.resolveCanonicalSensorId(context, candidate) }.getOrNull())
            add(runCatching { OttaiRegistry.resolveCanonicalSensorId(context, candidate) }.getOrNull())
            add(runCatching { AiDexTemperatureStore.canonicalId(candidate) }.getOrNull())
        }
        return candidates.toList()
    }

    private fun isPlausible(temperatureC: Float): Boolean =
        temperatureC.isFinite() && temperatureC > -20f && temperatureC < 80f
}
