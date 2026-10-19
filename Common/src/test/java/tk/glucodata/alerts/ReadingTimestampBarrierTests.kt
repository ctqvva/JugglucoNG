package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTimestampBarrierTests {

    @Test
    fun blocksCalibrationRecalculationOfCurrentReading() {
        val barrier = ReadingTimestampBarrier()
        barrier.suppressThrough(100L)

        assertTrue(barrier.blocks(100L))
        assertTrue(barrier.blocks(99L))
    }

    @Test
    fun newerSensorReadingAutomaticallyClearsSuppression() {
        val barrier = ReadingTimestampBarrier()
        barrier.suppressThrough(100L)

        assertFalse(barrier.blocks(101L))
    }

    @Test
    fun calibrationOnlyCrossingDoesNotConsumeNextEpisodeEntry() {
        val barrier = ReadingTimestampBarrier()
        val episodes = AlertEpisodeState<AlertType>()

        episodes.update(emptySet())
        barrier.suppressThrough(100L)

        repeat(3) {
            if (!barrier.blocks(100L)) {
                episodes.update(setOf(AlertType.HIGH))
            }
        }

        val nextReading = episodes.update(setOf(AlertType.HIGH))
        assertTrue(nextReading.shouldTryFire(AlertType.HIGH))
    }
}
