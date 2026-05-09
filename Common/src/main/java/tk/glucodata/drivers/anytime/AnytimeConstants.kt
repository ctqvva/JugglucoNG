// JugglucoNG — Anytime / Yuwell 4 / 4H (CT3) CGM Driver
// AnytimeConstants.kt
//
// Reverse-engineered from com.anytime.rus / com.kamin.cgmblelib + ist.com.sdk
// (see docs/ for full RE notes). Two co-existing BLE topologies:
//
//   • CT2 family (legacy, Telink chip) — 16-bit UUIDs 0xFFF0/FFF1/FFF2
//   • CT2.5 / CT3 / CT3_PLUS / CT3_YUWELL / CT3_ULTRASONIC / CT4 / CT5 —
//     proprietary 128-bit UUID block 00001000-1212-efde-1523-785feabcd123
//
// Frame format: no header, no preamble. Some opcodes carry a trailing sum
// byte; CT3-family commands are typically 1–7 bytes with no sum at all.
// CRC: never used. Integrity is "byte 0 == known opcode + structured length".

package tk.glucodata.drivers.anytime

import java.util.Locale
import java.util.UUID

object AnytimeConstants {

    // ---- Logging tag ----

    const val TAG = "Anytime"
    const val DEFAULT_DISPLAY_NAME = "Anytime CGM"
    const val PROVISIONAL_SENSOR_PREFIX = "ANY-"
    const val MAX_NATIVE_SENSOR_ID_CHARS = 16

    // ---- BLE UUIDs ----
    //
    // CT3 family advertises the proprietary 128-bit service.
    // CT2 (oldest, Telink BLE chip) uses standard 16-bit FFF0.

    val SERVICE_PRIMARY: UUID = UUID.fromString("00001000-1212-efde-1523-785feabcd123")
    val CHAR_NOTIFY_PRIMARY: UUID = UUID.fromString("00001001-1212-efde-1523-785feabcd123")
    val CHAR_WRITE_PRIMARY: UUID = UUID.fromString("00001002-1212-efde-1523-785feabcd123")

    val SERVICE_LEGACY_CT2: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_LEGACY: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE_LEGACY: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

    /** Standard CCCD descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Default MTU the official Anytime app negotiates. */
    const val DEFAULT_MTU = 211

    // ---- CT3 opcode catalog (TX → sensor) ----
    //
    // All bytes shown LSB-first.

    /** Read transmitter firmware version. Body: {0x01}. */
    const val TX_VERSION: Byte = 0x01

    /** Sync clock. Body: {0x03, year-1900, mon+1, day, hour, min, sec}. */
    const val TX_SET_DATE: Byte = 0x03

    /** Battery / temperature / IW health check. Body: {0x05}. */
    const val TX_CHECK: Byte = 0x05

    /** Init handshake (after setDate). Body: {0x06}. */
    const val TX_INIT: Byte = 0x06

    /** Pull next glucose record by id. Body: {0x08, idLo, idHi}. */
    const val TX_PULL_GLUCOSE: Byte = 0x08

    /** Upload fingerstick reference BG. Body: {0x09, mmolInt, mmolFrac/10}. */
    const val TX_INPUT_BG_MG: Byte = 0x09

    /** Unbind sensor. Body: {0x0A}. */
    const val TX_UNBIND: Byte = 0x0A

    /** Push factory K/R to transmitter. Body: {0x0B, KInt, KFrac/10, RInt, RFrac/10}. */
    const val TX_INPUT_KR: Byte = 0x0B

    /** Re-fetch one record as transmitter-computed. Body: {0x0C, idLo, idHi}. */
    const val TX_FETCH_COMPUTED_GLUCOSE: Byte = 0x0C

    /** Switch transmitter to low-power. Body: {0x0F}. */
    const val TX_LOW_POWER: Byte = 0x0F

    /** Reset session. Body: {0x11}. */
    const val TX_RESET: Byte = 0x11

    /** Voltage switch (CT3_PLUS / CT3_YUWELL / CT3_ULTRASONIC / CT4). 12 bytes. */
    const val TX_MODIFY_VOLTAGE: Byte = 0x15

    /** Formal version request (CT3_x / CT4 — selects voltage path). */
    const val TX_TRANSMITTER_FORMAL: Byte = 0x20

    // ---- Sensor → phone notification opcodes (RX) ----

    const val RX_VERSION: Byte = 0x01

    /** Two echo opcodes the firmware uses for setDate ack. */
    const val RX_SET_DATE_ACK_A: Byte = 0x03
    const val RX_SET_DATE_ACK_B: Byte = 0x04

    /** Check response — battery, sensor age, IW. ≥11 bytes. */
    const val RX_CHECK: Byte = 0x05

    /** Init ack. Body: {0x06}. */
    const val RX_INIT: Byte = 0x06

    /** TX-pushed glucose. 9-byte records (raw current + temp). */
    const val RX_PUSH_GLUCOSE: Byte = 0x07

    /** Pull response. Same 9-byte raw format as RX_PUSH_GLUCOSE. */
    const val RX_PULL_GLUCOSE: Byte = 0x08

    /** Input-BG ack. */
    const val RX_INPUT_BG_ACK: Byte = 0x09

    /** Unbind ack. */
    const val RX_UNBIND_ACK: Byte = 0x0A

    /** K/R upload ack. */
    const val RX_INPUT_KR_ACK: Byte = 0x0B

    /** Transmitter-computed full record (19 bytes). CT5 push or CT3 on demand. */
    const val RX_COMPUTED_GLUCOSE: Byte = 0x0C

    /** Low-power ack. */
    const val RX_LOW_POWER_ACK: Byte = 0x0F

    /** Reset response — bytes[2] != 0 ⇒ device was bound. */
    const val RX_RESET: Byte = 0x11

    /** Voltage-switch ack — bytes[1] is the voltage echo. */
    const val RX_MODIFY_VOLTAGE: Byte = 0x15

    /** Formal version response (≥6 bytes — version string starts at byte 1). */
    const val RX_TRANSMITTER_FORMAL: Byte = 0x20

    /** Series response (CT5 push). */
    const val RX_SERIES: Byte = 0x22

    // ---- 9-byte raw-current record offsets (RX_PUSH_GLUCOSE / RX_PULL_GLUCOSE) ----

    const val RAW_RECORD_SIZE = 9
    const val RAW_OFFSET_OPCODE = 0
    const val RAW_OFFSET_ID_LO = 1
    const val RAW_OFFSET_ID_HI = 2
    const val RAW_OFFSET_IB_INT = 3
    const val RAW_OFFSET_IB_FRAC = 4
    const val RAW_OFFSET_IW_INT = 5
    const val RAW_OFFSET_IW_FRAC = 6
    const val RAW_OFFSET_T_INT_PLUS_40 = 7
    const val RAW_OFFSET_T_FRAC = 8

    // Continuation records inside one notification skip the leading opcode byte
    // (the parser sees N records of 8 bytes after the initial 9-byte header).
    const val RAW_RECORD_CONTINUATION_SIZE = 8

    // ---- 19-byte computed-glucose record offsets (RX_COMPUTED_GLUCOSE) ----

    const val COMPUTED_RECORD_SIZE = 19
    const val COMPUTED_OFFSET_OPCODE = 0
    const val COMPUTED_OFFSET_ID_LO = 1
    const val COMPUTED_OFFSET_ID_HI = 2
    const val COMPUTED_OFFSET_HYPO_MIN = 3
    const val COMPUTED_OFFSET_HYPER_MIN = 4
    const val COMPUTED_OFFSET_IB_INT = 5
    const val COMPUTED_OFFSET_IB_FRAC = 6
    const val COMPUTED_OFFSET_IW_INT = 7
    const val COMPUTED_OFFSET_IW_FRAC = 8
    const val COMPUTED_OFFSET_T_INT_PLUS_40 = 9
    const val COMPUTED_OFFSET_T_FRAC = 10
    const val COMPUTED_OFFSET_GLU_INT = 11
    const val COMPUTED_OFFSET_GLU_FRAC = 12
    const val COMPUTED_OFFSET_BG_INT = 13
    const val COMPUTED_OFFSET_BG_FRAC = 14
    const val COMPUTED_OFFSET_ERROR = 15
    const val COMPUTED_OFFSET_TREND = 16
    const val COMPUTED_OFFSET_WARN = 17
    const val COMPUTED_OFFSET_PADDING = 18

    /** Temperature-encoding offset (sensor adds 40 to the integer part). */
    const val TEMP_INT_OFFSET = 40

    // ---- Battery / health thresholds ----

    /** Volts. Below this, CT3 reports "low power" and refuses to bind. */
    const val BATTERY_LOW_VOLTS_CT3 = 4.05f
    const val BATTERY_LOW_VOLTS_CT3_A = 3.9f
    const val BATTERY_LOW_VOLTS_CT2_5 = 2.97f

    /** Battery rated voltage (full transmitter). */
    const val BATTERY_FULL_VOLTS = 4.2f

    /** End-of-life warning: percentage threshold under which battery is "low". */
    const val BATTERY_WARN_PERCENT = 30

    // ---- Lifecycle / cadence defaults (overridden per family in AnytimeProfile) ----

    /** Sensor rated lifetime. CT3 is 14, 15 or 16 days depending on chemistry. */
    const val DEFAULT_RATED_LIFETIME_DAYS = 16

    /** Reading cadence — CT3 transmitter pushes ~every 1 minute via opcode 0x07. */
    const val DEFAULT_READING_INTERVAL_MINUTES = 1

    /** Warmup window before reliable readings. */
    const val DEFAULT_WARMUP_MINUTES = 60

    /** Watchdog — official app's pullDataDelay is 190 s. */
    const val PULL_WATCHDOG_SECONDS = 190L

    // ---- Algorithm fallback (linear K/R when libalgorithm-jni.so unavailable) ----

    /** Final output clamp: mg/dL × 10 (matches official UI 17–400 mg/dL). */
    const val ALGO_MGDL_MIN_TIMES10 = 17
    const val ALGO_MGDL_MAX_TIMES10 = 4000

    /** Mmol/L floor — official app's lower display bound. */
    const val ALGO_MMOL_FLOOR = 2.2

    // ---- Device family enum (`EDevice` equivalent) ----

    /**
     * Sensor family. Determines:
     *   - which BLE service variant (legacy 0xFFF0 vs proprietary 128-bit)
     *   - voltage-switching needed at first pair
     *   - which `algorithm` int to feed the JNI
     *   - rated lifetime (some sub-prefixes are 8 days, most are 14–16)
     *
     * Names mirror `EGattMessage` from the SDK.
     */
    enum class Family(val displayName: String, val needsLegacyUuids: Boolean) {
        CT2("CT2", true),
        CT2_5("CT2.5", false),
        CT3("CT3", false),
        CT3_PLUS("CT3-Plus", false),
        CT3_YUWELL("CT3-Yuwell", false),
        CT3_ULTRASONIC("CT3-Ultrasonic", false),
        CT4("CT4", false),
        CT5("CT5", false),
        UNKNOWN("Unknown", false),
    }

    /**
     * Per-prefix descriptor. `algorithm` is the int the JNI uses to dispatch into
     * the correct chemistry-specific pipeline inside libalgorithm-jni.so.
     * `endNumber` is the maximum 5-min-tick packet count before session ends.
     */
    data class FamilyEntry(
        val prefix: String,
        val family: Family,
        val algorithm: Int,
        val endNumber: Int,
    ) {
        val ratedLifetimeDays: Int
            get() = endNumber / 12 / 24 // 12 readings/hour × 24 hours

        val matchUpper: String get() = prefix.uppercase(Locale.US)
    }

    val FAMILY_TABLE: List<FamilyEntry> = listOf(
        // ---- CT2 (legacy 0xFFF0 chip) ----
        FamilyEntry("SN04", Family.CT2, 8, 6740),
        FamilyEntry("SN06", Family.CT2, 6, 3420),
        FamilyEntry("SN08", Family.CT2, 1, 6720),
        FamilyEntry("SN12", Family.CT2, 1, 4800),
        FamilyEntry("SN18", Family.CT2, 7, 3380),
        FamilyEntry("SN20", Family.CT2, 7, 6740),
        FamilyEntry("SN22", Family.CT2, 1, 4800),
        FamilyEntry("SN48", Family.CT2, 7, 6740),
        FamilyEntry("SN50", Family.CT2, 7, 6740),
        FamilyEntry("SN52", Family.CT2, 7, 6740),
        // ---- CT2.5 ----
        FamilyEntry("SN30", Family.CT2_5, 7, 6740),
        FamilyEntry("SN32", Family.CT2_5, 7, 3380),
        FamilyEntry("SN36", Family.CT2_5, 7, 6740),
        FamilyEntry("SN38", Family.CT2_5, 7, 6740),
        FamilyEntry("SN40", Family.CT2_5, 7, 6740),
        // ---- CT3 (target — 4/4H is SN16) ----
        FamilyEntry("SN16", Family.CT3, 3, 6740),
        // ---- CT3 Yuwell ----
        FamilyEntry("SN26", Family.CT3_YUWELL, 3, 6740),
        FamilyEntry("SN28", Family.CT3_YUWELL, 3, 3380),
        FamilyEntry("SN42", Family.CT3_YUWELL, 3, 6740),
        FamilyEntry("SN46", Family.CT3_YUWELL, 3, 6740),
        // ---- CT3 Plus ----
        FamilyEntry("SN56", Family.CT3_PLUS, 3, 6740),
        FamilyEntry("SN58", Family.CT3_PLUS, 3, 3380),
        FamilyEntry("SN60", Family.CT3_PLUS, 3, 6740),
        FamilyEntry("SN62", Family.CT3_PLUS, 3, 6740),
        FamilyEntry("SN66", Family.CT3_PLUS, 3, 6740),
        FamilyEntry("SN68", Family.CT3_PLUS, 3, 6740),
        FamilyEntry("SN70", Family.CT3_PLUS, 12, 6740),
        // ---- CT3 Ultrasonic ----
        FamilyEntry("SN17", Family.CT3_ULTRASONIC, 9, 6740),
        FamilyEntry("SN27", Family.CT3_ULTRASONIC, 12, 6740),
        FamilyEntry("SN29", Family.CT3_ULTRASONIC, 12, 3380),
        FamilyEntry("SN43", Family.CT3_ULTRASONIC, 9, 6740),
        FamilyEntry("SN47", Family.CT3_ULTRASONIC, 9, 6740),
        FamilyEntry("SN96", Family.CT3_ULTRASONIC, 12, 6740),
        FamilyEntry("SN98", Family.CT3_ULTRASONIC, 12, 6740),
        // ---- CT4 (voltage-switching) ----
        FamilyEntry("SN72", Family.CT4, 10, 7220),
        FamilyEntry("SN76", Family.CT4, 10, 7220),
        FamilyEntry("SN78", Family.CT4, 10, 7220),
        FamilyEntry("SN80", Family.CT4, 10, 7220),
        FamilyEntry("SN82", Family.CT4, 10, 3860),
        FamilyEntry("SN86", Family.CT4, 10, 3860),
        FamilyEntry("SN87", Family.CT4, 10, 7220),
        FamilyEntry("SN88", Family.CT4, 10, 7220),
        FamilyEntry("SN90", Family.CT4, 10, 4820),
        // ---- CT5 (no separate transmitter — placeholder) ----
        FamilyEntry("Anytime", Family.CT5, 11, 7695),
    )

    /** Default fallback when the advertised name doesn't match any known prefix. */
    val FAMILY_UNKNOWN: FamilyEntry = FamilyEntry("UNKNOWN", Family.UNKNOWN, 3, 6740)

    /** All accepted device-name prefixes (case-insensitive). */
    val KNOWN_PREFIXES: List<String> = FAMILY_TABLE.map { it.prefix }

    @JvmStatic
    fun resolveFamily(deviceName: String?): FamilyEntry {
        val trimmed = deviceName?.trim().orEmpty()
        if (trimmed.length < 4) return FAMILY_UNKNOWN
        val upper = trimmed.uppercase(Locale.US)
        // Most prefixes are 4 chars (SN##); CT5 is "Anytime" (7 chars).
        FAMILY_TABLE.firstOrNull { entry -> upper.startsWith(entry.matchUpper) }
            ?.let { return it }
        return FAMILY_UNKNOWN
    }

    @JvmStatic
    fun isAnytimeDevice(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return resolveFamily(trimmed).family != Family.UNKNOWN ||
            isLikelyPersistedSensorName(trimmed)
    }

    @JvmStatic
    fun isLikelyPersistedSensorName(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (isProvisionalSensorId(trimmed)) return true
        return Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE).matches(trimmed) ||
            Regex("^[0-9A-F]{12,16}$", RegexOption.IGNORE_CASE).matches(trimmed)
    }

    @JvmStatic
    fun isProvisionalSensorId(name: String?): Boolean =
        name?.trim()?.startsWith(PROVISIONAL_SENSOR_PREFIX, ignoreCase = true) == true

    /**
     * Canonicalise a sensor identity. BLE MACs collapse to colon-free uppercase;
     * advertised names persist as-is (taken into the registry verbatim).
     */
    @JvmStatic
    fun canonicalSensorId(sensorId: String?): String {
        val trimmed = sensorId?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        if (Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            return trimmed.uppercase(Locale.US).replace(":", "")
        }
        if (Regex("^[0-9A-F]{12,16}$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            return trimmed.uppercase(Locale.US).take(MAX_NATIVE_SENSOR_ID_CHARS)
        }
        return trimmed
    }

    @JvmStatic
    fun macAddressFromSensorId(sensorId: String?): String? {
        val canonical = canonicalSensorId(sensorId)
        if (!Regex("^[0-9A-F]{12}$", RegexOption.IGNORE_CASE).matches(canonical)) return null
        return canonical.chunked(2).joinToString(":")
    }

    @JvmStatic
    fun matchesCanonicalOrKnownNativeAlias(a: String?, b: String?): Boolean {
        val ca = canonicalSensorId(a)
        val cb = canonicalSensorId(b)
        if (ca.isEmpty() || cb.isEmpty()) return false
        return ca.equals(cb, ignoreCase = true)
    }

    @JvmStatic
    fun deriveInitialSensorId(deviceName: String?, address: String?): String {
        val addr = canonicalSensorId(address)
        if (addr.isNotEmpty() && Regex("^[0-9A-F]{12,16}$").matches(addr)) return addr
        val fallback = deviceName?.trim().orEmpty()
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .take(MAX_NATIVE_SENSOR_ID_CHARS - PROVISIONAL_SENSOR_PREFIX.length)
            .ifEmpty { "PENDING" }
        return PROVISIONAL_SENSOR_PREFIX + fallback
    }

    // ---- SharedPreferences key prefixes ----

    const val PREF_SENSORS_KEY = "anytime_sensors"
    const val PREF_FAMILY_PREFIX = "anytime_family_"
    const val PREF_QR_CONTENT_PREFIX = "anytime_qr_"
    const val PREF_K_PREFIX = "anytime_k_"
    const val PREF_R_PREFIX = "anytime_r_"
    const val PREF_LIFETIME_PREFIX = "anytime_lifetime_"
    const val PREF_LAST_GLUCOSE_ID_PREFIX = "anytime_last_id_"
    const val PREF_SENSOR_START_AT_PREFIX = "anytime_started_at_"
    const val PREF_WARMUP_STARTED_AT_PREFIX = "anytime_warmup_at_"
    const val PREF_VOLTAGE_PREFIX = "anytime_voltage_"
    const val PREF_DEVICE_NAME_PREFIX = "anytime_device_name_"
    const val PREF_TRANSMITTER_VERSION_PREFIX = "anytime_tx_version_"
    const val PREF_BOUND_PREFIX = "anytime_bound_"
}
