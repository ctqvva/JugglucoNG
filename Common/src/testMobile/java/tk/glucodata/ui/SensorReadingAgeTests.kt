package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ui.util.SensorReadingAgeUnit
import tk.glucodata.ui.util.nextSensorReadingAgeDelay
import tk.glucodata.ui.util.sensorReadingAge

/**
 * The counter is now shared by the sensor card, the dashboard sensor panel and
 * the hero card's peer pills, so its rounding and its tick schedule are worth
 * pinning: a wrong tick makes one surface show a frozen age while another moves.
 */
class SensorReadingAgeTests {
    private val now = 1_700_000_000_000L

    @Test
    fun freshReadingCountsWholeSeconds() {
        val age = sensorReadingAge(now, now - 5_400L)
        assertEquals(SensorReadingAgeUnit.SECONDS, age.unit)
        assertEquals(5, age.amount)
    }

    @Test
    fun aReadingInTheFutureReadsAsZeroRatherThanNegative() {
        val age = sensorReadingAge(now, now + 30_000L)
        assertEquals(SensorReadingAgeUnit.SECONDS, age.unit)
        assertEquals(0, age.amount)
    }

    @Test
    fun theFirstMinuteSwitchesToWholeMinutes() {
        assertEquals(59, sensorReadingAge(now, now - 59_999L).amount)
        val justOver = sensorReadingAge(now, now - 60_000L)
        assertEquals(SensorReadingAgeUnit.MINUTES, justOver.unit)
        assertEquals(1, justOver.amount)
    }

    @Test
    fun minutesRoundDownToTheMinuteJustPassed() {
        val age = sensorReadingAge(now, now - (12L * 60_000L + 59_000L))
        assertEquals(SensorReadingAgeUnit.MINUTES, age.unit)
        assertEquals(12, age.amount)
    }

    @Test
    fun secondsTickEverySecond() {
        assertEquals(1_000L, nextSensorReadingAgeDelay(now, now - 3_000L))
    }

    @Test
    fun minutesTickOnlyWhenTheMinuteRolls() {
        // 90s old: the text changes again 30s from now, not next second.
        assertEquals(30_000L, nextSensorReadingAgeDelay(now, now - 90_000L))
        // Exactly on a minute boundary: a full minute of quiet ahead.
        assertEquals(60_000L, nextSensorReadingAgeDelay(now, now - 120_000L))
    }
}
