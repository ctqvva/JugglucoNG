package tk.glucodata.drivers.aidex.native.protocol

import tk.glucodata.drivers.aidex.native.crypto.SerialCrypto
import java.util.concurrent.ConcurrentHashMap

/**
 * Sensor-specific material supplied outside the legacy serial-derived AiDex pairing scheme.
 *
 * AiDex X 1.15.1 added a native `setSn(serial, dynamic, secret, iv)` overload. When `dynamic` is
 * true, its native implementation copies these two 16-byte values over the values it would
 * otherwise derive from the serial. The first value is returned by `getSecret()` and is written
 * to F001; the second remains the protocol IV used for BOND and session traffic.
 *
 * This type deliberately accepts already-decoded bytes only. The vendor APK names its server
 * fields `encryptedAesKey` and `encryptedIV`, but their response encryption/authentication
 * contract is not established well enough to reproduce here.
 */
class AiDexPairingMaterial private constructor(
    secret: ByteArray,
    iv: ByteArray,
) {
    private val secretBytes = secret.copyOf()
    private val ivBytes = iv.copyOf()

    internal fun secretCopy(): ByteArray = secretBytes.copyOf()

    internal fun ivCopy(): ByteArray = ivBytes.copyOf()

    companion object {
        const val MATERIAL_SIZE = 16

        /** Reject malformed or partial material instead of silently falling back or truncating. */
        fun fromDecodedBytes(secret: ByteArray, iv: ByteArray): AiDexPairingMaterial? {
            if (secret.size != MATERIAL_SIZE || iv.size != MATERIAL_SIZE) return null
            return AiDexPairingMaterial(secret, iv)
        }
    }
}

/**
 * Process-local handoff point for a future authenticated key source or an explicit diagnostic
 * import. Material is never logged and is not persisted in plaintext by the BLE driver.
 */
internal object AiDexPairingMaterialRegistry {
    private val entries = ConcurrentHashMap<String, AiDexPairingMaterial>()

    fun install(serial: String, secret: ByteArray, iv: ByteArray): Boolean {
        if (registryKey(serial).isBlank()) return false
        val material = AiDexPairingMaterial.fromDecodedBytes(secret, iv) ?: return false
        entries[registryKey(serial)] = material
        return true
    }

    fun find(serial: String): AiDexPairingMaterial? = entries[registryKey(serial)]

    fun remove(serial: String) {
        entries.remove(registryKey(serial))
    }

    internal fun clearForTests() {
        entries.clear()
    }

    private fun registryKey(serial: String): String =
        SerialCrypto.stripPrefix(serial).uppercase()
}
