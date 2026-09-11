package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee: the identity of the main sensor is a fact about the minute,
 * not about the current selection. One test per branch of the rule.
 */
class MainSensorOwnershipTests {

    private companion object {
        const val MINUTE = ReadingDisplay.MINUTE_MS
        const val NOW = 1_800_000_000_000L
        val SEALED = NOW - 3L * 60L * MINUTE       // three hours ago
        val SETTLING = NOW - 10L * MINUTE          // ten minutes ago
    }

    private fun ownership(
        recorded: Map<Long, String>,
        primary: String? = "B",
    ) = MainSensorOwnership(recorded, primary, NOW)

    @Test
    fun aSealedMinuteWithARecordIsOwnedByTheRecordedSensor() {
        // A was on screen when this minute was first presented; the user has
        // since made B the main sensor. The minute does not follow them.
        val o = ownership(mapOf(ReadingDisplay.minuteOf(SEALED) to "A"), primary = "B")
        assertEquals("A", o.mainSensorAt(SEALED))
        assertTrue(o.isMainAt("A", SEALED))
        assertFalse(o.isMainAt("B", SEALED))
    }

    @Test
    fun aMinuteInsideTheGraceWindowFollowsTheCurrentPrimaryEvenIfRecorded() {
        // Presentation writes revisable records for settling minutes too. They
        // do not decide anything yet; the current primary does.
        val o = ownership(mapOf(ReadingDisplay.minuteOf(SETTLING) to "A"), primary = "B")
        assertEquals("B", o.mainSensorAt(SETTLING))
        assertTrue(o.isMainAt("B", SETTLING))
    }

    @Test
    fun aSealedMinuteWithNoRecordFallsBackToTheCurrentPrimary() {
        // Nobody has been shown this minute. Nothing is invented for it; it
        // simply reads as the current primary until it is first presented.
        val o = ownership(emptyMap(), primary = "B")
        assertEquals("B", o.mainSensorAt(SEALED))
        assertTrue(o.isMainAt("B", SEALED))
        assertFalse(o.hasRecordedOwnership)
    }

    @Test
    fun theFallbackPersistsNothing() {
        // The predicate has no write side at all: asking it does not record.
        val recorded = HashMap<Long, String>()
        val o = MainSensorOwnership(recorded, "B", NOW)
        o.mainSensorAt(SEALED)
        o.isMainAt("B", SEALED)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun withNoPrimaryAndNoRecordNothingIsMain() {
        val o = ownership(emptyMap(), primary = null)
        assertNull(o.mainSensorAt(SEALED))
        assertFalse(o.isMainAt("A", SEALED))
    }

    @Test
    fun aBlankSensorIsNeverMain() {
        val o = ownership(mapOf(ReadingDisplay.minuteOf(SEALED) to "A"))
        assertFalse(o.isMainAt(null, SEALED))
        assertFalse(o.isMainAt("  ", SEALED))
    }

    @Test
    fun theBoundaryIsTheGraceWindowMeasuredFromTheMinute() {
        val justSealed = NOW - ReadingDisplay.DISPLAY_SEAL_GRACE_MS - MINUTE
        val justSettling = NOW - ReadingDisplay.DISPLAY_SEAL_GRACE_MS + MINUTE
        val o = ownership(
            mapOf(
                ReadingDisplay.minuteOf(justSealed) to "A",
                ReadingDisplay.minuteOf(justSettling) to "A",
            ),
            primary = "B",
        )
        assertEquals("A", o.mainSensorAt(justSealed))
        assertEquals("B", o.mainSensorAt(justSettling))
    }
}
