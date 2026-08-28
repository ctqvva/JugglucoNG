package tk.glucodata.drivers.aidex

/**
 * Keeps BLE advertisement names separate from the canonical identity stored by JugglucoNG.
 *
 * AiDEX hardware generations may advertise an `X-` or `F-` product prefix. The app deliberately
 * keeps one stable `X-` namespace for managed AiDEX sensor identities. The normal protocol serial
 * is the bare advertisement serial; the advertised generation remains available for narrowly
 * gated protocol compatibility handling.
 */
object AiDexSerialIdentity {
    private const val CANONICAL_PREFIX = "X-"
    private const val MIN_SERIAL_LENGTH = 8
    private const val MAX_SERIAL_LENGTH = 14

    private val generationPrefixed = Regex(
        "(?:^|\\s)(?:AIDEX\\s+)?([XF])\\s*-?\\s*" +
            "([A-Z0-9]{$MIN_SERIAL_LENGTH,$MAX_SERIAL_LENGTH})(?=\$|\\s)",
        RegexOption.IGNORE_CASE,
    )
    private val familyPrefixed = Regex(
        "(?:^|\\s)(?:AIDEX|LINX|LUMI|VISTA)\\s*[-_]?\\s*" +
            "([A-Z0-9]{$MIN_SERIAL_LENGTH,$MAX_SERIAL_LENGTH})(?=\$|\\s)",
        RegexOption.IGNORE_CASE,
    )

    private data class AdvertisementIdentity(
        val bareSerial: String,
        val generation: Char?,
    ) {
        val canonicalId: String get() = CANONICAL_PREFIX + bareSerial
    }

    private fun parseAdvertisement(rawName: String): AdvertisementIdentity? {
        val trimmed = rawName.trim()
        generationPrefixed.find(trimmed)?.let { match ->
            return AdvertisementIdentity(
                bareSerial = match.groupValues[2].uppercase(),
                generation = match.groupValues[1].uppercase().single(),
            )
        }
        familyPrefixed.find(trimmed)?.let { match ->
            return AdvertisementIdentity(
                bareSerial = match.groupValues[1].uppercase(),
                generation = null,
            )
        }

        // Preserve the previous narrow bare-name compatibility without treating every arbitrary
        // alphanumeric BLE device name as an AiDEX sensor.
        val cleaned = trimmed.replace(" ", "")
        if (cleaned.length == 11 && cleaned.all(Char::isLetterOrDigit)) {
            return AdvertisementIdentity(cleaned.uppercase(), generation = null)
        }
        return null
    }

    fun canonicalFromAdvertisement(rawName: String): String? {
        return parseAdvertisement(rawName)?.canonicalId
    }

    fun bareSerial(serialOrAdvertisementName: String): String {
        canonicalFromAdvertisement(serialOrAdvertisementName)?.let { canonical ->
            return canonical.substring(CANONICAL_PREFIX.length)
        }
        val trimmed = serialOrAdvertisementName.trim()
        return if (
            trimmed.length in MIN_SERIAL_LENGTH..MAX_SERIAL_LENGTH &&
            trimmed.all(Char::isLetterOrDigit)
        ) {
            trimmed.uppercase()
        } else {
            trimmed
        }
    }

    fun fallbackCanonicalFromAddress(address: String): String =
        CANONICAL_PREFIX + address.filter(Char::isLetterOrDigit).uppercase()

    fun advertisedProtocolSerialForMacFallback(
        storedSensorId: String,
        address: String?,
        advertisedName: String?,
    ): String? {
        if (address.isNullOrBlank() || advertisedName.isNullOrBlank()) return null
        if (!storedSensorId.equals(fallbackCanonicalFromAddress(address), ignoreCase = true)) return null
        return canonicalFromAdvertisement(advertisedName)
            ?.substring(CANONICAL_PREFIX.length)
    }

    /**
     * Return the one alternate authentication serial used by F-generation compatibility retry.
     *
     * This is deliberately stricter than discovery parsing: the advertisement must explicitly
     * contain `F-`, and its serial must match either the stored canonical identity or the exact
     * historical `X-<MAC>` fallback for the connected address.
     */
    fun fGenerationAuthenticationSerial(
        storedSensorId: String,
        address: String?,
        advertisedName: String?,
    ): String? {
        if (advertisedName.isNullOrBlank()) return null
        val advertised = parseAdvertisement(advertisedName) ?: return null
        if (advertised.generation != 'F') return null

        val matchesCanonical = storedSensorId.equals(advertised.canonicalId, ignoreCase = true)
        val matchesMacFallback = !address.isNullOrBlank() &&
            storedSensorId.equals(fallbackCanonicalFromAddress(address), ignoreCase = true)
        if (!matchesCanonical && !matchesMacFallback) return null

        return "F${advertised.bareSerial}"
    }
}
