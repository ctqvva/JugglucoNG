package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the native poll geometry against the day-15 cliff.
 *
 * Poll storage is sized from the declared wear duration. If a profile ever declares fewer days
 * than the sensor is expected to run, `validPollIndex()` starts refusing partway through the
 * wear and live readings are dropped without anything user-visible saying so.
 */
class ICanHealthLifetimeTests {

    private val profiles = listOf(
        ICanHealthProfileResolver.resolve("iCGM-t3"),
        ICanHealthProfileResolver.resolve("iCGM-t6"),
        ICanHealthProfileResolver.resolve("iCGM-i7"),
        ICanHealthProfileResolver.resolve("iCGM-i7e"),
        ICanHealthProfileResolver.resolve("iCGM-H3"),
        ICanHealthProfileResolver.resolve(null),
    )

    @Test
    fun everyProfileDeclaresAtLeastItsRatedLifetime() {
        profiles.forEach { profile ->
            assertTrue(
                "${profile.familyName} declares ${profile.advisoryExpectedDays}d storage for a " +
                    "${profile.ratedLifetimeDays}d rating",
                profile.advisoryExpectedDays >= profile.ratedLifetimeDays
            )
        }
    }

    @Test
    fun everyProfileClearsTheFifteenDayPollFloor() {
        // maxstreampos() floors geometry at 15 days; declaring less can only shrink a shell.
        profiles.forEach { profile ->
            assertTrue(
                "${profile.familyName} would cap storage below the 15-day native floor",
                profile.advisoryExpectedDays >= 15
            )
        }
    }

    @Test
    fun declaredLifetimeStaysWithinTheNativeShellMaximum() {
        // Native maxdays is 46; a longer declaration would be silently clamped.
        profiles.forEach { profile ->
            assertTrue(
                "${profile.familyName} declares ${profile.advisoryExpectedDays}d, above native maxdays",
                profile.advisoryExpectedDays <= 46
            )
        }
    }

    @Test
    fun i3DeclaresTwentyEightDaysNotItsFifteenDayRating() {
        // The regression this covers: an i3 sized to its 15-day rating stopped storing on day 15
        // even though the hardware kept running.
        val i3 = ICanHealthProfileResolver.resolve("iCGM-t3")
        assertEquals(15, i3.ratedLifetimeDays)
        assertEquals(28, i3.advisoryExpectedDays)
    }
}
