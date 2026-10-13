package tk.glucodata.drivers

import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.Applic
import tk.glucodata.drivers.aidex.AiDexRetainedCalibration

object ManagedSensorRuntime {
    // resolveDriver builds a full UI snapshot per driver just to match a sensor, so the calibration
    // display path (which calls integratesUserCalibration per glucose point per frame) is far too
    // costly. The result is a driver-type property, stable per sensor across reconnects, so cache
    // it — but only once a driver is actually found, so a not-yet-connected sensor isn't pinned to
    // a wrong "false". Cleared by SensorIdentity.invalidateCaches on sensor set changes.
    private val integratesUserCalibrationCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @JvmStatic
    fun clearCaches() {
        integratesUserCalibrationCache.clear()
    }

    @JvmStatic
    fun resolveDriver(sensorId: String?): ManagedBluetoothSensorDriver? {
        val resolvedSensorId = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId
        if (resolvedSensorId.isNullOrBlank()) {
            return null
        }
        return SensorBluetooth.mygatts()
            .asSequence()
            .mapNotNull { it as? ManagedBluetoothSensorDriver }
            .firstOrNull { driver ->
                val snapshot = runCatching { driver.getManagedUiSnapshot(resolvedSensorId) }.getOrNull()
                when {
                    snapshot != null -> SensorIdentity.matches(snapshot.serial, resolvedSensorId)
                    else -> runCatching { driver.matchesManagedSensorId(resolvedSensorId) }.getOrDefault(false)
                }
            }
    }

    @JvmStatic
    fun resolveUiSnapshot(sensorId: String?, activeSensorId: String? = null): ManagedSensorUiSnapshot? {
        val resolvedSensorId = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId
        if (resolvedSensorId.isNullOrBlank()) {
            return null
        }
        return SensorBluetooth.mygatts()
            .asSequence()
            .mapNotNull { it as? ManagedBluetoothSensorDriver }
            .mapNotNull { driver ->
                runCatching { driver.getManagedUiSnapshot(activeSensorId ?: resolvedSensorId) }.getOrNull()
            }
            .firstOrNull { snapshot -> SensorIdentity.matches(snapshot.serial, resolvedSensorId) }
    }

    @JvmStatic
    fun resolveCurrentSnapshot(sensorId: String?, maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val resolvedSensorId = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId
        if (resolvedSensorId.isNullOrBlank()) {
            return null
        }
        return resolveDriver(resolvedSensorId)
            ?.let { driver -> runCatching { driver.getManagedCurrentSnapshot(maxAgeMillis) }.getOrNull() }
    }

    @JvmStatic
    fun integratesUserCalibration(sensorId: String?, isRawMode: Boolean): Boolean {
        val resolved = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId ?: return false
        val key = "$resolved|$isRawMode"
        integratesUserCalibrationCache[key]?.let { return it }
        // No driver yet: don't cache — the answer can change once the sensor connects.
        val driver = resolveDriver(resolved)
            ?: return AiDexRetainedCalibration.integrates(Applic.app, resolved, isRawMode)
        val result = runCatching { driver.integratesUserCalibration(isRawMode) }.getOrDefault(false)
        integratesUserCalibrationCache[key] = result
        return result
    }

    @JvmStatic
    fun rewritesAutoHistory(sensorId: String?): Boolean =
        runCatching { resolveDriver(sensorId)?.rewritesAutoHistory() == true }.getOrDefault(false)

    @JvmStatic
    fun notifyUserCalibrationRevisionChanged(revision: Long) {
        runCatching {
            SensorBluetooth.mygatts()
                .asSequence()
                .mapNotNull { it as? ManagedBluetoothSensorDriver }
                .forEach { driver ->
                    runCatching { driver.onUserCalibrationRevisionChanged(revision) }
                }
        }
    }
}
