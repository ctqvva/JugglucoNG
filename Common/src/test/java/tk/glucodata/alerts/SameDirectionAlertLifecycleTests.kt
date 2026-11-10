package tk.glucodata.alerts

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tk.glucodata.Log

class SameDirectionAlertLifecycleTests {
    private val forecast = AlertType.PRE_LOW
    private val t0 = 1_000_000L
    private val window = 15 * 60_000L
    private val state = SameDirectionAlertSuppression()
    private var logging = false

    @Before fun setup() {
        logging = Log.doLog
        Log.doLog = false
        AlertStateTracker.consumeManualTestAction(forecast)
        AlertStateTracker.resetState(forecast)
        assertTrue(AlertStateTracker.onAlertTriggered(forecast))
        state.onFired(forecast, t0)
    }

    @After fun cleanup() {
        AlertStateTracker.consumeManualTestAction(forecast)
        AlertStateTracker.resetState(forecast)
        Log.doLog = logging
    }

    private fun blocker(at: Long) = state.blockedBy(
        AlertType.FALLING_FAST, at, window,
        isAcknowledged = AlertStateTracker::wasLastFiringAcknowledged
    )

    @Test fun dismissedForecastStillCoversFallingFastFourMinutesLaterAfterConditionClears() {
        assertTrue(AlertStateTracker.onAlertDismissed(forecast))
        assertEquals(forecast, blocker(t0 + 60_000L)?.type)

        // The runtime calls resetState when the forecast leaves its active conditions.
        AlertStateTracker.resetState(forecast)
        assertFalse(AlertStateTracker.isDismissed(forecast))
        assertFalse(AlertStateTracker.isEpisodeActive(forecast))

        assertEquals(forecast, blocker(t0 + 4 * 60_000L)?.type)
        assertEquals(forecast, blocker(t0 + window - 1)?.type)
        assertNull(blocker(t0 + window))
    }

    @Test fun dismissalAfterConditionClearsStillAcknowledgesTheDeliveredForecast() {
        AlertStateTracker.resetState(forecast)
        assertTrue(AlertStateTracker.onAlertDismissed(forecast))
        AlertStateTracker.resetState(forecast)

        assertEquals(forecast, blocker(t0 + 4 * 60_000L)?.type)
    }

    @Test fun acceptedSnoozeCoversTheRestOfTheWindowAfterItsOwnExpiry() {
        // SnoozeManager records this only for an accepted, non-preemptive snooze.
        AlertStateTracker.onAlertSnoozed(forecast)
        AlertStateTracker.resetState(forecast)

        // A five-minute snooze ending must not shorten the 15-minute quiet period.
        assertEquals(forecast, blocker(t0 + 6 * 60_000L)?.type)
        assertEquals(forecast, blocker(t0 + window - 1)?.type)
        assertNull(blocker(t0 + window))
    }

    @Test fun clearingAnUnacknowledgedForecastDoesNotTurnItIntoAnAcknowledgement() {
        AlertStateTracker.resetState(forecast)
        assertNull(blocker(t0 + 4 * 60_000L))
    }

    @Test fun aNewDeliveryDoesNotInheritAnOlderAcknowledgement() {
        AlertStateTracker.onAlertDismissed(forecast)
        AlertStateTracker.resetState(forecast)
        assertTrue(AlertStateTracker.onAlertTriggered(forecast))
        state.onFired(forecast, t0 + 6 * 60_000L)

        assertNull(blocker(t0 + 7 * 60_000L))
    }

    @Test fun acknowledgingAManualTestDoesNotAcknowledgeTheProductionForecast() {
        AlertStateTracker.allowNextTriggerForTest(forecast)
        assertTrue(AlertStateTracker.shouldTrigger(forecast, AlertConfig(type = forecast)))
        assertFalse(AlertStateTracker.onAlertTriggered(forecast))
        AlertStateTracker.onAlertSnoozed(forecast)
        assertNull(blocker(t0 + 60_000L))
        assertFalse(AlertStateTracker.onAlertDismissed(forecast))
        assertNull(blocker(t0 + 60_000L))

        assertTrue(AlertStateTracker.onAlertDismissed(forecast))
        assertEquals(forecast, blocker(t0 + 60_000L)?.type)
    }

    @Test fun risingAcknowledgementAlsoSurvivesEpisodeReset() {
        val rising = AlertType.PRE_HIGH
        try {
            AlertStateTracker.onAlertTriggered(rising)
            state.onFired(rising, t0)
            AlertStateTracker.onAlertDismissed(rising)
            AlertStateTracker.resetState(rising)

            assertEquals(rising, state.blockedBy(
                AlertType.RISING_FAST, t0 + 4 * 60_000L, window,
                isAcknowledged = AlertStateTracker::wasLastFiringAcknowledged
            )?.type)
        } finally {
            AlertStateTracker.resetState(rising)
        }
    }

    @Test fun runtimeUsesTheRememberedAcknowledgementAfterReset() {
        // Exercise the real delivery gate as well as the pure policy above.
        val field = AlertRuntimeManager::class.java.getDeclaredField("sameDirectionSuppression")
        field.isAccessible = true
        val runtimeState = field.get(AlertRuntimeManager) as SameDirectionAlertSuppression
        val gate = AlertRuntimeManager::class.java.getDeclaredMethod(
            "suppressedBySameDirectionAlertLocked", AlertType::class.java
        )
        gate.isAccessible = true
        try {
            runtimeState.clear()
            runtimeState.onFired(forecast, System.currentTimeMillis() - 4 * 60_000L)
            AlertStateTracker.onAlertDismissed(forecast)
            AlertStateTracker.resetState(forecast)

            // No Android preferences in a JVM test: the gate uses its five-minute fallback.
            assertEquals(true, gate.invoke(AlertRuntimeManager, AlertType.FALLING_FAST))
        } finally {
            runtimeState.clear()
        }
    }
}
