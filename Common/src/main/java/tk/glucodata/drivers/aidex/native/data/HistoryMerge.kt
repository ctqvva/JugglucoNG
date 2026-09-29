// JugglucoNG — AiDex Native Kotlin Driver
// HistoryMerge.kt — Testable pure-logic helpers for the 0x23/0x24 history merge
//
// Extracted from AiDexBleManager so these can be unit tested without
// Android framework dependencies. The store filter is
// AiDexHistoryPolicy.historyStoreRejection.

package tk.glucodata.drivers.aidex.native.data

import tk.glucodata.drivers.aidex.native.protocol.AiDexOpcodes

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
    /** Number of entries with no glucose at all, sentinel minutes included (will be filtered) */
    val noGlucoseCount: Int,
    /** Updated fallback value for cross-page continuity */
    val lastKnownGlucose: Int?,
)

/**
 * Pure-logic helpers for the history merge.
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

    /**
     * Cache value for a minute 0x23 delivered as the sentinel (no reading). It merges to
     * glucose 0, which the store drops, instead of taking the carried-forward fallback.
     * A real 0x23 value is never 1023: the parser flags exactly that value as the sentinel.
     */
    const val CALIBRATED_SENTINEL_MARKER: Int = AiDexOpcodes.SENTINEL_GLUCOSE

    /** True for a cached 0x23 glucose value, false for a missing minute or a sentinel marker. */
    fun isRealCachedGlucose(value: Int?): Boolean =
        value != null && value != CALIBRATED_SENTINEL_MARKER

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
     * Cache 0x23 calibrated history entries. A sentinel row is cached as
     * [CALIBRATED_SENTINEL_MARKER] and counted as skipped; it never replaces a real value
     * already cached for that minute (HEAD skipped it, so the real value stayed).
     *
     * A full CRC-stripped page is 119 data rows. A last-row jump is real glucose,
     * not a CRC trailer — do not skip it.
     *
     * @param entries Parsed 0x23 history entries
     * @param cache Mutable map to populate (offset -> glucose mg/dL, or the sentinel marker)
     * @return Pair of (cached count, skipped count)
     */
    fun cacheCalibratedEntries(
        entries: List<CalibratedHistoryEntry>,
        cache: MutableMap<Int, Int>,
    ): Pair<Int, Int> {
        var cached = 0
        var skipped = 0

        for (entry in entries) {
            if (entry.isSentinel) {
                if (!isRealCachedGlucose(cache[entry.timeOffsetMinutes])) {
                    cache[entry.timeOffsetMinutes] = CALIBRATED_SENTINEL_MARKER
                }
                skipped++
                continue
            }

            cache[entry.timeOffsetMinutes] = entry.glucoseMgDl
            cached++
        }

        return cached to skipped
    }

    /**
     * The 0x23 cursor after a non-empty page: one past the last row of this page that holds a
     * real cached value, else one past the page. A sentinel marker does not count, so a
     * sentinel tail is asked for again by the next page, as on HEAD
     * (`entries.lastOrNull { cache.containsKey(it) } ?: entries.last()`, + 1, before markers).
     */
    fun nextRawCursorAfterPage(
        entries: List<CalibratedHistoryEntry>,
        cache: Map<Int, Int>,
    ): Int {
        val lastCached = entries.lastOrNull { isRealCachedGlucose(cache[it.timeOffsetMinutes]) }
        return (lastCached ?: entries.last()).timeOffsetMinutes + 1
    }

    /**
     * Merge 0x24 ADC entries with cached 0x23 calibrated glucose.
     *
     * For each ADC entry:
     * - If an exact offset match exists in the 0x23 cache, use it (and remove from cache)
     * - A sentinel marker gives glucose=0 (dropped by the store); it neither uses nor
     *   updates the fallback, so no neighbour's value is invented for that minute
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
            if (cachedGlucose == CALIBRATED_SENTINEL_MARKER) {
                glucose = 0f
                noGlucose++
            } else if (cachedGlucose != null) {
                glucose = cachedGlucose.toFloat()
                lastKnownGlucose = cachedGlucose
                merged++
            } else if (lastKnownGlucose != null) {
                glucose = lastKnownGlucose.toFloat()
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
}
