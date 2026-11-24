package tk.glucodata.drivers.novo

import android.content.Context
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiSnapshot

interface NovoDriver : ManagedBluetoothSensorDriver, ManagedSensorMaintenanceDriver {

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
        return ManagedSensorUiSnapshot(
            serial = sensorId,
            displayName = sensorId.removePrefix(NovoConstants.SENSOR_ID_PREFIX),
            deviceAddress = getDeviceAddress().ifBlank { "Unknown" },
            uiFamily = NovoConstants.UI_FAMILY,
            connectionStatus = status,
            subtitleStatus = status,
            showConnectionStatusInDetails = true,
            isActive = activeSensorId?.takeIf { it.isNotBlank() }?.equals(sensorId, ignoreCase = true) ?: false,
            isVendorConnected = isConnected(),
            vendorFirmware = runCatching { getFirmwareInfo() }.getOrDefault(""),
            vendorModel = "Bioecare NOVO CGM",
            viewMode = viewMode,
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = supportsManualCalibration(),
            supportsHardwareReset = supportsResetAction(),
        )
    }

    override fun removeManagedPersistence(context: Context) = NovoRegistry.clear(context)
}
