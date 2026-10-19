// JugglucoNG — AiDex Native Kotlin Driver
// HistoryMerge.kt — Testable pure-logic helpers for history merge and filtering
//
// Extracted from AiDexBleManager so these can be unit tested without
// Android framework dependencies.

package tk.glucodata.drivers.aidex.native.data

/**
 * Lightweight container for history entries ready for storage.
 * Matches the private HistoryStoreEntry in AiDexBleManager.
 */
data class HistoryStoreEntry(
    val offsetMinutes: Int,
    val glucoseMgDl: Float,
    val rawMgDl: Float,
    val isValid: Boolean,
    /** Skin temperature in °C from the 0x24 `i2` channel; NaN when unknown. */
    val temperatureC: Float = Float.NaN,
)

/**
 * Result of merging 0x24 ADC entries with cached 0x23 calibrated glucose.
 */
data class MergeResult(
    /** Merged entries ready for storage */
    val entries: List<HistoryStoreEntry>,
    /** Number of entries with exact 0x23 cache match */
    val mergedCount: Int,
    /** Number of entries that used fallback glucose */
    val fallbackCount: Int,
    /** Number of entries with no glucose at all (will be filtered) */
    val noGlucoseCount: Int,
    /** Updated fallback value for cross-page continuity */
    val lastKnownGlucose: Int?,
)

/**
 * Result of filtering history entries for storage.
 */
data class FilterResult(
    /** Entries that passed all filters */
    val passed: List<HistoryStoreEntry>,
    /** Total entries filtered out */
    val filteredCount: Int,
)

/**
 * Pure-logic helpers for history merge and filtering.
 * All methods are stateless and testable without Android dependencies.
 */
object HistoryMerge {

    // Constants matching AiDexBleManager companion object
    const val MIN_VALID_GLUCOSE_MGDL = 20
    const val MAX_VALID_GLUCOSE_MGDL = 500
    const val MAX_PLAUSIBLE_RAW_MMOL = 30f
    const val MGDL_PER_MMOL = 18.0182f
    const val MAX_PLAUSIBLE_RAW_MGDL = MAX_PLAUSIBLE_RAW_MMOL * MGDL_PER_MMOL
    const val MAX_OFFSET_DAYS = 30
    const val WARMUP_DURATION_MS = 7L * 60_000L  // 7 minutes
    private const val CONTROL_VALUE_DEVIATION_THRESHOLD_MGDL = 20

    /**
     * Raw AiDex values can spike briefly after physical sensor disturbance.
     * Treat implausible values as absent so they do not poison raw stats/charts.
     */
    fun normalizeRawMgDl(rawMgDl: Float?): Float? {
        val value = rawMgDl ?: return null
        if (!value.isFinite()) return null
        if (value <= 0f) return null
        if (value > MAX_PLAUSIBLE_RAW_MGDL) return null
        return value
    }

    /**
     * Cache 0x23 calibrated history entries, skipping sentinels and control values.
     *
     * The last entry of a full page (>=120 entries) can be a control/calibration value
     * embedded by the sensor. Skip it if it makes an implausible one-minute jump from
     * its neighbor. Field logs showed a page-tail 70 -> 97 mg/dL row overwriting the
     * matching direct F003 value; falling back to the previous history glucose is safer
     * than preserving an isolated page-boundary spike.
     *
     * @param entries Parsed 0x23 history entries
     * @param cache Mutable map to populate (offset -> glucose mg/dL)
     * @return Pair of (cached count, skipped count)
     */
    fun cacheCalibratedEntries(
        entries: List<CalibratedHistoryEntry>,
        cache: MutableMap<Int, Int>,
    ): Pair<Int, Int> {
        var cached = 0
        var skipped = 0
        val lastIdx = entries.size - 1

        for ((idx, entry) in entries.withIndex()) {
            if (entry.isSentinel) { skipped++; continue }

            if (idx == lastIdx && entries.size >= 120) {
                val prevGlucose = if (idx > 0) entries[idx - 1].glucoseMgDl else entry.glucoseMgDl
                val deviation = kotlin.math.abs(entry.glucoseMgDl - prevGlucose)
                if (deviation > CONTROL_VALUE_DEVIATION_THRESHOLD_MGDL) {
                    skipped++
                    continue
                }
            }

            cache[entry.timeOffsetMinutes] = entry.glucoseMgDl
            cached++
        }

        return cached to skipped
    }

    /**
     * Merge 0x24 ADC entries with cached 0x23 calibrated glucose.
     *
     * For each ADC entry:
     * - If an exact offset match exists in the 0x23 cache, use it (and remove from cache)
     * - Otherwise, use the most recent successfully matched value as fallback
     * - If no fallback available yet, set glucose=0 (will be filtered by store)
     *
     * @param adcEntries Parsed 0x24 brief history entries
     * @param calibratedCache Mutable map of offset -> calibrated glucose (entries are removed as matched)
     * @param initialFallback Initial fallback glucose from previous page (cross-page continuity)
     * @return MergeResult with entries and statistics
     */
    fun mergeHistoryEntries(
        adcEntries: List<AdcHistoryEntry>,
        calibratedCache: MutableMap<Int, Int>,
        initialFallback: Int?,
    ): MergeResult {
        var merged = 0
        var fallback = 0
        var noGlucose = 0
        var lastKnownGlucose: Int? = initialFallback

        val entries = adcEntries.map { entry ->
            val cachedGlucose = calibratedCache.remove(entry.timeOffsetMinutes)
            val glucose: Float
            if (cachedGlucose != null) {
                glucose = cachedGlucose.toFloat()
                lastKnownGlucose = cachedGlucose
                merged++
            } else if (lastKnownGlucose != null) {
                glucose = lastKnownGlucose!!.toFloat()
                fallback++
            } else {
                glucose = 0f
                noGlucose++
            }

            HistoryStoreEntry(
                offsetMinutes = entry.timeOffsetMinutes,
                glucoseMgDl = glucose,
                rawMgDl = normalizeRawMgDl(entry.rawValue) ?: 0f,
                isValid = !(entry.i1 == 0f && entry.i2 == 0f && entry.vc == 0f),
                temperatureC = entry.temperatureC,
            )
        }

        return MergeResult(
            entries = entries,
            mergedCount = merged,
            fallbackCount = fallback,
            noGlucoseCount = noGlucose,
            lastKnownGlucose = lastKnownGlucose,
        )
    }

    /**
     * Filter history entries for storage, applying all validity checks.
     *
     * Filters:
     * - Invalid entries (isValid = false)
     * - offsetMinutes <= 0
     * - offsetMinutes > MAX_OFFSET_DAYS * 24 * 60
     * - offsetMinutes > historyNewestOffset (beyond sensor's newest data)
     * - offsetMinutes >= liveOffsetCutoff (already stored by live F003)
     * - ADC saturation sentinel (glucoseInt >= 1023 and > 0)
     * - Out-of-range glucose (not in MIN_VALID..MAX_VALID)
     * - Warmup period (first 7 minutes after sensor start)
     * - Future timestamps (> now + 2 minutes)
     *
     * @param entries List of history store entries to filter
     * @param sensorStartMs Sensor start time in millis since epoch
     * @param nowMs Current time in millis since epoch
     * @param historyNewestOffset Sensor's reported newest offset (0 = no limit)
     * @param liveOffsetCutoff Offset at or above which entries are already stored by live pipeline (0 = no limit)
     * @return FilterResult with passed entries and filter count
     */
    fun filterForStorage(
        entries: List<HistoryStoreEntry>,
        sensorStartMs: Long,
        nowMs: Long,
        historyNewestOffset: Int = 0,
        liveOffsetCutoff: Int = 0,
    ): FilterResult {
        val passed = mutableListOf<HistoryStoreEntry>()
        var filtered = 0

        for (entry in entries) {
            if (!entry.isValid) { filtered++; continue }
            if (entry.offsetMinutes <= 0) { filtered++; continue }
            if (entry.offsetMinutes.toLong() > MAX_OFFSET_DAYS * 24L * 60L) { filtered++; continue }

            if (historyNewestOffset > 0 && entry.offsetMinutes > historyNewestOffset) {
                filtered++; continue
            }
            if (liveOffsetCutoff > 0 && entry.offsetMinutes >= liveOffsetCutoff) {
                filtered++; continue
            }

            val glucoseInt = entry.glucoseMgDl.toInt()
            if (glucoseInt >= 1023 && entry.glucoseMgDl > 0f) { filtered++; continue }
            if (glucoseInt !in MIN_VALID_GLUCOSE_MGDL..MAX_VALID_GLUCOSE_MGDL) { filtered++; continue }

            val historicalTimeMs = sensorStartMs + (entry.offsetMinutes.toLong() * 60_000L)

            val sensorAgeAtRecordMs = historicalTimeMs - sensorStartMs
            if (sensorAgeAtRecordMs in 0 until WARMUP_DURATION_MS) { filtered++; continue }

            if (historicalTimeMs > nowMs + 120_000L) { filtered++; continue }

            passed.add(entry)
        }

        return FilterResult(passed = passed, filteredCount = filtered)
    }
}
