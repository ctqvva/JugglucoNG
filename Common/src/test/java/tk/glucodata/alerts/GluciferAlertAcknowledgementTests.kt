package tk.glucodata.alerts

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tk.glucodata.GluciferPayload
import tk.glucodata.Log

class GluciferAlertAcknowledgementTests {
    private val type = AlertType.PRE_LOW
    private var previousLogging = false

    @Before fun setUp() {
        previousLogging = Log.doLog
        Log.doLog = false
        AlertStateTracker.resetState(type)
    }

    @After fun tearDown() {
        AlertStateTracker.consumeManualTestAction(type)
        AlertStateTracker.resetState(type)
        Log.doLog = previousLogging
    }

    private fun snapshot(sequence: Long) = GluciferPayload.build(
        sourceId = "acknowledgement-test", sequence = sequence,
        nowMs = 1_788_696_000_000L + sequence, glucoseTimeMs = 1_788_696_000_000L,
        mgdl = 123, selected = setOf("alert:pre_low"), values = emptyMap(),
        alertStates = mapOf("pre_low" to AlertStateTracker.activeEpisodeForExport(type))
    )

    @Test fun dismissSendsOffWithTheSameGlucoseReading() {
        assertTrue(AlertStateTracker.onAlertTriggered(type))
        val fired = snapshot(1)
        assertTrue(fired.getJSONObject("alerts").getBoolean("pre_low"))

        assertTrue(AlertStateTracker.onAlertDismissed(type))
        val acknowledged = snapshot(2)
        assertFalse(acknowledged.getJSONObject("alerts").getBoolean("pre_low"))
        assertEquals(fired.getJSONObject("glucose").toString(), acknowledged.getJSONObject("glucose").toString())
        assertSame(acknowledged, GluciferPayload.nextDelivery(fired, acknowledged, false, false))

        // Repeated acknowledgement does not create another changed payload.
        assertTrue(AlertStateTracker.onAlertDismissed(type))
        assertNull(GluciferPayload.nextDelivery(acknowledged, snapshot(3), false, false))
    }

    @Test fun snoozeResetClearsAndAnActualNewFiringTurnsItOnAgain() {
        AlertStateTracker.onAlertTriggered(type)
        // The phone activity and phone/watch snooze receivers already use this reset.
        AlertStateTracker.resetState(type)
        assertEquals(false, AlertStateTracker.activeEpisodeForExport(type))
        AlertStateTracker.onAlertTriggered(type)
        assertEquals(true, AlertStateTracker.activeEpisodeForExport(type))
    }

    @Test fun acknowledgedEpisodeStaysOffUntilAnotherActualFiring() {
        AlertStateTracker.onAlertTriggered(type)
        AlertStateTracker.onAlertDismissed(type)
        assertEquals(false, AlertStateTracker.activeEpisodeForExport(type))
        AlertStateTracker.resetState(type)
        assertEquals(false, AlertStateTracker.activeEpisodeForExport(type))
        AlertStateTracker.onAlertTriggered(type)
        assertEquals(true, AlertStateTracker.activeEpisodeForExport(type))
    }

    @Test fun closingAManualTestDoesNotAcknowledgeAProductionAlert() {
        AlertStateTracker.onAlertTriggered(type)
        AlertStateTracker.allowNextTriggerForTest(type)
        assertTrue(AlertStateTracker.shouldTrigger(type, AlertConfig(type = type)))
        assertFalse(AlertStateTracker.onAlertTriggered(type))
        assertFalse(AlertStateTracker.onAlertDismissed(type))
        assertEquals(true, AlertStateTracker.activeEpisodeForExport(type))
        assertTrue(AlertStateTracker.onAlertDismissed(type))
        assertEquals(false, AlertStateTracker.activeEpisodeForExport(type))
    }
}
