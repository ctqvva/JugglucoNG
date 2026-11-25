package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.accessibility.AodRefreshGate

class AodRefreshGateTests {
    @Test fun firstEventOfAReadingRunsAtOnceAndTheRestShareOneRun() {
        val gate = AodRefreshGate(400L)
        assertEquals(0L, gate.delayForRequest(1_000L))
        assertNull("already queued", gate.delayForRequest(1_000L))
        gate.onRefresh(1_001L)
        assertEquals(395L, gate.delayForRequest(1_006L))
        assertNull(gate.delayForRequest(1_100L))
        assertNull(gate.delayForRequest(1_300L))
        gate.onRefresh(1_401L)
        assertEquals("the next reading is not delayed", 0L, gate.delayForRequest(20_000L))
    }

    @Test fun droppedRefreshDoesNotBlockTheNextOverlay() {
        val gate = AodRefreshGate(400L)
        gate.delayForRequest(1_000L)
        gate.cancel()
        assertEquals(0L, gate.delayForRequest(1_050L))
    }

    @Test fun delayStaysInRangeWhenTheClockStepsBack() {
        val gate = AodRefreshGate(400L)
        gate.delayForRequest(5_000L)
        gate.onRefresh(5_000L)
        assertEquals(400L, gate.delayForRequest(1_000L))
    }
}
