package tk.glucodata.drivers.icanhealth

import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorUiFamily

data class ICanHealthCurrentSnapshot(
    val timeMillis: Long,
    val glucoseValue: Float,
    val rawValue: Float = Float.NaN,
    val rate: Float = Float.NaN,
    val sensorGen: Int = 0,
)

interface ICanHealthDriver : ManagedBluetoothSensorDriver {

    override fun canConnectWithoutDataptr(): Boolean = true

    override fun managesLiveRoomStorage(): Boolean = true

    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    fun getLifecycleSummary(): String = ""

    fun isUiEnabled(): Boolean = true

    fun getPassiveConnectionStatus(): String = ""

    fun getCurrentSnapshot(maxAgeMillis: Long): ICanHealthCurrentSnapshot? = null

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val snapshot = getCurrentSnapshot(maxAgeMillis) ?: return null
        return ManagedSensorCurrentSnapshot(
            timeMillis = snapshot.timeMillis,
            glucoseValue = snapshot.glucoseValue,
            rawGlucoseValue = snapshot.rawValue,
            rate = snapshot.rate,
            sensorGen = snapshot.sensorGen,
        )
    }

    override fun softDisconnect() {}

    override fun softReconnect() {}

    override fun terminateManagedSensor(wipeData: Boolean) {}

    fun supportsRawDisplayModes(): Boolean = false

    fun supportsSensorCalibration(): Boolean = false

    override fun supportsDisplayModes(): Boolean = supportsRawDisplayModes()

    override fun supportsManualCalibration(): Boolean = supportsSensorCalibration()

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val callback = this as? SuperGattCallback ?: return null
        val sensorSerial = callback.SerialNumber ?: return null
        val activeSensor = activeSensorId?.takeIf { it.isNotBlank() }
        val detailedStatus = runCatching { getDetailedBleStatus() }.getOrDefault("")
        val passiveConnectionStatus = if (detailedStatus.isBlank()) {
            runCatching { getPassiveConnectionStatus() }.getOrDefault("")
        } else {
            ""
        }
        return ManagedSensorUiSnapshot(
            serial = sensorSerial,
            displayName = runCatching { callback.mygetDeviceName() }.getOrDefault(sensorSerial),
            deviceAddress = callback.mActiveDeviceAddress ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.ICAN,
            connectionStatus = passiveConnectionStatus,
            detailedStatus = detailedStatus,
            subtitleStatus = detailedStatus.ifBlank { passiveConnectionStatus },
            showConnectionStatusInDetails = true,
            startTimeMs = runCatching { getStartTimeMs() }.getOrDefault(0L),
            officialEndMs = runCatching { getOfficialEndMs() }.getOrDefault(0L),
            expectedEndMs = runCatching { getExpectedEndMs() }.getOrDefault(0L),
            isUiEnabled = runCatching { isUiEnabled() }.getOrDefault(true),
            isActive = activeSensor != null && SensorIdentity.matches(sensorSerial, activeSensor),
            rssi = callback.readrssi,
            dataptr = callback.dataptr,
            viewMode = viewMode,
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = supportsManualCalibration(),
            isVendorConnected = callback.mActiveBluetoothDevice != null,
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

    fun calibrateSensor(glucoseMgDl: Int): Boolean

    val vendorFirmwareVersion: String

    val vendorModelName: String
}
