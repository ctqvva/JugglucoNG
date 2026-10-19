package tk.glucodata.drivers.anytime

import java.util.Locale
import org.json.JSONObject

/** Portable export of the authenticated state needed to resume one CT5 transmitter. */
internal object AnytimeCt5CredentialFile {
    private const val FORMAT = "juggluco-anytime-ct5-credentials"
    private const val VERSION = 1

    data class Parsed(
        val sensorId: String,
        val address: String,
        val deviceName: String,
        val cipherKey: Int,
        val randomB: IntArray,
        val temporaryId: String,
    )

    fun encode(
        sensorId: String,
        address: String,
        deviceName: String,
        cipherKey: Int,
        randomB: IntArray,
        temporaryId: String,
    ): String? {
        val parsed = validate(sensorId, address, deviceName, cipherKey, randomB, temporaryId)
            ?: return null
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("sensorId", parsed.sensorId)
            .put("address", parsed.address)
            .put("deviceName", parsed.deviceName)
            .put("cipherKey", parsed.cipherKey)
            .put("randomB", parsed.randomB.toHex())
            .put("temporaryId", parsed.temporaryId)
            .toString(2)
    }

    fun decode(jsonText: String): Parsed? = runCatching {
        val json = JSONObject(jsonText)
        if (json.optString("format") != FORMAT || json.optInt("version", -1) != VERSION) {
            return@runCatching null
        }
        val randomB = json.optString("randomB").decodeFourBytes() ?: return@runCatching null
        validate(
            sensorId = json.optString("sensorId"),
            address = json.optString("address"),
            deviceName = json.optString("deviceName"),
            cipherKey = json.optInt("cipherKey", -1),
            randomB = randomB,
            temporaryId = json.optString("temporaryId"),
        )
    }.getOrNull()

    private fun validate(
        sensorId: String,
        address: String,
        deviceName: String,
        cipherKey: Int,
        randomB: IntArray,
        temporaryId: String,
    ): Parsed? {
        val canonicalId = AnytimeConstants.canonicalSensorId(sensorId)
        if (!HEX_SENSOR_ID.matches(canonicalId)) return null
        val canonicalAddress = address.trim().uppercase(Locale.US)
        if (AnytimeConstants.canonicalSensorId(canonicalAddress) != canonicalId) return null
        val normalizedName = deviceName.trim()
        if (AnytimeConstants.resolveFamily(normalizedName).family != AnytimeConstants.Family.CT5) return null
        if (cipherKey !in 0..255 || randomB.size != 4 || randomB.any { it !in 0..255 }) return null
        if (!TEMPORARY_ID.matches(temporaryId)) return null
        return Parsed(
            sensorId = canonicalId,
            address = canonicalAddress,
            deviceName = normalizedName,
            cipherKey = cipherKey,
            randomB = randomB.copyOf(),
            temporaryId = temporaryId,
        )
    }

    private fun IntArray.toHex(): String = joinToString("") { "%02X".format(it and 0xFF) }

    private fun String.decodeFourBytes(): IntArray? {
        val value = trim()
        if (!HEX_RANDOM_B.matches(value)) return null
        return IntArray(4) { index -> value.substring(index * 2, index * 2 + 2).toInt(16) }
    }

    private val HEX_SENSOR_ID = Regex("^[0-9A-F]{12}$")
    private val HEX_RANDOM_B = Regex("^[0-9A-Fa-f]{8}$")
    private val TEMPORARY_ID = Regex("^[0-9]{4}$")
}
