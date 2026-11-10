package tk.glucodata.alerts

import org.junit.Assert.assertEquals
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

    // The editor shows 22:00 -> 08:00 for a range that was never set. Enabling the toggle
    // used to store only the flag, leaving both ends null, which the evaluator treats as "no
    // restriction": the alert fired all day under a screen that said night only.
    @Test
    fun enablingTheRangeStoresTheWindowTheEditorShows() {
        val config = AlertConfig(type = AlertType.HIGH).withTimeRangeEnabled(true)

        assertTrue(config.timeRangeEnabled)
        assertEquals(AlertDefaults.TIME_RANGE_DEFAULT_START_HOUR, config.activeStartHour)
        assertEquals(0, config.activeStartMinute)
        assertEquals(AlertDefaults.TIME_RANGE_DEFAULT_END_HOUR, config.activeEndHour)
        assertEquals(0, config.activeEndMinute)
        assertTrue(config.isActiveAtMinutes(23 * 60))
        assertTrue(config.isActiveAtMinutes(7 * 60 + 59))
        assertFalse(config.isActiveAtMinutes(12 * 60))
    }

    @Test
    fun enablingKeepsAWindowThatWasAlreadySet() {
        val config = AlertConfig(
            type = AlertType.LOW,
            timeRangeEnabled = false,
            activeStartHour = 6,
            activeStartMinute = 30,
            activeEndHour = 10,
            activeEndMinute = 15
        ).withTimeRangeEnabled(true)

        assertEquals(6, config.activeStartHour)
        assertEquals(30, config.activeStartMinute)
        assertEquals(10, config.activeEndHour)
        assertEquals(15, config.activeEndMinute)
    }

    @Test
    fun disablingKeepsTheWindowForNextTime() {
        val config = AlertConfig(type = AlertType.LOW)
            .withTimeRangeEnabled(true)
            .withActiveStart(5, 45)
            .withTimeRangeEnabled(false)

        assertFalse(config.timeRangeEnabled)
        assertEquals(5, config.activeStartHour)
        assertEquals(45, config.activeStartMinute)
        assertTrue(config.isActiveAtMinutes(12 * 60))
    }

    // A stored config can be enabled with neither end set (written before the fix). Moving one
    // end must fix the other where the editor shows it, not leave the range incomplete.
    @Test
    fun movingOnlyTheStartKeepsTheEndTheEditorShows() {
        val config = AlertConfig(type = AlertType.HIGH, timeRangeEnabled = true).withActiveStart(23, 0)

        assertEquals(AlertDefaults.TIME_RANGE_DEFAULT_END_HOUR, config.activeEndHour)
        assertEquals(0, config.activeEndMinute)
        assertTrue(config.isActiveAtMinutes(23 * 60 + 30))
        assertFalse(config.isActiveAtMinutes(12 * 60))
    }

    @Test
    fun movingOnlyTheEndKeepsTheStartTheEditorShows() {
        val config = AlertConfig(type = AlertType.HIGH, timeRangeEnabled = true).withActiveEnd(6, 30)

        assertEquals(AlertDefaults.TIME_RANGE_DEFAULT_START_HOUR, config.activeStartHour)
        assertEquals(0, config.activeStartMinute)
        assertTrue(config.isActiveAtMinutes(6 * 60 + 29))
        assertFalse(config.isActiveAtMinutes(6 * 60 + 30))
        assertFalse(config.isActiveAtMinutes(12 * 60))
    }

    // "Apply to all" copies a built-in alert's range onto the custom alerts. An incomplete
    // range used to become 00:00 -> 00:00 there, which a custom alert reads as never active.
    @Test
    fun anIncompleteRangeCopiesToACustomAlertAsTheWholeDay() {
        val incomplete = AlertConfig(type = AlertType.LOW, timeRangeEnabled = true)
        val custom = CustomAlertConfig(
            timeRangeEnabled = true,
            startTimeMinutes = incomplete.timeRangeStartMinutesOfDay(),
            endTimeMinutes = incomplete.timeRangeEndMinutesOfDay()
        )

        assertTrue(incomplete.isActiveAtMinutes(12 * 60))
        assertTrue(custom.isActiveTime(12 * 60))
        assertTrue(custom.isActiveTime(0))
    }

    @Test
    fun aCompleteRangeCopiesToACustomAlertUnchanged() {
        val config = AlertConfig(
            type = AlertType.LOW,
            timeRangeEnabled = true,
            activeStartHour = 22,
            activeStartMinute = 15,
            activeEndHour = 7,
            activeEndMinute = 45
        )

        assertEquals(22 * 60 + 15, config.timeRangeStartMinutesOfDay())
        assertEquals(7 * 60 + 45, config.timeRangeEndMinutesOfDay())
    }
}
