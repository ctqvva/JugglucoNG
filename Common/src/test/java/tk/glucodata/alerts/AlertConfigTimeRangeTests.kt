package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertConfigTimeRangeTests {

    @Test
    fun disabledTimeRangeIsAlwaysActive() {
        val config = AlertConfig(
            type = AlertType.LOW,
            timeRangeEnabled = false,
            activeStartHour = 6,
            activeEndHour = 10
        )

        assertTrue(config.isActiveAtMinutes(2 * 60))
        assertTrue(config.isActiveAtMinutes(12 * 60))
    }

    @Test
    fun incompleteTimeRangeIsAlwaysActive() {
        val config = AlertConfig(
            type = AlertType.LOW,
            timeRangeEnabled = true,
            activeStartHour = 6,
            activeEndHour = null
        )

        assertTrue(config.isActiveAtMinutes(12 * 60))
    }

    @Test
    fun sameDayRangeIsStartInclusiveAndEndExclusive() {
        val config = AlertConfig(
            type = AlertType.LOW,
            timeRangeEnabled = true,
            activeStartHour = 6,
            activeStartMinute = 30,
            activeEndHour = 10,
            activeEndMinute = 0
        )

        assertFalse(config.isActiveAtMinutes(6 * 60 + 29))
        assertTrue(config.isActiveAtMinutes(6 * 60 + 30))
        assertTrue(config.isActiveAtMinutes(9 * 60 + 59))
        assertFalse(config.isActiveAtMinutes(10 * 60))
    }

    @Test
    fun midnightToOneRangeIncludesThirtyMinutesPastMidnight() {
        val config = AlertConfig(
            type = AlertType.HIGH,
            timeRangeEnabled = true,
            activeStartHour = 0,
            activeStartMinute = 0,
            activeEndHour = 1,
            activeEndMinute = 0
        )

        assertTrue(config.isActiveAtMinutes(30))
    }

    @Test
    fun overnightRangeWrapsAcrossMidnight() {
        val config = AlertConfig(
            type = AlertType.HIGH,
            timeRangeEnabled = true,
            activeStartHour = 22,
            activeStartMinute = 15,
            activeEndHour = 7,
            activeEndMinute = 45
        )

        assertTrue(config.isActiveAtMinutes(23 * 60))
        assertTrue(config.isActiveAtMinutes(7 * 60 + 44))
        assertFalse(config.isActiveAtMinutes(7 * 60 + 45))
        assertFalse(config.isActiveAtMinutes(12 * 60))
    }

    @Test
    fun equalStartAndEndMeansNoActiveWindow() {
        val config = AlertConfig(
            type = AlertType.HIGH,
            timeRangeEnabled = true,
            activeStartHour = 6,
            activeStartMinute = 0,
            activeEndHour = 6,
            activeEndMinute = 0
        )

        assertFalse(config.isActiveAtMinutes(6 * 60))
        assertFalse(config.isActiveAtMinutes(5 * 60 + 59))
    }
}
