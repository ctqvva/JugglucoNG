package tk.glucodata.drivers.instara

import android.content.Context
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot

interface InstaraDriver : ManagedBluetoothSensorDriver, ManagedSensorMaintenanceDriver {

    override fun canConnectWithoutDataptr(): Boolean = true
    override fun managesLiveRoomStorage(): Boolean = true
    override fun hasNativeSensorBacking(): Boolean = false
    override fun shouldUseNativeHistorySync(): Boolean = false
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    fun getSerialNumber(): String
    fun getDeviceAddress(): String
    fun getConnectionStatus(): String = ""
    fun isConnected(): Boolean = false
    fun getBatteryPercent(): Int = -1
    fun getSensorStartMs(): Long = 0L

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val sn = getSerialNumber().takeIf { InstaraPersistence.isValidSn(it) } ?: return null
        val startMs = getSensorStartMs()
        return ManagedSensorUiSnapshot(
            serial = sn,
            displayName = "Instara G1",
            deviceAddress = getDeviceAddress().ifBlank { "Unknown" },
            uiFamily = ManagedSensorUiFamily.INSTARA,
            connectionStatus = getConnectionStatus(),
            isActive = activeSensorId?.takeIf { it.isNotBlank() }?.let { it == sn } ?: false,
            isVendorConnected = isConnected(),
            startTimeMs = startMs,
            batteryPercent = getBatteryPercent(),
            viewMode = viewMode,
        )
    }

    override fun removeManagedPersistence(context: Context) {
        InstaraPersistence.clear(context)
    }
}
