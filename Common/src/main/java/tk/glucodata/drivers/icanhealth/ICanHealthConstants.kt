// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Driver
// ICanHealthConstants.kt — BLE UUIDs, protocol constants, payload offsets
//
// Protocol: Standard Bluetooth CGM Service (0x181F) with firmware-selected
// hardcoded crypto:
// - glucose payloads: AES-128-ECB
// - F3/F4 auth: AES-128-CBC/PKCS7
// Reverse-engineered from iCanHealth app (com.sinocare.ican.health.ru v01.16.01.02).

package tk.glucodata.drivers.icanhealth

import java.util.UUID
import java.util.Locale
import kotlin.math.abs

object ICanHealthConstants {
    // Precompiled once. These were built inline on every call, and because
    // SensorIdentity.matches -> managedMatches asks every driver adapter to
    // canonicalise both ids, a single identity comparison compiled a fresh
    // java.util.regex.Pattern (ICU native) per adapter per side. A stuck-main-thread
    // stack dump landed in Pattern.compile beneath canonicalSensorId, and that path
    // runs per reading, per sensor, per UI snapshot and per notification build.
    private val SERIAL_P_FORM: Regex = Regex("^P\\d{9}[A-Z]{3}$", RegexOption.IGNORE_CASE)
    private val SERIAL_LT_FORM: Regex = Regex("^LT\\d{6,}[A-Z]{2,3}$", RegexOption.IGNORE_CASE)
    private val SERIAL_ALNUM_12_32: Regex = Regex("^[0-9A-Z]{12,32}$", RegexOption.IGNORE_CASE)
    private val SERIAL_ALNUM_16_32: Regex = Regex("^[0-9A-Z]{16,32}$", RegexOption.IGNORE_CASE)
    private val FULL_CANONICAL_HEX_SENSOR_ID_REGEX = Regex("^[0-9A-Z]{16}$", RegexOption.IGNORE_CASE)

    const val TAG = "ICanHealth"
    const val MAX_NATIVE_SENSOR_ID_CHARS = 16
    const val PROVISIONAL_SENSOR_PREFIX = "ICN-"
    const val LEGACY_PROVISIONAL_SENSOR_PREFIX = "ICAN-"
    const val DEFAULT_DISPLAY_NAME = "Sinocare CGM"
    const val DEFAULT_OLD_GLUCOSE_AES_KEY_ASCII = "4&13E6G24w6ZL3rF"
    const val DEFAULT_NEW_GLUCOSE_AES_KEY_ASCII = "y67BbdK!3qN?75t8"
    const val DEFAULT_OLD_ORIGINAL_HISTORY_AES_KEY_ASCII = "z3q9V419#7574XZI"
    const val DEFAULT_NEW_ORIGINAL_HISTORY_AES_KEY_ASCII = "X3c26#mb9~Sudyk9"
    const val DEFAULT_OLD_AUTH_AES_KEY_ASCII = "92AFQU*u9c695826"
    const val DEFAULT_NEW_AUTH_AES_KEY_ASCII = "px@9k5K*23cV%YDc"
    const val DEFAULT_GLUCOSE_AES_KEY_ASCII = DEFAULT_OLD_GLUCOSE_AES_KEY_ASCII
    const val DEFAULT_AUTH_USER_ID = ""
    // Late-life iCan firmware freezes its CGM-status offset at exactly 28 days
    // (see LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES below) — observed end-of-life
    // sequence count = 40320 min. The advisory expected end UI label should match
    // this physical cap, not the previous 31-day estimate.
    const val ADVISORY_EXPECTED_LIFETIME_DAYS = 28
    private const val STANDALONE_USER_ID_LENGTH = 12

    // ---- BLE Service UUIDs ----

    /** Standard Bluetooth CGM Service (same as AiDex — differentiated by device name) */
    val CGM_SERVICE: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")

    /** Device Information Service */
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    // ---- CGM Service Characteristics ----

    /** CGM Measurement (Notify) — glucose notifications (encrypted)
     *  Wire format: 3 bytes cleartext header + 16 bytes AES-128-ECB encrypted payload = 19 bytes */
    val CGM_MEASUREMENT: UUID = UUID.fromString("00002aa7-0000-1000-8000-00805f9b34fb")

    /** CGM Status (Read) — time offset from session start */
    val CGM_STATUS: UUID = UUID.fromString("00002aa9-0000-1000-8000-00805f9b34fb")

    /** CGM Session Start Time (Read) — sensor activation time */
    val CGM_SESSION_START_TIME: UUID = UUID.fromString("00002aaa-0000-1000-8000-00805f9b34fb")

    /** Record Access Control Point (Write + Indicate) — history fetch */
    val RACP: UUID = UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")

    /** CGM Specific Ops Control Point — vendor auth (F3/F4/F6/F2 commands) */
    val CGM_SPECIFIC_OPS: UUID = UUID.fromString("00002aac-0000-1000-8000-00805f9b34fb")

    // ---- Device Information Characteristics ----

    val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val SERIAL_NUMBER: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val SOFTWARE_REVISION: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")

    /** Client Characteristic Configuration Descriptor */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- Protocol Constants ----

    /** AES-128-ECB block size */
    const val AES_BLOCK_SIZE = 16

    /** Glucose notification total size (3 header + 16 encrypted) */
    const val GLUCOSE_NOTIFICATION_SIZE = 19

    /** Default glucose reading interval in minutes (older i3/i6/H6 family) */
    const val DEFAULT_READING_INTERVAL_MINUTES = 3

    /** Glucose measurement type byte in cleartext header */
    const val MEASUREMENT_TYPE_BYTE: Byte = 0x02

    /** Default rated lifetime in days (older i3/i6/i7 family) */
    const val DEFAULT_RATED_LIFETIME_DAYS = 15

    /** Default warmup duration in minutes */
    const val DEFAULT_WARMUP_MINUTES = 120

    /** mmol/L to mg/dL conversion factor (exact) */
    const val MMOL_TO_MGDL: Float = 18.0182f
    const val MIN_VALID_GLUCOSE_MGDL = 10f
    // Keep iCan aligned with the native stream validator (<552 mg/dL). Values
    // above the normal display maximum still render as HI instead of no data.
    const val MAX_VALID_GLUCOSE_MGDL = 551f

    private enum class BundledKeyFamily {
        OLD,
        NEW,
    }

    private fun parseBundledKeySelector(source: String?): Int? {
        return source
            ?.trim()
            ?.takeIf { it.length >= 12 }
            ?.substring(10, 12)
            ?.toIntOrNull(16)
    }

    private fun resolveBundledKeyFamily(
        launcherSerial: String?,
        softwareVersion: String?,
    ): BundledKeyFamily {
        val selector = parseBundledKeySelector(launcherSerial)
            ?: parseBundledKeySelector(softwareVersion)
        return if (selector != null && selector in 0x04..0x07) {
            BundledKeyFamily.NEW
        } else {
            BundledKeyFamily.OLD
        }
    }

    @JvmStatic
    fun usesNewBundledCrypto(softwareVersion: String?): Boolean =
        usesNewBundledCrypto(null, softwareVersion)

    @JvmStatic
    fun usesNewBundledCrypto(launcherSerial: String?, softwareVersion: String?): Boolean =
        resolveBundledKeyFamily(launcherSerial, softwareVersion) == BundledKeyFamily.NEW

    @JvmStatic
    fun resolveBundledGlucoseKey(softwareVersion: String?): String =
        resolveBundledGlucoseKey(null, softwareVersion)

    @JvmStatic
    fun resolveBundledGlucoseKey(launcherSerial: String?, softwareVersion: String?): String =
        when (resolveBundledKeyFamily(launcherSerial, softwareVersion)) {
            BundledKeyFamily.NEW -> DEFAULT_NEW_GLUCOSE_AES_KEY_ASCII
            BundledKeyFamily.OLD -> DEFAULT_OLD_GLUCOSE_AES_KEY_ASCII
        }

    @JvmStatic
    fun resolveBundledAuthKey(softwareVersion: String?): String =
        resolveBundledAuthKey(null, softwareVersion)

    @JvmStatic
    fun resolveBundledAuthKey(launcherSerial: String?, softwareVersion: String?): String =
        when (resolveBundledKeyFamily(launcherSerial, softwareVersion)) {
            BundledKeyFamily.NEW -> DEFAULT_NEW_AUTH_AES_KEY_ASCII
            BundledKeyFamily.OLD -> DEFAULT_OLD_AUTH_AES_KEY_ASCII
        }

    @JvmStatic
    fun resolveBundledOriginalHistoryKey(softwareVersion: String?): String =
        resolveBundledOriginalHistoryKey(null, softwareVersion)

    @JvmStatic
    fun resolveBundledOriginalHistoryKey(launcherSerial: String?, softwareVersion: String?): String =
        when (resolveBundledKeyFamily(launcherSerial, softwareVersion)) {
            BundledKeyFamily.NEW -> DEFAULT_NEW_ORIGINAL_HISTORY_AES_KEY_ASCII
            BundledKeyFamily.OLD -> DEFAULT_OLD_ORIGINAL_HISTORY_AES_KEY_ASCII
        }

    @JvmStatic
    fun resolveConfiguredAesKey(candidate: String?): String =
        resolveConfiguredAesKey(candidate, null)

    @JvmStatic
    fun resolveConfiguredAesKey(candidate: String?, softwareVersion: String?): String {
        return resolveConfiguredAesKey(candidate, null, softwareVersion)
    }

    @JvmStatic
    fun resolveConfiguredAesKey(candidate: String?, launcherSerial: String?, softwareVersion: String?): String {
        val trimmed = candidate?.trim().orEmpty()
        return if (trimmed.length == AES_BLOCK_SIZE) {
            trimmed
        } else {
            resolveBundledGlucoseKey(launcherSerial, softwareVersion)
        }
    }

    @JvmStatic
    fun normalizeStandaloneUserId(source: String?): String {
        val trimmed = normalizeConfiguredAuthUserId(source)
        return when {
            trimmed.length >= STANDALONE_USER_ID_LENGTH -> trimmed.takeLast(STANDALONE_USER_ID_LENGTH)
            trimmed.isNotEmpty() -> trimmed.padEnd(STANDALONE_USER_ID_LENGTH, '0')
            else -> "0".repeat(STANDALONE_USER_ID_LENGTH)
        }
    }

    @JvmStatic
    fun normalizeConfiguredAuthUserId(source: String?): String {
        return source
            ?.trim()
            ?.filter { it.code in 0x21..0x7E && !it.isWhitespace() }
            .orEmpty()
    }

    @JvmStatic
    fun normalizeOnboardingDeviceSn(source: String?): String {
        val sanitized = sanitizeSensorIdentity(source)
        if (sanitized.isEmpty()) {
            return ""
        }
        if (sanitized.length > 13) {
            return deriveShortSnFromActiveCode(sanitized)
        }
        return sanitized
    }

    /**
     * Derives the DIS-comparable identity prefix from a launcher QR/active code.
     * Extended Chinese codes encode the same eight characters as `XYabcdefg`,
     * while DIS exposes them as `0abcdefg`.
     */
    @JvmStatic
    fun onboardingIdentityPrefix(source: String?): String {
        val normalized = normalizeOnboardingDeviceSn(source)
        val shortSn = deriveShortSnFromActiveCode(normalized)
        return if (usesExtendedShortSn(shortSn)) {
            "0" + shortSn.substring(2)
        } else {
            shortSn
        }
    }

    @JvmStatic
    fun matchesOnboardingIdentity(onboardingDeviceSn: String?, deviceSerial: String?): Boolean {
        val expected = normalizeOnboardingDeviceSn(onboardingDeviceSn)
        val observed = sanitizeSensorIdentity(deviceSerial)
        if (expected.isEmpty() || observed.isEmpty()) {
            return false
        }
        val prefix = onboardingIdentityPrefix(expected)
        if (prefix.length < 8) {
            return false
        }
        if (expected == observed) {
            return true
        }
        if (observed.startsWith(prefix)) {
            return true
        }

        // i6 active codes and DIS serials encode the same seven-character sensor
        // core at different offsets. Example observed in production:
        // ZA1OR03MSE50 (active code) -> 01OR03MS00070101 (DIS serial).
        // Keep the post-connection identity gate, but recognize that vendor layout
        // instead of accepting whichever nearby iCan happened to connect first.
        return expected.length == 12 &&
            observed.length >= 8 &&
            expected.substring(2, 9) == observed.substring(1, 8)
    }

    private fun sanitizeSensorIdentity(source: String?): String =
        source
            ?.trim()
            ?.uppercase(Locale.US)
            ?.filter { it.isLetterOrDigit() }
            .orEmpty()

    private fun deriveShortSnFromActiveCode(activeCode: String): String {
        if (activeCode.length < 12) {
            return activeCode
        }
        val prefixLength = if (usesExtendedShortSn(activeCode)) 9 else 8
        return activeCode.take(prefixLength)
    }

    private fun usesExtendedShortSn(value: String): Boolean =
        value.length >= 9 && (value[0] > 'F' || value[1] > 'F')

    // ---- Vendor Auth Commands ----

    /** F3: Request authentication challenge */
    val CMD_REQUEST_CHALLENGE = byteArrayOf(0xF3.toByte())

    /** F4 prefix: Send authentication token (F4 10 [token_16bytes]) */
    const val AUTH_TOKEN_PREFIX: Byte = 0xF4.toByte()
    const val AUTH_TOKEN_LENGTH_BYTE: Byte = 0x10 // 16 bytes

    /** F6: Request sensor info */
    val CMD_REQUEST_SENSOR_INFO = byteArrayOf(0xF6.toByte())

    /** 0x1A: Start sensor session */
    val CMD_START_SENSOR = byteArrayOf(0x1A)

    /** 0xFC: Fingerstick calibration write */
    const val CALIBRATION_OPCODE: Byte = 0xFC.toByte()

    const val LAUNCHER_STATE_IDLE = 0x00
    const val LAUNCHER_STATE_WARMUP = 0x01
    const val LAUNCHER_STATE_ENDED = 0x40
    const val LAUNCHER_STATE_RUNNING = 0x80

    // Observed late-life iCan firmware reports ENDED and freezes the CGM status
    // offset here. Treat it as an observed vendor end for UI/backoff, not as a
    // cross-firmware hard stop.
    const val LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES = 28 * 24 * 60

    @JvmStatic
    fun isActiveLauncherState(state: Int): Boolean =
        state == LAUNCHER_STATE_RUNNING || state == LAUNCHER_STATE_WARMUP

    @JvmStatic
    fun isEndedStatusSequenceCap(state: Int, sequenceNumber: Int): Boolean =
        state == LAUNCHER_STATE_ENDED &&
            sequenceNumber >= LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES

    internal fun endedStatusEndTimestampMs(sessionStartEpochMs: Long, sequenceNumber: Int): Long? {
        if (sessionStartEpochMs <= 0L || sequenceNumber < LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES) {
            return null
        }
        val endedSequence = sequenceNumber.coerceAtLeast(LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES)
        return sessionStartEpochMs + endedSequence.toLong() * 60_000L
    }

    internal fun hasCompleteEndedStatusHistory(
        sessionStartEpochMs: Long,
        sequenceNumber: Int,
        tailTimestampMs: Long,
        toleranceMs: Long,
    ): Boolean {
        if (tailTimestampMs <= 0L) {
            return false
        }
        val endTimestampMs = endedStatusEndTimestampMs(sessionStartEpochMs, sequenceNumber) ?: return false
        return tailTimestampMs >= endTimestampMs - toleranceMs.coerceAtLeast(0L)
    }

    /**
     * True while a parsed session start still explains the sensor's own sequence counter.
     *
     * The counter ticks once a minute from session start, so `sessionStart + sequence` must land
     * on the moment the counter was read. A session start that is off by a whole UTC offset stays
     * internally consistent and so survives every per-candidate bounds check; only this
     * cross-check against the sensor's own counter catches it.
     *
     * Deliberately symmetric — a negative UTC offset drags the session start into the past.
     */
    internal fun sessionTimelineMatchesSequenceCounter(
        sessionStartEpochMs: Long,
        sequenceNumber: Int,
        sequenceObservedAtMs: Long,
        sequenceUnitMs: Long,
        toleranceMs: Long,
    ): Boolean {
        if (sessionStartEpochMs <= 0L || sequenceObservedAtMs <= 0L) {
            return false
        }
        if (sequenceNumber < 0) {
            // Nothing to cross-check against yet.
            return true
        }
        val impliedObservedAtMs = sessionStartEpochMs + sequenceNumber.toLong() * sequenceUnitMs
        return abs(impliedObservedAtMs - sequenceObservedAtMs) <= toleranceMs.coerceAtLeast(0L)
    }

    /**
     * How many readings the sensor produced between two live sequence numbers but never delivered.
     *
     * The sequence counter advances one unit per minute regardless of model, so a sensor on a
     * three-minute cadence steps by three. Returns 0 when the two are consecutive readings, and
     * 0 for any non-advancing or unusable input.
     */
    internal fun missedReadingsBetween(
        previousSequence: Int,
        currentSequence: Int,
        readingIntervalMinutes: Int,
    ): Int {
        if (previousSequence < 0 || currentSequence <= previousSequence || readingIntervalMinutes <= 0) {
            return 0
        }
        return ((currentSequence - previousSequence) / readingIntervalMinutes - 1).coerceAtLeast(0)
    }

    const val RACP_RESULT_SUCCESS = 0x01
    const val RACP_RESULT_NOT_SUPPORTED = 0x02
    const val RACP_RESULT_NO_DATA = 0x06
    const val RACP_RESULT_FAILED = 0x0A

    /**
     * Glucose-history RACP report-all.
     *
     * Decompiled `g.a(index, true, isNewPrefix)` uses type `0x02` for glucose
     * history and type `0x01` for original-history/current-temp batches.
     */
    @JvmStatic
    @JvmOverloads
    fun buildRacpReportAllGlucose(modernPrefix: Boolean = true): ByteArray = byteArrayOf(
        if (modernPrefix) 0xF1.toByte() else 0x01,
        0x01,
        0x02
    )

    /**
     * Original-history/current-temp RACP report-all.
     */
    @JvmStatic
    @JvmOverloads
    fun buildRacpReportAllOriginal(modernPrefix: Boolean = true): ByteArray = byteArrayOf(
        if (modernPrefix) 0xF1.toByte() else 0x01,
        0x01,
        0x01
    )

    @JvmStatic
    @JvmOverloads
    fun buildRacpReportFromGlucose(offset: Int, modernPrefix: Boolean = true): ByteArray {
        val safeOffset = offset.coerceAtLeast(1).coerceAtMost(0xFFFF)
        return byteArrayOf(
            if (modernPrefix) 0xF1.toByte() else 0x01,
            0x03,
            0x02,
            0x01,
            (safeOffset and 0xFF).toByte(),
            ((safeOffset ushr 8) and 0xFF).toByte()
        )
    }

    @JvmStatic
    @JvmOverloads
    fun buildRacpReportFromOriginal(offset: Int, modernPrefix: Boolean = true): ByteArray {
        val safeOffset = offset.coerceAtLeast(1).coerceAtMost(0xFFFF)
        return byteArrayOf(
            if (modernPrefix) 0xF1.toByte() else 0x01,
            0x03,
            0x01,
            0x01,
            (safeOffset and 0xFF).toByte(),
            ((safeOffset ushr 8) and 0xFF).toByte()
        )
    }

    // ---- Vendor Auth Response Prefixes ----

    /** Challenge response prefix: 06 F3 01 [4-byte challenge] */
    val RESPONSE_CHALLENGE_PREFIX = byteArrayOf(0x06, 0xF3.toByte(), 0x01)

    /** Auth success: 1C F4 01 */
    val RESPONSE_AUTH_SUCCESS = byteArrayOf(0x1C, 0xF4.toByte(), 0x01)

    /** Sensor info prefix: 1C F6 01 [data...] */
    val RESPONSE_SENSOR_INFO_PREFIX = byteArrayOf(0x1C, 0xF6.toByte(), 0x01)

    /** RACP complete: 06 00 F1 01 */
    val RESPONSE_RACP_COMPLETE = byteArrayOf(0x06, 0x00, 0xF1.toByte(), 0x01)

    /** 16-byte encrypted glucose-history/current XOR table (old firmware) */
    val LEGACY_HISTORY_GLUCOSE_XOR_TABLE_OLD = byteArrayOf(
        109, 41, -117, 45, 36, -89, 83, -13, -56, -72, 127, 45, -123, 77, 67, -15,
        -118, -94, 70, -83, -116, 119, -44, 45, -66, 96, -2, 7, -14, -2, 82, 119,
        -84, -113, 125, 29, -19, 56, -66, -20, -33, 120, 93, 65, -101, 104, 127, 40,
        -60, -14, 120, -12, -10, 71, 84, -43, 15, -82, 91, 39, -53, 65, 33, -126
    )

    /** 16-byte encrypted glucose-history/current XOR table (new firmware) */
    val LEGACY_HISTORY_GLUCOSE_XOR_TABLE_NEW = byteArrayOf(
        -65, 45, -104, 35, -46, -93, 117, 79, -8, -72, -121, 33, -40, 67, -44, 95,
        98, -86, 36, -83, -24, 116, 125, -62, -18, 110, 95, 39, 127, -14, -27, 39,
        -40, -116, -9, 28, -34, 62, -117, -34, -33, 127, -123, 72, 57, 111, -57, -78,
        -114, -12, 39, -11, 79, 68, 117, 109, -71, -81, -27, 34, 124, 65, 18, -72
    )

    // ---- SN History Packet Types ----

    const val SN_HISTORY_SUBTYPE_ORIGINAL = 0x01
    const val SN_HISTORY_SUBTYPE_GLUCOSE = 0x02
    const val SN_HISTORY_RECORD_START_OFFSET = 3
    const val SN_HISTORY_RECORD_SIZE_ORIGINAL = 4
    const val SN_HISTORY_RECORD_SIZE_GLUCOSE = 2

    // ---- Decrypted Payload Offsets (within 16-byte decrypted block) ----

    /** Byte 0: constant 0x0D (size/flags marker) */
    const val OFFSET_SIZE_FLAGS = 0

    /** Byte 1: status byte */
    const val OFFSET_STATUS = 1

    /** Bytes 2-3: glucose value as u16 LE (mmol/L x 100) */
    const val OFFSET_GLUCOSE_U16LE = 2

    /** Bytes 4-5: sequence number as u16 LE (integrity check) */
    const val OFFSET_SEQ_CHECK_U16LE = 4

    /** Bytes 9-10: processed electrochemical current (obfuscated u16 LE) */
    const val OFFSET_CURRENT_U16LE = 9

    /** Bytes 11-12: skin temperature as u16 LE / 10.0 */
    const val OFFSET_TEMPERATURE_U16LE = 11

    // ---- Device Name Patterns ----

    /** Known device name prefixes for iCanHealth sensors */
    val KNOWN_PREFIXES = arrayOf("iCGM-", "Sinocare CGM", "Sinocare ", "P", "LT")

    /** Check if a BLE device name matches an iCanHealth sensor */
    @JvmStatic
    fun isICanHealthDevice(name: String?): Boolean {
        if (name == null) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (KNOWN_PREFIXES.any { trimmed.startsWith(it) }) return true
        return isLikelyPersistedSensorName(trimmed)
    }

    @JvmStatic
    fun isLikelyPersistedSensorName(name: String?): Boolean {
        if (name == null) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (isProvisionalSensorId(trimmed)) return true
        return when {
            SERIAL_P_FORM.matches(trimmed) -> true
            SERIAL_LT_FORM.matches(trimmed) -> true
            SERIAL_ALNUM_12_32.matches(trimmed) -> true
            else -> false
        }
    }

    @JvmStatic
    fun canonicalSensorId(sensorId: String?): String {
        val trimmed = sensorId?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return if (SERIAL_ALNUM_16_32.matches(trimmed)) {
            trimmed.uppercase().take(MAX_NATIVE_SENSOR_ID_CHARS)
        } else {
            trimmed
        }
    }

    @JvmStatic
    fun nativeShortSensorAlias(sensorId: String?): String? {
        val canonical = canonicalSensorId(sensorId)
        if (canonical.isEmpty() || isProvisionalSensorId(canonical)) {
            return null
        }
        return if (FULL_CANONICAL_HEX_SENSOR_ID_REGEX.matches(canonical)) {
            canonical.takeLast(11)
        } else {
            null
        }
    }

    @JvmStatic
    fun nativeLookupSensorAlias(sensorId: String?): String? = nativeShortSensorAlias(sensorId)

    @JvmStatic
    fun legacyBrokenNativeAlias(sensorId: String?): String? {
        val nativeAlias = nativeShortSensorAlias(sensorId) ?: return null
        return nativeAlias.takeIf { it.length > 5 }?.substring(5)
    }

    @JvmStatic
    fun matchesCanonicalOrKnownNativeAlias(sensorId: String?, candidateId: String?): Boolean {
        val canonical = canonicalSensorId(sensorId)
        val candidate = canonicalSensorId(candidateId)
        if (canonical.isEmpty() || candidate.isEmpty()) {
            return false
        }
        if (canonical.equals(candidate, ignoreCase = true)) {
            return true
        }
        val nativeAlias = nativeLookupSensorAlias(canonical)
        if (nativeAlias != null && nativeAlias.equals(candidate, ignoreCase = true)) {
            return true
        }
        val brokenAlias = legacyBrokenNativeAlias(canonical)
        return brokenAlias != null && brokenAlias.equals(candidate, ignoreCase = true)
    }

    @JvmStatic
    fun isProvisionalSensorId(name: String?): Boolean {
        if (name == null) return false
        return name.startsWith(PROVISIONAL_SENSOR_PREFIX, ignoreCase = true) ||
            name.startsWith(LEGACY_PROVISIONAL_SENSOR_PREFIX, ignoreCase = true)
    }

    @JvmStatic
    fun deriveInitialSensorId(deviceName: String?, address: String): String {
        return deriveInitialSensorId(deviceName, address, null)
    }

    @JvmStatic
    fun deriveInitialSensorId(deviceName: String?, address: String?, onboardingDeviceSn: String?): String {
        val trimmedName = deviceName?.trim().orEmpty()
        if (isLikelyPersistedSensorName(trimmedName)) {
            return canonicalSensorId(trimmedName)
        }
        val normalizedOnboardingDeviceSn = normalizeOnboardingDeviceSn(onboardingDeviceSn)
        if (normalizedOnboardingDeviceSn.isNotEmpty()) {
            val provisionalSuffix = normalizedOnboardingDeviceSn
                .take(MAX_NATIVE_SENSOR_ID_CHARS - PROVISIONAL_SENSOR_PREFIX.length)
            return PROVISIONAL_SENSOR_PREFIX + provisionalSuffix
        }
        val sanitizedAddress = address
            ?.trim()
            ?.uppercase()
            ?.replace(":", "")
            .orEmpty()
        if (sanitizedAddress.isNotEmpty()) {
            return PROVISIONAL_SENSOR_PREFIX + sanitizedAddress
        }
        val fallbackSuffix = trimmedName
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .take(MAX_NATIVE_SENSOR_ID_CHARS - PROVISIONAL_SENSOR_PREFIX.length)
            .ifEmpty { "PENDING" }
        return PROVISIONAL_SENSOR_PREFIX + fallbackSuffix
    }

    @JvmStatic
    fun normalizePersistedSensorId(sensorId: String?, address: String?, displayName: String?): String {
        val trimmedId = canonicalSensorId(sensorId)
        val trimmedAddress = address?.trim().orEmpty()
        if (trimmedId.isEmpty()) {
            return deriveInitialSensorId(displayName, trimmedAddress)
        }
        if (trimmedAddress.isEmpty()) {
            return trimmedId
        }
        if (isProvisionalSensorId(trimmedId)) {
            return deriveInitialSensorId(displayName, trimmedAddress)
        }
        return if (isLikelyPersistedSensorName(trimmedId)) {
            trimmedId
        } else {
            deriveInitialSensorId(displayName, trimmedAddress)
        }
    }

    @JvmStatic
    fun usesDirectSnHistoryGlucoseEncoding(onboardingDeviceSn: String?): Boolean {
        val normalized = normalizeOnboardingDeviceSn(onboardingDeviceSn)
        if (normalized.length < 12) return false
        val uppercase = normalized.uppercase(Locale.US)
        val usesExtendedPrefix = uppercase.length > 12 && (uppercase[0] > 'F' || uppercase[1] > 'F')
        val selectorStart = if (usesExtendedPrefix) 10 else 9
        val selectorEnd = if (usesExtendedPrefix) 12 else 11
        if (selectorEnd > uppercase.length) return false
        val selector = uppercase.substring(selectorStart, selectorEnd)
        return selector.toIntOrNull() == 0
    }

    // ---- SharedPreferences Keys ----

    /** Preference key prefix for per-sensor AES key storage */
    const val PREF_AES_KEY_PREFIX = "icanhealth_aes_key_"

    /** Preference key for global (fallback) AES key */
    const val PREF_AES_KEY_GLOBAL = "icanhealth_aes_key_global"

    /** SharedPreferences key set for persisted iCanHealth sensors */
    const val PREF_SENSORS_KEY = "icanhealth_sensors"

    /** Preference key prefix for a user-entered onboarding device SN / active-code-derived SN */
    const val PREF_DEVICE_SN_PREFIX = "icanhealth_device_sn_"

    /** Preference key prefix for an explicit per-sensor auth userId / account id override */
    const val PREF_AUTH_USER_ID_PREFIX = "icanhealth_auth_user_id_"

    /** Preference key for a global auth userId / account id fallback */
    const val PREF_AUTH_USER_ID_GLOBAL = "icanhealth_auth_user_id_global"

    /** Preference key prefix for auto-recovered userId from F4 rejection (per-sensor) */
    const val PREF_RECOVERED_USER_ID_PREFIX = "icanhealth_recovered_user_id_"

    /** Legacy preference key prefix for "skip unknown vendor auth" caching */
    const val PREF_AUTH_BYPASS_UNTIL_PREFIX = "icanhealth_auth_bypass_until_"

    /** Preference key prefix for the latest sequence edge the iCan driver knows is already materialized */
    const val PREF_HISTORY_EDGE_SEQUENCE_PREFIX = "icanhealth_history_edge_sequence_"

    /** Preference key prefix for the timestamp of the latest materialized iCan sequence edge */
    const val PREF_HISTORY_EDGE_TIMESTAMP_PREFIX = "icanhealth_history_edge_timestamp_"

    /**
     * Repair generation for history written against a timezone-skewed session start.
     *
     * Builds before this generation parsed the 0x2AAA session start as UTC even when the sensor
     * had sent a local wall clock, so any backfilled record older than the device's UTC offset was
     * stored one whole offset late — a ghost copy of the curve interleaved with the real one.
     * Bump this to schedule another one-time repull-and-replace for every iCan sensor.
     */
    const val HISTORY_TIMEZONE_REPAIR_GENERATION = 1

    /** Preference key prefix for the repair generation already applied to a given iCan sensor */
    const val PREF_HISTORY_TZ_REPAIR_PREFIX = "icanhealth_history_tz_repair_"
}
