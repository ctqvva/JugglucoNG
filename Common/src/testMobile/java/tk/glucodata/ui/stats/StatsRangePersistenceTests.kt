package tk.glucodata.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure part of restoring the statistics range selection: what a stored value
 * resolves to, and how a restored custom range is clamped to the data that exists
 * today. Writing to SharedPreferences itself is left to the device.
 */
class StatsRangePersistenceTests {

    @Test
    fun storedPresetNameResolvesToThatPreset() {
        val resolved = resolveStoredStatsRange(
            presetName = "DAY_30",
            customStartMillis = Long.MIN_VALUE,
            customEndMillis = Long.MIN_VALUE
        )
        assertEquals(StatsRangeSelection.Preset(StatsTimeRange.DAY_30), resolved)
    }

    @Test
    fun unknownPresetNameFallsBackToNothingStored() {
        val resolved = resolveStoredStatsRange(
            presetName = "DAY_365",
            customStartMillis = Long.MIN_VALUE,
            customEndMillis = Long.MIN_VALUE
        )
        assertNull(resolved)
    }

    @Test
    fun storedCustomMillisResolveToACustomRange() {
        val resolved = resolveStoredStatsRange(
            presetName = null,
            customStartMillis = 1_000L,
            customEndMillis = 2_000L
        )
        assertEquals(
            StatsRangeSelection.Custom(StatsDateRange(startMillis = 1_000L, endMillis = 2_000L)),
            resolved
        )
    }

    @Test
    fun garbageCustomMillisFallBackToNothingStored() {
        assertNull(
            resolveStoredStatsRange(
                presetName = null,
                customStartMillis = 2_000L,
                customEndMillis = 1_000L
            )
        )
        assertNull(
            resolveStoredStatsRange(
                presetName = null,
                customStartMillis = -5L,
                customEndMillis = 1_000L
            )
        )
        assertNull(
            resolveStoredStatsRange(
                presetName = null,
                customStartMillis = 1_000L,
                customEndMillis = Long.MIN_VALUE
            )
        )
    }

    @Test
    fun nothingStoredResolvesToNull() {
        assertNull(
            resolveStoredStatsRange(
                presetName = null,
                customStartMillis = Long.MIN_VALUE,
                customEndMillis = Long.MIN_VALUE
            )
        )
    }

    @Test
    fun presetWinsOverLeftoverCustomMillis() {
        // The store clears one when writing the other, but a preset must still win
        // over stale custom keys from an interrupted write.
        val resolved = resolveStoredStatsRange(
            presetName = "DAY_7",
            customStartMillis = 1_000L,
            customEndMillis = 2_000L
        )
        assertEquals(StatsRangeSelection.Preset(StatsTimeRange.DAY_7), resolved)
    }

    @Test
    fun restoredCustomRangeInsideAvailableDataIsKept() {
        val available = StatsDateRange(startMillis = 0L, endMillis = 10_000L)
        val restored = StatsDateRange(startMillis = 2_000L, endMillis = 8_000L)
        assertEquals(restored, clampStatsDateRangeToAvailable(restored, available))
    }

    @Test
    fun restoredCustomRangePartlyOutsideAvailableDataIsClamped() {
        val available = StatsDateRange(startMillis = 5_000L, endMillis = 10_000L)
        val restored = StatsDateRange(startMillis = 2_000L, endMillis = 8_000L)
        assertEquals(
            StatsDateRange(startMillis = 5_000L, endMillis = 8_000L),
            clampStatsDateRangeToAvailable(restored, available)
        )
    }

    @Test
    fun restoredCustomRangeFullyOutsideAvailableDataFallsBackToAvailable() {
        val available = StatsDateRange(startMillis = 5_000L, endMillis = 10_000L)
        val restored = StatsDateRange(startMillis = 1_000L, endMillis = 2_000L)
        assertEquals(available, clampStatsDateRangeToAvailable(restored, available))
    }
}
