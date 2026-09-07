package tk.glucodata.alerts

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tk.glucodata.Log
import tk.glucodata.GluciferPayload

class GluciferAlertLifecycleTests {
    private val type = AlertType.PRE_LOW
    private val selected = setOf("alert:pre_low")
    private var logging = false

    @Before fun setup() {
        logging = Log.doLog
        Log.doLog = false
        AlertStateTracker.consumeManualTestAction(type)
        AlertStateTracker.resetState(type)
    }
    @After fun cleanup() {
        AlertStateTracker.consumeManualTestAction(type)
        AlertStateTracker.resetState(type)
        Log.doLog = logging
    }

    private fun snapshot() = AlertStateTracker.snapshotForExport()
    private fun detail() = snapshot().details.first { it.alert == "pre_low" }

    @Test fun quickFireAndAcknowledgementRetainBothEventsAndStableIds() {
        AlertStateTracker.onAlertTriggered(type)
        val fired = detail()
        AlertStateTracker.onAlertDismissed(type)
        val ack = detail()
        assertEquals("fired", fired.reason)
        assertEquals("acknowledged", ack.reason)
        assertNotEquals(fired.id, ack.id)
        assertEquals(listOf(fired, ack), snapshot().events.takeLast(2))
        assertEquals(false, snapshot().states["pre_low"])
        AlertStateTracker.onAlertDismissed(type)
        assertEquals(ack, detail())
        AlertStateTracker.resetState(type)
        assertEquals(ack, detail()) // Evaluation ticks must not relabel the acknowledgement.
        assertEquals(0, snapshot().eventsJson(emptySet()).length())
        assertEquals(0, snapshot().detailsJson(emptySet()).length())
    }

    @Test fun snoozeRetainsDeadlineAfterRuntimeResetAndCanFireAgain() {
        AlertStateTracker.onAlertTriggered(type)
        val until = System.currentTimeMillis() + 600000
        AlertStateTracker.onAlertSnoozedForExport(type, until)
        AlertStateTracker.resetState(type)
        assertEquals(false, snapshot().states["pre_low"])
        assertEquals("snoozed", detail().reason)
        assertEquals(until, detail().snoozedUntilMs)
        AlertStateTracker.onAlertTriggered(type)
        assertEquals(true, snapshot().states["pre_low"])
        assertEquals("fired", detail().reason)
        assertNull(detail().snoozedUntilMs)
    }

    @Test fun naturalResetRecordsClearedOnlyOnceAndBufferIsBounded() {
        repeat(40) {
            AlertStateTracker.onAlertTriggered(type)
            AlertStateTracker.resetState(type)
        }
        assertEquals("cleared", detail().reason)
        val last = detail()
        AlertStateTracker.resetState(type)
        assertEquals(last, detail())
        assertEquals(32, snapshot().events.size)
    }

    @Test fun manualTestDoesNotAddProductionEvents() {
        AlertStateTracker.onAlertTriggered(type)
        val before = snapshot()
        AlertStateTracker.allowNextTriggerForTest(type)
        assertTrue(AlertStateTracker.shouldTrigger(type, AlertConfig(type = type)))
        AlertStateTracker.onAlertTriggered(type)
        AlertStateTracker.onAlertSnoozedForExport(type, System.currentTimeMillis() + 600000)
        AlertStateTracker.onAlertDismissed(type)
        assertEquals(before, snapshot())
    }

    @Test fun metadataOnlyChangeRequiresDelivery() {
        fun payload(sequence: Long) = GluciferPayload.build("test", sequence,
            System.currentTimeMillis(), 1788696000000L, 123, selected, emptyMap(), snapshot().states)
            .put("alert_details", snapshot().detailsJson(selected))
            .put("alert_events", snapshot().eventsJson(selected))
        AlertStateTracker.onAlertTriggered(type)
        AlertStateTracker.onAlertDismissed(type)
        val ack = payload(1)
        AlertStateTracker.onAlertSnoozedForExport(type, System.currentTimeMillis() + 600000)
        val snoozed = payload(2)
        assertEquals(ack.getJSONObject("alerts").toString(), snoozed.getJSONObject("alerts").toString())
        assertFalse(GluciferPayload.sameData(ack, snoozed))
        assertNull(GluciferPayload.nextDelivery(snoozed, payload(3), false, false))
    }
}
