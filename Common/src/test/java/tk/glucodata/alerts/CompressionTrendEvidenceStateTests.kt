package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.CompressionLowDetector

class CompressionTrendEvidenceStateTests {
    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun suspect(current: Float = 100f) = CompressionLowDetector.OngoingSuspect(
        onsetMillis = t0 - 8 * minute,
        baselineMgdl = 130f,
        currentMgdl = current,
        meanDropMgdlPerMinute = -3.75f,
        depthMgdl = 30f,
        explainableDropMgdl = 0f
    )

    @Test
    fun selectedAlarmsDoNotQualifyWithoutDetectorEvidence() {
        val state = CompressionTrendEvidenceState()
        for (type in listOf(
            AlertType.PRE_LOW,
            AlertType.FALLING_FAST,
            AlertType.PRE_HIGH,
            AlertType.RISING_FAST,
            AlertType.HIGH,
            AlertType.VERY_HIGH
        )) {
            assertFalse(state.qualifies(type, "sensor-a", t0, 120f, 45 * minute))
        }
    }

    @Test
    fun suspiciousFallQualifiesItsFallAndSubsequentRebound() {
        val state = CompressionTrendEvidenceState()
        state.record("sensor-a", t0, suspect())

        assertTrue(state.qualifies(AlertType.PRE_LOW, "sensor-a", t0, 100f, 45 * minute))
        assertTrue(state.qualifies(AlertType.FALLING_FAST, "sensor-a", t0, 100f, 45 * minute))
        assertFalse(state.qualifies(AlertType.PRE_HIGH, "sensor-a", t0, 102f, 45 * minute))
        assertTrue(state.qualifies(AlertType.PRE_HIGH, "sensor-a", t0 + minute, 103f, 45 * minute))
        assertTrue(state.qualifies(AlertType.RISING_FAST, "sensor-a", t0 + minute, 110f, 45 * minute))
        assertTrue(state.qualifies(AlertType.HIGH, "sensor-a", t0 + minute, 190f, 45 * minute))
    }

    @Test
    fun evidenceExpiresAndNeverCrossesSensors() {
        val state = CompressionTrendEvidenceState()
        state.record("sensor-a", t0, suspect())

        assertFalse(state.qualifies(AlertType.PRE_HIGH, "sensor-b", t0 + minute, 120f, 45 * minute))
        state.record("sensor-a", t0, suspect())
        assertFalse(state.qualifies(AlertType.PRE_HIGH, "sensor-a", t0 + 46 * minute, 120f, 45 * minute))
    }

    @Test
    fun newerSuspicionCarriesTheLowestObservedPoint() {
        val state = CompressionTrendEvidenceState()
        state.record("sensor-a", t0, suspect(105f))
        state.record("sensor-a", t0 + minute, suspect(95f))

        assertFalse(state.qualifies(AlertType.PRE_HIGH, "sensor-a", t0 + 2 * minute, 97f, 45 * minute))
        assertTrue(state.qualifies(AlertType.PRE_HIGH, "sensor-a", t0 + 2 * minute, 98f, 45 * minute))
    }
}
