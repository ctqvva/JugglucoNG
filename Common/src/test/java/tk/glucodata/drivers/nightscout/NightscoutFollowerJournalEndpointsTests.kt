package tk.glucodata.drivers.nightscout

import org.junit.Assert.assertEquals
import org.junit.Test

class NightscoutFollowerJournalEndpointsTests {

    @Test
    fun treatmentsAndXdripFingersticksUseTheirSeparateNightscoutCollections() {
        val baseUrl = "https://example.com"

        assertEquals(
            "https://example.com/api/v1/treatments.json?count=512",
            NightscoutFollowerJournalEndpoints.treatments(baseUrl),
        )
        assertEquals(
            "https://example.com/api/v1/entries/mbg.json?count=512",
            NightscoutFollowerJournalEndpoints.fingersticks(baseUrl),
        )
    }
}
