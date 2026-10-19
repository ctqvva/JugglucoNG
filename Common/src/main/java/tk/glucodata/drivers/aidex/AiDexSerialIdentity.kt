package tk.glucodata.drivers.aidex

import java.util.Locale

/**
 * Keeps BLE advertisement names separate from the canonical identity stored by JugglucoNG.
 *
 * AiDEX product prefixes are advertisement labels, not protocol capabilities. They have changed
 * across hardware batches and must never decide which pairing-key strategy a sensor uses. The app
 * deliberately keeps one stable `X-` namespace internally while accepting any one-letter
 * generation prefix from advertisements.
 */
object AiDexSerialIdentity {
    private const val CANONICAL_PREFIX = "X-"
    private const val MIN_SERIAL_LENGTH = 8
    private const val MAX_SERIAL_LENGTH = 14

    private val generationPrefixed = Regex(
        "(?:^|\\s)(?:AIDEX\\s+)?[A-Z]\\s*-\\s*" +
            "([A-Z0-9]{$MIN_SERIAL_LENGTH,$MAX_SERIAL_LENGTH})(?=\$|\\s)",
        RegexOption.IGNORE_CASE,
    )
    // A family word may carry the generation letter glued to it, as in "IndiaX-22222E7T6K".
    private val familyPrefixed = Regex(
        "(?:^|\\s)(?:AIDEX|LINX|LUMI|VISTA|SMART|INDIA)\\s*(?:[A-Z]\\s*-|[-_])?\\s*" +
            "([A-Z0-9]{$MIN_SERIAL_LENGTH,$MAX_SERIAL_LENGTH})(?=\$|\\s)",
        RegexOption.IGNORE_CASE,
    )

    // Rebrands whose advertisement names the product line. Display only: the badge reads it,
    // identity and pairing never do. Anything unlisted keeps the family badge.
    private val productBrands = listOf(
        Regex("^\\s*lumi(?:flex)?(?=\$|[\\s_0-9-])", RegexOption.IGNORE_CASE) to "LUMI",
    )

    /** Badge brand for an advertised name such as "Lumi-2222258CUW", or null for the family's own. */
    fun productBrandFromAdvertisement(rawName: String?): String? {
        if (rawName.isNullOrBlank()) return null
        return productBrands.firstOrNull { (pattern, _) -> pattern.containsMatchIn(rawName) }?.second
    }

    fun canonicalFromAdvertisement(rawName: String): String? {
        val trimmed = rawName.trim()
        generationPrefixed.find(trimmed)?.let { match ->
            return CANONICAL_PREFIX + match.groupValues[1].uppercase(Locale.ROOT)
        }
        familyPrefixed.find(trimmed)?.let { match ->
            return CANONICAL_PREFIX + match.groupValues[1].uppercase(Locale.ROOT)
        }

        // Preserve the previous narrow bare-name compatibility without treating every arbitrary
        // alphanumeric BLE device name as an AiDEX sensor.
        val cleaned = trimmed.replace(" ", "")
        if (cleaned.length == 11 && cleaned.all(Char::isLetterOrDigit)) {
            return CANONICAL_PREFIX + cleaned.uppercase(Locale.ROOT)
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
            trimmed.uppercase(Locale.ROOT)
        } else {
            trimmed
        }
    }

    fun fallbackCanonicalFromAddress(address: String): String =
        CANONICAL_PREFIX + address.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)

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
