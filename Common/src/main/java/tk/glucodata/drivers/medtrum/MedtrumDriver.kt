// MedtrumDriver.kt — Medtrum EasyTouch managed-driver contract.
//
// Medtrum is a fully managed BLE sensor: the driver owns BLE, parsing,
// algorithm, persistence and live Room storage. There is no native backing.

package tk.glucodata.drivers.medtrum

import android.content.Context
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot

interface MedtrumDriver : ManagedBluetoothSensorDriver, ManagedSensorMaintenanceDriver {

    override fun canConnectWithoutDataptr(): Boolean = true
    override fun managesLiveRoomStorage(): Boolean = true
    override fun hasNativeSensorBacking(): Boolean = false
    override fun shouldUseNativeHistorySync(): Boolean = false
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    fun getSerialNumber(): Long
    fun getDeviceAddress(): String
    fun getConnectionStatus(): String = ""

    /** True while the patch link is up; drives the calibrate action's enabled state. */
    fun isConnected(): Boolean = false

    /** Patch battery in millivolts, 0 when not yet reported. */
    fun getBatteryMillivolts(): Int = 0

    /** When the patch was inserted, 0 until a readState record has told us. */
    fun getStartTimeMs(): Long = 0L

    /** End of the patch's 14 days, 0 while the start is not known. */
    fun getExpectedEndMs(): Long = MedtrumSensorLife.endFromStart(getStartTimeMs())

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val sn = getSerialNumber().takeIf { it != 0L }?.toString() ?: return null
        val address = getDeviceAddress()
        val startMs = runCatching { getStartTimeMs() }.getOrDefault(0L)
        val endMs = MedtrumSensorLife.endFromStart(startMs)
        val nowMs = System.currentTimeMillis()
        return ManagedSensorUiSnapshot(
            serial = sn,
            displayName = "Medtrum TouchCare S9 MD3660",
            deviceAddress = address.ifBlank { "Unknown" },
            startTimeMs = startMs,
            officialEndMs = endMs,
            expectedEndMs = endMs,
            sensorAgeHours = MedtrumSensorLife.ageHours(startMs, nowMs),
            sensorRemainingHours = MedtrumSensorLife.remainingHours(startMs, nowMs),
            isSensorExpired = endMs > 0L && nowMs >= endMs,
            uiFamily = ManagedSensorUiFamily.MEDTRUM,
            connectionStatus = runCatching { getConnectionStatus() }.getOrDefault(""),
            detailedStatus = runCatching { getDetailedBleStatus() }.getOrDefault(""),
            isActive = activeSensorId?.takeIf { it.isNotBlank() }?.let { it == sn } ?: false,
            isVendorConnected = runCatching { isConnected() }.getOrDefault(false),
            batteryMillivolts = runCatching { getBatteryMillivolts() }.getOrDefault(0),
            viewMode = viewMode,
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = supportsManualCalibration(),
            supportsHardwareReset = supportsResetAction(),
        )
    }

    override fun removeManagedPersistence(context: Context) {
        MedtrumPersistence.clear(context)
    }
}
