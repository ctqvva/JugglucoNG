package tk.glucodata.glucosemeter

import java.util.Locale
import java.util.UUID

/**
 * Sorts what a Bluetooth scan finds into meters and everything else. The scan itself is
 * unfiltered, because not every meter advertises a service, so this only decides which results
 * are listed first; a meter it does not recognise is still offered under the other devices.
 */
object MeterAdvertisement {
    @JvmField
    val GLUCOSE_SERVICE: UUID = UUID.fromString("00001808-0000-1000-8000-00805f9b34fb")

    @JvmField
    val VERIO_SERVICE: UUID = UUID.fromString("af9df7a1-e595-11e3-96b4-0002a5d5c51b")

    @JvmField
    val SATELLITE_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    private const val SATELLITE_NAME_PREFIX = "satellite"

    // Names meters are known to advertise under, for the ones that leave the service out.
    private val meterNamePrefixes = listOf(
        "meter+", // Accu-Chek Guide, Instant
        "accu-chek",
        "contour",
        "onetouch",
        "caresens",
        SATELLITE_NAME_PREFIX,
    )

    /**
     * The Satellite advertises no service at all in some traces, so its name counts too;
     * without it the code dialog never shows.
     */
    @JvmStatic
    fun isSatellite(name: String, serviceUuids: Collection<UUID>): Boolean =
        SATELLITE_SERVICE in serviceUuids ||
            name.lowercase(Locale.ROOT).startsWith(SATELLITE_NAME_PREFIX)

    @JvmStatic
    fun looksLikeMeter(name: String, serviceUuids: Collection<UUID>): Boolean {
        if (GLUCOSE_SERVICE in serviceUuids || VERIO_SERVICE in serviceUuids) return true
        if (isSatellite(name, serviceUuids)) return true
        val lower = name.trim().lowercase(Locale.ROOT)
        return meterNamePrefixes.any(lower::startsWith)
    }
}
