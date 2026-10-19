package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A sensor's colour is a property of the set of sensors, not of which one the
 * user is looking at. Swapping main and secondary reorders the selection; it
 * must not trade the colours.
 */
class SensorVisualsStableColorTests {

    /** Two ids that share a palette slot, so one has to be bumped. */
    private fun collidingPair(): Pair<String, String> {
        val slot = SensorVisuals.colorIndex("A")
        var candidate = 0
        while (SensorVisuals.colorIndex("Z$candidate") != slot) candidate++
        return "A" to "Z$candidate"
    }

    @Test
    fun swappingTheOrderDoesNotSwapTheColours() {
        val (a, b) = collidingPair()
        val forward = SensorVisuals.distinctColorArgbs(listOf(a, b))
        val swapped = SensorVisuals.distinctColorArgbs(listOf(b, a))
        assertEquals(forward[0], swapped[1]) // a keeps its colour
        assertEquals(forward[1], swapped[0]) // b keeps its colour
        assert(forward[0] != forward[1])
    }

    @Test
    fun theMapIsStableUnderReordering() {
        val (a, b) = collidingPair()
        assertEquals(
            SensorVisuals.distinctColorArgbMap(listOf(a, b)),
            SensorVisuals.distinctColorArgbMap(listOf(b, a)),
        )
    }
}
