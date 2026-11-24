package tk.glucodata.drivers.scgm

import android.content.Context
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot

interface ScgmDriver : ManagedBluetoothSensorDriver, ManagedSensorMaintenanceDriver {

    override fun canConnectWithoutDataptr(): Boolean = true
    override fun managesLiveRoomStorage(): Boolean = true
    override fun hasNativeSensorBacking(): Boolean = false
    override fun shouldUseNativeHistorySync(): Boolean = false
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    fun getConnectionStatus(): String = ""
    fun isConnected(): Boolean = false
    fun requestHistoryBackfill(): Boolean = false
    fun getDeviceAddress(): String = ""
    fun getFirmwareInfo(): String = ""

    override fun supportsManualCalibration(): Boolean = false
    override fun supportsDisplayModes(): Boolean = false
    override fun supportsResetAction(): Boolean = false

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val sensorId = (this as? SuperGattCallback)?.SerialNumber ?: return null
        val status = runCatching { getDetailedBleStatus() }.getOrDefault("")
        val battery = batteryMillivolts()
        val statusText = if (ScgmParser.isLowBattery(battery)) "$status · low battery" else status
        return ManagedSensorUiSnapshot(
            serial = sensorId,
            displayName = sensorId.removePrefix(ScgmConstants.SENSOR_ID_PREFIX),
            deviceAddress = getDeviceAddress().ifBlank { "Unknown" },
            uiFamily = ManagedSensorUiFamily.SCGM,
            connectionStatus = statusText,
            subtitleStatus = statusText,
            showConnectionStatusInDetails = true,
            isActive = activeSensorId?.takeIf { it.isNotBlank() }?.equals(sensorId, ignoreCase = true) ?: false,
            isVendorConnected = isConnected(),
            vendorFirmware = runCatching { getFirmwareInfo() }.getOrDefault(""),
            vendorModel = "Bioecare SCGM",
            batteryMillivolts = battery,
            viewMode = viewMode,
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = supportsManualCalibration(),
            supportsHardwareReset = supportsResetAction(),
        )
    }

    fun batteryMillivolts(): Int = 0

    override fun removeManagedPersistence(context: Context) = ScgmRegistry.clear(context)
}
