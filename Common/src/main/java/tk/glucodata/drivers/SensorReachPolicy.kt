package tk.glucodata.drivers

import java.util.concurrent.ConcurrentHashMap

/** How a sensor this phone reads itself is doing, beyond its status line. */
enum class SensorReach {
    FINE,

    /** No reading for a while and no link either: the phone cannot get to the sensor. */
    OUT_OF_REACH,

    /** Linked, but the sensor has not sent a reading for a while. */
    QUIET,
}

object SensorReachPolicy {
    /** The app's own "no current reading" age (Notify.glucosetimeout). */
    const val STALE_MS = 330_000L

    /**
     * A link that went down a moment ago is a reconnect, not a lost sensor: drivers
     * drop and remake the link on their own, and a stale reading over one of those
     * gaps must not flash the prompt.
     */
    const val LINK_SETTLE_MS = 60_000L

    /** At or under this, an out-of-reach sensor's battery is worth naming. */
    const val LOW_BATTERY_PERCENT = 20

    /**
     * Whether the source holds a Bluetooth link of its own. Followers have no sensor to
     * reach: Nightscout, API and Lumiflex by family, an MQ cloud follower by its own word.
     */
    fun holdsOwnLink(family: ManagedSensorUiFamily, readsOverBluetooth: Boolean): Boolean =
        readsOverBluetooth &&
            family != ManagedSensorUiFamily.GENERIC && family != ManagedSensorUiFamily.NIGHTSCOUT

    /**
     * @param lastReadingMs the newest reading's time, 0 when the sensor has none yet.
     * @param linkDownSinceMs when the link was last seen going down, 0 while it is up.
     * @param catchingUp the driver is fetching a backlog: readings arrive, just old ones.
     */
    fun resolve(
        nowMs: Long,
        lastReadingMs: Long,
        linkUp: Boolean,
        linkDownSinceMs: Long,
        catchingUp: Boolean,
    ): SensorReach {
        if (catchingUp) return SensorReach.FINE
        val hasReading = lastReadingMs > 0L
        if (hasReading && nowMs - lastReadingMs < STALE_MS) return SensorReach.FINE
        if (linkUp) {
            // A new sensor with no reading yet is warming up or starting; its own status says so.
            return if (hasReading) SensorReach.QUIET else SensorReach.FINE
        }
        val downForMs = if (linkDownSinceMs > 0L) nowMs - linkDownSinceMs else 0L
        // Without a reading to date it from, the link itself has to have been gone that long.
        val neededMs = if (hasReading) LINK_SETTLE_MS else STALE_MS
        return if (downForMs >= neededMs) SensorReach.OUT_OF_REACH else SensorReach.FINE
    }

    private val linkDownSince = ConcurrentHashMap<String, Long>()

    /** Notes the link as seen now and returns since when it has been down (0 while up). */
    fun observeLink(serial: String, linkUp: Boolean, nowMs: Long): Long {
        if (linkUp) {
            linkDownSince.remove(serial)
            return 0L
        }
        return linkDownSince.getOrPut(serial) { nowMs }
    }

    /** A paused, handed-over or switched-off sensor is not lost; the next look starts afresh. */
    fun forgetLink(serial: String) {
        linkDownSince.remove(serial)
    }
}
