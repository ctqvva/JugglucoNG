// OttaiDriver.kt — managed-driver contract for Ottai CGM. Mirrors AnytimeDriver.

package tk.glucodata.drivers.ottai

import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot

data class OttaiCurrentSnapshot(
    val timeMillis: Long,
    val glucoseValue: Float,         // current display unit
    val rawValue: Float = Float.NaN, // raw current (for diagnostic view)
    val rate: Float = Float.NaN,
    val sensorGen: Int = 0,
)

interface OttaiDriver : ManagedBluetoothSensorDriver, ManagedSensorMaintenanceDriver {

    override fun canConnectWithoutDataptr(): Boolean = true
    override fun managesLiveRoomStorage(): Boolean = true
    override fun shouldUseNativeHistorySync(): Boolean = false
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    fun isUiEnabled(): Boolean = true
    fun getPassiveConnectionStatus(): String = ""

    /**
     * Activate the sensor (irreversible — starts the lifetime). Only callable
     * once cloud materials are present and BLE auth has produced a session key.
     */
    fun requestActivation(): Boolean

    /** Pull buffered history from the device. */
    fun requestHistoryBackfill(): Boolean

    fun getCurrentSnapshot(maxAgeMillis: Long): OttaiCurrentSnapshot? = null

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val s = getCurrentSnapshot(maxAgeMillis) ?: return null
        return ManagedSensorCurrentSnapshot(
            timeMillis = s.timeMillis,
            glucoseValue = s.glucoseValue,
            rawGlucoseValue = s.rawValue,
            rate = s.rate,
            sensorGen = s.sensorGen,
        )
    }

    override fun softDisconnect() {}
    override fun softReconnect() {}
    override fun terminateManagedSensor(wipeData: Boolean) {}

    // Decoded readings carry both calculated glucose and raw sensor current.
    fun supportsRawDisplayModes(): Boolean = false
    fun isVendorConnectedForUi(): Boolean =
        (this as? SuperGattCallback)?.mActiveBluetoothDevice != null
    override fun supportsResetAction(): Boolean = false
    override fun supportsDisplayModes(): Boolean = supportsRawDisplayModes()
    override fun supportsManualCalibration(): Boolean = false

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val callback = this as? SuperGattCallback ?: return null
        val sensorSerial = callback.SerialNumber ?: return null
        val active = activeSensorId?.takeIf { it.isNotBlank() }
        val detailedStatus = runCatching { getDetailedBleStatus() }.getOrDefault("")
        val passiveStatus = if (detailedStatus.isBlank()) {
            runCatching { getPassiveConnectionStatus() }.getOrDefault("")
        } else ""
        return ManagedSensorUiSnapshot(
            serial = sensorSerial,
            displayName = OttaiConstants.canonicalSensorId(sensorSerial).ifBlank { sensorSerial },
            deviceAddress = callback.mActiveDeviceAddress ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.OTTAI,
            connectionStatus = passiveStatus,
            detailedStatus = detailedStatus,
            subtitleStatus = detailedStatus.ifBlank { passiveStatus },
            showConnectionStatusInDetails = true,
            startTimeMs = runCatching { getStartTimeMs() }.getOrDefault(0L),
            officialEndMs = runCatching { getOfficialEndMs() }.getOrDefault(0L),
            expectedEndMs = runCatching { getExpectedEndMs() }.getOrDefault(0L),
            isUiEnabled = runCatching { isUiEnabled() }.getOrDefault(true),
            isActive = active != null && SensorIdentity.matches(sensorSerial, active),
            rssi = callback.readrssi,
            dataptr = callback.dataptr,
            viewMode = viewMode,
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = supportsManualCalibration(),
            supportsHardwareReset = supportsResetAction(),
            isVendorConnected = runCatching { isVendorConnectedForUi() }
                .getOrDefault(callback.mActiveBluetoothDevice != null),
            isSensorExpired = runCatching { isSensorExpired() }.getOrDefault(false),
            sensorRemainingHours = runCatching { getSensorRemainingHours() }.getOrDefault(-1),
            sensorAgeHours = runCatching { getSensorAgeHours() }.getOrDefault(-1),
            vendorFirmware = runCatching { vendorFirmwareVersion }.getOrDefault(""),
            vendorModel = runCatching { vendorModelName }.getOrDefault(""),
        )
    }

    fun getStartTimeMs(): Long
    fun getOfficialEndMs(): Long
    fun getExpectedEndMs(): Long
    fun isSensorExpired(): Boolean
    fun getSensorRemainingHours(): Int
    fun getSensorAgeHours(): Int
    fun getReadingIntervalMinutes(): Int
    override fun calibrateSensor(glucoseMgDl: Int): Boolean = false

    val vendorFirmwareVersion: String
    val vendorModelName: String
}
