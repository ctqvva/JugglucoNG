package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteIobSnapshotTests {
    private val clone = RemoteIobSnapshot.Values(
        iobUnits = 4.5f,
        eiobUnits = 3.2f,
        cobGrams = 12f,
        timestampMillis = 20_000L,
        source = RemoteIobSnapshot.Source.CLONE,
    )
    private val nightscout = clone.copy(
        iobUnits = 2.1f,
        source = RemoteIobSnapshot.Source.NIGHTSCOUT,
    )
    private val api = clone.copy(
        iobUnits = 1.8f,
        source = RemoteIobSnapshot.Source.API,
    )

    @Test
    fun activeRegisteredCloneWinsOverNightscout() {
        assertEquals(
            clone,
            RemoteIobSnapshot.select(true, true, clone, nightscout),
        )
    }

    @Test
    fun localCloneOffIgnoresEvenFreshCachedClone() {
        assertEquals(
            nightscout,
            RemoteIobSnapshot.select(false, true, clone, nightscout),
        )
        assertEquals(
            nightscout,
            RemoteIobSnapshot.select(true, false, clone, nightscout),
        )
    }

    @Test
    fun noRemoteSourceReturnsNull() {
        assertNull(RemoteIobSnapshot.select(true, true, null, null))
        assertNull(RemoteIobSnapshot.select(true, true, null, null, null))
    }

    @Test
    fun apiSnapshotFillsInWhenCloneAndNightscoutAreAbsent() {
        assertEquals(
            api,
            RemoteIobSnapshot.select(true, true, null, null, api),
        )
        assertEquals(
            api,
            RemoteIobSnapshot.select(false, false, null, null, api),
        )
        assertEquals(
            nightscout,
            RemoteIobSnapshot.select(false, false, clone, nightscout, api),
        )
    }

    @Test
    fun cloneAndNightscoutKeepPrecedenceOverApi() {
        assertEquals(
            clone,
            RemoteIobSnapshot.select(true, true, clone, nightscout, api),
        )
        assertEquals(
            nightscout,
            RemoteIobSnapshot.select(true, true, null, nightscout, api),
        )
    }
}
