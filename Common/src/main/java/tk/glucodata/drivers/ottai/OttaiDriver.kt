package tk.glucodata.drivers.ottai

import android.content.Context
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot

interface OttaiDriver : ManagedBluetoothSensorDriver {

    override fun canConnectWithoutDataptr(): Boolean = true
    override fun managesLiveRoomStorage(): Boolean = true
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    fun isUiEnabled(): Boolean = true

    fun getCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? = null

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? =
        getCurrentSnapshot(maxAgeMillis)

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val callback = this as? SuperGattCallback ?: return null
        val serial = callback.SerialNumber ?: return null
        val active = activeSensorId?.takeIf { it.isNotBlank() }
        val detailed = runCatching { getDetailedBleStatus() }.getOrDefault("")
        return ManagedSensorUiSnapshot(
            serial = serial,
            displayName = runCatching { callback.mygetDeviceName() }.getOrDefault(serial),
            deviceAddress = callback.mActiveDeviceAddress ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.GENERIC,
            detailedStatus = detailed,
            subtitleStatus = detailed,
            isUiEnabled = runCatching { isUiEnabled() }.getOrDefault(true),
            isActive = active != null && serial.equals(active, ignoreCase = true),
            rssi = callback.readrssi,
            dataptr = callback.dataptr,
            viewMode = viewMode,
            isVendorConnected = callback.mActiveBluetoothDevice != null,
        )
    }

    override fun softDisconnect() {}
    override fun softReconnect() {}
    override fun terminateManagedSensor(wipeData: Boolean) {}

    fun getStartTimeMs(): Long = 0L
    fun getOfficialEndMs(): Long = 0L
    fun getExpectedEndMs(): Long = getOfficialEndMs()
    fun isSensorExpired(): Boolean = false
    fun getSensorRemainingHours(): Int = -1
    fun getSensorAgeHours(): Int = -1
    fun getReadingIntervalMinutes(): Int = 5

    val vendorFirmwareVersion: String get() = ""
    val vendorModelName: String get() = ""
    val batteryMillivolts: Int get() = 0
    val batteryPercent: Int get() = -1

    companion object {
        fun findForSensor(sensorId: String?): OttaiDriver? {
            if (sensorId.isNullOrBlank()) return null
            return SensorBluetooth.mygatts().firstOrNull { cb ->
                (cb as? OttaiDriver)?.matchesManagedSensorId(sensorId) == true
            } as? OttaiDriver
        }

        fun findAll(): List<OttaiDriver> =
            SensorBluetooth.mygatts().mapNotNull { it as? OttaiDriver }

        fun createFromPersistence(context: Context, sensorId: String, dataptr: Long): OttaiBleManager? =
            OttaiRegistry.createRestoredCallback(context, sensorId, dataptr)
    }
}
