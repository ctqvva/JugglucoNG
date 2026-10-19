package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlucoseReadingSourceTests {
    @Test
    fun cloneTransportRoundTripsThroughStableDatabaseValue() {
        CloneTransport.entries.forEach { transport ->
            assertEquals(
                transport,
                GlucoseReadingSource.cloneTransport(
                    GlucoseReadingSource.forCloneTransport(transport)
                ),
            )
        }
    }

    @Test
    fun nonCloneSourcesDoNotPretendToHaveAnIceRoute() {
        assertNull(GlucoseReadingSource.cloneTransport(GlucoseReadingSource.NIGHTSCOUT))
        assertNull(GlucoseReadingSource.cloneTransport(GlucoseReadingSource.SENSOR))
    }
}
