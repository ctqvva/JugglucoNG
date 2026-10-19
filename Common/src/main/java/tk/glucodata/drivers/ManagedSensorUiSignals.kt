package tk.glucodata.drivers

object ManagedSensorUiSignals {
    @Volatile
    private var deviceListDirty = false

    @JvmStatic
    fun markDeviceListDirty() {
        deviceListDirty = true
    }

    @JvmStatic
    fun consumeDeviceListDirty(): Boolean {
        if (!deviceListDirty) {
            return false
        }
        deviceListDirty = false
        return true
    }

    @JvmStatic
    fun isDeviceListDirty(): Boolean = deviceListDirty
}
