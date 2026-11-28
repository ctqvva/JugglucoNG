package tk.glucodata.drivers.sibionics

import java.util.Locale
import java.util.UUID

/**
 * The GS3 protocol identity: a 6-byte MAC that goes into the auth packet and the
 * REGISTERED_V3 IV. It is read from the sensor, never taken from the BLE address:
 * the Device Information Serial Number String (0x2A25) first, then the advertisement
 * (manufacturer data 0x55AA, or service data 0x5347). Without either the driver does
 * not handshake at all.
 */
internal object SibionicsGs3Identity {
    val DEVICE_INFORMATION_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val SERIAL_NUMBER_STRING: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    const val MANUFACTURER_ID = 0x55AA
    val SERVICE_DATA_UUID: UUID = UUID.fromString("00005347-0000-1000-8000-00805f9b34fb")

    private val MAC_TEXT = Regex(
        "(?<![0-9A-Fa-f])((?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{12})(?![0-9A-Fa-f])",
    )

    /**
     * The MAC in a Serial Number String value: `AA:BB:CC:DD:EE:FF`, `AA-BB-…`, or twelve
     * hex digits standing on their own, possibly inside a longer label. A value of exactly
     * six non-text bytes is taken as the MAC itself.
     */
    fun macFromSerialNumber(value: ByteArray?): ByteArray? {
        if (value == null || value.isEmpty()) return null
        val text = String(value, Charsets.ISO_8859_1).trim { it <= ' ' }
        val printable = text.isNotEmpty() && text.all { it in ' '..'~' }
        if (printable) {
            MAC_TEXT.find(text)?.let { match ->
                return bytesFromHex(match.value.filter { it != ':' && it != '-' })?.takeIf(::isUsable)
            }
            return null
        }
        return value.takeIf { it.size == 6 }?.copyOf()?.takeIf(::isUsable)
    }

    /**
     * The first six bytes of the 0x55AA manufacturer payload (company id already removed, as
     * Android's ScanRecord returns it), else of the 0x5347 service data.
     */
    fun macFromAdvertisement(manufacturerData: ByteArray?, serviceData: ByteArray?): ByteArray? =
        listOfNotNull(manufacturerData, serviceData)
            .firstNotNullOfOrNull { payload ->
                payload.takeIf { it.size >= 6 }?.copyOf(6)?.takeIf(::isUsable)
            }

    fun format(mac: ByteArray): String =
        mac.joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }

    fun parse(text: String?): ByteArray? =
        SibionicsConstants.macBytes(text)?.takeIf(::isUsable)

    private fun isUsable(mac: ByteArray): Boolean =
        mac.size == 6 && mac.any { it != 0.toByte() } && mac.any { it != 0xFF.toByte() }

    private fun bytesFromHex(hex: String): ByteArray? {
        if (hex.length != 12) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
