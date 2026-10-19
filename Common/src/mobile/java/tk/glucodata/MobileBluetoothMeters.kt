package tk.glucodata

/**
 * The phone's BLE meter adapter for [BluetoothMeterAccess] (direction.md §4,
 * category P). The class itself stays in this package; only the entry points shared
 * code used to name are behind the registry.
 */
object MobileBluetoothMeters : BluetoothMeters {
    override fun startDevices() {
        BluetoothGlucoseMeter.startDevices()
    }

    override fun stopDevices() {
        BluetoothGlucoseMeter.stopDevices()
    }
}
