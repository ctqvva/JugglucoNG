package tk.glucodata

/**
 * The phone's Health Connect bridge (direction.md §4, category P — "needs phone-only
 * libraries").
 *
 * `HealthConnection` is androidx.health and stays in `src/mobile`, as the recipe asks;
 * shared code used to name it from `Applic`, `MainActivity`, `SuperGattCallback` and
 * `Settings`, which is why a 12-line watch stub had to exist. The shared calls go through
 * here instead: the phone registers, the watch registers nothing, and a caller that gets
 * null skips the call (P2).
 *
 * The watch stub's bodies were empty, so skipping is what the watch already did. Every
 * shared call site is behind `!isWearable` or an SDK check anyway.
 */
interface HealthConnect {
    fun start(activity: MainActivity)
    fun stop()

    /**
     * Exports what native holds for this sensor to Health Connect. [sensorPtr] is native's
     * pointer for it, which is what the callers already resolve: #464 made that lookup
     * overridable so a driver that keeps no dataptr (iCan) can supply its own.
     */
    fun writeAll(sensorPtr: Long, sensorName: String)
}

object HealthConnectAccess {
    @Volatile
    private var bridge: HealthConnect? = null

    @JvmStatic
    fun register(bridge: HealthConnect) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    /** Null on a flavour without Health Connect (the watch). */
    @JvmStatic
    fun get(): HealthConnect? = bridge
}
