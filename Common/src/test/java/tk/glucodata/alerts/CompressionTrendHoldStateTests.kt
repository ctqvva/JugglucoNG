package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.CompressionTrendHoldState.Decision

class CompressionTrendHoldStateTests {
    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun replay(type: AlertType, values: List<Float>): Decision {
        require(values.size == 7)
        val state = CompressionTrendHoldState()
        values.dropLast(1).forEachIndexed { index, value ->
            assertEquals(
                "type=$type minute=$index",
                Decision.HOLD,
                state.onCandidate(type, t0 + index * minute, t0 + index * minute, value, false)
            )
        }
        return state.onCandidate(type, t0 + 6 * minute, t0 + 6 * minute, values.last(), false)
    }

    @Test
    fun recordedCompressionWavesDropBothEarlyWarningFamilies() {
        // Minimized minute-value waves from the reported false PRE_LOW entries. The last
        // wave also completed the configured FALLING_FAST early-trigger distance.
        val waves = listOf(
            listOf(97f, 96f, 98f, 102f, 107f, 112f, 117f),
            listOf(131f, 130f, 130f, 131f, 135f, 140f, 145f),
            listOf(133f, 131f, 129f, 129f, 129f, 131f, 133f),
            listOf(133f, 131f, 132f, 134f, 134f, 141f, 151f)
        )

        for (wave in waves) {
            assertEquals(Decision.DROP, replay(AlertType.PRE_LOW, wave))
            assertEquals(Decision.DROP, replay(AlertType.FALLING_FAST, wave))
        }
    }

    @Test
    fun recordedGenuineFallsAreReleasedAtTheBound() {
        val genuineFalls = listOf(
            listOf(87f, 86f, 85f, 81f, 80f, 79f, 78f),
            listOf(80f, 76f, 72f, 69f, 67f, 66f, 65f)
        )

        for (fall in genuineFalls) {
            assertEquals(Decision.ALLOW, replay(AlertType.PRE_LOW, fall))
            assertEquals(Decision.ALLOW, replay(AlertType.FALLING_FAST, fall))
        }
    }

    @Test
    fun actualLowBypassesAWaitImmediately() {
        val state = CompressionTrendHoldState()
        assertEquals(Decision.HOLD,
            state.onCandidate(AlertType.PRE_LOW, t0, t0, 82f, actualLow = false))

        assertEquals(Decision.ALLOW,
            state.onCandidate(AlertType.PRE_LOW, t0 + minute, t0 + minute, 69f, actualLow = true))
        assertFalse(state.isHolding(AlertType.PRE_LOW))
    }

    @Test
    fun lowAndVeryLowNeverEnterTheState() {
        val state = CompressionTrendHoldState()
        for (type in listOf(AlertType.LOW, AlertType.VERY_LOW)) {
            assertEquals(Decision.ALLOW,
                state.onCandidate(type, t0, t0, 70f, actualLow = false))
            assertFalse(state.isHolding(type))
        }
    }

    @Test
    fun aReleasedCandidateCannotStartASecondWait() {
        val state = CompressionTrendHoldState()
        assertEquals(Decision.HOLD,
            state.onCandidate(AlertType.PRE_LOW, t0, t0, 90f, actualLow = false))
        assertEquals(Decision.ALLOW,
            state.onCandidate(AlertType.PRE_LOW, t0 + 6 * minute, t0 + 6 * minute, 80f, false))

        // A delivery cooldown can make the same threshold episode pending again.
        assertEquals(Decision.ALLOW,
            state.onCandidate(AlertType.PRE_LOW, t0 + 7 * minute, t0 + 7 * minute, 79f, false))

        state.onCandidateCleared(AlertType.PRE_LOW)
        assertEquals(Decision.HOLD,
            state.onCandidate(AlertType.PRE_LOW, t0 + 20 * minute, t0 + 20 * minute, 95f, false))
    }

    @Test
    fun onlyANewerMissingDeltaCandidateEndsItsRun() {
        val state = CompressionTrendHoldState()
        assertEquals(Decision.HOLD,
            state.onCandidate(AlertType.FALLING_FAST, t0, t0, 130f, actualLow = false))

        state.onDeltaCandidateMissing(t0)
        assertTrue(state.isHolding(AlertType.FALLING_FAST))

        state.onDeltaCandidateMissing(t0 + minute)
        assertFalse(state.isHolding(AlertType.FALLING_FAST))
        assertEquals(Decision.HOLD,
            state.onCandidate(AlertType.FALLING_FAST, t0 + 2 * minute, t0 + 2 * minute, 140f, false))
    }

    @Test
    fun clockOrReadingTimeGoingBackFailsOpen() {
        val state = CompressionTrendHoldState()
        assertEquals(Decision.HOLD,
            state.onCandidate(AlertType.PRE_LOW, t0, t0, 100f, actualLow = false))
        assertEquals(Decision.ALLOW,
            state.onCandidate(AlertType.PRE_LOW, t0 - 1, t0, 99f, actualLow = false))
        assertEquals(Decision.ALLOW,
            state.onCandidate(AlertType.PRE_LOW, t0 + minute, t0 + minute, 98f, false))

        state.onCandidateCleared(AlertType.PRE_LOW)
        assertEquals(Decision.HOLD,
            state.onCandidate(AlertType.PRE_LOW, t0, t0, 100f, actualLow = false))
        assertEquals(Decision.ALLOW,
            state.onCandidate(AlertType.PRE_LOW, t0 + minute, t0 - 1, 99f, false))
    }
}
