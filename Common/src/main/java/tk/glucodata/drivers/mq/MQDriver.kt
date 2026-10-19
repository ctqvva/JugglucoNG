// MQDriver.kt — MQ-specific managed-driver contract.
//
// Mirrors ICanHealthDriver in structure. MQ is a managed BLE sensor without
// native backing today: the driver owns BLE, parsing, algorithm, persistence
// and live Room storage.

package tk.glucodata.drivers.mq

import android.content.Context
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot

data class MQCurrentSnapshot(
    val timeMillis: Long,
    val glucoseValue: Float,
    val rawValue: Float = Float.NaN,
    val rate: Float = Float.NaN,
    val sensorGen: Int = 0,
)

interface MQDriver : ManagedBluetoothSensorDriver, ManagedSensorMaintenanceDriver {

    override fun canConnectWithoutDataptr(): Boolean = true
    override fun managesLiveRoomStorage(): Boolean = true
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    fun getLifecycleSummary(): String = ""
    fun isUiEnabled(): Boolean = true
    fun getPassiveConnectionStatus(): String = ""
    fun refreshVendorBootstrap(
        context: Context,
        qrCode: String? = null,
        account: String? = null,
        password: String? = null,
    ): Boolean = false

    fun getCurrentSnapshot(maxAgeMillis: Long): MQCurrentSnapshot? = null

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val snap = getCurrentSnapshot(maxAgeMillis) ?: return null
        return ManagedSensorCurrentSnapshot(
            timeMillis = snap.timeMillis,
            glucoseValue = snap.glucoseValue,
            rawGlucoseValue = snap.rawValue,
            rate = snap.rate,
            sensorGen = snap.sensorGen,
        )
    }

    override fun softDisconnect() {}
    override fun softReconnect() {}
    override fun terminateManagedSensor(wipeData: Boolean) {}

    fun supportsRawDisplayModes(): Boolean = false
    fun supportsSensorCalibration(): Boolean = true
    override fun supportsResetAction(): Boolean = true

    override fun supportsDisplayModes(): Boolean = supportsRawDisplayModes()
    override fun supportsManualCalibration(): Boolean = supportsSensorCalibration()

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
            displayName = runCatching { callback.mygetDeviceName() }.getOrDefault(sensorSerial),
            deviceAddress = callback.mActiveDeviceAddress ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.MQ,
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
            isVendorConnected = callback.mActiveBluetoothDevice != null,
            isSensorExpired = runCatching { isSensorExpired() }.getOrDefault(false),
            sensorRemainingHours = runCatching { getSensorRemainingHours() }.getOrDefault(-1),
            sensorAgeHours = runCatching { getSensorAgeHours() }.getOrDefault(-1),
            vendorFirmware = runCatching { vendorFirmwareVersion }.getOrDefault(""),
            vendorModel = runCatching { vendorModelName }.getOrDefault(""),
            batteryMillivolts = runCatching { batteryMillivolts }.getOrDefault(0),
            batteryPercent = runCatching { batteryPercent }.getOrDefault(-1),
        )
    }

    fun getStartTimeMs(): Long
    fun getOfficialEndMs(): Long
    fun getExpectedEndMs(): Long
    fun isSensorExpired(): Boolean
    fun getSensorRemainingHours(): Int
    fun getSensorAgeHours(): Int
    fun getReadingIntervalMinutes(): Int
    override fun calibrateSensor(glucoseMgDl: Int): Boolean

    val vendorFirmwareVersion: String
    val vendorModelName: String
    val batteryMillivolts: Int
    val batteryPercent: Int get() = -1
}
