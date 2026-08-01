package tk.glucodata.drivers.sibionics

import java.util.UUID

internal object SibionicsBattery {
    val SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val LEVEL_CHARACTERISTIC: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    /** Bluetooth SIG Battery Level is an unsigned, one-byte percentage. */
    fun parsePercent(value: ByteArray): Int? =
        value.singleOrNull()
            ?.toInt()
            ?.and(0xFF)
            ?.takeIf { it in 0..100 }
}
