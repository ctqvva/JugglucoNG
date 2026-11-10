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
    // Precompiled once. These were built inline on every call, and because
    // SensorIdentity.matches -> managedMatches asks every driver adapter to
    // canonicalise both ids, a single identity comparison compiled a fresh
    // java.util.regex.Pattern (ICU native) per adapter per side. A stuck-main-thread
    // stack dump landed in Pattern.compile beneath canonicalSensorId, and that path
    // runs per reading, per sensor, per UI snapshot and per notification build.
    private val MAC_WITH_COLONS: Regex = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE)
    private val PLAIN_HEX_12_16: Regex = Regex("^[0-9A-F]{12,16}$", RegexOption.IGNORE_CASE)
    private val PLAIN_HEX_12: Regex = Regex("^[0-9A-F]{12}$", RegexOption.IGNORE_CASE)
    private val PLAIN_HEX_12_16_CASE_SENSITIVE: Regex = Regex("^[0-9A-F]{12,16}$")

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

    /** Pull a counted raw-history series. Body: {0x22, idLo, idHi, count, sum}. */
    const val TX_PULL_GLUCOSE_SERIES: Byte = 0x22

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

    /** CT5 identity challenge. Body: {0x30, randomB[4], convolve(randomB, randomA)[4], sum}. */
    const val TX_CT5_SET_ID: Byte = 0x30

    /** CT5 reconnect identity check. Body: {0x31, randomB[4], sum}. */
    const val TX_CT5_CHECK_ID: Byte = 0x31

    /** CT5 push ACK. Body: {0x35, 0x55, 0xAA, sum}. */
    const val TX_CT5_PUSH_ACK: Byte = 0x35

    /** CT5 series-history pull. Body: {0x37, idLo, idHi, count, sum}. */
    const val TX_CT5_PULL_SERIES: Byte = 0x37

    /** CT5 encrypted K/R and temporary ID setup. */
    const val TX_CT5_SET_PARAMETERS: Byte = 0x38

    /** CT5 encrypted QR/KR query. Body: {0x3F, 0x55, 0xAA, sum}. */
    const val TX_CT5_QUERY_SSN: Byte = 0x3F

    /** CT5 end-cycle/unbind request. Body: {0x0A, temporaryId[4], sum}. */
    const val TX_CT5_END_CYCLE: Byte = TX_UNBIND

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

    /** Ack for the SDK's family-less `unBindRequest()` — `{0x58, 0x55, 0xAA, 0x57}`. */
    const val RX_UNBIND_ACK_GENERIC: Byte = 0x58

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

    /** Counted CT2.5/CT3A/CT4 raw-history series response. */
    const val RX_SERIES: Byte = 0x22

    /** Legacy unsolicited CT2.5/CT3A/CT4 raw-history dump, same payload layout as RX_SERIES. */
    const val RX_LEGACY_RAW_DUMP: Byte = 0x0D

    /** CT5 reconnect identity check response. */
    const val RX_CT5_CHECK_ID: Byte = 0x31

    /** CT5 transmitter-computed live push. */
    const val RX_CT5_PUSH_GLUCOSE: Byte = 0x35

    /** CT5 encrypted history series response. */
    const val RX_CT5_SERIES: Byte = 0x37

    /** CT5 encrypted K/R setup response. */
    const val RX_CT5_SET_PARAMETERS: Byte = 0x38

    /** CT5 encrypted QR/KR response. */
    const val RX_CT5_QUERY_SSN: Byte = 0x3F

    // ---- CT2 opcode catalog — ASCII-letter opcodes, distinct numbering from CT3/CT2.5 ----
    // Confirmed live on SN08402458, 2026-09-07 (see docs/ct-driver-plan.md §B0-B1).

    /** Transmitter version request. Body: {0x03} (not an ASCII letter). */
    const val TX_CT2_VERSION: Byte = 0x03

    /** Handshake with the device's own advertised name. Body: {0x48, ASCII(name), sum}. */
    const val TX_CT2_HANDSHAKE: Byte = 0x48

    /** Sync clock. Body: {0x54, yearHi, yearLo, month, day, hour, min, sec, sum}. */
    const val TX_CT2_SET_DATE: Byte = 0x54

    /** Init session. Body: {0x53, 0x55, 0xAA, sum}. */
    const val TX_CT2_INIT: Byte = 0x53

    /** Self-test / check. Body: {0x43, 0x55, 0xAA, sum}. */
    const val TX_CT2_CHECK: Byte = 0x43

    /** Low power. Body: {0x57, 0x55, 0xAA, sum}. */
    const val TX_CT2_LOW_POWER: Byte = 0x57

    /** Unbind. Body: {0x58, 0x55, 0xAA, sum}. */
    const val TX_CT2_UNBIND: Byte = 0x58

    /** Pull one record by id. Body: {0x55, idHi, idLo, sum}. */
    const val TX_CT2_PULL_GLUCOSE: Byte = 0x55

    /** Fingerstick reference BG (mg/dL big-endian). Body: {0x08, mgdlHi, mgdlLo, sum}. */
    const val TX_CT2_INPUT_BG_MG: Byte = 0x08

    /**
     * Glucose computed by the transmitter. Body: `{0x09, idHi, idLo}`.
     *
     * Not used: on the tested CT-14 firmware the answer is the constant
     * `09 00 00 03 36` for any id (captures at ids 4310 and 4319), which does not
     * fit the SDK's `GluMM = bArr[1]/10` layout and is not a reading source. Kept
     * as protocol documentation; re-test before using it.
     */
    const val TX_CT2_GLUCOSE_BY_TRANSMITTER: Byte = 0x09

    /** Handshake ACK — fixed frame {0x48, 0x55, 0xAA, sum}. */
    const val RX_CT2_HANDSHAKE_ACK: Byte = 0x48

    /** setDate ACK — fixed frame {0x54, 0x55, 0xAA, sum}. */
    const val RX_CT2_SET_DATE_ACK: Byte = 0x54

    /** Init ACK (echoes the init opcode). */
    const val RX_CT2_INIT_ACK: Byte = 0x53

    /** Self-test / check response (8 bytes: Iw, Ib, T, powerByte, sum). */
    const val RX_CT2_CHECK: Byte = 0x43

    /** Live glucose push — 15-byte record, battery byte carries real charge. */
    const val RX_CT2_PUSH_GLUCOSE: Byte = 0x44

    /**
     * Pull response — same 15-byte layout as RX_CT2_PUSH_GLUCOSE but byte 13 is
     * always 0xFF (no live battery). Confirmed live: NOT derivable from
     * TX_CT2_PULL_GLUCOSE (0x55) arithmetically; hard-coded, never computed.
     */
    const val RX_CT2_PULL_RESPONSE: Byte = 0x47

    // ---- 9-byte / 11-byte raw-current records (RX_PUSH_GLUCOSE / RX_PULL_GLUCOSE) ----

    const val RAW_RECORD_SIZE = 9
    const val WIDE_RAW_RECORD_SIZE = 11
    const val WIDE_RAW_SERIES_CHUNK_SIZE = 6
    const val CT5_RAW_CHUNK_SIZE = 11
    const val CT5_VOLTAGE_CHUNK_SIZE = 15
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
    const val BATTERY_LOW_VOLTS_CT4 = 2.97f

    /** Battery rated voltage (full transmitter). */
    const val BATTERY_FULL_VOLTS = 4.2f

    /** End-of-life warning: percentage threshold under which battery is "low". */
    const val BATTERY_WARN_PERCENT = 30

    /**
     * CT2 low-battery threshold, percent (not volts). The CT2 check response
     * byte 6 is a power byte that scales like a percentage (observed 96-100),
     * not a voltage; no discharged-sensor capture exists yet, so 20 is a
     * placeholder to revisit with live low-battery data.
     */
    const val BATTERY_LOW_PERCENT_CT2 = 20

    // ---- Lifecycle / cadence defaults (overridden per family in AnytimeProfile) ----

    /** Sensor rated lifetime. CT3 is 14, 15 or 16 days depending on chemistry. */
    const val DEFAULT_RATED_LIFETIME_DAYS = 16

    /** Reading cadence — CT3 records one glucose point every 3 minutes. */
    const val DEFAULT_READING_INTERVAL_MINUTES = 3

    /** Warmup window before reliable readings. */
    const val DEFAULT_WARMUP_MINUTES = 60

    /**
     * CT5 warm-up. Hardware evidence (fresh activation trace 2026-08-17): live
     * records start at glucoseId=0 and carry valid telemetry but no glucose;
     * the first non-zero glucose arrived at id=14, i.e. 14 x 3 min = 42 min.
     * 45 minutes is the vendor-rounded window that covers it.
     */
    const val CT5_WARMUP_MINUTES = 40

    /**
     * K/R sent in CT5 setParameters when neither the transmitter SSN nor a scanned
     * code yields a calibration. Both SSNs seen so far decode to R=1.0 with
     * K=1.09 and K=1.15; the transmitter computes Auto glucose from these, so a
     * scanned code replaces them as soon as the user has one.
     */
    const val CT5_DEFAULT_K = 1.10f
    const val CT5_DEFAULT_R = 1.0f

    /**
     * Whether a sample falls inside the post-activation settling window and so must
     * not be published, stored or exported.
     *
     * Deliberately a property of the SAMPLE rather than of the wall clock (the same
     * shape as `OttaiConstants.isWithinWarmup`): a history replay of the same records
     * then reaches the same verdict on every pass and across restarts, instead of
     * re-admitting during warm-up whatever the live path already refused.
     *
     * [anchorMs] must be an authoritative activation instant — pass 0 to disable the
     * gate rather than feeding it a provisional start. The window is per family
     * ([AnytimeProfile.warmupMs]), so CT2 SN06/SN12 (180 min) and the 60-minute
     * families get their own horizon.
     */
    @JvmStatic
    fun isWithinWarmup(anchorMs: Long, sampleMs: Long, windowMs: Long): Boolean =
        anchorMs > 0L &&
            sampleMs > 0L &&
            windowMs > 0L &&
            sampleMs < anchorMs + windowMs

    /**
     * Whether a CT5 that entered streaming should be re-bound: the sensor has never
     * delivered a frame ([lastGlucoseId] < 0) and no 0x35/0x37 notification of any kind
     * ([lastDataRxAtMs], set before parsing) arrived since streaming began. Content is
     * irrelevant — a malformed or glucose-less push still proves a measuring
     * transmitter — and a sensor that has ever produced data is never re-bound, so
     * this cannot re-initialise a running session.
     */
    @JvmStatic
    fun ct5CachedCipherLooksUnbound(lastGlucoseId: Int, lastDataRxAtMs: Long, streamingSinceMs: Long): Boolean =
        lastGlucoseId < 0 && lastDataRxAtMs < streamingSinceMs

    /** Watchdog — official app's pullDataDelay is 190 s. */
    const val PULL_WATCHDOG_SECONDS = 190L

    // ---- Algorithm fallback (linear K/R when libalgorithm-jni.so unavailable) ----

    /** Final output clamp: mg/dL × 10. Native output has been observed below 3.0 mmol/L. */
    const val ALGO_MGDL_MIN_TIMES10 = 170
    const val ALGO_MGDL_MAX_TIMES10 = 4000

    /** Linear fallback Auto floor only; Raw remains unclamped and native values bypass this. */
    const val ALGO_MMOL_FLOOR = 2.2

    // ---- CT-14 empirical model (Apex/Blueberry reference, 2026-09-14) ----
    //
    // "CT-14" is the product (transmitter names SN04/SN08/SN20/SN48/SN50/SN52 in
    // the CT2 protocol family — see FAMILY_TABLE). Derived from the Blueberry app's
    // own CT-14 series: 3085 records with iw/ib/t and the model's rawGlucose/glucose.
    // `rawGlucose` fits `Iw + CT14_AGING_NA_PER_DAY * elapsedDays` with a 0.0002
    // residual, and `glucose = (raw - intercept) / slope`. With no user calibration
    // the app used slope/intercept below; `Ib` does not participate at all (rows
    // differing only in Ib gave identical rawGlucose).

    /** Sensor-aging drift of the raw current, nA per day since the session start. */
    const val CT14_AGING_NA_PER_DAY = 0.4f

    /** Default CT-14 calibration line, used until the user enters a fingerstick. */
    const val CT14_DEFAULT_SLOPE = 1.667f
    const val CT14_DEFAULT_INTERCEPT = 3.33f

    // ---- CT4 MK4 reference K0 (docs/MK4_FINAL_SUMMARY.md §2, §8) ----
    //
    // CT4 has no factory QR (like CT2), so `qr.k` is usually 0. The reference
    // chain needs a real K0; the MK4 logs pin it at 1.13 (K_BASE = 1.2 * 1.13 =
    // 1.356). Used only when no user/factory K is available.
    const val CT4_DEFAULT_K0 = 1.13f

    /**
     * Response opcodes the CT-14 (CT2 protocol family) owns, including its unbind
     * ack. Kept as an explicit set so this family is dispatched on its own and can
     * never fall through to the generic CT3/CT2.5 handlers: 0x08 and 0x09 mean
     * different things in the two namespaces, and 0x58 is CT2 unbind vs CT5 generic
     * unbind.
     */
    @JvmStatic
    fun isCt14Opcode(opcode: Byte): Boolean = when (opcode) {
        RX_CT2_HANDSHAKE_ACK,
        RX_CT2_SET_DATE_ACK,
        RX_CT2_INIT_ACK,
        RX_CT2_CHECK,
        RX_CT2_PUSH_GLUCOSE,
        RX_CT2_PULL_RESPONSE,
        RX_UNBIND_ACK_GENERIC -> true
        else -> false
    }

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
     * `endNumber` is the vendor's nominal record horizon (vendor tables include
     * a small initialization allowance). CT5 firmware may continue emitting
     * live ids beyond this value, so it is not a hard BLE/history boundary.
     */
    data class FamilyEntry(
        val prefix: String,
        val family: Family,
        val algorithm: Int,
        val endNumber: Int,
    ) {
        val ratedLifetimeDays: Int
            get() = endNumber / 20 / 24 // 20 readings/hour × 24 hours

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
        FamilyEntry("SN91", Family.CT3_ULTRASONIC, 10, 6740),
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
        // ---- CT5 (integrated transmitter/sensor, encrypted identity/data layer) ----
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

    /**
     * A known SN## identity beats the shared "Anytime" brand prefix, regardless of
     * source: SN91 was observed changing its BLE name to "Anytime  4pro" at the
     * same address on reconnect. Prefer connect-time names over GATT/cache/serial
     * within that specificity, and only select CT5 by brand when no SN## is known.
     * If none classify, keep the first non-blank name.
     */
    @JvmStatic
    fun resolveHandshakeName(
        cachedName: String?,
        connectedName: String?,
        activeName: String?,
        serial: String?,
    ): String {
        val trimmed = listOf(connectedName, activeName, cachedName, serial)
            .map { it?.trim().orEmpty() }
        return trimmed.firstOrNull {
            val family = resolveFamily(it).family
            family != Family.UNKNOWN && family != Family.CT5
        }
            ?: trimmed.firstOrNull { resolveFamily(it).family != Family.UNKNOWN }
            ?: trimmed.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    @JvmStatic
    fun isAnytimeDevice(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return resolveFamily(trimmed).family != Family.UNKNOWN ||
            isLikelyPersistedSensorName(trimmed)
    }

    /**
     * On-demand electrode-current self-test (`{0x43, 0x55, 0xAA, sum}`). Only the
     * CT2/CT-14 generation answers it; CT2.5 and later have no such request.
     */
    @JvmStatic
    fun supportsSelfTest(family: Family): Boolean = family == Family.CT2

    @JvmStatic
    fun isLikelyPersistedSensorName(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (isProvisionalSensorId(trimmed)) return true
        return MAC_WITH_COLONS.matches(trimmed) ||
            PLAIN_HEX_12_16.matches(trimmed)
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
        if (MAC_WITH_COLONS.matches(trimmed)) {
            return trimmed.uppercase(Locale.US).replace(":", "")
        }
        if (PLAIN_HEX_12_16.matches(trimmed)) {
            return trimmed.uppercase(Locale.US).take(MAX_NATIVE_SENSOR_ID_CHARS)
        }
        return trimmed
    }

    @JvmStatic
    fun macAddressFromSensorId(sensorId: String?): String? {
        val canonical = canonicalSensorId(sensorId)
        if (!PLAIN_HEX_12.matches(canonical)) return null
        return canonical.chunked(2).joinToString(":")
    }

    @JvmStatic
    fun matchesCanonicalOrKnownNativeAlias(a: String?, b: String?): Boolean {
        val ca = canonicalSensorId(a).uppercase(Locale.US)
        val cb = canonicalSensorId(b).uppercase(Locale.US)
        if (ca.isEmpty() || cb.isEmpty()) return false
        if (ca == cb) return true
        if (!ca.all { it in '0'..'9' || it in 'A'..'F' } ||
            !cb.all { it in '0'..'9' || it in 'A'..'F' }
        ) {
            return false
        }
        val shorter = if (ca.length < cb.length) ca else cb
        val longer = if (ca.length < cb.length) cb else ca
        return shorter.length >= 7 && longer.endsWith(shorter)
    }

    @JvmStatic
    fun deriveInitialSensorId(deviceName: String?, address: String?): String {
        val addr = canonicalSensorId(address)
        if (addr.isNotEmpty() && PLAIN_HEX_12_16_CASE_SENSITIVE.matches(addr)) return addr
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

    /**
     * Start of the glucose-id timeline: the wall-clock time id 0 would have had.
     *
     * Persisted separately from PREF_SENSOR_START_AT_PREFIX because the two answer
     * different questions. The sensor start is a display value the check frame can
     * also supply; this one records that a timeline anchor already exists, which is
     * what keeps a repeated live id from re-anchoring the session after a restart.
     */
    const val PREF_TIMELINE_START_AT_PREFIX = "anytime_timeline_at_"
    const val PREF_VOLTAGE_PREFIX = "anytime_voltage_"
    const val PREF_DEVICE_NAME_PREFIX = "anytime_device_name_"
    const val PREF_TRANSMITTER_VERSION_PREFIX = "anytime_tx_version_"
    const val PREF_BOUND_PREFIX = "anytime_bound_"
    const val PREF_REF_BG_MGDL_TIMES10_PREFIX = "anytime_ref_bg_x10_"
    const val PREF_REF_BG_GLUCOSE_ID_PREFIX = "anytime_ref_bg_id_"
    const val PREF_REF_BG_APPLIED_GLUCOSE_ID_PREFIX = "anytime_ref_bg_applied_id_"
    const val PREF_REF_BG_HISTORY_PREFIX = "anytime_ref_bg_history_"
    const val PREF_RAW_HISTORY_PREFIX = "anytime_raw_history_"
    const val PREF_CALIBRATOR_TEMP_SMOOTH_PREV_PREFIX = "anytime_calib_temp_smooth_"
    const val PREF_CALIBRATOR_FILTERED_PREV_PREFIX = "anytime_calib_filtered_"
    const val PREF_CALIBRATOR_LAST_ID_PREFIX = "anytime_calib_last_id_"
    const val PREF_CT3_NATIVE_STATE_PREFIX = "anytime_ct3_native_state_"
    const val PREF_TEMPERATURE_HISTORY_PREFIX = "anytime_temp_history_"
    const val PREF_CT5_CIPHER_KEY_PREFIX = "anytime_ct5_cipher_"

    /** Learned mg/dL-per-nA for a CT5, and how many readings taught it. */
    const val PREF_CT5_RAW_SCALE_PREFIX = "anytime_ct5_raw_scale_"
    const val PREF_CT5_RAW_SCALE_SAMPLES_PREFIX = "anytime_ct5_raw_scale_n_"
    const val PREF_CT5_RANDOM_B_PREFIX = "anytime_ct5_randomb_"
    const val PREF_CT5_TEMP_ID_PREFIX = "anytime_ct5_tempid_"
    const val PREF_CT5_RECOVERY_CIPHER_KEY_PREFIX = "anytime_ct5_recovery_cipher_"
    const val PREF_CT5_RECOVERY_RANDOM_B_PREFIX = "anytime_ct5_recovery_randomb_"
    const val PREF_CT5_RECOVERY_TEMP_ID_PREFIX = "anytime_ct5_recovery_tempid_"

    /**
     * Highest CT5 glucose id whose computed record we have actually imported.
     * Distinct from PREF_LAST_GLUCOSE_ID_PREFIX, which also advances on
     * warm-up/no-glucose frames that carry no point to store.
     */
    const val PREF_CT5_HIGHEST_IMPORTED_ID_PREFIX = "anytime_ct5_imported_id_"

    /** Pending CT5 gap repair (inclusive start / exclusive end), so an interrupted
     *  repair survives a process restart instead of being replayed from zero. */
    const val PREF_CT5_GAP_FROM_PREFIX = "anytime_ct5_gap_from_"
    const val PREF_CT5_GAP_STOP_BEFORE_PREFIX = "anytime_ct5_gap_stop_"

    /** Auto-repair ids the transmitter repeatedly proved it cannot currently serve. */
    const val PREF_CT5_SKIPPED_HISTORY_IDS_PREFIX = "anytime_ct5_skipped_history_ids_"
    /** CT5 history ids the transmitter returned, including points already present in Room. */
    const val PREF_CT5_RESOLVED_HISTORY_IDS_PREFIX = "anytime_ct5_resolved_history_ids_"
    const val PREF_CT5_GAP_FAILURE_FROM_PREFIX = "anytime_ct5_gap_failure_from_"
    const val PREF_CT5_GAP_FAILURE_STOP_PREFIX = "anytime_ct5_gap_failure_stop_"
    const val PREF_CT5_GAP_FAILURE_COUNT_PREFIX = "anytime_ct5_gap_failure_count_"
}
