// JugglucoNG — AiDex Native Kotlin Driver
// Data classes for parsed protocol data
//
// All glucose values stored in mg/dL internally.

package tk.glucodata.drivers.aidex.native.data

/**
 * Parsed result from a 17-byte F003 data frame.
 */
data class GlucoseFrame(
    /** Opcode byte (byte[0]) — determines scaling and frame type */
    val opcode: Int,
    /** Minutes since sensor start (decoded from bytes[1..4] seconds / 60) */
    val timeOffsetMinutes: Int,
    /** Calibrated glucose in mg/dL (after opcode scaling) */
    val glucoseMgDl: Float,
    /** Raw 10-bit glucose value before scaling */
    val rawGlucosePacked: Int,
    /** Raw channel 1 value (i1 = u16le([8..9]) / 100) */
    val i1: Float,
    /** Raw channel 2 value (i2 = u16le([10..11]) / 100) */
    val i2: Float,
    /** CRC-16 from frame tail (u16le([15..16])) */
    val crc16: Int,
    /** Whether this is a valid glucose reading (not sentinel, in range) */
    val isValid: Boolean,
) {
    /**
     * Skin temperature in °C.
     *
     * The `i2` channel tracks skin temperature, not a second glucose current:
     * verified against a co-worn Sibionics sensor (r ≈ 0.72 over ~5.5 h,
     * identical span, −0.08 °C mean bias). Already in °C, no scaling.
     */
    val temperatureC: Float get() = i2
}

/**
 * Parsed result from a 5-byte F003 status/keepalive frame.
 */
data class StatusFrame(
    /** First byte — status indicator */
    val header: Int,
)

/**
 * A parsed history record from GET_HISTORIES_RAW (0x23) response.
 * 2-byte entries: factory-calibrated glucose, 10-bit packed.
 */
data class CalibratedHistoryEntry(
    /** Time offset in minutes from sensor start */
    val timeOffsetMinutes: Int,
    /** Glucose value in mg/dL (10-bit, 0-1023) */
    val glucoseMgDl: Int,
    /** Status bit from byte[1] & 0x04 */
    val statusBit: Boolean,
    /** Whether this is the sentinel (1023 = no reading) */
    val isSentinel: Boolean,
)

/**
 * A parsed history record from GET_HISTORIES (0x24) response.
 * 5-byte entries: pre-calibration ADC data (i1, i2, vc).
 */
data class AdcHistoryEntry(
    /** Time offset in minutes from sensor start */
    val timeOffsetMinutes: Int,
    /** Raw ADC channel 1 (u16 / 100) */
    val i1: Float,
    /** Raw ADC channel 2 (u16 / 100) */
    val i2: Float,
    /** Voltage/calibration (u8 / 100) */
    val vc: Float,
    /** Raw value = i1 * 10 (matches Android selectVendorRawLane) */
    val rawValue: Float,
    /** Sensor glucose = i1 * 18.0182 (treating i1 as mmol/L -> mg/dL) */
    val sensorGlucose: Float,
) {
    /** Skin temperature in °C — same `i2` channel as [GlucoseFrame.temperatureC]. */
    val temperatureC: Float get() = i2
}

/**
 * A parsed calibration record from GET_CALIBRATION (0x27) response.
 */
data class CalibrationRecord(
    /** Record index */
    val index: Int,
    /** Time offset in minutes from sensor start */
    val timeOffsetMinutes: Int,
    /** Reference glucose in mg/dL */
    val referenceGlucoseMgDl: Int,
    /** Calibration factor (u16 / 100) */
    val calibrationFactor: Float,
    /** Calibration offset (s16 / 100) */
    val calibrationOffset: Float,
)

/**
 * Parsed broadcast advertisement reading.
 */
data class BroadcastReading(
    /** Glucose value in mg/dL (10-bit packed) */
    val glucoseMgDl: Int,
    /** Simple 8-bit glucose fallback */
    val glucoseFallback: Int,
    /** Time offset in minutes since sensor start */
    val timeOffsetMinutes: Long,
    /** Trend value (signed i8) */
    val trend: Int,
    /** Device name from advertisement */
    val deviceName: String,
) {
    /** The best glucose value to use */
    val bestGlucose: Int
        get() = if (glucoseMgDl in 30..500) glucoseMgDl else glucoseFallback
}

/**
 * A glucose reading ready for storage/display.
 * Composite of data from multiple sources (F003, 0x23, 0x24).
 */
data class GlucoseReading(
    /** Milliseconds since epoch */
    val timestamp: Long,
    /** Sensor serial number (bare, without "X-" prefix) */
    val sensorSerial: String,
    /** Factory-calibrated mg/dL (from 0x23 / F003 broadcast) */
    val autoValue: Float?,
    /** i1 * 10 (from 0x24 / F003) */
    val rawValue: Float?,
    /** i1 * 18.0182 (from 0x24 / F003) */
    val sensorGlucose: Float?,
    /** Raw ADC channel 1 */
    val rawI1: Float?,
    /** Raw ADC channel 2 */
    val rawI2: Float?,
    /** Minutes from sensor start */
    val timeOffsetMinutes: Int,
    /** Skin temperature in °C (F003 `i2` channel); null when unknown */
    val temperatureC: Float? = null,
) {
    /** Composite key for deduplication: "${timestamp}_${sensorSerial}" */
    val compositeKey: String get() = "${timestamp}_${sensorSerial}"
}

/**
 * Sensor metadata.
 */
data class SensorInfo(
    /** Serial number (bare, without "X-" prefix) */
    val serial: String,
    /** Activation date (millis since epoch), null if not activated */
    val activationDateMs: Long?,
    /** Sensor start date (millis since epoch) */
    val startDateMs: Long?,
    /** Maximum sensor lifetime in days */
    val maxDays: Int = 15,
)
