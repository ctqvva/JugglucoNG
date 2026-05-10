package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentDisplaySourceTests {

    @Test
    fun resolveFromLive_usesMatchedHistoryRawInRawPrimaryMode() {
        val timestamp = 1_700_000_000_000L
        val recentPoints = listOf(GlucosePoint(timestamp, 75f, 31f))

        val snapshot = CurrentDisplaySource.resolveFromLive(
            liveValueText = null,
            liveNumericValue = 75f,
            rate = 0f,
            targetTimeMillis = timestamp,
            sensorId = "8760080A00070000",
            sensorGen = 0,
            index = 0,
            source = "test",
            recentPoints = recentPoints,
            viewMode = 1,
            isMmol = false
        )

        requireNotNull(snapshot)
        assertEquals(31f, snapshot.rawValue)
        assertEquals(31f, snapshot.primaryValue)
        assertEquals(75f, snapshot.autoValue)
    }

    @Test
    fun prepareRecentPointsForCurrent_smoothsLivePointBeforeTrendResolution() {
        val minute = 60_000L
        val recentPoints = listOf(
            GlucosePoint(0L * minute, 100f, 90f),
            GlucosePoint(5L * minute, 100f, 90f),
            GlucosePoint(10L * minute, 100f, 90f)
        )
        val current = CurrentGlucoseSource.Snapshot(
            timeMillis = 15L * minute,
            valueText = "",
            numericValue = 130f,
            rawNumericValue = Float.NaN,
            rate = 0f,
            sensorId = "test",
            sensorGen = 0,
            index = 0,
            source = "test"
        )

        val processed = CurrentDisplaySource.prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = 0L,
            viewMode = 0,
            smoothAllData = true,
            smoothingMinutes = 10,
            collapseChunks = false
        )

        assertEquals(15L * minute, processed.last().timestamp)
        assertEquals(115f, processed.last().value, 0.001f)
    }

    @Test
    fun prepareRecentPointsForCurrent_doesNotInjectAutoAsRawInLiveCandidate() {
        // The synthetic live candidate must not carry the live auto value in
        // its raw lane just because viewMode is raw-primary. Doing so makes
        // raw-mode displays render the auto value as if it were raw, and
        // raw+auto renders the same value twice — visible end-user breakage.
        // History's raw is preserved on exact-timestamp merges by
        // preferRicherLivePoint via the liveRawIsFallback flag, and findExactPoint
        // prefers real raw-bearing history points for non-exact insertions, so
        // dropping the fallback from the candidate is safe.
        val minute = 60_000L
        val recentPoints = listOf(
            GlucosePoint(0L, 100f, 80f),
            GlucosePoint(5L * minute, 101f, 82f)
        )
        val current = CurrentGlucoseSource.Snapshot(
            timeMillis = 10L * minute,
            valueText = "",
            numericValue = 90f,
            rawNumericValue = Float.NaN,
            rate = 0f,
            sensorId = "test",
            sensorGen = 0,
            index = 0,
            source = "test"
        )

        val processed = CurrentDisplaySource.prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = 0L,
            viewMode = 1,
            smoothAllData = false,
            smoothingMinutes = 0,
            collapseChunks = false
        )

        assertEquals(10L * minute, processed.last().timestamp)
        // Candidate carries auto only; rawValue remains 0/missing so the snapshot
        // resolver (via findExactPoint) prefers history's real raw lane.
        assertEquals(90f, processed.last().value, 0.001f)
        assertEquals(0f, processed.last().rawValue, 0.001f)
    }

    @Test
    fun resolveFromLive_prefersHistoryRawOverLiveCandidateInRawPrimaryMode() {
        // Mode 1 (raw): primary should be the real raw lane. With native Sibionics
        // the live candidate has no raw, and the previous behavior was to fall back
        // to the auto value — which surfaced as auto-shown-as-raw in the displayed
        // primary. After the fix the candidate carries no raw, and the snapshot
        // resolver picks the real history raw via findExactPoint.
        val historyTs = 1_700_000_000_000L
        val liveTs = historyTs + 30_000L
        val recentPoints = listOf(
            GlucosePoint(historyTs, 3.6f, 1.4f),
            GlucosePoint(liveTs, 3.6f, 0f) // synthetic candidate post-merge (no raw)
        )

        val snapshot = CurrentDisplaySource.resolveFromLive(
            liveValueText = null,
            liveNumericValue = 3.6f,
            rate = 0f,
            targetTimeMillis = liveTs,
            sensorId = "sibionics-test",
            sensorGen = 0,
            index = 0,
            source = "test",
            recentPoints = recentPoints,
            viewMode = 1,
            isMmol = true
        )

        requireNotNull(snapshot)
        // Primary in raw mode must be the real raw value, not the auto value.
        assertEquals(1.4f, snapshot.primaryValue, 0.001f)
        assertEquals(1.4f, snapshot.rawValue, 0.001f)
    }

    @Test
    fun resolveFromLive_prefersHistoryRawAndAutoInRawPlusAuto() {
        // Mode 3 (raw+auto): primary=raw, secondary=auto. With the old auto-as-raw
        // fallback the candidate had value==rawValue==auto, so the display rendered
        // the same number twice. After the fix history's distinct raw lane wins.
        val historyTs = 1_700_000_000_000L
        val liveTs = historyTs + 30_000L
        val recentPoints = listOf(
            GlucosePoint(historyTs, 3.6f, 1.4f),
            GlucosePoint(liveTs, 3.6f, 0f)
        )

        val snapshot = CurrentDisplaySource.resolveFromLive(
            liveValueText = null,
            liveNumericValue = 3.6f,
            rate = 0f,
            targetTimeMillis = liveTs,
            sensorId = "sibionics-test",
            sensorGen = 0,
            index = 0,
            source = "test",
            recentPoints = recentPoints,
            viewMode = 3,
            isMmol = true
        )

        requireNotNull(snapshot)
        assertEquals(1.4f, snapshot.primaryValue, 0.001f)
        assertEquals(1.4f, snapshot.rawValue, 0.001f)
        assertEquals(3.6f, snapshot.autoValue, 0.001f)
        // Both lanes must render distinct values.
        requireNotNull(snapshot.secondaryStr)
    }

    @Test
    fun resolveFromLive_prefersHistoryWithRawOverSyntheticLiveCandidateInAutoPlusRaw() {
        // Repro of the Sibionics screenshot bug: live current arrives at a slightly
        // later timestamp than the latest history row and carries no raw lane (native
        // Sibionics returns rawNumericValue=NaN). The merged points list ends with
        // both the real history (rich) and the synthetic candidate (lane-stripped).
        // findExactPoint must prefer the row that carries raw so the snapshot's
        // displayValues keeps the secondary lane in mode 2 — otherwise notification,
        // widgets, lock screen, AOD all drop the raw display.
        val historyTs = 1_700_000_000_000L
        val liveTs = historyTs + 30_000L
        val recentPoints = listOf(
            GlucosePoint(historyTs, 3.6f, 1.4f),
            GlucosePoint(liveTs, 3.6f, 0f) // synthetic candidate post-merge
        )

        val snapshot = CurrentDisplaySource.resolveFromLive(
            liveValueText = null,
            liveNumericValue = 3.6f,
            rate = 0f,
            targetTimeMillis = liveTs,
            sensorId = "sibionics-test",
            sensorGen = 0,
            index = 0,
            source = "test",
            recentPoints = recentPoints,
            viewMode = 2,
            isMmol = true
        )

        requireNotNull(snapshot)
        assertEquals(3.6f, snapshot.primaryValue, 0.001f)
        assertEquals(1.4f, snapshot.rawValue, 0.001f)
        // Secondary lane must survive — the bug surfaced as snapshot.secondaryStr == null,
        // which silently dropped the raw display in every consumer of Snapshot.displayValues.
        requireNotNull(snapshot.secondaryStr)
    }

    @Test
    fun prepareRecentPointsForCurrent_keepsFreshHistoryWhenLiveOverlaps() {
        val timestamp = 10L * 60_000L
        val recentPoints = listOf(GlucosePoint(timestamp, 101f, 82f))
        val current = CurrentGlucoseSource.Snapshot(
            timeMillis = timestamp,
            valueText = "",
            numericValue = 105f,
            rawNumericValue = Float.NaN,
            rate = 0f,
            sensorId = "test",
            sensorGen = 0,
            index = 0,
            source = "test"
        )

        val processed = CurrentDisplaySource.prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = 0L,
            viewMode = 1,
            smoothAllData = false,
            smoothingMinutes = 0,
            collapseChunks = false
        )

        assertEquals(101f, processed.last().value, 0.001f)
        assertEquals(82f, processed.last().rawValue, 0.001f)
    }

    @Test
    fun prepareRecentPointsForCurrent_keepsLiveRawWhenAutoRawHistoryHasOnlyAuto() {
        val timestamp = 10L * 60_000L
        val liveTimestamp = timestamp + 30_000L
        val recentPoints = listOf(GlucosePoint(timestamp, 4.7f, 0f))
        val current = CurrentGlucoseSource.Snapshot(
            timeMillis = liveTimestamp,
            valueText = "",
            numericValue = 4.7f,
            rawNumericValue = 3.5f,
            rate = 0f,
            sensorId = "anytime-test",
            sensorGen = 0,
            index = 0,
            source = "test"
        )

        val processed = CurrentDisplaySource.prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = 0L,
            viewMode = 2,
            smoothAllData = false,
            smoothingMinutes = 0,
            collapseChunks = false
        )

        assertEquals(liveTimestamp, processed.last().timestamp)
        assertEquals(4.7f, processed.last().value, 0.001f)
        assertEquals(3.5f, processed.last().rawValue, 0.001f)

        val snapshot = CurrentDisplaySource.resolveFromLive(
            liveValueText = null,
            liveNumericValue = 4.7f,
            rate = 0f,
            targetTimeMillis = liveTimestamp,
            sensorId = "anytime-test",
            sensorGen = 0,
            index = 0,
            source = "test",
            recentPoints = processed,
            viewMode = 2,
            isMmol = true
        )

        requireNotNull(snapshot)
        assertEquals(4.7f, snapshot.primaryValue, 0.001f)
        assertEquals(3.5f, snapshot.rawValue, 0.001f)
        requireNotNull(snapshot.secondaryStr)
    }
}
