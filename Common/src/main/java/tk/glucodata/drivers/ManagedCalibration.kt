package tk.glucodata.drivers

import tk.glucodata.Log
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback

// Fingerstick calibration against whichever locally connected driver supports
// it. Used by the wear calibration screen (standalone mode) and by the phone
// when the watch relays a /calibrate message (companion mode).
object ManagedCalibration {
    private const val LOG_ID = "ManagedCalibration"

    @JvmStatic
    fun findCalibratableDriver(): SuperGattCallback? = runCatching {
        SensorBluetooth.mygatts()?.firstOrNull { gatt ->
            gatt is ManagedSensorMaintenanceDriver &&
                (gatt as? ManagedBluetoothSensorDriver)?.supportsManualCalibration() == true
        }
    }.getOrNull()

    /** @param glucoseMgDl fingerstick value in mg/dL. Blocking; call off the main thread. */
    @JvmStatic
    fun applyFingerstickCalibration(glucoseMgDl: Int): Boolean {
        if (glucoseMgDl < 20 || glucoseMgDl > 600) {
            Log.e(LOG_ID, "rejecting out-of-range calibration value $glucoseMgDl mg/dL")
            return false
        }
        val driver = findCalibratableDriver() ?: run {
            Log.i(LOG_ID, "no calibratable driver connected")
            return false
        }
        val ok = runCatching {
            (driver as ManagedSensorMaintenanceDriver).calibrateSensor(glucoseMgDl)
        }.getOrDefault(false)
        Log.i(LOG_ID, "calibrateSensor($glucoseMgDl mg/dL) -> $ok")
        return ok
    }
}
