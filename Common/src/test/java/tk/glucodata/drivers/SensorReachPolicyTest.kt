package tk.glucodata.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorReachPolicyTest {
    private val now = 10_000_000_000L
    private val stale = now - SensorReachPolicy.STALE_MS
    private val fresh = now - 60_000L

    private fun reach(
        lastReadingMs: Long,
        linkUp: Boolean,
        linkDownSinceMs: Long = 0L,
        catchingUp: Boolean = false,
    ) = SensorReachPolicy.resolve(now, lastReadingMs, linkUp, linkDownSinceMs, catchingUp)

    @Test
    fun aFreshReadingIsFineWhateverTheLink() {
        assertEquals(SensorReach.FINE, reach(fresh, linkUp = true))
        assertEquals(SensorReach.FINE, reach(fresh, linkUp = false, linkDownSinceMs = now - 3_600_000L))
    }

    @Test
    fun staleReadingWithNoLinkIsOutOfReachOnceTheLinkHasStayedDown() {
        assertEquals(
            SensorReach.OUT_OF_REACH,
            reach(stale, linkUp = false, linkDownSinceMs = now - SensorReachPolicy.LINK_SETTLE_MS),
        )
        // A reconnect in progress is not a lost sensor.
        assertEquals(SensorReach.FINE, reach(stale, linkUp = false, linkDownSinceMs = now - 5_000L))
    }

    @Test
    fun staleReadingOverALiveLinkIsQuiet() {
        assertEquals(SensorReach.QUIET, reach(stale, linkUp = true))
    }

    @Test
    fun aSensorWithoutReadingsNeedsTheLinkGoneForTheWholeStaleSpan() {
        assertEquals(SensorReach.FINE, reach(0L, linkUp = true))
        assertEquals(SensorReach.FINE, reach(0L, linkUp = false, linkDownSinceMs = now - 120_000L))
        assertEquals(
            SensorReach.OUT_OF_REACH,
            reach(0L, linkUp = false, linkDownSinceMs = now - SensorReachPolicy.STALE_MS),
        )
    }

    @Test
    fun aBacklogBeingFetchedIsNeitherLostNorQuiet() {
        assertEquals(SensorReach.FINE, reach(stale, linkUp = true, catchingUp = true))
        assertEquals(
            SensorReach.FINE,
            reach(stale, linkUp = false, linkDownSinceMs = now - 3_600_000L, catchingUp = true),
        )
    }

    @Test
    fun linkTrackerKeepsTheFirstDownSightingUntilTheLinkComesBack() {
        val serial = "reach-test"
        SensorReachPolicy.forgetLink(serial)
        assertEquals(100L, SensorReachPolicy.observeLink(serial, linkUp = false, nowMs = 100L))
        assertEquals(100L, SensorReachPolicy.observeLink(serial, linkUp = false, nowMs = 900L))
        assertEquals(0L, SensorReachPolicy.observeLink(serial, linkUp = true, nowMs = 1_000L))
        assertEquals(2_000L, SensorReachPolicy.observeLink(serial, linkUp = false, nowMs = 2_000L))
        SensorReachPolicy.forgetLink(serial)
    }

    @Test
    fun followersHaveNoSensorToReach() {
        assertFalse(SensorReachPolicy.holdsOwnLink(ManagedSensorUiFamily.NIGHTSCOUT))
        assertFalse(SensorReachPolicy.holdsOwnLink(ManagedSensorUiFamily.GENERIC))
        assertTrue(SensorReachPolicy.holdsOwnLink(ManagedSensorUiFamily.SIBIONICS))
        assertTrue(SensorReachPolicy.holdsOwnLink(ManagedSensorUiFamily.AIDEX))
    }
}
