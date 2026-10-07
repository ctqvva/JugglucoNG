package tk.glucodata.drivers.sibionics

import java.util.Locale
import java.util.UUID

object SibionicsConstants {
    const val TAG = "SibionicsManaged"
    const val SENSOR_GEN = 0
    const val MGDL_PER_MMOLL = 18.0f
    const val READING_INTERVAL_MS = 60_000L
    const val DAY_MS = 24L * 60L * 60L * 1000L
    const val MAX_ALGORITHM_GLUCOSE_MMOL = 50.0f
    const val MAX_ALGORITHM_GLUCOSE_MGDL = MAX_ALGORITHM_GLUCOSE_MMOL * MGDL_PER_MMOLL
    const val DEFAULT_EXPECTED_LIFETIME_MS = 14L * DAY_MS
    const val EXTENDED_CHINESE_LIFETIME_MS = 572L * 60L * 60L * 1000L - 19L * 60L * 1000L
    const val SIBIONICS2_OFFICIAL_LIFETIME_MS = 22L * DAY_MS
    const val SIBIONICS2_EXPECTED_LIFETIME_MS = 23L * DAY_MS
    const val GS3_EXPECTED_LIFETIME_MS = DEFAULT_EXPECTED_LIFETIME_MS + 36L * 60L * 60L * 1000L

    const val MANAGED_PREFIX = "SIBI:"

    val SERVICE_FF30: UUID = UUID.fromString("0000ff30-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_FF31: UUID = UUID.fromString("0000ff31-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE_FF32: UUID = UUID.fromString("0000ff32-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    enum class ProtocolMode {
        UNKNOWN,
        CHINESE,
        V120,
    }

    internal fun initialProtocolMode(variant: Variant, persisted: ProtocolMode): ProtocolMode =
        when {
            variant == Variant.SIBIONICS2 -> ProtocolMode.V120
            persisted != ProtocolMode.UNKNOWN -> persisted
            variant.prefersChineseProbe -> ProtocolMode.CHINESE
            else -> ProtocolMode.UNKNOWN
        }

    enum class Variant(
        val id: String,
        val legacySubtype: Int,
        val displayLabel: String,
        val appId: String,
        val registrationKeyHex: String,
        val fallbackShortCode: String,
    ) {
        EU(
            id = "eu",
            legacySubtype = 0,
            displayLabel = "Sibionics GS1 (EU)",
            appId = "com.sisensing.sijoy",
            registrationKeyHex = "56CE249349040C94F8B4B2375A8752D5CBE7A17814B502D9132489C0BFDFC99F0CAC670E8CBB085AF1C780B3D282E3",
            fallbackShortCode = "YMWD016F",
        ),
        HEMATONIX(
            id = "hematonix",
            legacySubtype = 1,
            displayLabel = "Hematonix",
            appId = "com.sisensing.rusibionics",
            registrationKeyHex = "60B05FEB7C0A148DEED2B3375A8754D9D0E6A5751BCE02D9132489C0BFDFC99F0CAC670E8DA7115CEACF87B7DE8FD4612E1B7638C2",
            fallbackShortCode = "YEZ1450H",
        ),
        CHINESE(
            id = "chinese",
            legacySubtype = 2,
            displayLabel = "Sibionics GS1 (CN)",
            appId = "com.sisensing.sisensingcgm",
            registrationKeyHex = "4E8E1CAF43051F97EEC9C1475A8752D5C387D17A65B002D9132489C0BFDFC99F0CAC670E8CBB1150E6D581B7D08FC03404052C57AD58",
            fallbackShortCode = "GEPD802J",
        ),
        SIBIONICS2(
            id = "sibionics2",
            legacySubtype = 3,
            displayLabel = "Sibionics 2",
            appId = "com.sisensing.eco",
            registrationKeyHex = "068449FA5C1B1F97EEC9C1475A8752D5C387D17A65B002D9132489C0BFDFC99F0CAC670E9AB10D62FDE0B2B1E7",
            fallbackShortCode = "0316015A",
        ),
        GS3(
            id = "gs3",
            legacySubtype = 4,
            displayLabel = "Sibionics GS3",
            appId = "com.sisensing.gs3",
            registrationKeyHex = "46C04E9267430C94F8B4B2375A8752D5CBE7A17814B502D9132489C0BFDFC99F0CAC670E98A1510AEA9186FAC6",
            fallbackShortCode = "YEZ1450H",
        );

        val prefersChineseProbe: Boolean
            get() = this == CHINESE

        val usesV116AAlgorithm: Boolean
            get() = this == EU || this == HEMATONIX || this == SIBIONICS2

        val officialLifetimeMs: Long
            get() = when (this) {
                SIBIONICS2 -> SIBIONICS2_OFFICIAL_LIFETIME_MS
                else -> DEFAULT_EXPECTED_LIFETIME_MS
            }

        val expectedLifetimeMs: Long
            get() = when (this) {
                SIBIONICS2 -> SIBIONICS2_EXPECTED_LIFETIME_MS
                GS3 -> GS3_EXPECTED_LIFETIME_MS
                else -> EXTENDED_CHINESE_LIFETIME_MS
            }

        companion object {
            fun fromId(raw: String?): Variant {
                val normalized = raw?.trim()?.lowercase(Locale.US).orEmpty()
                return entries.firstOrNull {
                    it.id == normalized || it.name.lowercase(Locale.US) == normalized
                } ?: EU
            }

            fun fromLegacySubtype(subtype: Int): Variant =
                entries.firstOrNull { it.legacySubtype == subtype } ?: EU
        }
    }

    fun normalizeToken(raw: String?): String =
        raw.orEmpty()
            .trim()
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() || it == ':' || it == '-' || it == '_' }

    fun normalizeBleName(raw: String?): String =
        raw.orEmpty()
            .trim()
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() }

    fun isSibionics2TransmitterName(raw: String?): Boolean {
        val name = normalizeBleName(raw)
        return name.length in 8..16 &&
            name.startsWith('P') &&
            name.substring(1, 4).all(Char::isDigit)
    }

    fun isValidAlgorithmGlucoseMgdl(value: Float): Boolean =
        value.isFinite() && value > 0f && value <= MAX_ALGORITHM_GLUCOSE_MGDL

    fun canonicalSensorId(raw: String?): String {
        val normalized = normalizeToken(raw)
        if (normalized.isBlank()) return ""
        return if (normalized.startsWith(MANAGED_PREFIX, ignoreCase = true)) {
            MANAGED_PREFIX + normalized.removePrefix(MANAGED_PREFIX).trim()
        } else {
            MANAGED_PREFIX + normalized
        }
    }

    fun stripManagedPrefix(raw: String?): String =
        normalizeToken(raw)
            .removePrefix(MANAGED_PREFIX)
            .ifBlank { normalizeBleName(raw) }

    fun matchesId(left: String?, right: String?): Boolean {
        val a = canonicalSensorId(left).takeIf { it.isNotBlank() } ?: return false
        val b = canonicalSensorId(right).takeIf { it.isNotBlank() } ?: return false
        if (a.equals(b, ignoreCase = true)) return true
        val ashort = stripManagedPrefix(a)
        val bshort = stripManagedPrefix(b)
        return ashort.isNotBlank() && bshort.isNotBlank() && (
            ashort.equals(bshort, ignoreCase = true) ||
                (ashort.length >= 6 && bshort.endsWith(ashort.takeLast(6), ignoreCase = true)) ||
                (bshort.length >= 6 && ashort.endsWith(bshort.takeLast(6), ignoreCase = true))
            )
    }

    fun normalizeBleAddress(raw: String?): String? {
        val text = raw?.trim()?.uppercase(Locale.US).orEmpty()
        if (text.isBlank()) return null
        val parts = text.split(':')
        if (parts.size == 6 && parts.all { it.length == 2 && it.all(::isHexDigit) }) {
            return parts.joinToString(":")
        }
        val compact = text.filter(::isHexDigit)
        if (compact.length != 12) return null
        return compact.chunked(2).joinToString(":")
    }

    fun macBytes(raw: String?): ByteArray? {
        val address = normalizeBleAddress(raw) ?: return null
        val bytes = ByteArray(6)
        val parts = address.split(':')
        for (i in 0 until 6) {
            bytes[i] = parts[i].toInt(16).toByte()
        }
        return bytes
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'
}
