package tk.glucodata.drivers.aidex.native.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexHistoryPolicyTests {

    @Test fun sourceRepairRewindsBothTracksAfterStockReleaseAdvancedTheirCursors() {
        val start = 1_791_584_291_000L
        val missing = start + 6358L * 60_000L
        val offset = AiDexHistoryPolicy.sourceRepairOffset(missing, start, 1, 1, 6489)
        assertEquals(6358, offset)
        val plan = AiDexHistoryPolicy.planInitialDownload(1, 1, 6489, 6479, 6479, offset)
        assertEquals(AiDexHistoryPolicy.InitialAction.REQUEST_RAW, plan.action)
        assertEquals(6358, plan.rawNextIndex)
        assertEquals(6358, plan.briefNextIndex)
        // Reconnect still repairs the hole even if an interrupted repair advanced cursors.
        assertEquals(6358, AiDexHistoryPolicy.planInitialDownload(1, 1, 6499, 6490, 6490, offset).requestOffset)
    }

    @Test fun sourceRepairMatchesWallClockMinuteAndRejectsUnavailableSessions() {
        val start = 1_791_584_291_000L
        val roundedRoomTime = (start + 6358L * 60_000L) / 60_000L * 60_000L
        assertEquals(6358, AiDexHistoryPolicy.sourceRepairOffset(roundedRoomTime, start, 1, 1, 6489))
        assertNull(AiDexHistoryPolicy.sourceRepairOffset(start - 60_000L, start, 1, 1, 6489))
        assertNull(AiDexHistoryPolicy.sourceRepairOffset(start + 60_000L, start, 2, 1, 6489))
        assertNull(AiDexHistoryPolicy.sourceRepairOffset(start + 60_000L, start, 1, 2, 6489))
        assertNull(AiDexHistoryPolicy.sourceRepairOffset(start + 6490L * 60_000L, start, 1, 1, 6489))
        assertNull(AiDexHistoryPolicy.sourceRepairOffset(roundedRoomTime, 0L, 1, 1, 6489))
        assertEquals(AiDexHistoryPolicy.InitialAction.COMPLETE_ALREADY_CAUGHT_UP,
            AiDexHistoryPolicy.planInitialDownload(1, 1, 6489, 6490, 6490, 0).action)
    }

    @Test
    fun planInitialDownload_completesImmediatelyForEmptyRange() {
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 0,
            rawStart = 0,
            newest = 0,
            persistedRawNextIndex = 0,
            persistedBriefNextIndex = 0,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.COMPLETE_EMPTY, plan.action)
        assertEquals(0, plan.rawNextIndex)
        assertEquals(0, plan.briefNextIndex)
    }

    @Test
    fun planInitialDownload_rewindsPersistedOffsetsThatAreAheadOfNewest() {
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 10,
            rawStart = 12,
            newest = 40,
            persistedRawNextIndex = 120,
            persistedBriefNextIndex = 140,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.REQUEST_RAW, plan.action)
        assertEquals(12, plan.rawNextIndex)
        assertEquals(10, plan.briefNextIndex)
        assertEquals(12, plan.requestOffset)
    }

    @Test
    fun planInitialDownload_skipsToBriefWhenRawIsAlreadyCaughtUp() {
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 10,
            rawStart = 12,
            newest = 40,
            persistedRawNextIndex = 41,
            persistedBriefNextIndex = 20,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.REQUEST_BRIEF, plan.action)
        assertEquals(41, plan.rawNextIndex)
        assertEquals(20, plan.briefNextIndex)
        assertEquals(20, plan.requestOffset)
    }

    @Test
    fun planInitialDownload_keepsExpiredRawCursorCaughtUpWhenBriefLags() {
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 1,
            rawStart = 1,
            newest = 22_039,
            persistedRawNextIndex = 22_041,
            persistedBriefNextIndex = 21_668,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.REQUEST_BRIEF, plan.action)
        assertEquals(22_041, plan.rawNextIndex)
        assertEquals(21_668, plan.briefNextIndex)
        assertEquals(21_668, plan.requestOffset)
    }

    @Test
    fun planInitialDownload_completesWhenBothTracksAreAlreadyCaughtUp() {
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 10,
            rawStart = 12,
            newest = 40,
            persistedRawNextIndex = 41,
            persistedBriefNextIndex = 41,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.COMPLETE_ALREADY_CAUGHT_UP, plan.action)
    }

    @Test
    fun shouldEmitCatchUpBroadcast_onlyWhenHistoryIsNewerThanLiveCutoff() {
        assertTrue(
            AiDexHistoryPolicy.shouldEmitCatchUpBroadcast(
                lastHistoryNewestGlucose = 100f,
                lastHistoryNewestOffset = 50,
                liveOffsetCutoff = 49,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.shouldEmitCatchUpBroadcast(
                lastHistoryNewestGlucose = 100f,
                lastHistoryNewestOffset = 50,
                liveOffsetCutoff = 50,
            )
        )
    }

    @Test
    fun shouldSkipHistoryEntryForLiveDedupe_onlySkipsExactLiveOffset() {
        assertTrue(
            AiDexHistoryPolicy.shouldSkipHistoryEntryForLiveDedupe(
                entryOffsetMinutes = 469,
                liveOffsetCutoff = 469,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.shouldSkipHistoryEntryForLiveDedupe(
                entryOffsetMinutes = 470,
                liveOffsetCutoff = 469,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.shouldSkipHistoryEntryForLiveDedupe(
                entryOffsetMinutes = 468,
                liveOffsetCutoff = 469,
            )
        )
    }

    @Test
    fun wearDurationMinutes_usesDeclaredWearDays() {
        assertNull(AiDexHistoryPolicy.wearDurationMinutes(null))
        assertNull(AiDexHistoryPolicy.wearDurationMinutes(0))
        assertEquals(14_400L, AiDexHistoryPolicy.wearDurationMinutes(10))
        assertEquals(20_160L, AiDexHistoryPolicy.wearDurationMinutes(14))
        assertEquals(21_600L, AiDexHistoryPolicy.wearDurationMinutes(15))
        assertEquals(23_040L, AiDexHistoryPolicy.wearDurationMinutes(16))
    }

    @Test
    fun isWithinWearDuration_rejectsPostExpiryOffsets() {
        assertTrue(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 14_399,
                wearDays = 10,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 14_400,
                wearDays = 10,
            )
        )
        assertTrue(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 20_159,
                wearDays = 14,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 20_160,
                wearDays = 14,
            )
        )
        assertTrue(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 21_599,
                wearDays = 15,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 21_600,
                wearDays = 15,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 21_668,
                wearDays = 15,
            )
        )
        assertTrue(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 23_039,
                wearDays = 16,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 23_040,
                wearDays = 16,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = -1,
                wearDays = 15,
            )
        )
        assertTrue(
            AiDexHistoryPolicy.isWithinWearDuration(
                offsetMinutes = 21_668,
                wearDays = null,
            )
        )
    }

    @Test
    fun shouldQuarantinePostResetHistoryRange_detectsOldResidueAfterFreshReset() {
        assertTrue(
            AiDexHistoryPolicy.shouldQuarantinePostResetHistoryRange(
                newestOffsetMinutes = 21_256,
                resetRequestedAtMs = 1_000_000L,
                nowMs = 1_060_000L,
            )
        )
    }

    @Test
    fun shouldQuarantinePostResetHistoryRange_allowsPlausibleNewSessionOffsets() {
        assertFalse(
            AiDexHistoryPolicy.shouldQuarantinePostResetHistoryRange(
                newestOffsetMinutes = 12,
                resetRequestedAtMs = 1_000_000L,
                nowMs = 1_060_000L,
            )
        )
    }

    @Test
    fun resolveOffsetBackedTimestampMs_prefersAlignedTimestampWhenStartAndOffsetAreKnown() {
        val resolved = AiDexHistoryPolicy.resolveOffsetBackedTimestampMs(
            observedAtMs = 1_000_000L,
            sensorStartMs = 100_000L,
            offsetMinutes = 15,
        )

        assertEquals(1_000_000L, resolved)
    }

    @Test
    fun resolveOffsetBackedTimestampMs_fallsBackWhenOffsetTimestampWouldBeTooFarInFuture() {
        val resolved = AiDexHistoryPolicy.resolveOffsetBackedTimestampMs(
            observedAtMs = 1_000_000L,
            sensorStartMs = 900_000L,
            offsetMinutes = 10,
        )

        assertEquals(1_000_000L, resolved)
    }

    @Test
    fun shouldAcceptRealtimeTimestamp_rejectsObservedHistoryRegression() {
        assertFalse(
            AiDexHistoryPolicy.shouldAcceptRealtimeTimestamp(
                candidateTimeMs = 1_782_006_372_000L,
                latestAcceptedTimeMs = 1_782_133_332_000L,
            )
        )
    }

    @Test
    fun shouldAcceptRealtimeTimestamp_allowsDuplicateAndNewerLiveSamples() {
        assertTrue(AiDexHistoryPolicy.shouldAcceptRealtimeTimestamp(2_000L, 2_000L))
        assertTrue(AiDexHistoryPolicy.shouldAcceptRealtimeTimestamp(2_001L, 2_000L))
    }
}
