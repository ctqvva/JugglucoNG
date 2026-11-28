// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.drivers.caresens

/** Decodes the canonical GS1 payload produced by PhotoScan. */
internal class CareSensAirQr private constructor(
    val gtin: String,
    val expiry: String,
    val serial: String,
    private val pin: String,
    val sensorCode: String,
) {
    val sensorId: String get() = expiry.take(4) + serial

    fun pairingPin(): ByteArray = pin.toByteArray(Charsets.US_ASCII)

    companion object {
        fun parse(payload: String): CareSensAirQr? {
            if (payload.length != 69 ||
                !payload.startsWith("\u001d01") ||
                !payload.startsWith("17", 17) ||
                !payload.startsWith("21", 25) ||
                !payload.startsWith("\u001d240", 39) ||
                !payload.startsWith("\u001d250", 49)
            ) return null

            val gtin = payload.substring(3, 17)
            val expiry = payload.substring(19, 25)
            val serial = payload.substring(27, 39)
            val pin = payload.substring(43, 49)
            val sensorCode = payload.substring(53)
            if (!gtin.all { it in '0'..'9' } || !expiry.all { it in '0'..'9' } ||
                !serial.all(::isVisibleAscii) || !pin.all(::isVisibleAscii) ||
                !sensorCode.all(::isVisibleAscii)
            ) return null

            return CareSensAirQr(gtin, expiry, serial, pin, sensorCode)
        }
    }
}

internal fun isVisibleAscii(char: Char): Boolean = char in '!'..'~'
