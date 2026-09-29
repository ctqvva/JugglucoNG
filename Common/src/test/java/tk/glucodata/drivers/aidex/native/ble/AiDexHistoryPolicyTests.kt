package tk.glucodata.drivers.aidex.native.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexHistoryPolicyTests {

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
    fun planInitialDownload_rewindsRawToBriefWhenBriefNeverStarted() {
        // A drop during the 0x23 phase: raw persisted mid-ring, brief never persisted at all.
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 1,
            rawStart = 1,
            newest = 5_010,
            persistedRawNextIndex = 3_000,
            persistedBriefNextIndex = 0,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.REQUEST_RAW, plan.action)
        assertEquals(1, plan.rawNextIndex)
        assertEquals(1, plan.briefNextIndex)
        assertEquals(1, plan.requestOffset)
    }

    @Test
    fun planInitialDownload_rewindsCaughtUpRawToBriefWhenRawCacheIsEmpty() {
        // 0x23 finished (raw = newest + 1), 0x24 was cut short, and the cached 0x23 rows went with
        // the teardown: 0x24 cannot merge without them.
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 1,
            rawStart = 1,
            newest = 5_010,
            persistedRawNextIndex = 5_011,
            persistedBriefNextIndex = 4_000,
            rawCacheEmpty = true,
            wearDays = 15,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.REQUEST_RAW, plan.action)
        assertEquals(4_000, plan.rawNextIndex)
        assertEquals(4_000, plan.briefNextIndex)
        assertEquals(4_000, plan.requestOffset)
    }

    @Test
    fun planInitialDownload_keepsExpiredRawCursorCaughtUpEvenWithEmptyCache() {
        // The 5b88ece7 case with the cache gone: the brief tail lies past the wear duration.
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 1,
            rawStart = 1,
            newest = 22_039,
            persistedRawNextIndex = 22_041,
            persistedBriefNextIndex = 21_668,
            rawCacheEmpty = true,
            wearDays = 15,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.REQUEST_BRIEF, plan.action)
        assertEquals(22_041, plan.rawNextIndex)
        assertEquals(21_668, plan.briefNextIndex)
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
    fun shouldQuarantinePostResetHistoryRange_doesNotQuarantineWhenClockIsBehindTheBarrier() {
        assertFalse(
            AiDexHistoryPolicy.shouldQuarantinePostResetHistoryRange(
                newestOffsetMinutes = 21_256,
                resetRequestedAtMs = 2_000_000L,
                nowMs = 1_000_000L,
            )
        )
    }

    @Test
    fun rawCursorAfterUnalignedPage_retriesOnceThenAcceptsTheSensorPage() {
        val stayed = AiDexHistoryPolicy.rawCursorAfterUnalignedPage(
            requestedOffset = 100,
            firstEntryOffset = 150,
            proposedNext = 160,
            gapAlreadyRetried = false,
        )
        assertEquals(100 to true, stayed)

        val accepted = AiDexHistoryPolicy.rawCursorAfterUnalignedPage(
            requestedOffset = 100,
            firstEntryOffset = 150,
            proposedNext = 160,
            gapAlreadyRetried = true,
        )
        assertEquals(160 to false, accepted)

        val aligned = AiDexHistoryPolicy.rawCursorAfterUnalignedPage(
            requestedOffset = 150,
            firstEntryOffset = 150,
            proposedNext = 160,
            gapAlreadyRetried = true,
        )
        assertEquals(160 to false, aligned)
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
        // The offset has to move the stamp off observedAtMs, otherwise both branches
        // return the same number and the test cannot tell them apart.
        val resolved = AiDexHistoryPolicy.resolveOffsetBackedTimestampMs(
            observedAtMs = 1_000_000L,
            sensorStartMs = 100_000L,
            offsetMinutes = 10,
        )

        assertEquals(700_000L, resolved)
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

    @Test
    fun shouldStampDirectLiveForDedupe_onlyTrustedPositiveOffset() {
        assertFalse(AiDexHistoryPolicy.shouldStampDirectLiveForDedupe(null))
        assertFalse(AiDexHistoryPolicy.shouldStampDirectLiveForDedupe(0))
        assertTrue(AiDexHistoryPolicy.shouldStampDirectLiveForDedupe(14400))
    }

    @Test
    fun shouldSnapLiveCutoffToNewest_neverSnapsUntrustedLive() {
        assertFalse(
            AiDexHistoryPolicy.shouldSnapLiveCutoffToNewest(
                liveOffsetCutoff = 0,
                lastDirectLiveReadingTimeMs = 1_000L,
                newest = 14518,
                trustedOffsetWasUsed = false,
            )
        )
        assertFalse(
            AiDexHistoryPolicy.shouldSnapLiveCutoffToNewest(
                liveOffsetCutoff = 0,
                lastDirectLiveReadingTimeMs = 1_000L,
                newest = 14518,
                trustedOffsetWasUsed = true,
            )
        )
    }

    // -- planInitialDownload: brief cursor ahead of raw --

    @Test
    fun planInitialDownload_healsBriefAheadOfRaw_failsOnHead() {
        // HEAD kept brief = 5011: 0x23 re-caches 3000..6000, then 0x24 restarts at 5011 and the
        // minutes 3000..5010 are never merged against the rows 0x23 just delivered.
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 1,
            rawStart = 1,
            newest = 6_000,
            persistedRawNextIndex = 3_000,
            persistedBriefNextIndex = 5_011,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.REQUEST_RAW, plan.action)
        assertEquals(3_000, plan.rawNextIndex)
        assertEquals(3_000, plan.briefNextIndex)
        assertEquals(3_000, plan.requestOffset)
        assertEquals(3_000, plan.downloadStartIndex)
    }

    @Test
    fun planInitialDownload_healStopsAtBriefStart() {
        // 0x24's ring starts at 4000, so the healed brief cursor is max(raw 3000, briefStart 4000).
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 4_000,
            rawStart = 1,
            newest = 6_000,
            persistedRawNextIndex = 3_000,
            persistedBriefNextIndex = 5_011,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.REQUEST_RAW, plan.action)
        assertEquals(3_000, plan.rawNextIndex)
        assertEquals(4_000, plan.briefNextIndex)
    }

    @Test
    fun planInitialDownload_leavesBriefAheadOfCaughtUpRaw() {
        // Pin: raw 6001 > newest 6000 means no 0x23 pass follows, so there is nothing to heal against.
        val plan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = 1,
            rawStart = 1,
            newest = 6_000,
            persistedRawNextIndex = 6_001,
            persistedBriefNextIndex = 6_005,
        )

        assertEquals(AiDexHistoryPolicy.InitialAction.COMPLETE_ALREADY_CAUGHT_UP, plan.action)
        assertEquals(6_001, plan.rawNextIndex)
        assertEquals(6_005, plan.briefNextIndex)
    }

    // -- Empty 0x23 page and the brief cursor cap --

    @Test
    fun decideEmptyRawPage_newestZeroMatchesHead() {
        // newest 0: no retry, cap = 0 + 1 = 1, i.e. HEAD's brief <= newest.
        assertEquals(
            AiDexHistoryPolicy.EmptyRawPageAction.START_BRIEF,
            AiDexHistoryPolicy.decideEmptyRawPage(
                rawNextIndex = 1,
                briefNextIndex = 0,
                newestOffset = 0,
                emptyPageRetried = false,
            )
        )
        assertEquals(
            AiDexHistoryPolicy.EmptyRawPageAction.COMPLETE,
            AiDexHistoryPolicy.decideEmptyRawPage(
                rawNextIndex = 1,
                briefNextIndex = 1,
                newestOffset = 0,
                emptyPageRetried = false,
            )
        )
    }

    @Test
    fun decideEmptyRawPage_rawCaughtUpMatchesHead() {
        // raw 501 > newest 500: no retry, cap = min(501, 500 + 1) = 501.
        assertEquals(
            AiDexHistoryPolicy.EmptyRawPageAction.START_BRIEF,
            AiDexHistoryPolicy.decideEmptyRawPage(
                rawNextIndex = 501,
                briefNextIndex = 100,
                newestOffset = 500,
                emptyPageRetried = false,
            )
        )
        assertEquals(
            AiDexHistoryPolicy.EmptyRawPageAction.COMPLETE,
            AiDexHistoryPolicy.decideEmptyRawPage(
                rawNextIndex = 501,
                briefNextIndex = 501,
                newestOffset = 500,
                emptyPageRetried = false,
            )
        )
    }

    @Test
    fun decideEmptyRawPage_retriesMidRingPageOnce_failsOnHead() {
        // HEAD: brief 100 <= newest 500 started 0x24, which then ran over 300..500 without 0x23 rows.
        assertEquals(
            AiDexHistoryPolicy.EmptyRawPageAction.RETRY_RAW,
            AiDexHistoryPolicy.decideEmptyRawPage(
                rawNextIndex = 300,
                briefNextIndex = 100,
                newestOffset = 500,
                emptyPageRetried = false,
            )
        )
    }

    @Test
    fun decideEmptyRawPage_afterRetryBriefRunsOnlyUpToRaw() {
        // cap = min(300, 500 + 1) = 300.
        assertEquals(
            AiDexHistoryPolicy.EmptyRawPageAction.START_BRIEF,
            AiDexHistoryPolicy.decideEmptyRawPage(
                rawNextIndex = 300,
                briefNextIndex = 100,
                newestOffset = 500,
                emptyPageRetried = true,
            )
        )
        // failsOnHead: HEAD started 0x24 here (300 <= 500).
        assertEquals(
            AiDexHistoryPolicy.EmptyRawPageAction.COMPLETE,
            AiDexHistoryPolicy.decideEmptyRawPage(
                rawNextIndex = 300,
                briefNextIndex = 300,
                newestOffset = 500,
                emptyPageRetried = true,
            )
        )
    }

    @Test
    fun briefCursorCap_isRawBoundedByNewestPlusOne() {
        assertEquals(501, AiDexHistoryPolicy.briefCursorCap(rawNextIndex = 501, newestOffset = 500))
        assertEquals(300, AiDexHistoryPolicy.briefCursorCap(rawNextIndex = 300, newestOffset = 500))
        assertEquals(501, AiDexHistoryPolicy.briefCursorCap(rawNextIndex = 700, newestOffset = 500))
        assertEquals(1, AiDexHistoryPolicy.briefCursorCap(rawNextIndex = 5, newestOffset = 0))
    }

    @Test
    fun nextBriefCursor_neverPassesCap_failsOnHead() {
        // 0x24 page 4990..5036 (47 rows), newest 5000, raw 5001: cap = min(5001, 5000 + 1) = 5001.
        val cap = AiDexHistoryPolicy.briefCursorCap(rawNextIndex = 5_001, newestOffset = 5_000)
        val page = (4_990..5_036).toList()

        assertEquals(5_001, AiDexHistoryPolicy.nextBriefCursor(lastRowOffset = page.last(), cap = cap)) // HEAD: 5037
        assertEquals((4_990..5_000).toList(), AiDexHistoryPolicy.rowsBelowCap(page, cap) { it })
    }

    @Test
    fun nextBriefCursor_followsLastRowBelowCap() {
        assertEquals(147, AiDexHistoryPolicy.nextBriefCursor(lastRowOffset = 146, cap = 5_001))
        val page = (100..146).toList()
        assertEquals(page, AiDexHistoryPolicy.rowsBelowCap(page, 5_001) { it })
    }

    @Test
    fun rowsBelowCap_dropsRowsAtOrAboveCapAndKeepsOrder() {
        assertEquals(
            (290..299).toList(),
            AiDexHistoryPolicy.rowsBelowCap((290..310).toList(), 300) { it },
        )
        assertEquals(
            listOf(290, 299, 291),
            AiDexHistoryPolicy.rowsBelowCap(listOf(305, 290, 299, 300, 291), 300) { it },
        )
    }

    // -- historyStoreRejection: the per-row checks of storeHistoryEntries --

    private val storeStartMs = 1_700_000_000_000L
    private val storeNowMs = storeStartMs + 1_000L * 60_000L // "now" is offset 1000

    private fun rejection(
        offset: Int = 500,
        glucose: Float = 100f,
        valid: Boolean = true,
        nowMs: Long = storeNowMs,
        wearDays: Int? = null,
        newest: Int = 0,
        liveCutoff: Int = 0,
    ) = AiDexHistoryPolicy.historyStoreRejection(
        offsetMinutes = offset,
        glucoseMgDl = glucose,
        isValid = valid,
        sensorStartMs = storeStartMs,
        nowMs = nowMs,
        wearDays = wearDays,
        historyNewestOffset = newest,
        liveOffsetCutoff = liveCutoff,
    )

    @Test
    fun historyStoreRejection_storesPlainRow() {
        assertNull(rejection())
    }

    @Test
    fun historyStoreRejection_invalidFirst() {
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.INVALID, rejection(valid = false))
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.INVALID, rejection(offset = 0, glucose = 0f, valid = false))
    }

    @Test
    fun historyStoreRejection_offsetBounds() {
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.NONPOSITIVE_OFFSET, rejection(offset = 0))
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.NONPOSITIVE_OFFSET, rejection(offset = -1))
        assertNull(rejection(offset = 1))
        // No warmup gate: the deleted HistoryMerge.filterForStorage dropped offsets 1..6.
        assertNull(rejection(offset = 3))

        // 30 days = 43200 minutes; "now" 31 days in keeps the future check out of it.
        val laterNowMs = storeStartMs + 31L * 24 * 60 * 60_000L
        assertNull(rejection(offset = 43_200, nowMs = laterNowMs))
        assertEquals(
            AiDexHistoryPolicy.HistoryStoreRejection.OFFSET_TOO_LARGE,
            rejection(offset = 43_201, nowMs = laterNowMs),
        )
        // Order: the 30-day bound comes before the wear duration.
        assertEquals(
            AiDexHistoryPolicy.HistoryStoreRejection.OFFSET_TOO_LARGE,
            rejection(offset = 43_201, nowMs = laterNowMs, wearDays = 15),
        )
    }

    @Test
    fun historyStoreRejection_wearDuration() {
        // 15 days = 21600 minutes; "now" 16 days in.
        val nowMs = storeStartMs + 16L * 24 * 60 * 60_000L
        assertNull(rejection(offset = 21_599, nowMs = nowMs, wearDays = 15))
        assertEquals(
            AiDexHistoryPolicy.HistoryStoreRejection.PAST_WEAR,
            rejection(offset = 21_600, nowMs = nowMs, wearDays = 15),
        )
        assertNull(rejection(offset = 21_600, nowMs = nowMs, wearDays = null))
        // Order: wear before newest.
        assertEquals(
            AiDexHistoryPolicy.HistoryStoreRejection.PAST_WEAR,
            rejection(offset = 21_600, nowMs = nowMs, wearDays = 15, newest = 21_000),
        )
    }

    @Test
    fun historyStoreRejection_newestOffset() {
        assertNull(rejection(offset = 1_000, newest = 1_000))
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.PAST_NEWEST, rejection(offset = 1_001, newest = 1_000))
        // newest 0 = no limit (offset 1001 is now + 60 s, inside the 2-minute slack).
        assertNull(rejection(offset = 1_001, newest = 0))
        // Order: newest before the live dedupe.
        assertEquals(
            AiDexHistoryPolicy.HistoryStoreRejection.PAST_NEWEST,
            rejection(offset = 500, newest = 499, liveCutoff = 500),
        )
    }

    @Test
    fun historyStoreRejection_liveDedupeOnlyExactMinute() {
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.LIVE_DEDUPE, rejection(offset = 500, liveCutoff = 500))
        assertNull(rejection(offset = 499, liveCutoff = 500))
        assertNull(rejection(offset = 501, liveCutoff = 500))
        // Order: the dedupe comes before the glucose checks.
        assertEquals(
            AiDexHistoryPolicy.HistoryStoreRejection.LIVE_DEDUPE,
            rejection(offset = 500, glucose = 0f, liveCutoff = 500),
        )
    }

    @Test
    fun historyStoreRejection_glucoseBounds() {
        assertNull(rejection(glucose = 20f))
        assertNull(rejection(glucose = 500f))
        assertNull(rejection(glucose = 500.9f)) // toInt() = 500
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.OUT_OF_RANGE, rejection(glucose = 19.9f))
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.OUT_OF_RANGE, rejection(glucose = 501f))
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.OUT_OF_RANGE, rejection(glucose = 0f))
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.OUT_OF_RANGE, rejection(glucose = 1_022.9f))
        // Order: 1023 and above is also out of range, but reported as the sentinel.
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.SENTINEL, rejection(glucose = 1_023f))
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.SENTINEL, rejection(glucose = 2_000f))
    }

    @Test
    fun historyStoreRejection_futureSlackIsTwoMinutes() {
        // Offset 1002 = now + 120 s: stored. Offset 1003 = now + 180 s: rejected.
        assertNull(rejection(offset = 1_002))
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.FUTURE, rejection(offset = 1_003))
        // Order: the glucose checks come before the future check.
        assertEquals(AiDexHistoryPolicy.HistoryStoreRejection.OUT_OF_RANGE, rejection(offset = 1_003, glucose = 0f))
    }
}
