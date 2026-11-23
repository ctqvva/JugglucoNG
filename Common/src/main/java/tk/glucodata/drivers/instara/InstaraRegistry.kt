package tk.glucodata.drivers.instara

import android.content.Context
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth

object InstaraRegistry {

    private const val TAG = "InstaraRegistry"

    @JvmStatic
    fun addSensor(context: Context, sn: String): String? {
        if (!InstaraPersistence.isValidSn(sn)) {
            Log.w(TAG, "addSensor: invalid SN")
            return null
        }
        val sensorId = InstaraPersistence.canonicalSensorId(sn)
        val previous = InstaraPersistence.getSerialNumber(context)
        if (previous.isNotBlank() && !previous.equals(sensorId, ignoreCase = true)) {
            InstaraPersistence.setLastBtAddress(context, "")
            InstaraPersistence.setSensorStartMs(context, 0L)
            runCatching { Natives.finishSensorById(previous) }
                .onFailure { Log.stack(TAG, "addSensor(finishSensorById)", it) }
        }
        InstaraPersistence.setSerialNumber(context, sensorId)

        retireOtherDrivers(sensorId)
        val blue = SensorBluetooth.blueone ?: return sensorId
        val existing = findDriver(sensorId)
        val callback = existing ?: InstaraBleManager(sensorId, 0L).also { manager ->
            manager.mActiveDeviceAddress = InstaraPersistence.getLastBtAddress(context)
                .takeIf { it.isNotBlank() }
            SensorBluetooth.gattcallbacks.add(manager)
            runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        }
        if (SensorBluetooth.blueone === blue) {
            if (existing != null && (existing.isAuthRejected() || existing.isDeviceRejected())) {
                existing.softReconnect()
            } else {
                callback.connectDevice(0L)
            }
        }
        return sensorId
    }

    @JvmStatic
    fun findDriver(sensorId: String): InstaraBleManager? =
        SensorBluetooth.gattcallbacks.firstNotNullOfOrNull { callback ->
            (callback as? InstaraBleManager)?.takeIf { it.matchesManagedSensorId(sensorId) }
        }

    private fun retireOtherDrivers(sensorId: String) {
        SensorBluetooth.gattcallbacks
            .filterIsInstance<InstaraBleManager>()
            .filterNot { it.matchesManagedSensorId(sensorId) }
            .forEach { stale ->
                runCatching { stale.terminateManagedSensor(wipeData = false) }
                    .onFailure { Log.stack(TAG, "retireOtherDrivers", it) }
                SensorBluetooth.gattcallbacks.remove(stale)
            }
    }
}
