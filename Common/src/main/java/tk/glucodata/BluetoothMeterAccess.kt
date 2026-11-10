package tk.glucodata

/**
 * The phone's Bluetooth finger-stick meter support (direction.md §4, category P).
 *
 * `BluetoothGlucoseMeter` is 380 lines of phone-only BLE in `src/mobile`; shared code
 * reached it from `Applic` (on start) and `Backup` (when the static-numbers setting is
 * toggled), which is why a stub existed on the watch. The class stays where it is and
 * the shared calls come through here; both call sites are already inside `!isWearable`.
 */
interface BluetoothMeters {
    fun startDevices()
    fun stopDevices()
}

object BluetoothMeterAccess {
    @Volatile
    private var meters: BluetoothMeters? = null

    @JvmStatic
    fun register(meters: BluetoothMeters) {
        this.meters = meters
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = meters != null

    /** Null on a flavour without phone meters (the watch). */
    @JvmStatic
    fun get(): BluetoothMeters? = meters
}
