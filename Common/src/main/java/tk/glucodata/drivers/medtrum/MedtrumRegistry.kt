// MedtrumRegistry.kt — persist a Medtrum sensor and start its driver.
//
// Mirrors ICanHealthRegistry.addSensor: write prefs first (so the identity adapter
// resurrects the driver on every later app start), then reuse-or-create the callback.

package tk.glucodata.drivers.medtrum

import android.content.Context
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity

object MedtrumRegistry {

    private const val TAG = "MedtrumRegistry"

    @JvmStatic
    fun addSensor(context: Context, serialNumber: Long): String? {
        if (serialNumber <= 0L) {
            Log.w(TAG, "addSensor: invalid serial $serialNumber")
            return null
        }
        val sensorId = MedtrumPersistence.canonicalSensorId(serialNumber)
        val previousSerial = MedtrumPersistence.getSerialNumber(context)
        if (previousSerial != 0L && previousSerial != serialNumber) {
            // The old patch's address would be tried first and its auth dialect reused.
            MedtrumPersistence.setLastBtAddress(context, "")
            MedtrumPersistence.setLegacyAuth(context, false)
            MedtrumPersistence.setSensorStartMs(context, 0L)
            MedtrumPersistence.setSessionKey(context, "")
            MedtrumPersistence.setPendingArchive(context, "")
            // Its native mirror can outlive its driver (turning Bluetooth collection off clears
            // the callbacks without finishing the shell). Finish it by id, or the next rebuild
            // adopts it as a generic Libre callback that can stay selected.
            runCatching { Natives.finishSensorById(previousSerial.toString()) }
                .onFailure { Log.stack(TAG, "addSensor(finishSensorById)", it) }
        }
        MedtrumPersistence.setSerialNumber(context, serialNumber)
        MedtrumPersistence.setSensorId(context, sensorId)

        retireOtherDrivers(sensorId)
        val blue = SensorBluetooth.blueone ?: return sensorId
        val existing = findDriver(sensorId)
        val callback = existing ?: MedtrumBleManager(sensorId, 0L).also {
            it.mActiveDeviceAddress = MedtrumPersistence.getLastBtAddress(context).takeIf { addr -> addr.isNotBlank() }
            SensorBluetooth.gattcallbacks.add(it)
            runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        }
        if (SensorBluetooth.blueone === blue) {
            if (existing != null && (existing.isAuthRejected() || existing.isDeviceRejected())) {
                // A reused driver can be parked after a wrong serial or a rejected address, and
                // connectDevice() refuses while that holds. Re-confirming the serial is the user's
                // retry, so take the explicit retry path that clears those flags.
                Log.i(TAG, "Retrying blocked Medtrum driver for $sensorId")
                existing.softReconnect()
            } else {
                callback.connectDevice(0)
            }
        }
        return sensorId
    }

    /** The running driver for this sensor, if SensorBluetooth holds one. */
    @JvmStatic
    fun findDriver(sensorId: String): MedtrumBleManager? =
        SensorBluetooth.gattcallbacks.firstNotNullOfOrNull { callback ->
            (callback as? MedtrumBleManager)?.takeIf {
                SensorIdentity.matches(it.SerialNumber, sensorId) || it.matchesManagedSensorId(sensorId)
            }
        }

    /**
     * Persistence holds one Medtrum serial, so a driver for any other serial is a leftover — most
     * often a mistyped serial the patch rejected in setup. Left running it would keep its own
     * address and reconnect loop alongside the new one.
     */
    private fun retireOtherDrivers(sensorId: String) {
        SensorBluetooth.gattcallbacks
            .filterIsInstance<MedtrumBleManager>()
            .filterNot { SensorIdentity.matches(it.SerialNumber, sensorId) || it.matchesManagedSensorId(sensorId) }
            .forEach { stale ->
                Log.i(TAG, "Retiring Medtrum driver ${stale.SerialNumber} for $sensorId")
                runCatching { stale.terminateManagedSensor(wipeData = false) }
                    .onFailure { Log.stack(TAG, "retireOtherDrivers", it) }
                SensorBluetooth.gattcallbacks.remove(stale)
            }
    }
}