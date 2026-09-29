package tk.glucodata.drivers.aidex

import tk.glucodata.drivers.aidex.native.crypto.SerialCrypto
import java.util.UUID

/**
 * Scan-time identity for AiDex-family sensors. Serial parsing is the only bind key;
 * a MAC, 0x181F CGM UUID, or family-name hint is not enough to persist or derive keys.
 */
object AiDexScanIdentity {

    val CGM_SERVICE_UUID: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
    val VENDOR_SERVICE_UUID: UUID = UUID.fromString("0000f000-0000-1000-8000-00805f9b34fb")
    val FF30_SERVICE_UUID: UUID = UUID.fromString("0000ff30-0000-1000-8000-00805f9b34fb")

    data class ScanDetection(
        val displayName: String,
        val serial: String?,
        val isLikelyAiDex: Boolean,
        val detectedViaFf30: Boolean,
        val serialFromAdvert: Boolean,
    )

    // Bodies are bounded at 14 like AiDexSerialIdentity, and the lookahead refuses to take a prefix
    // of a longer run, so what these two mint strips back through bareSerial. It does not stop
    // find() re-anchoring at a later X, or a later family word, inside such a run and taking the
    // tail; that is accepted on purpose, because a lookbehind refusing every X that follows an
    // alphanumeric re-identifies those names - LUMIX2222267V4E would become X-X2222267V4E.
    private val X_PREFIXED =
        Regex("X\\s*-?\\s*([A-Z0-9]{8,14})(?![A-Z0-9])", RegexOption.IGNORE_CASE)
    private val FAMILY_PREFIXED =
        Regex("(?:AIDEX|LINX|LUMI|VISTA)\\s*[-_]?\\s*([A-Z0-9]{8,14})(?![A-Z0-9])", RegexOption.IGNORE_CASE)

    fun normalizeSerial(rawName: String): String? {
        val xMatch = X_PREFIXED.find(rawName)
        if (xMatch != null) {
            return "X-${xMatch.groupValues[1].uppercase()}"
        }

        val familyMatch = FAMILY_PREFIXED.find(rawName)
        if (familyMatch != null) {
            return "X-${familyMatch.groupValues[1].uppercase()}"
        }

        // Names these two refuse are the canonical parser's: any one-letter generation prefix
        // (F-, G-, Q-) and the bare form. It is what the binding path derives keys through, so a
        // sensor it can read must be selectable here too.
        return AiDexSerialIdentity.canonicalFromAdvertisement(rawName)
    }

    fun detectCandidate(
        address: String,
        deviceName: String?,
        scanRecordName: String?,
        scanRecordBytes: ByteArray?,
        advertisedServiceUuids: List<UUID>?,
    ): ScanDetection {
        val localName = extractLocalName(scanRecordBytes)
        val advertNames = listOf(localName, scanRecordName)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        val cachedNames = listOf(deviceName)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        val names = linkedSetOf<String>().apply {
            addAll(advertNames)
            addAll(cachedNames)
        }

        val serialName = names.firstOrNull { normalizeSerial(it) != null }
        val advertSerial = advertNames.firstNotNullOfOrNull { normalizeSerial(it) }
        val serial = advertSerial ?: cachedNames.firstNotNullOfOrNull { normalizeSerial(it) }
        val nameLooksAiDex = names.any(::looksLikeFamilyName)
        val hasFf30 = advertisedServiceUuids?.contains(FF30_SERVICE_UUID) == true ||
            scanRecordAdvertises16BitService(scanRecordBytes, 0xFF30)
        val hasPrimaryServiceHint =
            advertisedServiceUuids?.any { it == CGM_SERVICE_UUID || it == VENDOR_SERVICE_UUID } == true ||
                scanRecordAdvertises16BitService(scanRecordBytes, 0x181F) ||
                scanRecordAdvertises16BitService(scanRecordBytes, 0xF000)
        val isLikelyAiDex = serial != null || nameLooksAiDex || hasFf30 || hasPrimaryServiceHint
        val displayName = serialName ?: names.firstOrNull() ?: address
        return ScanDetection(
            displayName = displayName,
            serial = serial,
            isLikelyAiDex = isLikelyAiDex,
            detectedViaFf30 = hasFf30,
            serialFromAdvert = advertSerial != null,
        )
    }

    /** [address] is the radio the serial was seen on; a serial that only spells that MAC is refused. */
    fun canBind(serial: String?, address: String?): Boolean =
        !serial.isNullOrBlank() && !isMacFallbackSerial(serial, address)

    /**
     * Scan-list upsert for one radio at [address]: an advert-derived serial may replace a cached one.
     * A cache-only packet must not overwrite an advert SN (OS name cache).
     */
    fun shouldReplaceScanSerial(
        existingSerial: String?,
        existingFromAdvert: Boolean,
        candidateSerial: String?,
        candidateFromAdvert: Boolean,
        address: String?,
    ): Boolean {
        if (candidateSerial.isNullOrBlank() || !canBind(candidateSerial, address)) return false
        if (existingSerial.isNullOrBlank()) return true
        if (existingSerial.equals(candidateSerial, ignoreCase = true)) return false
        if (existingFromAdvert && !candidateFromAdvert) return false
        return candidateFromAdvert
    }

    /**
     * True when [existingSerial] is a real SN already persisted on [newAddress].
     * Leftover MAC-fallback rows are not a conflict — those are retargeted.
     */
    fun persistAddressOccupiedByOtherRealSerial(
        existingSerial: String?,
        existingAddress: String?,
        newSerial: String,
        newAddress: String,
    ): Boolean {
        if (existingSerial.isNullOrBlank() || existingAddress.isNullOrBlank()) return false
        if (!existingAddress.equals(newAddress, ignoreCase = true)) return false
        if (isMacFallbackSerial(existingSerial, existingAddress)) return false
        val existingBare = SerialCrypto.stripPrefix(existingSerial.trim())
        val newBare = SerialCrypto.stripPrefix(newSerial.trim())
        return !existingBare.equals(newBare, ignoreCase = true)
    }

    private val LETTER_HEX12 = Regex("^[A-Za-z]-([0-9A-Fa-f]{12})$")

    /**
     * Leftover identity from the old MAC-as-serial fallback: `<letter>-` followed by the 12 hex
     * digits of this sensor's own BLE [address]. Real AiDex serials can also be `<letter>-<12 hex>`,
     * so the shape alone proves nothing: without the sensor's own address it is never a leftover.
     * Other drivers' bare 12-hex ids (Ottai, Anytime, MQ) have no letter prefix and never match.
     */
    fun isMacFallbackSerial(serial: String?, address: String?): Boolean {
        val body = LETTER_HEX12.matchEntire(serial?.trim() ?: return false)?.groupValues?.get(1) ?: return false
        val mac = address?.filter { it.isLetterOrDigit() } ?: return false
        return body.equals(mac, ignoreCase = true)
    }

    fun looksLikeFamilyName(rawName: String): Boolean {
        val lowered = rawName.lowercase()
        return lowered.contains("aidex") ||
            lowered.contains("linx") ||
            lowered.contains("lumi") ||
            lowered.contains("vista")
    }

    fun extractLocalName(scanRecord: ByteArray?): String? {
        if (scanRecord == null) return null
        var offset = 0
        while (offset < scanRecord.size - 1) {
            val len = scanRecord[offset].toInt() and 0xFF
            if (len == 0) break
            val next = offset + len + 1
            if (next > scanRecord.size) break
            val type = scanRecord[offset + 1].toInt() and 0xFF
            if (type == 0x08 || type == 0x09) {
                val start = offset + 2
                if (next > start) {
                    return try {
                        String(scanRecord, start, next - start, Charsets.UTF_8)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            offset = next
        }
        return null
    }

    fun scanRecordAdvertises16BitService(scanRecord: ByteArray?, serviceShortUuid: Int): Boolean {
        if (scanRecord == null) return false
        var offset = 0
        while (offset < scanRecord.size - 1) {
            val len = scanRecord[offset].toInt() and 0xFF
            if (len == 0) break
            val next = offset + len + 1
            if (next > scanRecord.size) break
            val type = scanRecord[offset + 1].toInt() and 0xFF
            if (type == 0x02 || type == 0x03) {
                var uuidOffset = offset + 2
                while (uuidOffset + 1 < next) {
                    val uuid = (scanRecord[uuidOffset].toInt() and 0xFF) or
                        ((scanRecord[uuidOffset + 1].toInt() and 0xFF) shl 8)
                    if (uuid == serviceShortUuid) return true
                    uuidOffset += 2
                }
            }
            offset = next
        }
        return false
    }
}
