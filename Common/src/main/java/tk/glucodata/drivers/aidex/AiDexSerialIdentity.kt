package tk.glucodata.drivers.aidex

/**
 * Keeps BLE advertisement names separate from the canonical identity stored by JugglucoNG.
 *
 * AiDEX hardware generations may advertise an `X-` or `F-` product prefix. The prefix is not
 * part of the serial used by the protocol, while the app deliberately keeps one stable `X-`
 * namespace for managed AiDEX sensor identities.
 */
object AiDexSerialIdentity {
    private const val CANONICAL_PREFIX = "X-"
    private const val MIN_SERIAL_LENGTH = 8
    private const val MAX_SERIAL_LENGTH = 14

    private val generationPrefixed = Regex(
        "(?:^|\\s)(?:AIDEX\\s+)?[XF]\\s*-?\\s*" +
            "([A-Z0-9]{$MIN_SERIAL_LENGTH,$MAX_SERIAL_LENGTH})(?=\$|\\s)",
        RegexOption.IGNORE_CASE,
    )
    private val familyPrefixed = Regex(
        "(?:^|\\s)(?:AIDEX|LINX|LUMI|VISTA)\\s*[-_]?\\s*" +
            "([A-Z0-9]{$MIN_SERIAL_LENGTH,$MAX_SERIAL_LENGTH})(?=\$|\\s)",
        RegexOption.IGNORE_CASE,
    )

    fun canonicalFromAdvertisement(rawName: String): String? {
        val trimmed = rawName.trim()
        generationPrefixed.find(trimmed)?.let { match ->
            return CANONICAL_PREFIX + match.groupValues[1].uppercase()
        }
        familyPrefixed.find(trimmed)?.let { match ->
            return CANONICAL_PREFIX + match.groupValues[1].uppercase()
        }

        // Preserve the previous narrow bare-name compatibility without treating every arbitrary
        // alphanumeric BLE device name as an AiDEX sensor.
        val cleaned = trimmed.replace(" ", "")
        if (cleaned.length == 11 && cleaned.all(Char::isLetterOrDigit)) {
            return CANONICAL_PREFIX + cleaned.uppercase()
        }
        return null
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
}
