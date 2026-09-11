package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneIobSnapshotTests {
    @After
    fun tearDown() {
        CloneIobSnapshot.clear()
    }

    @Test
    fun finiteSnapshotRoundTripsWithoutInventingMissingValues() {
        val encoded = CloneIobSnapshot.encode(
            floatArrayOf(4.5f, 3.2f, Float.NaN, 0.8f, Float.NaN),
            timestampMillis = 10_000L,
        )

        val parsed = CloneIobSnapshot.parse(encoded)
        assertNotNull(parsed)
        assertEquals(4.5f, parsed!!.iobUnits, 0.0001f)
        assertEquals(3.2f, parsed.eiobUnits, 0.0001f)
        assertTrue(parsed.cobGrams.isNaN())
        assertEquals(0.8f, parsed.iobNext30Units, 0.0001f)
        assertTrue(parsed.cobNext30Grams.isNaN())
    }

    @Test
    fun malformedOrEmptySnapshotsAreRejected() {
        assertNull(CloneIobSnapshot.parse("not json"))
        assertNull(CloneIobSnapshot.parse("{\"schema\":\"wrong\",\"timestamp\":1,\"iob\":2}"))
        assertNull(CloneIobSnapshot.parse("{\"schema\":\"tk.glucodata.clone.iob.v1\",\"timestamp\":1}"))
        assertEquals("", CloneIobSnapshot.encode(floatArrayOf(Float.NaN, Float.NaN, Float.NaN), 1L))
    }

    @Test
    fun delayedPacketCannotReplaceNewerState() {
        val newer = CloneIobSnapshot.RemoteIob(2f, 1f, 12f, 0.5f, 4f, 20_000L)
        val older = newer.copy(iobUnits = 9f, timestampMillis = 10_000L)

        assertTrue(CloneIobSnapshot.update(newer))
        assertFalse(CloneIobSnapshot.update(older))
        assertEquals(2f, CloneIobSnapshot.fresh(20_000L)!!.iobUnits, 0.0001f)
    }

    @Test
    fun staleStateExpiresButFutureClockSkewRemainsFresh() {
        val snapshot = CloneIobSnapshot.RemoteIob(2f, 1f, 12f, 0.5f, 4f, 20_000L)
        CloneIobSnapshot.update(snapshot)

        assertNotNull(CloneIobSnapshot.fresh(20_000L + CloneIobSnapshot.FRESHNESS_WINDOW_MS))
        assertNull(CloneIobSnapshot.fresh(20_001L + CloneIobSnapshot.FRESHNESS_WINDOW_MS))
        assertNotNull(CloneIobSnapshot.fresh(19_000L))
    }
}
