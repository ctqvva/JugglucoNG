package tk.glucodata.drivers.aidex.native.protocol

import tk.glucodata.drivers.aidex.AiDexSerialIdentity

/**
 * Selects the single F-generation F001 compatibility retry.
 *
 * A one-byte zero is the terminal F001 response from the new F-generation sensor seen in traces.
 * Only that response can select the generation-qualified serial, and it can be selected once per
 * BLE connection. Other short responses and unrelated advertisements remain fail-closed.
 */
class AiDexF001AuthFallback {
    var attempted: Boolean = false
        private set

    fun selectAlternative(
        response: ByteArray,
        storedSensorId: String,
        address: String?,
        advertisedName: String?,
        currentProtocolSerial: String,
    ): String? {
        if (response.size != 1 || response[0] != 0.toByte() || attempted) return null
        val alternative = AiDexSerialIdentity.fGenerationAuthenticationSerial(
            storedSensorId = storedSensorId,
            address = address,
            advertisedName = advertisedName,
        ) ?: return null
        if (alternative.equals(currentProtocolSerial, ignoreCase = true)) return null
        attempted = true
        return alternative
    }

    fun reset() {
        attempted = false
    }
}
