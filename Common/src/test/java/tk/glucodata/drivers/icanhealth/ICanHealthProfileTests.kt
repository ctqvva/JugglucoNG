package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Test

class ICanHealthProfileTests {

    @Test
    fun lifetimesUseDayConvertedToMillis() {
        val profile = ICanHealthProfile(
            familyName = "test",
            readingIntervalMinutes = 3,
            warmupMinutes = 120,
            ratedLifetimeDays = 7,
            advisoryExpectedDays = 10,
        )
        assertEquals(7L * 24 * 60 * 60 * 1000, profile.ratedLifetimeMs())
        assertEquals(10L * 24 * 60 * 60 * 1000, profile.expectedLifetimeMs())
    }

    @Test
    fun advisoryDefaultsTo28Days() {
        val profile = ICanHealthProfile("test", 3, 120, 15)
        assertEquals(28, profile.advisoryExpectedDays)
        assertEquals(28L * 24 * 60 * 60 * 1000, profile.expectedLifetimeMs())
    }

    @Test
    fun i7eWinsOverI7() {
        assertEquals("i7e", ICanHealthProfileResolver.resolve("i7e").familyName)
        assertEquals("i7", ICanHealthProfileResolver.resolve("i7pro").familyName)
        assertEquals("i7", ICanHealthProfileResolver.resolve("I7S").familyName)
    }

    @Test
    fun h6VariantSelection() {
        assertEquals("H6-15", ICanHealthProfileResolver.resolve("h6 YK").familyName)
        assertEquals("H6-15", ICanHealthProfileResolver.resolve("h6 zb").familyName)
        val h6 = ICanHealthProfileResolver.resolve("x h6")
        assertEquals("H6", h6.familyName)
        assertEquals(7, h6.ratedLifetimeDays)
        assertEquals(120, h6.warmupMinutes)
    }

    @Test
    fun otherFamilies() {
        assertEquals("H3", ICanHealthProfileResolver.resolve("h3").familyName)
        assertEquals("i6", ICanHealthProfileResolver.resolve("i6pro").familyName)
        assertEquals("o3", ICanHealthProfileResolver.resolve("o3").familyName)
        assertEquals("i3", ICanHealthProfileResolver.resolve("t3").familyName)
    }

    @Test
    fun unknownAndNullFallBackToDefault() {
        val expected = ICanHealthProfileResolver.resolve(null)
        assertEquals("iCan", expected.familyName)
        assertEquals(3, expected.readingIntervalMinutes)
        assertEquals(120, expected.warmupMinutes)
        assertEquals(15, expected.ratedLifetimeDays)
        assertEquals(expected, ICanHealthProfileResolver.resolve("totally-unknown"))
    }

    @Test
    fun hyphensAreNormalisedToSpacesBeforeMatching() {
        // The "h6-14"/"h6-15" literals can never match: normalize() turns '-' into ' '.
        assertEquals("iCan", ICanHealthProfileResolver.resolve("H6-14").familyName)
        assertEquals("iCan", ICanHealthProfileResolver.resolve("H6-15").familyName)
    }
}
