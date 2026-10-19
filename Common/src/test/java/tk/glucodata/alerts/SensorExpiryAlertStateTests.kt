package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorExpiryAlertStateTests {

    // A sensor end far enough in the future that "N minutes before end" is always positive.
    private val end = 100L * 24 * 60 * 60 * 1000

    private fun min(m: Int) = m.toLong() * 60_000L

    /** Mirrors the SharedPreferences StringSet backing: flat "endTimeMs:minutes" entries. */
    private class FakeWarnedStore : ExpiryWarnedStore {
        var entries = setOf<String>()

        override fun load(endTimeMs: Long): Set<Int> = entries.mapNotNull { entry ->
            entry.takeIf { it.startsWith("$endTimeMs:") }?.substringAfter(':')?.toIntOrNull()
        }.toSet()

        override fun save(endTimeMs: Long, thresholds: Set<Int>) {
            entries = thresholds.map { "$endTimeMs:$it" }.toSet()
        }
    }

    private fun newState(store: ExpiryWarnedStore = FakeWarnedStore()) = SensorExpiryAlertState(store)

    /**
     * Evaluate a tick "minutesBefore" minutes before [endMs], with delivery
     * succeeding - what the runtime does when a reading is available.
     */
    private fun SensorExpiryAlertState.fire(
        minutesBefore: Int,
        thresholds: Set<Int>,
        endMs: Long = end,
        snoozed: Boolean = false,
        active: Boolean = true
    ): Set<Int> = fireUndelivered(minutesBefore, thresholds, endMs, snoozed, active)
        .also { triggered -> triggered.forEach { confirmDelivered(it) } }

    /** Same tick, but the alert never reached the user (no reading, suppressed). */
    private fun SensorExpiryAlertState.fireUndelivered(
        minutesBefore: Int,
        thresholds: Set<Int>,
        endMs: Long = end,
        snoozed: Boolean = false,
        active: Boolean = true
    ): Set<Int> = triggeredThresholds(
        enabled = true,
        activeNow = active,
        snoozed = snoozed,
        endTimeMs = endMs,
        nowMs = endMs - min(minutesBefore),
        thresholdsMinutes = thresholds
    )

    private val T3D = 4320
    private val T1D = 1440
    private val T6H = 360

    @Test
    fun singleThresholdFiresOnceOnEntry() {
        val s = newState()
        val t = setOf(T1D)
        assertEquals(emptySet<Int>(), s.fire(1800, t))     // 30h before: baseline, outside window
        assertEquals(emptySet<Int>(), s.fire(1500, t))     // 25h before: still outside
        assertEquals(setOf(T1D), s.fire(1440, t))          // 24h before: crosses in -> fire
        assertEquals(emptySet<Int>(), s.fire(1200, t))     // inside: no re-fire
        assertEquals(emptySet<Int>(), s.fire(60, t))
    }

    @Test
    fun firstObservationInsideUnwarnedWindowFiresCatchUpOnce() {
        val s = newState()
        assertEquals(setOf(T1D), s.fire(60, setOf(T1D)))        // due, never warned anywhere
        assertEquals(emptySet<Int>(), s.fire(30, setOf(T1D)))   // once only
    }

    @Test
    fun multipleThresholdsEachFireOnceInOrder() {
        val s = newState()
        val t = setOf(T3D, T1D, T6H)
        assertEquals(emptySet<Int>(), s.fire(5760, t))     // 4d before: baseline, outside all
        assertEquals(setOf(T3D), s.fire(4320, t))          // enter 3d
        assertEquals(emptySet<Int>(), s.fire(4000, t))     // inside 3d, no new edge
        assertEquals(setOf(T1D), s.fire(1440, t))          // enter 1d
        assertEquals(setOf(T6H), s.fire(360, t))           // enter 6h
        assertEquals(emptySet<Int>(), s.fire(30, t))
    }

    @Test
    fun baselineInsideSeveralUnwarnedWindowsFiresOnlyMostUrgent() {
        val s = newState()
        val t = setOf(T3D, T1D, T6H)
        assertEquals(setOf(T6H), s.fire(300, t))           // 5h before, inside all three: cascade guard
        assertEquals(emptySet<Int>(), s.fire(120, t))      // rest silently adopted
    }

    @Test
    fun newSensorRearmsAllThresholds() {
        val s = newState()
        val t = setOf(T1D)
        s.fire(1800, t)
        assertEquals(setOf(T1D), s.fire(1440, t))
        // A different end time = new sensor: the whole sequence runs again.
        val end2 = end + 14L * 24 * 60 * 60 * 1000
        assertEquals(emptySet<Int>(), s.fire(1800, t, endMs = end2))   // baseline for new sensor
        assertEquals(setOf(T1D), s.fire(1440, t, endMs = end2))
    }

    @Test
    fun addingThresholdInsideItsWindowDoesNotFireRetroactively() {
        val s = newState()
        s.fire(4320, setOf(T6H))                           // baseline outside the 6h window (with 6h only)
        // Now 3h before end, user adds the 1d threshold whose window opened long ago.
        val res = s.fire(180, setOf(T6H, T1D))
        assertEquals(setOf(T6H), res)                      // 6h crosses now and fires; 1d is NOT retroactive
    }

    @Test
    fun cascadeFiresOnlySmallestWhenSeveralComeDueAtOnce() {
        val s = newState()
        val t = setOf(T3D, T1D, T6H)
        s.fire(5760, t)                                    // baseline outside all
        // Big jump (e.g. app resumed) straight to 30 min before end.
        assertEquals(setOf(T6H), s.fire(30, t))            // only the most urgent fires
        assertEquals(emptySet<Int>(), s.fire(20, t))       // rest silently marked warned
    }

    @Test
    fun snoozeDefersButDoesNotSwallowThreshold() {
        val s = newState()
        val t = setOf(T3D, T1D)
        s.fire(5760, t)                                    // baseline outside
        assertEquals(setOf(T3D), s.fire(4320, t))          // 3d fires
        // Snoozed while the 1d window is crossed: nothing now...
        assertEquals(emptySet<Int>(), s.fire(1440, t, snoozed = true))
        // ...but once snooze ends it still fires (not swallowed).
        assertEquals(setOf(T1D), s.fire(1400, t))
    }

    @Test
    fun emptyOrDisabledFiresNothing() {
        val s = newState()
        assertEquals(emptySet<Int>(), s.fire(60, emptySet()))
        assertEquals(
            emptySet<Int>(),
            s.triggeredThresholds(false, true, false, end, end - min(60), setOf(T1D))
        )
    }

    @Test
    fun windowEntryDuringProcessDowntimeFiresCatchUpAfterRestart() {
        val store = FakeWarnedStore()
        val t = setOf(T3D)
        val s1 = SensorExpiryAlertState(store)
        assertEquals(emptySet<Int>(), s1.fire(5760, t))    // baseline outside; process dies here
        val s2 = SensorExpiryAlertState(store)             // restart after the 3d window opened
        assertEquals(setOf(T3D), s2.fire(4000, t))         // due but never warned -> catch-up
        assertEquals(emptySet<Int>(), s2.fire(3990, t))
        val s3 = SensorExpiryAlertState(store)             // yet another restart
        assertEquals(emptySet<Int>(), s3.fire(3980, t))    // persisted: no re-fire
    }

    @Test
    fun restartWithSeveralUnwarnedOpenWindowsFiresOnlyMostUrgent() {
        val store = FakeWarnedStore()
        val t = setOf(T3D, T1D)
        SensorExpiryAlertState(store).fire(5760, t)        // baseline outside both; process dies
        val s2 = SensorExpiryAlertState(store)
        assertEquals(setOf(T1D), s2.fire(1000, t))         // both open: cascade guard picks 1d
        val s3 = SensorExpiryAlertState(store)
        assertEquals(emptySet<Int>(), s3.fire(990, t))     // 3d was persisted-adopted, no late fire
    }

    @Test
    fun restartWhileSnoozedStillFiresCatchUpAfterSnoozeEnds() {
        val store = FakeWarnedStore()
        SensorExpiryAlertState(store).fire(5760, setOf(T3D))
        val s2 = SensorExpiryAlertState(store)
        assertEquals(emptySet<Int>(), s2.fire(4000, setOf(T3D), snoozed = true))
        assertEquals(setOf(T3D), s2.fire(3990, setOf(T3D)))
    }

    @Test
    fun midEpisodeActivationIsAdoptedAndSurvivesRestart() {
        val store = FakeWarnedStore()
        val s1 = SensorExpiryAlertState(store)
        s1.fire(5760, setOf(T6H))                                       // baseline with 6h only
        assertEquals(emptySet<Int>(), s1.fire(4000, setOf(T6H, T3D)))   // 3d added inside its window: adopted
        val s2 = SensorExpiryAlertState(store)                          // restart
        assertEquals(emptySet<Int>(), s2.fire(3990, setOf(T6H, T3D)))   // adoption persisted, still silent
        assertEquals(setOf(T6H), s2.fire(360, setOf(T6H, T3D)))         // 6h edge unaffected
    }

    @Test
    fun editingThresholdsMidEpisodeKeepsTheConfiguredOnesArmed() {
        // Field regression: the user had 3d/2d/1d/12h/1h configured, got the 3d
        // warning and nothing afterwards, while the persisted warned-set showed
        // every threshold marked for that sensor. Adding one threshold must not
        // silence the ones that were configured all along.
        val s = newState()
        val before = setOf(4320, 2880, 1440, 720, 60)
        val after = before + T6H
        assertEquals(emptySet<Int>(), s.fire(5760, before))
        assertEquals(setOf(4320), s.fire(4320, before))
        assertEquals(emptySet<Int>(), s.fire(2900, after))   // 6h added, its window still shut
        assertEquals(setOf(2880), s.fire(2880, after))
        assertEquals(setOf(1440), s.fire(1440, after))
        assertEquals(setOf(720), s.fire(720, after))
        assertEquals(setOf(T6H), s.fire(360, after))
        assertEquals(setOf(60), s.fire(60, after))
    }

    @Test
    fun reselectingAThresholdIsNotTreatedAsANewOne() {
        val store = FakeWarnedStore()
        val s1 = SensorExpiryAlertState(store)
        s1.fire(5760, setOf(T3D, T1D))                                  // baseline outside both
        assertEquals(setOf(T3D), s1.fire(4320, setOf(T3D, T1D)))
        assertEquals(emptySet<Int>(), s1.fire(2000, setOf(T3D)))        // 1d deselected
        assertEquals(emptySet<Int>(), s1.fire(1400, setOf(T3D)))        // its edge passes while off
        // Reselected inside the open window: not fired retroactively, but not
        // recorded as warned either - it never was.
        assertEquals(emptySet<Int>(), s1.fire(1300, setOf(T3D, T1D)))
        assertEquals(setOf("$end:$T3D"), store.entries)
        // ...so the usual catch-up still applies after a restart.
        assertEquals(setOf(T1D), SensorExpiryAlertState(store).fire(1290, setOf(T3D, T1D)))
    }

    @Test
    fun disableEnableCycleMidEpisodeLeavesLaterThresholdsArmed() {
        val s = newState()
        val t = setOf(4320, 2880, 1440, 720, 60)
        assertEquals(emptySet<Int>(), s.fire(5760, t))
        assertEquals(setOf(4320), s.fire(4320, t))
        // User switches the alert off and back on again.
        assertEquals(
            emptySet<Int>(),
            s.triggeredThresholds(false, true, false, end, end - min(4000), t)
        )
        assertEquals(emptySet<Int>(), s.fire(4000, t))    // re-enabled: 3d stays warned, nothing due
        assertEquals(setOf(2880), s.fire(2880, t))
        assertEquals(setOf(60), s.fire(60, t))
    }

    @Test
    fun onlyThresholdsAbsentFromThePreviousConfigCountAsNew() {
        val configured = setOf(4320, 2880, 1440, 720, 60)
        val nowMs = end - min(30)
        // Same set saved again (e.g. after an enable/disable cycle): nothing is new,
        // so no open window may be adopted.
        assertEquals(
            emptySet<Int>(),
            newlyOpenExpiryThresholds(configured, configured, end, nowMs)
        )
        // One threshold genuinely added while several windows are open: only that
        // one is adopted.
        assertEquals(
            setOf(T6H),
            newlyOpenExpiryThresholds(configured, configured + T6H, end, nowMs)
        )
    }

    @Test
    fun newThresholdIsAdoptedOnlyWhileItsWindowIsOpen() {
        val nowMs = end - min(300)   // 5h before the end
        assertEquals(
            setOf(T6H),
            newlyOpenExpiryThresholds(setOf(T3D), setOf(T3D, T6H), end, nowMs)
        )
        assertEquals(
            emptySet<Int>(),
            newlyOpenExpiryThresholds(setOf(T3D), setOf(T3D, 60), end, nowMs)
        )
        // No plausible sensor end: nothing to adopt against.
        assertEquals(
            emptySet<Int>(),
            newlyOpenExpiryThresholds(setOf(T3D), setOf(T3D, T6H), 0L, nowMs)
        )
    }

    @Test
    fun undeliveredWarningIsOfferedAgainInsteadOfBeingSwallowed() {
        // #98: no current glucose reading means the notification path cannot
        // deliver. The warning must survive that tick, not count as warned.
        val store = FakeWarnedStore()
        val s = SensorExpiryAlertState(store)
        s.fire(1800, setOf(T1D))
        assertEquals(setOf(T1D), s.fireUndelivered(1440, setOf(T1D)))   // edge, delivery fails
        assertEquals(setOf<String>(), store.entries)                    // nothing recorded as warned
        assertEquals(setOf(T1D), s.fireUndelivered(1400, setOf(T1D)))   // still owed
        assertEquals(setOf(T1D), s.fire(1300, setOf(T1D)))              // reading is back: delivered
        assertEquals(setOf("$end:$T1D"), store.entries)
        assertEquals(emptySet<Int>(), s.fire(1200, setOf(T1D)))         // and only once
    }

    @Test
    fun undeliveredWarningStillFiresAfterRestart() {
        val store = FakeWarnedStore()
        val s1 = SensorExpiryAlertState(store)
        s1.fire(1800, setOf(T1D))
        assertEquals(setOf(T1D), s1.fireUndelivered(1440, setOf(T1D)))  // never delivered, process dies
        assertEquals(setOf(T1D), SensorExpiryAlertState(store).fire(1430, setOf(T1D)))
    }

    @Test
    fun moreUrgentThresholdSupersedesAnUndeliveredOne() {
        val store = FakeWarnedStore()
        val s = SensorExpiryAlertState(store)
        s.fire(5760, setOf(T1D, T6H))
        assertEquals(setOf(T1D), s.fireUndelivered(1440, setOf(T1D, T6H)))  // 1d owed, undelivered
        // The 6h edge arrives first: it wins, and the stale 1d warning is dropped
        // rather than queued behind it.
        assertEquals(setOf(T6H), s.fire(360, setOf(T1D, T6H)))
        assertEquals(setOf("$end:$T1D", "$end:$T6H"), store.entries)
        assertEquals(emptySet<Int>(), s.fire(120, setOf(T1D, T6H)))
    }

    @Test
    fun newSensorRearmsAndPrunesOldPersistedEntries() {
        val store = FakeWarnedStore()
        val t = setOf(T1D)
        val s = SensorExpiryAlertState(store)
        s.fire(1800, t)
        assertEquals(setOf(T1D), s.fire(1440, t))                      // warned + persisted
        val end2 = end + 14L * 24 * 60 * 60 * 1000
        assertEquals(emptySet<Int>(), s.fire(1800, t, endMs = end2))   // baseline outside for new sensor
        assertEquals(setOf(T1D), s.fire(1440, t, endMs = end2))        // fires again for new sensor
        assertEquals(setOf("$end2:$T1D"), store.entries)               // old sensor's entry pruned
    }

    @Test
    fun stableEndTimeAcrossManyTicksKeepsLatchArmed() {
        // Regression for the getendtime() clamp: an end time that stays constant
        // must not re-baseline, and each edge fires exactly once.
        val s = newState()
        val t = setOf(T3D, T1D)
        assertEquals(emptySet<Int>(), s.fire(5760, t))
        for (m in 5700 downTo 4330 step 15) assertEquals(emptySet<Int>(), s.fire(m, t))
        assertEquals(setOf(T3D), s.fire(4320, t))
        for (m in 4305 downTo 1441 step 15) assertEquals(emptySet<Int>(), s.fire(m, t))
        assertEquals(setOf(T1D), s.fire(1440, t))
        for (m in 1425 downTo 15 step 15) assertEquals(emptySet<Int>(), s.fire(m, t))
    }

    @Test
    fun clampedOrPastEndTimeSourceIsRejected() {
        val now = end - min(1000)
        // A Natives.getendtime()-style source returns "now" while the sensor runs.
        assertEquals(0L, selectSensorExpiryEndMs(listOf("A" to now), "A", now))
        assertEquals(0L, selectSensorExpiryEndMs(listOf("A" to (now - 5_000L)), "A", now))
        assertEquals(0L, selectSensorExpiryEndMs(emptyList(), null, now))
    }

    @Test
    fun endSelectionPrefersDisplayedSensorElseFarthestEnd() {
        val now = 1_000_000L
        val candidates = listOf("A" to now + 100_000L, "B" to now + 900_000L)
        assertEquals(now + 100_000L, selectSensorExpiryEndMs(candidates, "A", now))
        assertEquals(now + 100_000L, selectSensorExpiryEndMs(candidates, "a", now))
        assertEquals(now + 900_000L, selectSensorExpiryEndMs(candidates, null, now))
        assertEquals(now + 900_000L, selectSensorExpiryEndMs(candidates, "unknown", now))
        // A displayed sensor without a plausible end must not shadow the running one.
        assertEquals(
            now + 900_000L,
            selectSensorExpiryEndMs(listOf("A" to 0L, "B" to now + 900_000L), "A", now)
        )
    }

    @Test
    fun defaultSensorExpiryConfigKeeps24hThreshold() {
        val cfg = AlertDefaults.defaultConfig(AlertType.SENSOR_EXPIRY, isMmol = false)
        assertEquals(setOf(1440), cfg.expiryWarningMinutes)
    }

    @Test
    fun sanitizeDropsUnknownThresholds() {
        assertEquals(setOf(1440, 360), sanitizeExpiryWarningMinutes(setOf(1440, 360, 999, 7)))
    }
}
