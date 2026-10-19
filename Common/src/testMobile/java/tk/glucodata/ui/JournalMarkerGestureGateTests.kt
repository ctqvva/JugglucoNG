package tk.glucodata.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalMarkerGestureGateTests {

    @Test
    fun stationaryReleaseClicksTheMarker() {
        val gate = JournalMarkerGestureGate(touchSlop = 10f)
        gate.observe(displacementX = 0f, displacementY = 0f, pointerCount = 1)

        assertTrue(gate.shouldClick(released = true))
    }

    @Test
    fun movementBelowTouchSlopStillClicksTheMarker() {
        val gate = JournalMarkerGestureGate(touchSlop = 10f)
        gate.observe(displacementX = 6f, displacementY = 7f, pointerCount = 1)

        assertTrue(gate.shouldClick(released = true))
    }

    @Test
    fun horizontalDragHandsTheGestureToTheChart() {
        val gate = JournalMarkerGestureGate(touchSlop = 10f)
        gate.observe(displacementX = 10f, displacementY = 0f, pointerCount = 1)

        assertFalse(gate.shouldClick(released = true))
    }

    @Test
    fun verticalDragDoesNotClickTheMarker() {
        val gate = JournalMarkerGestureGate(touchSlop = 10f)
        gate.observe(displacementX = 0f, displacementY = 12f, pointerCount = 1)

        assertFalse(gate.shouldClick(released = true))
    }

    @Test
    fun secondPointerCancelsTheMarkerClick() {
        val gate = JournalMarkerGestureGate(touchSlop = 10f)
        gate.observe(displacementX = 0f, displacementY = 0f, pointerCount = 2)

        assertFalse(gate.shouldClick(released = true))
    }

    @Test
    fun canceledGestureDoesNotClickTheMarker() {
        val gate = JournalMarkerGestureGate(touchSlop = 10f)

        assertFalse(gate.shouldClick(released = false))
    }
}
