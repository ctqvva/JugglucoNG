package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardHistoryCollectionPolicyTests {

    @Test
    fun onlyTheHistoryRouteRunsTheUnboundedStream() {
        assertFalse(
            DashboardHistoryCollectionPolicy.runsUnboundedHistoryStream(
                DashboardViewModel.CollectionMode.DASHBOARD
            )
        )
        assertTrue(
            DashboardHistoryCollectionPolicy.runsUnboundedHistoryStream(
                DashboardViewModel.CollectionMode.FULL_HISTORY
            )
        )
    }

    @Test
    fun dashboardHistoryCoalescingSkipsOnlyInitialEmission() {
        assertFalse(
            DashboardHistoryCollectionPolicy.shouldCoalesceEmission(
                DashboardViewModel.CollectionMode.DASHBOARD,
                hasSeenHistoryEmission = false,
            )
        )
        assertTrue(
            DashboardHistoryCollectionPolicy.shouldCoalesceEmission(
                DashboardViewModel.CollectionMode.DASHBOARD,
                hasSeenHistoryEmission = true,
            )
        )
        assertFalse(
            DashboardHistoryCollectionPolicy.shouldCoalesceEmission(
                DashboardViewModel.CollectionMode.FULL_HISTORY,
                hasSeenHistoryEmission = true,
            )
        )
    }

    // ── history recovery ──────────────────────────────────────────────────────

    private companion object {
        const val MINUTE_MS = 60L * 1000L
        const val NOW = 1_700_000_000_000L
        const val COVERAGE_TOLERANCE_MS = 5L * MINUTE_MS
        const val TAIL_TOLERANCE_MS = 2L * MINUTE_MS
    }

    private fun shouldRecover(
        requiredStartMs: Long = 0L,
        oldestStoredMs: Long? = NOW - 60L * MINUTE_MS,
        latestStoredMs: Long? = NOW,
        currentSnapshotTimeMs: Long = NOW,
        snapshotIsSameSensor: Boolean = true
    ): Boolean = DashboardHistoryCollectionPolicy.shouldRequestHistoryRecovery(
        requiredStartMs = requiredStartMs,
        oldestStoredMs = oldestStoredMs,
        latestStoredMs = latestStoredMs,
        currentSnapshotTimeMs = currentSnapshotTimeMs,
        snapshotIsSameSensor = snapshotIsSameSensor,
        coverageToleranceMs = COVERAGE_TOLERANCE_MS,
        tailToleranceMs = TAIL_TOLERANCE_MS
    )

    @Test
    fun noStoredHistoryAtAllAsksForRecovery() {
        assertTrue(shouldRecover(oldestStoredMs = null, latestStoredMs = null))
    }

    @Test
    fun aStoredTailThatKeepsUpWithTheLiveReadingIsLeftAlone() {
        assertFalse(shouldRecover(latestStoredMs = NOW, currentSnapshotTimeMs = NOW))
    }

    @Test
    fun aStoredTailFallingBehindTheLiveReadingAsksForRecovery() {
        assertTrue(
            shouldRecover(
                latestStoredMs = NOW - 30L * MINUTE_MS,
                currentSnapshotTimeMs = NOW
            )
        )
    }

    /**
     * The regression the current-sensor tail exists to prevent.
     *
     * Before, this check was handed the dashboard's drawn history. Once that
     * became a merged cross-sensor list, a retired sensor's rows could sit at the
     * end of it — so a live sensor 30 minutes behind looked current, and no
     * re-sync was ever requested. Passing only the current sensor's own rows is
     * what makes the stale case visible again; the merged tail must not be able
     * to answer for it.
     */
    @Test
    fun aReadingFromAnotherSensorCannotVouchForThisOne() {
        // Same numbers as the falling-behind case, but the live reading belongs
        // to a different sensor: it is no longer evidence either way, so the
        // check must not silently conclude "up to date" from it.
        assertFalse(
            shouldRecover(
                latestStoredMs = NOW - 30L * MINUTE_MS,
                currentSnapshotTimeMs = NOW,
                snapshotIsSameSensor = false
            )
        )
    }

    @Test
    fun aNewSensorWithNoOldRowsIsNotTreatedAsMissingCoverage() {
        // requiredStartMs 0 means "no coverage requirement" — a sensor installed
        // an hour ago legitimately has nothing older.
        assertFalse(
            shouldRecover(
                requiredStartMs = 0L,
                oldestStoredMs = NOW - 60L * MINUTE_MS
            )
        )
    }

    @Test
    fun historyThatDoesNotReachTheRequiredStartAsksForRecovery() {
        assertTrue(
            shouldRecover(
                requiredStartMs = NOW - 24L * 60L * MINUTE_MS,
                oldestStoredMs = NOW - 60L * MINUTE_MS
            )
        )
    }
}
