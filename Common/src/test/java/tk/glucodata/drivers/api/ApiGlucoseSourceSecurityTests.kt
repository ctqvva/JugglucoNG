package tk.glucodata.drivers.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

class ApiGlucoseSourceSecurityTests {

    @Test
    fun normalizeUrl_httpRejected() {
        assertEquals("", ApiGlucoseSourceRegistry.normalizeUrl("http://example.com"))
        assertFalse(ApiGlucoseSourceRegistry.isSecureUrlInput("http://example.com"))
    }

    @Test
    fun normalizeUrl_httpsPreservedAndBareHostPromoted() {
        assertEquals("https://example.com", ApiGlucoseSourceRegistry.normalizeUrl("https://example.com"))
        assertEquals("https://example.com", ApiGlucoseSourceRegistry.normalizeUrl("example.com"))
        assertTrue(ApiGlucoseSourceRegistry.isSecureUrlInput("example.com"))
    }

    @Test
    fun parseJsonReading_restoresCalibratedMgdlAlias() {
        val reading = ApiGlucoseSourceParserTestUtil.parseEntry(
            mapOf(
                "calibratedMgdl" to 123.0,
                "autoMgdl" to 119.0,
                "timestamp" to 1718928000000L,
            )
        )

        assertEquals(123f, reading!!.glucoseMgdl, 0.01f)
        assertEquals(123f, reading.calibratedMgdl, 0.01f)
        assertEquals(119f, reading.autoMgdl, 0.01f)
    }

    @Test
    fun parseJsonReading_rejectsUnknownShape() {
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("timestamp" to 1718928000000L)))
    }

    @Test
    fun parseJsonReading_rejectsFutureTimestampBeyondDrift() {
        val tooFuture = System.currentTimeMillis() + 11L * 60L * 1000L

        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 123.0, "timestamp" to tooFuture)))
    }

    @Test
    fun parseOutboundJson_readsEntriesArrayAndSkipsInvalid() {
        val readings = listOf(
            mapOf("sgv" to 101, "mills" to 1718928000000L),
            mapOf("unknown" to 10, "mills" to 1718928000000L),
        ).mapNotNull(ApiGlucoseSourceParserTestUtil::parseEntry)

        assertEquals(1, readings.size)
        assertEquals(101f, readings.single().glucoseMgdl, 0.01f)
    }

    @Test
    fun parseJsonReading_restoresRawAndRateAliases() {
        val reading = ApiGlucoseSourceParserTestUtil.parseEntry(
            mapOf(
                "glucose_mgdl" to 101,
                "rawMgdl" to 110,
                "rate_mmol_per_min" to 0.1,
                "mills" to 1718928000000L,
            )
        )

        assertEquals(110f, reading!!.rawMgdl, 0.01f)
        assertEquals(1.80182f, reading.rate, 0.0001f)
    }

    @Test
    fun virtualBridge_sanitizeCurrentRateRejectsOutOfBounds() {
        assertEquals(0f, VirtualGlucoseSensorBridge.sanitizeCurrentRate(30.01f), 0.0f)
        assertEquals(0f, VirtualGlucoseSensorBridge.sanitizeCurrentRate(-30.01f), 0.0f)
        assertEquals(0f, VirtualGlucoseSensorBridge.sanitizeCurrentRate(Float.NaN), 0.0f)
    }

    @Test
    fun virtualBridge_sanitizeCurrentRateAllowsBoundary() {
        assertEquals(30f, VirtualGlucoseSensorBridge.sanitizeCurrentRate(30f), 0.0f)
        assertEquals(-30f, VirtualGlucoseSensorBridge.sanitizeCurrentRate(-30f), 0.0f)
    }

    // ===== M8: Comprehensive alias-pinning tests =====
    // These pin every accepted JSON field alias so future regressions
    // (accidental removal) fail CI.

    private val ts = 1718928000000L

    // --- Primary mg/dL aliases ---

    @Test
    fun alias_primaryMgdl_glucose_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "timestamp" to ts))
        assertEquals(100f, r!!.glucoseMgdl, 0.01f)
    }

    @Test
    fun alias_primaryMgdl_sgv() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("sgv" to 101.0, "timestamp" to ts))
        assertEquals(101f, r!!.glucoseMgdl, 0.01f)
    }

    @Test
    fun alias_primaryMgdl_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("mgdl" to 102.0, "timestamp" to ts))
        assertEquals(102f, r!!.glucoseMgdl, 0.01f)
    }

    @Test
    fun alias_primaryMgdl_calibrated_glucose_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("calibrated_glucose_mgdl" to 103.0, "timestamp" to ts))
        assertEquals(103f, r!!.glucoseMgdl, 0.01f)
    }

    @Test
    fun alias_primaryMgdl_calibrated_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("calibrated_mgdl" to 104.0, "timestamp" to ts))
        assertEquals(104f, r!!.glucoseMgdl, 0.01f)
    }

    @Test
    fun alias_primaryMgdl_calibratedMgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("calibratedMgdl" to 105.0, "timestamp" to ts))
        assertEquals(105f, r!!.glucoseMgdl, 0.01f)
    }

    // --- Primary mmol aliases ---

    @Test
    fun alias_primaryMmol_glucose_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mmol" to 5.5, "timestamp" to ts))
        assertEquals(5.5f * 18.0182f, r!!.glucoseMgdl, 0.1f)
    }

    @Test
    fun alias_primaryMmol_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("mmol" to 6.0, "timestamp" to ts))
        assertEquals(6.0f * 18.0182f, r!!.glucoseMgdl, 0.1f)
    }

    @Test
    fun alias_primaryMmol_calibrated_glucose_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("calibrated_glucose_mmol" to 7.0, "timestamp" to ts))
        assertEquals(7.0f * 18.0182f, r!!.glucoseMgdl, 0.1f)
    }

    @Test
    fun alias_primaryMmol_calibrated_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("calibrated_mmol" to 8.0, "timestamp" to ts))
        assertEquals(8.0f * 18.0182f, r!!.glucoseMgdl, 0.1f)
    }

    // --- Auto mg/dL aliases ---

    private fun autoEntry(key: String, value: Double) =
        mapOf("glucose_mgdl" to 100.0, key to value, "timestamp" to ts)

    @Test
    fun alias_autoMgdl_auto_glucose_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("auto_glucose_mgdl", 95.0))
        assertEquals(95f, r!!.autoMgdl, 0.01f)
    }

    @Test
    fun alias_autoMgdl_auto_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("auto_mgdl", 96.0))
        assertEquals(96f, r!!.autoMgdl, 0.01f)
    }

    @Test
    fun alias_autoMgdl_autoMgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("autoMgdl", 97.0))
        assertEquals(97f, r!!.autoMgdl, 0.01f)
    }

    @Test
    fun alias_autoMgdl_uncalibrated_glucose_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("uncalibrated_glucose_mgdl", 98.0))
        assertEquals(98f, r!!.autoMgdl, 0.01f)
    }

    @Test
    fun alias_autoMgdl_uncalibrated_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("uncalibrated_mgdl", 99.0))
        assertEquals(99f, r!!.autoMgdl, 0.01f)
    }

    // --- Auto mmol aliases ---

    @Test
    fun alias_autoMmol_auto_glucose_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("auto_glucose_mmol", 5.0))
        assertEquals(5.0f * 18.0182f, r!!.autoMgdl, 0.1f)
    }

    @Test
    fun alias_autoMmol_auto_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("auto_mmol", 5.5))
        assertEquals(5.5f * 18.0182f, r!!.autoMgdl, 0.1f)
    }

    @Test
    fun alias_autoMmol_autoMmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("autoMmol", 6.0))
        assertEquals(6.0f * 18.0182f, r!!.autoMgdl, 0.1f)
    }

    @Test
    fun alias_autoMmol_uncalibrated_glucose_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("uncalibrated_glucose_mmol", 6.5))
        assertEquals(6.5f * 18.0182f, r!!.autoMgdl, 0.1f)
    }

    @Test
    fun alias_autoMmol_uncalibrated_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(autoEntry("uncalibrated_mmol", 7.0))
        assertEquals(7.0f * 18.0182f, r!!.autoMgdl, 0.1f)
    }

    // --- Raw mg/dL aliases ---

    @Test
    fun alias_rawMgdl_raw_glucose_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "raw_glucose_mgdl" to 110.0, "timestamp" to ts))
        assertEquals(110f, r!!.rawMgdl, 0.01f)
    }

    @Test
    fun alias_rawMgdl_raw_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "raw_mgdl" to 111.0, "timestamp" to ts))
        assertEquals(111f, r!!.rawMgdl, 0.01f)
    }

    @Test
    fun alias_rawMgdl_rawMgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "rawMgdl" to 112.0, "timestamp" to ts))
        assertEquals(112f, r!!.rawMgdl, 0.01f)
    }

    @Test
    fun alias_rawMgdl_raw_gluc_mgdl() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "raw_gluc_mgdl" to 113.0, "timestamp" to ts))
        assertEquals(113f, r!!.rawMgdl, 0.01f)
    }

    // --- Raw mmol aliases ---

    @Test
    fun alias_rawMmol_raw_glucose_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "raw_glucose_mmol" to 6.0, "timestamp" to ts))
        assertEquals(6.0f * 18.0182f, r!!.rawMgdl, 0.1f)
    }

    @Test
    fun alias_rawMmol_raw_mmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "raw_mmol" to 6.5, "timestamp" to ts))
        assertEquals(6.5f * 18.0182f, r!!.rawMgdl, 0.1f)
    }

    @Test
    fun alias_rawMmol_rawMmol() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "rawMmol" to 7.0, "timestamp" to ts))
        assertEquals(7.0f * 18.0182f, r!!.rawMgdl, 0.1f)
    }

    // --- Raw fallback aliases ---

    @Test
    fun alias_rawFallback_raw_value() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "raw_value" to 120.0, "timestamp" to ts))
        assertEquals(120f, r!!.rawMgdl, 0.01f)
    }

    @Test
    fun alias_rawFallback_raw() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "raw" to 130.0, "timestamp" to ts))
        assertEquals(130f, r!!.rawMgdl, 0.01f)
    }

    @Test
    fun alias_rawFallback_raw_valueMmolConversion() {
        val r = ApiGlucoseSourceParserTestUtil.parseEntry(
            mapOf("glucose_mgdl" to 100.0, "raw_value" to 5.0, "raw_unit" to "mmol", "timestamp" to ts)
        )
        assertEquals(5.0f * 18.0182f, r!!.rawMgdl, 0.1f)
    }

    // --- Negative tests: out-of-range / garbage values ---

    @Test
    fun negative_glucoseZero_returnsNull() {
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 0.0, "timestamp" to ts)))
    }

    @Test
    fun negative_glucoseNegative_returnsNull() {
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to -50.0, "timestamp" to ts)))
    }

    @Test
    fun negative_glucoseNaN_returnsNull() {
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to Double.NaN, "timestamp" to ts)))
    }

    @Test
    fun negative_glucoseInfinity_returnsNull() {
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to Double.POSITIVE_INFINITY, "timestamp" to ts)))
    }

    @Test
    fun negative_missingTimestamp_returnsNull() {
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0)))
    }

    @Test
    fun negative_zeroTimestamp_returnsNull() {
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "timestamp" to 0L)))
    }

    @Test
    fun negative_14digitTimestamp_returnsNull() {
        // 14-digit timestamps are rejected (unsupported precision)
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("glucose_mgdl" to 100.0, "timestamp" to 17189280000000L)))
    }

    @Test
    fun negative_garbageOnlyFields_returnsNull() {
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(mapOf("foo" to 100, "bar" to "baz", "timestamp" to ts)))
    }

    @Test
    fun negative_emptyEntry_returnsNull() {
        assertNull(ApiGlucoseSourceParserTestUtil.parseEntry(emptyMap()))
    }
}

object ApiGlucoseSourceParserTestUtil {
    private const val MGDL_PER_MMOLL = 18.0182f
    private const val MIN_REASONABLE_TIMESTAMP_MS = 946_684_800_000L
    private const val MAX_FUTURE_TIMESTAMP_DRIFT_MS = 10L * 60L * 1000L

    fun parseEntry(entry: Map<String, Any?>): VirtualGlucoseSensorBridge.Reading? {
        val primaryMgdl = firstFiniteField(
            entry, "glucose_mgdl", "sgv", "mgdl",
            "calibrated_glucose_mgdl", "calibrated_mgdl", "calibratedMgdl"
        ) ?: firstFiniteField(
            entry, "glucose_mmol", "mmol",
            "calibrated_glucose_mmol", "calibrated_mmol"
        )?.let { it * MGDL_PER_MMOLL }
            ?: return null

        val autoMgdl = firstFiniteField(
            entry, "auto_glucose_mgdl", "auto_mgdl", "autoMgdl",
            "uncalibrated_glucose_mgdl", "uncalibrated_mgdl"
        ) ?: firstFiniteField(
            entry, "auto_glucose_mmol", "auto_mmol", "autoMmol",
            "uncalibrated_glucose_mmol", "uncalibrated_mmol"
        )?.let { it * MGDL_PER_MMOLL }
            ?: Double.NaN

        val calibratedMgdl = if (autoMgdl.isFinite() && autoMgdl > 0.0) {
            primaryMgdl
        } else {
            firstFiniteField(
                entry, "calibrated_glucose_mgdl", "calibrated_mgdl", "calibratedMgdl"
            ) ?: firstFiniteField(
                entry, "calibrated_glucose_mmol", "calibrated_mmol"
            )?.let { it * MGDL_PER_MMOLL }
                ?: Double.NaN
        }

        val timestamp = normalizeTimestamp(
            firstLong(entry["timestamp"], entry["date"], entry["mills"], entry["datetime"])
        ) ?: return null

        val rate = firstFiniteAny(entry["rate_mgdl_per_min"])?.toFloat()
            ?: firstFiniteAny(entry["rate_mmol_per_min"])?.let { (it * MGDL_PER_MMOLL).toFloat() }
            ?: Float.NaN

        return VirtualGlucoseSensorBridge.Reading(
            timestampMs = timestamp,
            glucoseMgdl = primaryMgdl.toFloat(),
            autoMgdl = autoMgdl.toFloat(),
            calibratedMgdl = calibratedMgdl.toFloat(),
            rawMgdl = parseRawMgdl(entry)?.toFloat() ?: Float.NaN,
            rate = rate,
        )
    }

    private fun parseRawMgdl(entry: Map<String, Any?>): Double? {
        val explicit = firstFiniteField(
            entry, "raw_glucose_mgdl", "raw_mgdl", "rawMgdl", "raw_gluc_mgdl"
        ) ?: firstFiniteField(
            entry, "raw_glucose_mmol", "raw_mmol", "rawMmol"
        )?.let { it * MGDL_PER_MMOLL }
        if (explicit != null) return explicit

        val rawValue = firstFiniteField(entry, "raw_value", "raw") ?: return null
        val unit = listOf("raw_unit", "display_unit", "unit")
            .firstNotNullOfOrNull { (entry[it] as? String)?.takeIf(String::isNotBlank) }
            .orEmpty()
            .lowercase()
        return when {
            unit.contains("mmol") -> rawValue * MGDL_PER_MMOLL
            unit.contains("mg") -> rawValue
            rawValue in 1.0..40.0 -> rawValue * MGDL_PER_MMOLL
            rawValue in 40.0..600.0 -> rawValue
            else -> null
        }
    }

    private fun firstFiniteField(entry: Map<String, Any?>, vararg keys: String): Double? =
        keys.asSequence()
            .mapNotNull { key -> (entry[key] as? Number)?.toDouble() }
            .firstOrNull { it.isFinite() && it > 0.0 }

    private fun firstFiniteAny(value: Any?): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun firstLong(vararg values: Any?): Long =
        values.asSequence()
            .mapNotNull { it as? Number }
            .map { it.toLong() }
            .firstOrNull { it > 0L } ?: 0L

    private fun normalizeTimestamp(raw: Long): Long? {
        if (raw <= 0L) return null
        val millis = when (raw) {
            in 1_000_000_000L..9_999_999_999L -> raw * 1_000L
            in 1_000_000_000_000L..9_999_999_999_999L -> raw
            in 1_000_000_000_000_000L..9_999_999_999_999_999L -> raw / 1_000L
            in 1_000_000_000_000_000_000L..Long.MAX_VALUE -> raw / 1_000_000L
            else -> return null
        }
        return millis.takeIf(::isPlausibleTimestamp)
    }

    private fun isPlausibleTimestamp(timestampMs: Long): Boolean =
        timestampMs in MIN_REASONABLE_TIMESTAMP_MS..(System.currentTimeMillis() + MAX_FUTURE_TIMESTAMP_DRIFT_MS)
}
