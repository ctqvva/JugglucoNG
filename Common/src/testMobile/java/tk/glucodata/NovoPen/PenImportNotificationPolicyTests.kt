package tk.glucodata.NovoPen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class PenImportNotificationPolicyTests {

    @Test
    fun theResultNotificationDefaultsToOneMinute() {
        assertEquals(1, PenImportNotificationPolicy.DEFAULT_DURATION_MINUTES)
        assertEquals(
            TimeUnit.MINUTES.toMillis(1),
            PenImportNotificationPolicy.timeoutMillis(
                PenImportNotificationPolicy.DEFAULT_DURATION_MINUTES,
            ),
        )
    }

    @Test
    fun zeroTurnsTheNotificationOff() {
        assertNull(PenImportNotificationPolicy.timeoutMillis(0))
    }

    @Test
    fun sliderOffersShortResultsWithoutLosingTheOldMaximum() {
        assertEquals(
            listOf(0, 1, 5, 15, 30, 60, 180, 720),
            PenImportNotificationPolicy.durationOptionsMinutes,
        )
    }

    @Test
    fun storedValuesAreClampedToTheNearestSupportedDuration() {
        assertEquals(0, PenImportNotificationPolicy.normalizeDurationMinutes(-3))
        assertEquals(5, PenImportNotificationPolicy.normalizeDurationMinutes(4))
        assertEquals(30, PenImportNotificationPolicy.normalizeDurationMinutes(40))
        assertEquals(720, PenImportNotificationPolicy.normalizeDurationMinutes(10_000))
        assertEquals(0, PenImportNotificationPolicy.normalizeDurationMinutes(Int.MIN_VALUE))
        assertEquals(720, PenImportNotificationPolicy.normalizeDurationMinutes(Int.MAX_VALUE))
    }

    @Test
    fun sliderIndicesRoundAndStayInRange() {
        assertEquals(1f, PenImportNotificationPolicy.sliderIndexForDuration(1))
        assertEquals(7f, PenImportNotificationPolicy.sliderIndexForDuration(10_000))
        assertEquals(0, PenImportNotificationPolicy.durationMinutesForSliderIndex(-1f))
        assertEquals(15, PenImportNotificationPolicy.durationMinutesForSliderIndex(3.4f))
        assertEquals(30, PenImportNotificationPolicy.durationMinutesForSliderIndex(3.6f))
        assertEquals(720, PenImportNotificationPolicy.durationMinutesForSliderIndex(99f))
    }
}
