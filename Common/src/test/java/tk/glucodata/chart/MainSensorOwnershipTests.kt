package tk.glucodata.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee: the identity of the main sensor is a fact about the minute,
 * not about the current selection — and where nothing was recorded, the record
 * says nothing rather than guessing.
 */
class MainSensorOwnershipTests {

    private companion object {
        const val MINUTE = MainSensorOwnership.MINUTE_MS
        const val NOW = 1_800_000_000_000L
        val SEALED = NOW - 3L * 60L * MINUTE       // three hours ago
        val SETTLING = NOW - 10L * MINUTE          // ten minutes ago
    }

    private fun ownership(recorded: Map<Long, String>) = MainSensorOwnership(recorded, NOW)

    @Test
    fun aSealedMinuteWithARecordIsOwnedByTheRecordedSensor() {
        // A was on screen when this minute was first presented; the user has
        // since made B the main sensor. The minute does not follow them.
        val o = ownership(mapOf(MainSensorOwnership.minuteOf(SEALED) to "A"))
        assertEquals("A", o.mainSensorAt(SEALED))
        assertEquals(true, o.isMainAt("A", SEALED))
        assertEquals(false, o.isMainAt("B", SEALED))
    }

    @Test
    fun aMinuteInsideTheGraceWindowHasNoOpinionEvenIfRecorded() {
        // Presentation writes revisable records for settling minutes too. They
        // decide nothing yet; the live merge does.
        val o = ownership(mapOf(MainSensorOwnership.minuteOf(SETTLING) to "A"))
        assertNull(o.mainSensorAt(SETTLING))
        assertNull(o.isMainAt("A", SETTLING))
        assertNull(o.isMainAt("B", SETTLING))
    }

    @Test
    fun aSealedMinuteWithNoRecordHasNoOpinion() {
        // Nobody has been shown this minute. Answering "current primary" here
        // demoted a retired sensor's whole history — the only line there —
        // because its serial was not today's primary. The live merge already
        // chose it as the main line; the record has nothing to add.
        val o = ownership(emptyMap())
        assertNull(o.mainSensorAt(SEALED))
        assertNull(o.isMainAt("retired-sensor", SEALED))
        assertFalse(o.hasRecordedOwnership)
    }

    @Test
    fun thePredicatePersistsNothing() {
        val recorded = HashMap<Long, String>()
        val o = MainSensorOwnership(recorded, NOW)
        o.mainSensorAt(SEALED)
        o.isMainAt("B", SEALED)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aBlankSensorIsNeverMainWhereTheRecordHasAnOpinion() {
        val o = ownership(mapOf(MainSensorOwnership.minuteOf(SEALED) to "A"))
        assertEquals(false, o.isMainAt(null, SEALED))
        assertEquals(false, o.isMainAt("  ", SEALED))
    }

    @Test
    fun theBoundaryIsTheGraceWindowMeasuredFromTheMinute() {
        val justSealed = NOW - MainSensorOwnership.SEAL_GRACE_MS - MINUTE
        val justSettling = NOW - MainSensorOwnership.SEAL_GRACE_MS + MINUTE
        val o = ownership(
            mapOf(
                MainSensorOwnership.minuteOf(justSealed) to "A",
                MainSensorOwnership.minuteOf(justSettling) to "A",
            )
        )
        assertEquals("A", o.mainSensorAt(justSealed))
        assertNull(o.mainSensorAt(justSettling))
    }
    @Test
    fun resumingAfterClockAdvanceKeepsUnchangedOwnershipEqual() {
        val records = mapOf(MainSensorOwnership.minuteOf(SEALED) to "A")
        val before = MainSensorOwnership(records, NOW)
        val resumed = MainSensorOwnership(records, NOW + 20 * MINUTE)
        assertEquals(before, resumed)
        assertEquals(before.hashCode(), resumed.hashCode())
        assertEquals(MainSensorOwnership.NONE, MainSensorOwnership(emptyMap(), NOW))
    }

    @Test
    fun resumingAfterARecordedMinuteSealsChangesOwnership() {
        val minute = MainSensorOwnership.minuteOf(SETTLING)
        val records = mapOf(minute to "B")
        val before = MainSensorOwnership(records, NOW)
        val resumed = MainSensorOwnership(records, minute + MainSensorOwnership.SEAL_GRACE_MS)
        assertFalse(before == resumed)
        assertNull(before.mainSensorAt(minute))
        assertEquals("B", resumed.mainSensorAt(minute))
    }

    @Test
    fun resumedOwnershipIncludesRecordsAddedWhileHidden() {
        val minute = MainSensorOwnership.minuteOf(SEALED)
        val before = MainSensorOwnership(mapOf(minute to "A"), NOW)
        val resumed = MainSensorOwnership(mapOf(minute to "B"), NOW + MINUTE)
        assertFalse(before == resumed)
        assertEquals("B", resumed.mainSensorAt(minute))
    }

}
